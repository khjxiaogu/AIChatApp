/**
 * AI 前端公共协议层（Vue mixin），所有 AI 前端（painter / painter-local / custom / galgame / trpg / ptrpg）共用。
 *
 * 负责：
 *   1. 连接生命周期：建连、断线重连（退避）、心跳保活、主动断开（不再重连）；
 *   2. 下行帧路由：把 {type:...} 统一协议帧分发到各页面的钩子，公共帧（心跳/分页/状态）在这里处理完；
 *   3. 历史记录分页状态机：isLoadingMore / hasMoreHistory / historyTopReached / onHistoryScroll /
 *      loadMoreHistory，以及向上翻页时的视口锚定（避免插入历史后跳动）；
 *   4. 连接状态提示文案（连接超时 / 连接错误 / 连接断开）。
 *
 * 页面用法：
 *   new Vue({ mixins: [AIProtocolMixin], data: {...},
 *     methods: {
 *       aiSocketUrl() { return 'ws://...'; },      // 必须实现
 *       aiOnFrame(frame) {},                       // 需要排队的帧（content/message/remove/audio 等）
 *       aiOnBatch(items, meta) {},                 // 历史分页落地（items 已标准化）
 *       ...
 *     }});
 *
 * 统一下行协议（服务端 -> 浏览器，全部带 type）：
 *   status{generating,[conversation_id,price,tokens,isVoiceEnabled,isVoiceSupported,isVoiceUsable,models,modelTree,model]}
 *   model{model} / voice{isVoiceEnabled?|isVoiceSupported?|isVoiceUsable?} / notice{content}
 *   sendResult{msgId,ok,reason}（对某次发送的确认/拒收，只发给发起连接）
 *   sendbox{action:"refill",content,msgId?,requestId?}（有条件回填，不再表示清空）
 *   message{id,title,content[]} / content{id,content[]} / remove{id} / batch_messages{messages[],prepend,hasMore,oldestId,newestId}
 *   thinking{content} / tool_call{tool_name} / audio{id,audioId} / done{price,tokens} / scene{scene,scene_data}
 *   input{input,prompt} / toolApproval{...} / toolApprovalResult{...} / local_request{...} / pong{id}
 * 其中 content[] 为结构化内容数组（{type:"text",text}/{type:"image",image_url}/...）；
 * tokens 为本轮新增的配额用量（当轮累计 getEquivantTokens 的增加值，只有数值、不带明细）；
 * 历史记录（batch_messages 的每一项）另带 token_cost，表示该条记录所属轮次的配额用量
 * （标准化后为 aiOnBatch 收到的 items[].tokenCost），实时轮次的同一个值由 done.tokens 下发。
 */
(function (global) {
	'use strict';

	/** 断线重连的快速重试次数（之后按 RECONNECT_DELAY 退避） */
	var RECONNECT_FAST_TRIES = 3;
	/** 退避后的重连间隔 */
	var RECONNECT_DELAY = 5000;
	/** 触发加载更早历史记录的顶部阈值（px） */
	var HISTORY_TOP_THRESHOLD = 60;
	/** 心跳发送间隔与等待 pong 的超时时间 */
	var HEARTBEAT_INTERVAL = 10000;
	var HEARTBEAT_TIMEOUT = 10000;
	/** 打字机基准速度：每秒揭示的字符数 */
	var REVEAL_BASE_CPS = 25;
	/** 积压超过该字符数后开始加速追赶 */
	var REVEAL_CATCHUP_AFTER = 30;
	/** 每多积压 1 个字符，每秒额外揭示的字符数 */
	var REVEAL_CATCHUP_GAIN = 6;
	/** 单帧最大步进秒数，避免切回前台时一帧刷完 */
	var REVEAL_MAX_STEP = 0.1;
	/** 场景图片槽位：与服务端 scene 值一致，页面模板直接读 aiImages[槽位] */
	var AI_IMAGE_SLOTS = ['front', 'side', 'back'];
	/** 等待服务端 sendResult 确认的时间（与心跳超时同量级） */
	var SEND_ACK_TIMEOUT = 10000;
	/** 重连后等待历史记录落地再处理 Outbox 的宽限时间 */
	var OUTBOX_RESOLVE_DELAY = 1500;
	/** recentSends 的保留上限与有效期：用于匹配迟到的回填帧 */
	var OUTBOX_RECENT_MAX = 32;
	var OUTBOX_RECENT_TTL = 10 * 60 * 1000;

	/**
	 * 生成一个客户端消息标识。
	 *
	 * @return {String} 唯一标识
	 */
	function newMessageId() {
		if (global.crypto && typeof global.crypto.randomUUID === 'function')
			return 'c-' + global.crypto.randomUUID();
		return 'c-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
	}

	/**
	 * 把结构化内容数组转成纯文本，供只渲染文本的页面使用。
	 *
	 * @param {Array|String} content 结构化内容
	 * @return {String} 纯文本
	 */
	function contentToText(content) {
		if (content == null)
			return '';
		if (typeof content === 'string')
			return content;
		if (!Array.isArray(content))
			return String(content);
		var text = '';
		for (var i = 0; i < content.length; i++) {
			var item = content[i];
			if (item == null)
				continue;
			if (typeof item === 'string') {
				text += item;
			} else if (item.type === 'text') {
				text += item.text || '';
			} else if (item.description) {
				text += item.description;
			} else if (item.result) {
				text += item.result;
			}
		}
		return text;
	}

	/** 连接状态提示文案 */
	function connTipText(reason, attempt) {
		var label = reason === 'timeout' ? '连接超时'
				: reason === 'error' ? '连接错误'
				: '连接断开';
		var times = attempt > 1 ? '（第 ' + attempt + ' 次重试）' : '';
		return label + '，正在重新连接' + times + '…';
	}

	/**
	 * 把服务端下发的价格统一格式化为两位小数展示（协议里是字符串，如 "1.2345"）。
	 *
	 * @param {String|Number} price 原始价格
	 * @return {String} 展示用价格，空值返回 null 表示"未变化"
	 */
	function formatPrice(price) {
		if (price == null)
			return null;
		var text = String(price);
		var dot = text.indexOf('.');
		return dot < 0 ? text : text.substring(0, dot + 3);
	}

	/**
	 * 判断一条消息是否来自用户。
	 * <p>
	 * 优先使用服务端下发的内部标识 {@code role}；老数据没有 role 时退回显示名比较。
	 * </p>
	 *
	 * @param {Object} msg 消息对象
	 * @param {String} [userTitle] 用户角色的显示名，默认「你」
	 * @return {Boolean}
	 */
	function isUserMessage(msg, userTitle) {
		if (!msg)
			return false;
		if (msg.role)
			return msg.role === 'user';
		return msg.title === (userTitle || '你');
	}

	var AIProtocolMixin = {
		data: function () {
			// 场景图片槽位由公共层统一持有（键与服务端 scene 值一致），页面直接读 aiImages / aiImageLists
			var images = {}, imageLoading = {}, imageLists = {};
			for (var i = 0; i < AI_IMAGE_SLOTS.length; i++) {
				images[AI_IMAGE_SLOTS[i]] = '';
				imageLists[AI_IMAGE_SLOTS[i]] = [];
				imageLoading[AI_IMAGE_SLOTS[i]] = false;
			}
			return {
				aiConnected: false,        // 传输层是否已连接
				aiConnState: 'closed',     // 'connecting' | 'connected' | 'closed'
				connTip: null,             // 连接状态提示条文案，null 表示不显示
				connTipKind: 'closed',     // 'timeout' | 'error' | 'closed'，提示条样式用
				connTipAttempt: 0,         // 当前重试次数
				// ---- 服务端状态帧（status / model / voice / done）维护的会话状态，页面直接读取，不再各自复制 ----
				aiGenerating: false,       // 服务端是否正在生成（status.generating，done 会复位）
				aiStopping: false,         // 是否已请求停止、正在等服务端结束本轮（aiSendStop 置位，done/status 复位）
				aiPrice: '0.00',           // 本会话消费价格（status.price / done.price）
				aiTurnTokens: 0,           // 本轮新增配额用量（status.tokens / done.tokens），只有数值不带明细
				aiVoiceEnabled: false,     // 语音会话开关（status.isVoiceEnabled / voice.isVoiceEnabled）
				aiVoiceSupported: false,   // 对应 App 是否包含朗读服务（status.isVoiceSupported；与在线状态无关）
				aiVoiceUsable: false,      // 当前语音服务是否在线可用（status.isVoiceUsable / voice.isVoiceUsable）
				aiModel: '',               // 当前生效模型标识（status.model / model.model，空为默认模型）
				aiModelTree: [],           // 可用模型目录树（status.modelTree）
				// ---- 场景图片：地址 + 加载状态都由公共层维护，模板直接绑定 aiImages / aiImageLists ----
				aiImages: images,          // 各槽位首图 {front:'', side:'', back:''}，单图场景直接绑定这个
				aiImageLists: imageLists,  // 各槽位完整图片列表 {front:[...], ...}，多角色站位等场景绑定这个
				aiImageLoading: imageLoading, // {front:false, side:false, back:false}
				// ---- 统一对话框（aiPrompt / aiConfirm 使用，模板绑定 <ai-dialog>）----
				aiDialog: {
					open: false,
					mode: 'prompt',        // 'prompt' | 'confirm'
					title: '',
					message: '',
					value: '',
					placeholder: '',
					okText: '确定',
					cancelText: '取消',
					kind: '',
				},
				// ---- 历史记录分页 ----
				isLoadingMore: false,      // 是否正在加载更早的历史记录
				hasMoreHistory: false,     // 服务端是否还有更早的历史记录
				historyTopReached: false,  // 是否已经翻到最早一条
				suppressAutoScroll: false, // 插入历史记录期间抑制自动滚动
			};
		},
		created: function () {
			this._aiImageSeq = {};      // 每个槽位的请求序号，用于丢弃过期的加载结果
			this._aiMsgKeySeq = 0;      // 匿名消息（还没有历史 id）的本地 key 序号
			// ---- 发送事务（Outbox）：输入框只在发送动作里清空，内容在确认前不丢 ----
			this._aiOutbox = [];        // 已交给 WebSocket、尚未收到 sendResult 的消息
			this._aiRecentSends = {};   // 已确认的 msgId -> 快照，用于匹配迟到的回填帧
			this._aiOutboxLoaded = false;
			this._aiOutboxKey = null;   // 载入 Outbox 时使用的会话键，切换会话时仍按旧键保存
			this._aiDraftMsgId = null;  // 从 Outbox 恢复到输入框后，原文重试时沿用原 msgId
			this._aiDraftText = null;   // 恢复时的原文，用于判断用户是否改过内容
			this._aiPendingRefill = null;
			this._aiAppliedRefills = {}; // 已处理过的回填 id，避免断线补发时重复恢复
			this._aiRequestId = null;   // 最近一次 revert/regen 的请求标识
			this._aiResolvePending = false;
			this._aiResolveTimer = null;
			this._aiRefillChip = null;
		},
		mounted: function () {
			// popstate：全屏看图等覆盖层用后退键关闭（页面实现 aiOnPopState 时生效）
			this._aiOnPopState = () => this.aiOnPopState();
			if (global.addEventListener)
				global.addEventListener('popstate', this._aiOnPopState);
			this._aiRevealQueue = [];
			this._aiChain = null;
			this._aiGeneration = 0;
			this.aiInitHeartbeat();
		},
		computed: {
			/**
			 * 最后一条消息是否来自用户。
			 * <p>
			 * 消息操作按钮（撤回/重说/朗读）挂在最后一条消息下面：
			 * 最后一条是用户消息时应只提供"撤回"，因为此时没有可重说或朗读的回复。
			 * </p>
			 */
			lastMessageIsUser: function () {
				var list = this.messages;
				if (!list || !list.length)
					return false;
				return isUserMessage(list[list.length - 1], this.aiUserTitle);
			},
			/**
			 * 本轮是否仍在进行：正在生成，或已请求停止但服务端还没结束本轮。
			 * <p>需要"本轮进行期间禁止操作"的地方（上传图片、发送消息、生成等）统一用它判断，
			 * 只判断 {@code aiGenerating} 会在"停止中"的窗口里漏掉。</p>
			 */
			aiGenerationBusy: function () {
				return !!(this.aiGenerating || this.aiStopping);
			},
		},
		beforeDestroy: function () {
			if (global.removeEventListener && this._aiOnPopState)
				global.removeEventListener('popstate', this._aiOnPopState);
			// 未确认的发送与待恢复内容保留到 sessionStorage，刷新/返回后继续对账
			this.aiOutboxPersist();
			if (this._aiResolveTimer) {
				clearTimeout(this._aiResolveTimer);
				this._aiResolveTimer = null;
			}
			this._aiResolvePending = false;
			this.aiHideRefillChip();
			this.aiCancelPipeline();
			this.aiRevealClear();
			this.aiDisconnect();
		},
		methods: {
			/* ================= 需要页面重写的钩子 ================= */

			/** @return {String} 完整的 WebSocket 地址；返回空则不会建连 */
			aiSocketUrl: function () { return ''; },
			/** 传输层连接建立（onopen） */
			aiOnSocketOpen: function () {},
			/** 连接状态变化：state 为 'connecting' | 'connected' | 'closed'，reason 为 'timeout'|'error'|'closed' */
			aiOnConnState: function (state, reason, attempt) {},
			/** 未被公共层消费的帧，以及需要页面排队处理的帧（content/message/remove/audio 等） */
			aiOnFrame: function (frame) {},
			/** 历史分页落地：items 为标准化条目数组，meta 为 {prepend, hasMore} */
			aiOnBatch: function (items, meta) {},
			/** 生成状态变化 */
			aiOnStatus: function (frame) {},
			/** 新增/覆盖一条消息 */
			aiOnMessage: function (frame) {},
			/** 追加流式内容 */
			aiOnContent: function (frame) {},
			/** 删除一条消息 */
			aiOnRemove: function (frame) {},
			/** 提示消息 */
			aiOnNotice: function (text) { this.aiToast(text); },
			/** 输入框回填后的通知（公共层已按 msgId/草稿状态做过条件恢复，页面一般不需要重写） */
			aiOnSendbox: function (text) {},
			/** 模型切换回执 */
			aiOnModel: function (model) {},
			/** 语音开关变化 */
			aiOnVoice: function (frame) {},
			/** 生成结束 */
			aiOnDone: function (frame) {},
			/**
			 * 场景（立绘/背景）切换，默认实现：立即开始预加载，管道只等加载完成再继续后续帧。
			 *
			 * @param {String} scene 场景槽位
			 * @param {String} data  场景数据（可逗号分隔多张）
			 * @return {Promise} 图片加载完成的 Promise（调用方可按需等待）
			 */
			aiOnScene: function (scene, data) {
				var promise = this.aiApplyScene(scene, data);
				this.aiEnqueue({ type: 'scene', promise: promise });
				return promise;
			},
			/** 需要用户补充输入 */
			aiOnInput: function (input, prompt) {},
			/** 工具调用许可请求 */
			aiOnToolApproval: function (frame) {},
			/** 工具调用许可结果 */
			aiOnToolApprovalResult: function (frame) {},
			/** 音频生成完成 */
			aiOnAudio: function (frame) {},
			/** 思考过程增量（绘画前端） */
			aiOnThinking: function (frame) {},
			/** 工具调用展示（绘画前端） */
			aiOnToolCall: function (frame) {},
			/** 本地代理请求（本地绘画模式） */
			aiOnLocalRequest: function (frame) {},
			/** 浏览器后退 */
			aiOnPopState: function () {},
			/** 翻到最早一条时的回调（页面可用来复位提示） */
			aiOnHistoryTop: function () {},
			/** 历史容器每次滚动都会回调（页面可用它维护"是否贴底"等状态） */
			aiOnScroll: function (container) {},
			/** 历史记录滚动容器（用于分页锚定）；默认取 $refs.histlay */
			aiHistoryContainer: function () { return this.$refs.histlay || null; },
			/**
			 * 轻提示（全局统一实现：页面右上角浮层，自动消失，不阻塞操作）。
			 * <p>页面有更合适的实现（例如自己的提示组件）时可以重写本方法。</p>
			 *
			 * @param {String} text 提示文案
			 * @param {Object} [options] {kind: 'info'|'warn'|'error', duration: 毫秒}
			 */
			aiToast: function (text, options) {
				if (!text)
					return;
				if (typeof document === 'undefined') {
					console.log('[AI] ' + text);
					return;
				}
				var opts = options || {};
				var duration = opts.duration || 2600;
				var host = document.getElementById('ai-toast-host');
				if (!host) {
					host = document.createElement('div');
					host.id = 'ai-toast-host';
					document.body.appendChild(host);
				}
				var item = document.createElement('div');
				item.className = 'ai-toast' + (opts.kind ? ' is-' + opts.kind : '');
				item.textContent = text;
				host.appendChild(item);
				setTimeout(function () {
					item.className += ' is-out';
				}, duration);
				setTimeout(function () {
					if (item.parentNode)
						item.parentNode.removeChild(item);
				}, duration + 300);
			},

			/* ================= 上行帧（与下行一致，统一使用 type） ================= */

			/**
			 * 发送一个上行帧：{type, ...extra}。
			 * 所有上行指令都必须带 type，不再使用 operation 字段。
			 *
			 * @param {String} type 帧类型
			 * @param {Object} [extra] 附加字段
			 * @return {Boolean} 是否发送成功（连接未就绪时为 false）
			 */
			aiSendFrame: function (type, extra) {
				var frame = { type: type };
				if (extra) {
					for (var k in extra) {
						if (Object.prototype.hasOwnProperty.call(extra, k))
							frame[k] = extra[k];
					}
				}
				return this.aiSend(frame);
			},
			/**
			 * 用户发言：把输入框内容作为一个“待确认的发送事务”交给服务端。
			 * <p>
			 * 调用成功（已交给 WebSocket）后立即清空输入框，但整段内容会留在 Outbox 里，
			 * 直到服务端回 {@code sendResult{ok:true}}（或用历史/实时消息对账确认已送达）才丢弃。
			 * 断线或超时后保留同一条 {@code msgId}：重连时先与历史对账，仍未送达的内容会按
			 * “草稿为空才恢复”的规则放回输入框，用户重发时沿用这个 id，服务端据此幂等去重。
			 * 清空只发生在这一次发送动作里，任何服务端帧都不会再清空输入框。
			 * </p>
			 * <p>
			 * 如果 {@link #aiIsOpen()} 为 false 或帧没有交给 WebSocket，则返回 false 且
			 * <b>不改动输入框</b>，调用方据此提示用户。
			 * </p>
			 *
			 * @param {String} text    要发送的文本
			 * @param {Object} [options] {extras: 附件快照（引用消息等）}
			 * @return {Boolean} 是否已交给 WebSocket
			 */
			aiSendMessage: function (text, options) {
				var content = text == null ? '' : String(text);
				if (!this.aiIsOpen())
					return false;
				var opts = options || {};
				var extras = opts.extras !== undefined ? opts.extras
					: (typeof this.aiDraftExtras === 'function' ? this.aiDraftExtras() : null);
				// 草稿原文与“实际发送文本”分开存：绘画页发送的是带引用标记的完整文本，
				// 失败恢复时应该还原用户写的正文，而不是把引用格式塞回输入框
				var draftText = this.aiGetDraft();
				// 恢复后原样重发：沿用原 msgId，服务端不会重复生成；
				// 用户改过内容则视为新消息，换新 id，避免服务端按旧 id 去重把新内容吞掉
				var reuseId = this._aiDraftMsgId && draftText === this._aiDraftText ? this._aiDraftMsgId : null;
				var msgId = reuseId || newMessageId();
				if (reuseId) {
					this.aiOutboxRemove(reuseId);
				}
				this._aiDraftMsgId = null;
				this._aiDraftText = null;
				var entry = {
					msgId: msgId,
					text: content,
					draftText: draftText,
					extras: extras,
					state: 'unacked',
					time: Date.now(),
					timer: null,
				};
				this._aiOutbox.push(entry);
				this.aiOutboxPersist();
				var sent = false;
				try {
					sent = this.aiSendFrame('message', { content: content, msgId: msgId });
				} catch (err) {
					console.warn('[AI] 发送消息失败', err);
					sent = false;
				}
				if (!sent) {
					// 交给 WebSocket 之前就失败了：保留内容，不制造“已发送”的假象
					this.aiOutboxRemove(msgId);
					this._aiDraftMsgId = msgId;
					this._aiDraftText = draftText;
					this.aiOutboxPersist();
					return false;
				}
				// 清空只在这里发生一次；内容已经在 Outbox 里，失败时可以恢复
				this.aiSetDraft('');
				if (typeof this.aiClearDraftExtras === 'function')
					this.aiClearDraftExtras();
				this.aiOutboxStartTimer(entry);
				if (typeof this.aiOnSendQueued === 'function')
					this.aiOnSendQueued(entry);
				return true;
			},

			/* ================= 输入框草稿（页面可覆盖） ================= */

			/**
			 * 读取当前草稿文本。
			 * <p>对话页用 {@code newMessage}，绘画页用 {@code inputText}；页面有别的字段时可覆盖本方法。</p>
			 *
			 * @return {String} 当前草稿
			 */
			aiGetDraft: function () {
				if (this.newMessage !== undefined)
					return this.newMessage == null ? '' : String(this.newMessage);
				if (this.inputText !== undefined)
					return this.inputText == null ? '' : String(this.inputText);
				return '';
			},
			/**
			 * 写入当前草稿文本。
			 *
			 * @param {String} text 新内容
			 */
			aiSetDraft: function (text) {
				var value = text == null ? '' : String(text);
				if (this.newMessage !== undefined)
					this.newMessage = value;
				if (this.inputText !== undefined)
					this.inputText = value;
				if (typeof this.aiAfterDraftChange === 'function')
					this.aiAfterDraftChange();
			},
			/**
			 * 草稿附件快照：引用消息等需要随发送一起暂存、失败时恢复的内容。
			 * 页面按需覆盖。
			 *
			 * @return {Object|null}
			 */
			aiDraftExtras: function () {
				return null;
			},
			/** 清空草稿附件，页面按需覆盖 */
			aiClearDraftExtras: function () {},
			/**
			 * 恢复草稿附件。
			 *
			 * @param {Object|null} extras 发送时保存的快照
			 */
			aiRestoreDraftExtras: function (extras) {},
			/** 草稿变化后的页面收尾（默认重算 textarea 高度） */
			aiAfterDraftChange: function () {
				if (typeof this.adjustTextareaHeight === 'function')
					this.adjustTextareaHeight();
			},

			/* ================= 发送事务（Outbox） ================= */

			/** 按 msgId 查找 Outbox 项 */
			aiFindOutbox: function (msgId) {
				if (!this._aiOutbox)
					return null;
				for (var i = 0; i < this._aiOutbox.length; i++) {
					if (this._aiOutbox[i].msgId === msgId)
						return this._aiOutbox[i];
				}
				return null;
			},
			/** 从 Outbox 移除一项并清理其定时器 */
			aiOutboxRemove: function (msgId) {
				if (!this._aiOutbox)
					return;
				for (var i = this._aiOutbox.length - 1; i >= 0; i--) {
					if (this._aiOutbox[i].msgId !== msgId)
						continue;
					if (this._aiOutbox[i].timer)
						clearTimeout(this._aiOutbox[i].timer);
					this._aiOutbox.splice(i, 1);
					return;
				}
			},
			/** 为一次发送启动 ACK 超时计时 */
			aiOutboxStartTimer: function (entry) {
				if (!entry)
					return;
				if (entry.timer)
					clearTimeout(entry.timer);
				var self = this;
				entry.timer = setTimeout(function () {
					entry.timer = null;
					self.aiOnSendTimeout(entry);
				}, SEND_ACK_TIMEOUT);
			},
			/**
			 * 发送成功回执：删除 Outbox 项并记入 recentSends，供迟到的回填帧匹配。
			 *
			 * @param {Object} frame sendResult 帧
			 */
			aiHandleSendResult: function (frame) {
				if (!frame || !frame.msgId)
					return;
				var entry = this.aiFindOutbox(frame.msgId);
				if (!entry)
					return;
				if (entry.timer) {
					clearTimeout(entry.timer);
					entry.timer = null;
				}
				if (frame.ok) {
					this.aiOutboxRemove(frame.msgId);
					this._aiRecentSends[frame.msgId] = {
						text: entry.text,
						draftText: entry.draftText,
						extras: entry.extras,
						time: Date.now(),
					};
					this.aiPruneRecentSends();
					this.aiOutboxPersist();
					if (typeof this.aiOnSendAccepted === 'function')
						this.aiOnSendAccepted(entry);
					return;
				}
				entry.state = 'failed';
				this.aiOutboxPersist();
				if (typeof this.aiOnSendFailed === 'function')
					this.aiOnSendFailed(entry, frame);
			},
			/**
			 * ACK 超时：消息可能已送达也可能没有。保留 Outbox 项，等重连后先用历史/消息对账；
			 * 仍未送达的内容会放回输入框，不立即覆盖用户当前草稿。
			 *
			 * @param {Object} entry Outbox 项
			 */
			aiOnSendTimeout: function (entry) {
				if (!entry || entry.state === 'failed')
					return;
				entry.state = 'uncertain';
				this.aiOutboxPersist();
				this.aiToast('消息发送未确认，连接恢复后会核对是否送达', { kind: 'warn' });
			},
			/** 发送成功后的页面钩子 */
			aiOnSendAccepted: function (entry) {},
			/** 消息交给 WebSocket 后的页面钩子 */
			aiOnSendQueued: function (entry) {},
			/**
			 * 服务端拒收（忙 / 空消息 / 写入失败）。
			 * <p>本轮没有真正开始，复位页面可能提前置上的“生成中”状态，并把内容还给用户。</p>
			 */
			aiOnSendFailed: function (entry, frame) {
				this.aiGenerating = false;
				var reason = frame && frame.reason;
				// 先移出 Outbox：紧随其后的 sendbox 回填帧会按 msgId 找不到项而忽略，避免重复恢复
				this.aiOutboxRemove(entry.msgId);
				this.aiOutboxPersist();
				this.aiToast(reason === 'busy' ? '操作太快啦，请稍后再试' : '消息未发送', { kind: 'warn' });
				this.aiRestoreOrOffer(entry);
			},
			/**
			 * 把失败的内容还给用户：输入框为空时直接恢复；否则不覆盖，只给“恢复”入口。
			 *
			 * @param {Object} entry Outbox 项
			 */
			aiRestoreOrOffer: function (entry) {
				if (!entry)
					return;
				var text = entry.draftText != null ? entry.draftText : entry.text;
				if (this.aiGetDraft() === '') {
					this.aiRestoreDraft(text, entry.extras, entry.msgId);
					this.aiToast('已恢复未发送的内容', { kind: 'info' });
				} else {
					this._aiPendingRefill = { text: text, extras: entry.extras, msgId: entry.msgId };
					this.aiOutboxPersist();
					this.aiShowRefillChip();
				}
			},
			/**
			 * 恢复一段草稿，并记住 msgId：用户再次发送时沿用同一个 id，服务端不会重复生成。
			 *
			 * @param {String} text    草稿文本
			 * @param {Object} [extras] 附件快照
			 * @param {String} [msgId]  原消息标识
			 */
			aiRestoreDraft: function (text, extras, msgId) {
				this.aiSetDraft(text);
				if (typeof this.aiRestoreDraftExtras === 'function')
					this.aiRestoreDraftExtras(extras);
				this._aiDraftMsgId = msgId || null;
				this._aiDraftText = text == null ? '' : String(text);
			},
			/**
			 * 处理服务端回填帧。
			 * <p>
			 * 规则：
			 * </p>
			 * <ul>
			 *   <li>空内容一律忽略（旧服务端的“清空”不再生效）；</li>
			 *   <li>带 msgId 的回填只处理本标签页自己的发送（在 Outbox 或 recentSends 中）；</li>
			 *   <li>带 requestId 的回填只处理最近一次 revert/regen 的发起方；</li>
			 *   <li>输入框为空才自动恢复，否则不覆盖，只给“恢复”入口。</li>
			 * </ul>
			 *
			 * @param {Object} frame sendbox 帧
			 */
			aiApplySendbox: function (frame) {
				if (!frame)
					return;
				var content = frame.content;
				var action = frame.action;
				if (content == null || content === '') {
					// 旧服务端没有 sendResult，sendbox{content:""} 是它唯一的“已接收”信号：
					// 只用来确认最早的未确认发送，绝不清空输入框（清空只由发送动作负责）
					if (!action) {
						var oldest = null;
						for (var i = 0; i < this._aiOutbox.length; i++) {
							var queued = this._aiOutbox[i];
							if (queued.state !== 'unacked' && queued.state !== 'uncertain')
								continue;
							if (!oldest || (queued.time || 0) < (oldest.time || 0))
								oldest = queued;
						}
						if (oldest) {
							this.aiOutboxRemove(oldest.msgId);
							this._aiRecentSends[oldest.msgId] = {
								text: oldest.text,
								draftText: oldest.draftText,
								extras: oldest.extras,
								time: Date.now(),
							};
							this.aiPruneRecentSends();
							this.aiOutboxPersist();
						}
					}
					return;
				}
				if (action && action !== 'refill')
					return;
				var msgId = frame.msgId || '';
				var requestId = frame.requestId || '';
				if (requestId && requestId !== this._aiRequestId)
					return;
				var known = null;
				if (msgId) {
					known = this.aiFindOutbox(msgId) || this._aiRecentSends[msgId] || null;
					if (!known)
						return;   // 别的标签页/别的会话的回填，不处理
				}
				// 服务端明确要求回填：这条发送已失败，移出 Outbox，避免重连结算时再次处理
				if (msgId && this.aiFindOutbox(msgId)) {
					this.aiOutboxRemove(msgId);
					this.aiOutboxPersist();
				}
				// （下面若因草稿非空而只展示“恢复”入口，也会把待恢复内容持久化）
				// 断线补发可能把同一条回填发给多个新连接：本标签页只处理一次
				var refillKey = msgId || requestId;
				if (refillKey) {
					if (this._aiAppliedRefills[refillKey])
						return;
					this._aiAppliedRefills[refillKey] = Date.now();
					this.aiPruneAppliedRefills();
				}
				// 有本地草稿快照时优先还原用户正文，服务端下发的完整文本作为兜底
				var restoreText = known && known.draftText != null ? known.draftText : String(content);
				if (this.aiGetDraft() === '') {
					this.aiRestoreDraft(restoreText, known && known.extras, msgId || null);
					this.aiToast('已恢复未发送的内容', { kind: 'info' });
				} else {
					this._aiPendingRefill = {
						text: restoreText,
						extras: known && known.extras,
						msgId: msgId || null,
					};
					this.aiOutboxPersist();
					this.aiShowRefillChip();
				}
				if (typeof this.aiOnSendbox === 'function')
					this.aiOnSendbox(String(content));
			},
			/** 显示“恢复未发送内容”的浮动提示条 */
			aiShowRefillChip: function () {
				if (typeof document === 'undefined' || !document.body)
					return;
				this.aiHideRefillChip();
				var self = this;
				var chip = document.createElement('div');
				chip.className = 'ai-refill-chip';
				var label = document.createElement('span');
				label.textContent = '上一条内容未发送';
				var button = document.createElement('button');
				button.type = 'button';
				button.textContent = '恢复';
				button.onclick = function () { self.aiApplyPendingRefill(); };
				var close = document.createElement('button');
				close.type = 'button';
				close.className = 'ai-refill-chip-close';
				close.title = '忽略';
				close.textContent = '×';
				close.onclick = function () {
					self._aiPendingRefill = null;
					self.aiOutboxPersist();
					self.aiHideRefillChip();
				};
				chip.appendChild(label);
				chip.appendChild(button);
				chip.appendChild(close);
				document.body.appendChild(chip);
				this._aiRefillChip = chip;
			},
			/** 移除“恢复未发送内容”的浮动提示条 */
			aiHideRefillChip: function () {
				if (this._aiRefillChip && this._aiRefillChip.parentNode)
					this._aiRefillChip.parentNode.removeChild(this._aiRefillChip);
				this._aiRefillChip = null;
			},
			/** 用户点击“恢复”：输入框为空则放入，已有内容则追加，保证两边都不丢 */
			aiApplyPendingRefill: function () {
				var pending = this._aiPendingRefill;
				if (!pending)
					return;
				this._aiPendingRefill = null;
				this.aiOutboxPersist();
				this.aiHideRefillChip();
				var current = this.aiGetDraft();
				if (current === '') {
					this.aiRestoreDraft(pending.text, pending.extras, pending.msgId);
				} else {
					// 追加后内容已经变了，不能沿用原 msgId，否则服务端会把“新内容”当成旧消息去重掉
					this.aiRestoreDraft(current.replace(/\s+$/, '') + '\n' + pending.text, pending.extras, null);
				}
			},

			/* ================= Outbox 持久化 / 对账 / 结算 ================= */

			/** sessionStorage 键：同一标签页、同一会话独立保存 */
			aiOutboxStorageKey: function () {
				var id = this.chatId || this.conversationId || '';
				return 'aiwuxia-outbox:' + location.pathname + ':' + id;
			},
			/** 把未确认发送与最近确认记录写入 sessionStorage（失败静默） */
			aiOutboxPersist: function () {
				if (typeof sessionStorage === 'undefined')
					return;
				// 还没从存储里载入、内存里也没有待发/待恢复内容：不要写空记录覆盖上一次的未发送内容
				if (!this._aiOutboxLoaded && !(this._aiOutbox && this._aiOutbox.length) && !this._aiPendingRefill)
					return;
				try {
					var data = {
						outbox: [],
						recent: this._aiRecentSends || {},
						pendingRefill: this._aiPendingRefill || null,
					};
					for (var i = 0; i < (this._aiOutbox || []).length; i++) {
						var entry = this._aiOutbox[i];
						data.outbox.push({
							msgId: entry.msgId,
							text: entry.text,
							draftText: entry.draftText,
							extras: entry.extras,
							state: entry.state,
							time: entry.time,
						});
					}
					// 切换会话时页面可能已经改了 chatId，这里仍写回载入时对应的旧会话
					var key = this._aiOutboxKey || this.aiOutboxStorageKey();
					sessionStorage.setItem(key, JSON.stringify(data));
				} catch (err) {
					// 隐私模式/配额已满：忽略，内存状态仍然有效
				}
			},
			/** 从 sessionStorage 恢复 Outbox / recentSends（只执行一次） */
			aiOutboxLoad: function () {
				if (this._aiOutboxLoaded)
					return;
				this._aiOutboxLoaded = true;
				this._aiOutboxKey = this.aiOutboxStorageKey();
				if (typeof sessionStorage === 'undefined')
					return;
				try {
					var raw = sessionStorage.getItem(this._aiOutboxKey);
					if (!raw)
						return;
					var data = JSON.parse(raw) || {};
					if (Array.isArray(data.outbox)) {
						for (var i = 0; i < data.outbox.length; i++) {
							var entry = data.outbox[i];
							if (!entry || !entry.msgId || !entry.text)
								continue;
							entry.state = entry.state === 'failed' ? 'uncertain' : (entry.state || 'unacked');
							entry.timer = null;
							this._aiOutbox.push(entry);
						}
					}
					if (data.recent && typeof data.recent === 'object')
						this._aiRecentSends = data.recent;
					if (data.pendingRefill && data.pendingRefill.text)
						this._aiPendingRefill = data.pendingRefill;
					this.aiPruneRecentSends();
					if (this._aiPendingRefill) {
						// 上次没来得及恢复的内容：重新展示“恢复”入口（不自动覆盖草稿）
						var self = this;
						if (typeof this.$nextTick === 'function')
							this.$nextTick(function () { self.aiShowRefillChip(); });
						else
							this.aiShowRefillChip();
					}
				} catch (err) {
					// 数据损坏：忽略
				}
			},
			/** 清理过期的 recentSends，并限制总数 */
			aiPruneRecentSends: function () {
				var now = Date.now();
				var ids = [];
				for (var id in this._aiRecentSends) {
					if (!Object.prototype.hasOwnProperty.call(this._aiRecentSends, id))
						continue;
					if (now - (this._aiRecentSends[id].time || 0) > OUTBOX_RECENT_TTL)
						delete this._aiRecentSends[id];
					else
						ids.push(id);
				}
				if (ids.length > OUTBOX_RECENT_MAX) {
					var self = this;
					ids.sort(function (a, b) {
						return (self._aiRecentSends[a].time || 0) - (self._aiRecentSends[b].time || 0);
					});
					for (var i = 0; i < ids.length - OUTBOX_RECENT_MAX; i++)
						delete this._aiRecentSends[ids[i]];
				}
			},
			/** 清理已处理回填记录，避免无限增长 */
			aiPruneAppliedRefills: function () {
				var now = Date.now();
				var ids = [];
				for (var id in this._aiAppliedRefills) {
					if (!Object.prototype.hasOwnProperty.call(this._aiAppliedRefills, id))
						continue;
					if (now - this._aiAppliedRefills[id] > OUTBOX_RECENT_TTL)
						delete this._aiAppliedRefills[id];
					else
						ids.push(id);
				}
				if (ids.length > OUTBOX_RECENT_MAX) {
					var self = this;
					ids.sort(function (a, b) { return self._aiAppliedRefills[a] - self._aiAppliedRefills[b]; });
					for (var i = 0; i < ids.length - OUTBOX_RECENT_MAX; i++)
						delete this._aiAppliedRefills[ids[i]];
				}
			},
			/** 重连后等待历史落地，再安排一次 Outbox 结算 */
			aiScheduleOutboxResolve: function () {
				if (this._aiResolveTimer)
					clearTimeout(this._aiResolveTimer);
				var self = this;
				this._aiResolveTimer = setTimeout(function () {
					self._aiResolveTimer = null;
					self.aiResolveOutbox();
				}, OUTBOX_RESOLVE_DELAY);
			},
			/**
			 * 结算所有仍未确认的发送。
			 * <p>
			 * 到这里还没被历史对账消掉的条目，说明服务端没有留下这条用户消息：
			 * 要么根本没收到，要么收到后生成失败/配额不足被回滚。这里不做盲目重发
			 * （重发可能和刚到达的回填帧竞争，造成重复生成），而是按统一规则把内容还给用户：
			 * 输入框为空就直接恢复，否则只给“恢复”入口。重试时沿用原 msgId，
			 * 服务端已回滚的会正常处理，从未收到的也不会被去重误判。
			 * </p>
			 */
			aiResolveOutbox: function () {
				if (this._aiResolveTimer) {
					clearTimeout(this._aiResolveTimer);
					this._aiResolveTimer = null;
				}
				this._aiResolvePending = false;
				if (!this._aiOutbox || !this._aiOutbox.length)
					return;
				var resolved = false;
				for (var i = this._aiOutbox.length - 1; i >= 0; i--) {
					var entry = this._aiOutbox[i];
					if (entry.state !== 'unacked' && entry.state !== 'uncertain')
						continue;
					this.aiOutboxRemove(entry.msgId);
					this.aiRestoreOrOffer(entry);
					resolved = true;
				}
				if (resolved)
					this.aiOutboxPersist();
			},
			/**
			 * 用单条实时 message 帧对账 Outbox：服务端把用户消息写进历史后会推送
			 * {@code message{role:"user"}}，收到它说明这次发送已经送达，直接消掉 Outbox 项。
			 *
			 * @param {Object} frame message 帧
			 */
			aiReconcileOutboxMessage: function (frame) {
				if (!frame || !this._aiOutbox || !this._aiOutbox.length)
					return;
				var role = frame.role || (isUserMessage(frame, this.aiUserTitle) ? 'user' : '');
				if (role !== 'user')
					return;
				var text = this.aiNormalizeDraftText(this.aiContentToText(frame.content));
				for (var i = this._aiOutbox.length - 1; i >= 0; i--) {
					var entry = this._aiOutbox[i];
					if (entry.state !== 'unacked' && entry.state !== 'uncertain')
						continue;
					if (this.aiNormalizeDraftText(entry.text) !== text)
						continue;
					if (entry.timer)
						clearTimeout(entry.timer);
					this._aiOutbox.splice(i, 1);
					this._aiRecentSends[entry.msgId] = {
						text: entry.text,
						draftText: entry.draftText,
						extras: entry.extras,
						time: Date.now(),
					};
				}
				this.aiPruneRecentSends();
				this.aiOutboxPersist();
			},
			/**
			 * 用最新一页历史记录对账 Outbox：已经出现在历史里的用户消息视为已送达，
			 * 避免重连后误把已送达的消息还给用户。
			 *
			 * @param {Array} items 标准化后的历史条目
			 * @param {Object} meta  {prepend, hasMore}
			 */
			aiReconcileOutbox: function (items, meta) {
				if (!items || !this._aiOutbox || !this._aiOutbox.length)
					return;
				if (meta && meta.prepend)
					return;
				var changed = false;
				for (var i = this._aiOutbox.length - 1; i >= 0; i--) {
					var entry = this._aiOutbox[i];
					if (entry.state !== 'unacked' && entry.state !== 'uncertain')
						continue;
					var text = this.aiNormalizeDraftText(entry.text);
					for (var j = items.length - 1; j >= 0; j--) {
						var item = items[j];
						if (!item || item.role !== 'user')
							continue;
						if (this.aiNormalizeDraftText(item.text) !== text)
							continue;
						if (entry.timer)
							clearTimeout(entry.timer);
						this._aiOutbox.splice(i, 1);
						this._aiRecentSends[entry.msgId] = {
							text: entry.text,
							draftText: entry.draftText,
							extras: entry.extras,
							time: Date.now(),
						};
						changed = true;
						break;
					}
				}
				if (changed) {
					this.aiPruneRecentSends();
					this.aiOutboxPersist();
				}
			},
			/** 对账用的文本归一化 */
			aiNormalizeDraftText: function (text) {
				return String(text == null ? '' : text).replace(/\r\n/g, '\n').replace(/\s+$/, '');
			},
			/**
			 * 构造带引用消息的用户文本。
			 * <p>
			 * 引用正文逐行加 {@code > } 前缀，并用「【引用消息·对方/我】」与
			 * 「【引用结束】」包起来，便于模型区分引用内容与用户本轮输入；
			 * 前端也用同一格式还原引用块。
			 * </p>
			 *
			 * @param {Object} quote 被引用消息 {role:'user'|'assistant', text:'...'}
			 * @param {String} body  用户本轮输入
			 * @return {String} 发送给服务端的完整文本
			 */
			aiBuildQuotedMessage: function (quote, body) {
				var content = body == null ? '' : String(body);
				if (!quote || !quote.text)
					return content;
				var author = quote.role === 'assistant' ? '对方' : '我';
				var lines = String(quote.text).replace(/\r\n/g, '\n').split('\n');
				var quoted = lines.map(function (line) {
					return '> ' + line;
				}).join('\n');
				return '【引用消息·' + author + '】\n' + quoted + '\n【引用结束】\n\n' + content;
			},
			/**
			 * 解析带引用消息的文本，返回 {author, quoted, body}；不是引用消息时返回 null。
			 *
			 * @param {String} text 消息文本
			 * @return {Object|null}
			 */
			aiParseQuotedMessage: function (text) {
				var src = String(text == null ? '' : text).replace(/\r\n/g, '\n');
				var lines = src.split('\n');
				if (!lines.length)
					return null;
				var head = /^【引用消息(?:·(我|对方))?】$/.exec(lines[0].trim());
				if (!head)
					return null;
				var end = -1;
				for (var i = 1; i < lines.length; i++) {
					if (lines[i].trim() === '【引用结束】') {
						end = i;
						break;
					}
				}
				if (end < 0)
					return null;
				var quoted = lines.slice(1, end).map(function (line) {
					return line.replace(/^>\s?/, '');
				}).join('\n');
				var body = lines.slice(end + 1).join('\n').replace(/^\n+/, '');
				return { author: head[1] || '', quoted: quoted, body: body };
			},
			/**
			 * 把引用正文裁剪到指定长度，避免引用整段超长回复撑爆上下文。
			 *
			 * @param {String} text  引用正文
			 * @param {Number} [max] 最大字符数，默认 2000
			 * @return {String}
			 */
			aiTruncateQuote: function (text, max) {
				var src = String(text == null ? '' : text).trim();
				var limit = max || 2000;
				return src.length > limit ? src.slice(0, limit) + '\n…（引用已截断）' : src;
			},
			/** 触发生成 */
			aiSendGenerate: function () {
				return this.aiSendFrame('generate');
			},
			/**
			 * 停止生成。
			 * <p>
			 * 服务端中断是异步的：发送成功后进入"停止中"状态，按钮保持等待态并禁止重复点击，
			 * 直到本轮真正结束（done 帧，或 status.generating=false）才由公共层复位。
			 * </p>
			 *
			 * @return {Boolean} 是否发送成功（停止中或连接未就绪时返回 false）
			 */
			aiSendStop: function () {
				if (this.aiStopping)
					return false;
				var sent = this.aiSendFrame('stop');
				if (sent)
					this.aiStopping = true;
				return sent;
			},
			/** 撤回最后一轮对话（带请求标识，回填只回到发起标签页） */
			aiSendRevert: function () {
				var requestId = newMessageId();
				this._aiRequestId = requestId;
				return this.aiSendFrame('revert', { requestId: requestId });
			},
			/** 重新生成最后一条回复（带请求标识，失败回填只回到发起标签页） */
			aiSendRegen: function () {
				var requestId = newMessageId();
				this._aiRequestId = requestId;
				return this.aiSendFrame('regen', { requestId: requestId });
			},
			/** 为最后一条回复生成语音 */
			aiSendGenVoice: function () {
				return this.aiSendFrame('genVoice');
			},
			/** 开关语音会话 */
			aiSetVoiceEnabled: function (enabled) {
				return this.aiSendFrame('setVoiceEnabled', { voiceEnabled: !!enabled });
			},
			/** 切换模型（空字符串表示默认模型） */
			aiSetModel: function (model) {
				return this.aiSendFrame('model', { model: model == null ? '' : model });
			},
			/** 回答服务端的补充输入请求 */
			aiSendPromptReply: function (input, content) {
				return this.aiSendFrame('prompt', { input: input, content: content });
			},
			/** 回答工具调用许可请求 */
			aiSendToolApproval: function (id, approved) {
				return this.aiSendFrame('toolApproval', { id: id, approved: !!approved });
			},

			/* ================= 动画管道（帧顺序 + 可等待的条件） ================= */

			/**
			 * 把需要按顺序/按动画节奏落地的帧放进串行管道。
			 * <p>
			 * 不实现 {@link #aiHandleQueuedFrame} 的页面不需要调用本方法，行为与直接处理完全一致。
			 * 管道用 Promise 链串行执行：顺序天然得到保证，页面可以在处理器里 await 动画或图片加载。
			 * </p>
			 *
			 * @param {Object} frame 上行帧（或页面自己的动画任务，需带 type）
			 */
			aiEnqueue: function (frame) {
				this._aiChain = (this._aiChain || Promise.resolve())
					.then(() => this.aiHandleQueuedFrame(frame))
					.catch((err) => console.error('[AI] 帧处理失败', frame, err));
			},
			/** 页面重写：管道里的帧如何落地（可 async / await） */
			aiHandleQueuedFrame: function (frame) {},
			/**
			 * 取消管道：切换会话、销毁时调用。
			 * <p>
			 * 世代号自增后，旧任务在 await 返回时会自行退出，不会再写入新会话的数据；
			 * 未执行的链尾被丢弃，正在进行中的揭示动画一并清空。
			 * </p>
			 */
			aiCancelPipeline: function () {
				this._aiGeneration = (this._aiGeneration || 0) + 1;
				this._aiChain = null;
				this.aiRevealClear();
			},
			/** 当前管道世代号：await 之后可用它判断任务是否已被取消 */
			aiPipelineGeneration: function () {
				return this._aiGeneration || 0;
			},
			/** 等待若干毫秒 */
			aiDelay: function (ms) {
				return new Promise((resolve) => setTimeout(resolve, ms));
			},
			/**
			 * 等待条件成立（例如立绘加载完成），被取消时立即返回。
			 *
			 * @param {Function} predicate 返回 true 表示可以继续
			 * @param {Object} [options] {timeout: 毫秒上限, interval: 轮询间隔}
			 * @return {Promise}
			 */
			aiWaitFor: function (predicate, options) {
				var opts = options || {};
				var timeout = opts.timeout != null ? opts.timeout : 5000;
				var interval = opts.interval != null ? opts.interval : 30;
				var gen = this.aiPipelineGeneration();
				var started = Date.now();
				return new Promise((resolve) => {
					var check = () => {
						if (gen !== this.aiPipelineGeneration()
								|| predicate.call(this)
								|| (timeout && Date.now() - started >= timeout)) {
							resolve();
							return;
						}
						setTimeout(check, interval);
					};
					check();
				});
			},

			/* ================= 打字机（揭示）动画 ================= */
			/*
			 * 与"逐字改写消息文本"不同，这里消息的 text 始终是完整内容，
			 * 只用一个 revealed 指针表示已显示到第几个字符：
			 *   - 数据与展示解耦，流式增量可以立即并入 text，不必阻塞管道；
			 *   - 单一 rAF 循环推进，不再有"每字符一个定时器"；
			 *   - 积压越多揭示越快（追赶），长文本不会让人等很久；
			 *   - 取消只需停循环，不留悬空定时器。
			 * 模板用 aiRevealedText/aiRevealedLines 渲染；没有 revealed 字段的消息显示全文。
			 */

			/**
			 * 登记一条消息做揭示动画（消息对象的 text 需已是完整内容）。
			 *
			 * @param {Object} msg 响应式消息对象
			 */
			aiRevealStart: function (msg) {
				if (!msg)
					return;
				if (msg.revealed == null)
					msg.revealed = 0;
				if (!this._aiRevealQueue)
					this._aiRevealQueue = [];
				if (this._aiRevealQueue.indexOf(msg) < 0)
					this._aiRevealQueue.push(msg);
				this._aiStartRevealLoop();
			},
			/** 立刻显示完整内容 */
			aiRevealFinish: function (msg) {
				if (!msg)
					return;
				msg.revealed = (msg.text == null ? '' : String(msg.text)).length;
			},
			/** 从揭示队列里移除（消息被删除时调用，避免继续为它调度） */
			aiRevealDrop: function (msg) {
				if (!msg || !this._aiRevealQueue)
					return;
				var i = this._aiRevealQueue.indexOf(msg);
				if (i >= 0)
					this._aiRevealQueue.splice(i, 1);
			},
			/** 清空揭示队列并停止动画 */
			aiRevealClear: function () {
				this._aiRevealQueue = [];
				if (this._aiRevealRaf != null && typeof cancelAnimationFrame === 'function')
					cancelAnimationFrame(this._aiRevealRaf);
				if (this._aiRevealTimer != null) {
					clearTimeout(this._aiRevealTimer);
					this._aiRevealTimer = null;
				}
				this._aiRevealRaf = null;
				this._aiRevealLast = null;
			},
			/**
			 * 已揭示的文本；没有 revealed 字段的消息返回全文（兼容不做揭示的场景）。
			 *
			 * @param {Object} msg 消息对象
			 * @return {String}
			 */
			aiRevealedText: function (msg) {
				if (!msg)
					return '';
				var text = msg.text == null ? '' : String(msg.text);
				if (msg.revealed == null)
					return text;
				return text.slice(0, Math.max(0, Math.floor(msg.revealed)));
			},
			/** 已揭示的文本按行切分，供逐行渲染的模板使用 */
			aiRevealedLines: function (msg) {
				return this.aiRevealedText(msg).split('\n');
			},
			/**
			 * 推进一帧揭示动画（与定时器解耦，便于测试）。
			 *
			 * @param {Number} dt 距上一帧的秒数
			 * @return {Boolean} 是否仍有消息处于揭示中
			 */
			aiRevealStep: function (dt) {
				var queue = this._aiRevealQueue || [];
				var active = false;
				for (var i = 0; i < queue.length; i++) {
					var msg = queue[i];
					if (!msg || msg.revealed == null)
						continue;
					var total = (msg.text == null ? '' : String(msg.text)).length;
					if (msg.revealed >= total)
						continue;
					var backlog = total - msg.revealed;
					// 积压越多揭示越快：短句接近逐字，长文自动追赶
					var cps = REVEAL_BASE_CPS + Math.max(0, backlog - REVEAL_CATCHUP_AFTER) * REVEAL_CATCHUP_GAIN;
					msg.revealed = Math.min(total, msg.revealed + cps * dt);
					active = true;
				}
				return active;
			},
			/** 启动/继续揭示循环（空闲时自动停止） */
			_aiStartRevealLoop: function () {
				if (this._aiRevealRaf != null || this._aiRevealTimer != null)
					return;
				var step = (ts) => {
					this._aiRevealRaf = null;
					var now = ts == null ? Date.now() : ts;
					var dt = this._aiRevealLast == null ? 0.016 : Math.min(REVEAL_MAX_STEP, (now - this._aiRevealLast) / 1000);
					this._aiRevealLast = now;
					if (!this.aiRevealStep(dt)) {
						this._aiRevealQueue = [];
						this._aiRevealLast = null;
						return;
					}
					this._aiScheduleReveal(step);
				};
				this._aiRevealLast = null;
				this._aiScheduleReveal(step);
			},
			_aiScheduleReveal: function (step) {
				if (typeof requestAnimationFrame === 'function') {
					this._aiRevealRaf = requestAnimationFrame(step);
				} else {
					// 无 rAF 环境（如测试）退化为 16ms 定时器
					this._aiRevealTimer = setTimeout(() => {
						this._aiRevealTimer = null;
						step(Date.now());
					}, 16);
				}
			},

			/* ================= 场景图片（立绘/背景） ================= */
			/*
			 * 各页面只需声明自己的图片槽位与资源前缀，
			 * "预加载 → 加载完成才换图 → 返回可等待的 Promise" 这套逻辑在这里统一实现，
			 * 页面不再各写一份 setXxxImage。
			 */

			/**
			 * 场景数据前缀（如 {@code 'resource/'}），页面按需重写。
			 * <p>槽位本身无需配置：{@code front}/{@code side}/{@code back} 即服务端 scene 值，
			 * 地址与加载状态统一放在公共层的 {@code aiImages} / {@code aiImageLoading}。</p>
			 */
			aiScenePrefix: function () {
				return '';
			},
			/**
			 * 查询某个图片槽位是否正在加载。
			 *
			 * @param {String} slot 槽位名（front/side/back）
			 * @return {Boolean}
			 */
			aiIsImageLoading: function (slot) {
				return !!(this.aiImageLoading && this.aiImageLoading[slot]);
			},
			/**
			 * 是否有任意图片正在加载（例如发送消息前判断立绘是否就位）。
			 *
			 * @return {Boolean}
			 */
			aiIsAnyImageLoading: function () {
				var loading = this.aiImageLoading || {};
				for (var slot in loading) {
					if (loading[slot])
						return true;
				}
				return false;
			},
			/**
			 * 拆解场景数据为图片地址列表。
			 * <p>
			 * 服务端下发的 {@code scene_data} 可能是<b>逗号分隔的多个地址</b>
			 * （例如 trpg 的多角色站位：{@code "a.png,b.png"}），
			 * 这里统一拆开并去掉空白项，单图场景会得到长度为 1 的列表。
			 * </p>
			 *
			 * @param {String} data 场景数据
			 * @return {Array} 地址列表（不含前缀）
			 */
			aiSplitSceneData: function (data) {
				if (data == null || data === '')
					return [];
				var parts = String(data).split(',');
				var urls = [];
				for (var i = 0; i < parts.length; i++) {
					var part = parts[i].replace(/^\s+|\s+$/g, '');
					if (part)
						urls.push(part);
				}
				return urls;
			},
			/**
			 * 重置所有图片：清空各槽位地址、图片列表与加载状态，并让仍在途的加载结果失效
			 * （切换会话时调用，避免上一个会话的立绘/背景残留）。
			 */
			aiResetImages: function () {
				if (!this.aiImages)
					this.aiImages = {};
				if (!this.aiImageLists)
					this.aiImageLists = {};
				if (!this.aiImageLoading)
					this.aiImageLoading = {};
				if (!this._aiImageSeq)
					this._aiImageSeq = {};
				for (var i = 0; i < AI_IMAGE_SLOTS.length; i++) {
					var slot = AI_IMAGE_SLOTS[i];
					this._aiImageSeq[slot] = (this._aiImageSeq[slot] || 0) + 1;
					this.aiImages[slot] = '';
					this.aiImageLists[slot] = [];
					this.aiImageLoading[slot] = false;
				}
			},
			/**
			 * 切换某个槽位的图片。
			 * <p>
			 * 入参可以是逗号分隔的字符串，也可以是数组（多张图会全部预加载）；
			 * <b>全部加载完成后才把地址写入</b> {@code aiImageLists[slot]} 与首图 {@code aiImages[slot]}，
			 * 期间加载状态记录在 {@code aiImageLoading[slot]}；
			 * 若有任一张加载失败，则保持原图不变（与原单图行为一致）。
			 * 返回的 Promise 在加载结束（成功或失败）时 resolve，供动画管道等待。
			 * </p>
			 *
			 * @param {String|Array} src 图片地址（可逗号分隔）
			 * @return {Promise}
			 */
			aiSetImage: function (slot, src) {
				if (AI_IMAGE_SLOTS.indexOf(slot) < 0)
					return Promise.resolve();          // 未知槽位：忽略
				if (!this.aiImages)
					this.aiImages = {};
				if (!this.aiImageLists)
					this.aiImageLists = {};
				if (!this.aiImageLoading)
					this.aiImageLoading = {};
				if (!this._aiImageSeq)
					this._aiImageSeq = {};
				var urls;
				if (Object.prototype.toString.call(src) === '[object Array]') {
					urls = [];
					for (var i = 0; i < src.length; i++) {
						if (src[i])
							urls.push(String(src[i]));
					}
				} else {
					urls = this.aiSplitSceneData(src);
				}
				var current = this.aiImageLists[slot] || [];
				if (urls.length === 0) {
					if (current.length === 0 && !this.aiImages[slot])
						return Promise.resolve();
					this._aiImageSeq[slot] = (this._aiImageSeq[slot] || 0) + 1;
					this.aiImages[slot] = '';
					this.aiImageLists[slot] = [];
					this.aiImageLoading[slot] = false;
					return Promise.resolve();
				}
				// 与当前列表完全一致且不在加载中：无需重载
				if (urls.join('\n') === current.join('\n') && !this.aiImageLoading[slot])
					return Promise.resolve();
				var self = this;
				var seq = (this._aiImageSeq[slot] || 0) + 1;
				this._aiImageSeq[slot] = seq;
				var pending = urls.length;
				var failed = false;
				var loaded = new Promise(function (resolve) {
					var settle = function () {
						if (--pending > 0)
							return;
						// 期间没有更新的请求（或重置）才落地
						if (self._aiImageSeq[slot] === seq) {
							self.aiImageLoading[slot] = false;
							if (!failed) {
								self.aiImageLists[slot] = urls;
								self.aiImages[slot] = urls[0];   // 全部加载完成才换图
							}
						}
						resolve();
					};
					for (var k = 0; k < urls.length; k++) {
						(function (url) {
							var img = new Image();
							img.onload = settle;
							img.onerror = function (err) {
								failed = true;
								console.error('图片加载失败', url, err);
								settle();
							};
							img.src = url;
						})(urls[k]);
					}
				});
				this.aiImageLoading[slot] = true;
				return loaded;
			},
			/**
			 * 应用场景：立即开始切换（本方法不阻塞），返回加载完成的 Promise。
			 * <p>场景数据按逗号拆分为多张图片，并逐个加上 {@link #aiScenePrefix}。</p>
			 *
			 * @param {String} scene 场景槽位
			 * @param {String} data  场景数据（不含前缀，可逗号分隔多张）
			 * @return {Promise}
			 */
			aiApplyScene: function (scene, data) {
				var urls = this.aiSplitSceneData(data);
				var prefix = this.aiScenePrefix();
				for (var i = 0; i < urls.length; i++)
					urls[i] = prefix + urls[i];
				return this.aiSetImage(scene, urls);
			},

			/* ================= 统一对话框（替代 prompt / confirm） ================= */

			/**
			 * 统一样式的输入对话框，替代浏览器 {@code prompt}。
			 *
			 * @param {String|Object} options 文案，或 {title, message, value, placeholder, okText, cancelText}
			 * @return {Promise<String|null>} 确定返回输入内容，取消/Esc/点遮罩返回 null（与 prompt 语义一致）
			 */
			aiPrompt: function (options) {
				return this._aiOpenDialog('prompt', options);
			},
			/**
			 * 统一样式的确认对话框，替代浏览器 {@code confirm}。
			 *
			 * @param {String|Object} options 文案，或 {title, message, okText, cancelText, kind:'danger'}
			 * @return {Promise<Boolean>} 确定返回 true，取消返回 false
			 */
			aiConfirm: function (options) {
				return this._aiOpenDialog('confirm', options);
			},
			_aiOpenDialog: function (mode, options) {
				var opts = typeof options === 'string' ? { message: options } : (options || {});
				// 上一次对话框还没关闭：先按取消处理，避免 Promise 悬挂
				if (this._aiDialogResolve) {
					var previous = this._aiDialogResolve;
					this._aiDialogResolve = null;
					previous(mode === 'prompt' ? null : false);
				}
				this.aiDialog = {
					open: true,
					mode: mode,
					title: opts.title || (mode === 'prompt' ? '请输入' : '请确认'),
					message: opts.message || '',
					value: opts.value != null ? String(opts.value) : '',
					placeholder: opts.placeholder || '',
					okText: opts.okText || '确定',
					cancelText: opts.cancelText || '取消',
					kind: opts.kind || '',
				};
				this._aiDialogSeq = (this._aiDialogSeq || 0) + 1;   // 组件据此重新聚焦
				var self = this;
				return new Promise(function (resolve) {
					self._aiDialogResolve = resolve;
				});
			},
			/** 输入框内容变化（由对话框组件调用） */
			aiDialogInput: function (value) {
				if (this.aiDialog)
					this.aiDialog.value = value == null ? '' : value;
			},
			/** 确定 */
			aiDialogOk: function () {
				var dialog = this.aiDialog || {};
				this._aiCloseDialog(dialog.mode === 'prompt' ? (dialog.value == null ? '' : String(dialog.value)) : true);
			},
			/** 取消（Esc / 点遮罩 / 取消按钮） */
			aiDialogCancel: function () {
				this._aiCloseDialog((this.aiDialog && this.aiDialog.mode === 'prompt') ? null : false);
			},
			_aiCloseDialog: function (result) {
				var resolve = this._aiDialogResolve;
				this._aiDialogResolve = null;
				if (this.aiDialog)
					this.aiDialog.open = false;
				if (resolve)
					resolve(result);
			},

			/* ================= 连接与心跳 ================= */

			aiInitHeartbeat: function () {
				if (this._aiHeartbeat)
					return this._aiHeartbeat;
				if (typeof createHeartbeat !== 'function') {
					console.warn('[AI] 未加载 heartbeat.js，心跳保活不可用');
					return null;
				}
				this._aiHeartbeat = createHeartbeat({
					getSocket: () => this.aiSocket,
					interval: HEARTBEAT_INTERVAL,
					timeout: HEARTBEAT_TIMEOUT,
					onTimeout: () => this.aiOnHeartbeatTimeout(),
				});
				return this._aiHeartbeat;
			},

			/** 建立连接（会先断开旧连接）；重复调用是安全的 */
			aiConnect: function () {
				var url = this.aiSocketUrl();
				if (!url) {
					console.warn('[AI] 未提供 WebSocket 地址，放弃连接');
					return;
				}
				this.aiDisconnect();
				this._aiReconnectAttempts = 0;
				this._aiOpenSocket(url);
			},
			_aiOpenSocket: function (url) {
				if (this._aiReconnectTimer) {
					clearTimeout(this._aiReconnectTimer);
					this._aiReconnectTimer = null;
				}
				this.aiStopHeartbeat();
				this._aiCloseSocket();
				this._aiSetConnState('connecting', this._aiConnReason, this._aiReconnectAttempts);
				var ws;
				try {
					ws = new WebSocket(url);
				} catch (err) {
					console.error('[AI] 建立连接失败', err);
					this._aiConnReason = 'error';
					this._aiShowConnTip('error');
					this._aiScheduleReconnect();
					return;
				}
				this.aiSocket = ws;
				ws.onopen = () => {
					this.aiConnected = true;
					this._aiReconnectAttempts = 0;
					this._aiConnReason = null;
					var wasDown = this.aiHideConnTip();
					this.aiStartHeartbeat();
					this._aiSetConnState('connected', null, 0);
					// 先恢复上次未确认的发送，再让页面处理连接成功（页面可能立刻发送/读写草稿）
					this.aiOutboxLoad();
					this.aiOnSocketOpen();
					if (this._aiOutbox.length) {
						this._aiResolvePending = true;
						this.aiScheduleOutboxResolve();
					}
					if (wasDown)
						this.aiToast('已重新连接');
				};
				ws.onmessage = (event) => {
					var frame;
					try {
						frame = JSON.parse(event.data);
					} catch (err) {
						console.warn('[AI] 解析消息失败', err);
						return;
					}
					this.aiHandleFrame(frame);
				};
				ws.onerror = (err) => {
					console.error('[AI] WebSocket 错误', err);
					this.aiStopHeartbeat();
					this._aiConnReason = 'error';
					this._aiShowConnTip('error');
					this._aiSetConnState('error', 'error', this._aiReconnectAttempts);
				};
				ws.onclose = () => {
					this.aiConnected = false;
					this.aiStopHeartbeat();
					this.aiSocket = null;
					// 心跳超时会先自行判定断开，此时不要覆盖成"连接断开"
					if (this._aiConnReason !== 'timeout')
						this._aiConnReason = 'closed';
					this._aiShowConnTip(this._aiConnReason);
					this._aiSetConnState('closed', this._aiConnReason, this._aiReconnectAttempts);
					this._aiScheduleReconnect();
				};
			},
			/** 摘掉监听后关闭 socket，避免触发 onclose 里的自动重连 */
			_aiCloseSocket: function () {
				var ws = this.aiSocket;
				this.aiSocket = null;
				if (!ws)
					return;
				if (typeof detachAndCloseSocket === 'function') {
					detachAndCloseSocket(ws);
					return;
				}
				try {
					ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null;
					ws.close();
				} catch (err) {
					console.log(err);
				}
			},
			/** 主动断开并且不再重连（页面销毁、切换会话时使用） */
			aiDisconnect: function () {
				this.aiStopHeartbeat();
				if (this._aiReconnectTimer) {
					clearTimeout(this._aiReconnectTimer);
					this._aiReconnectTimer = null;
				}
				this._aiCloseSocket();
				this.aiConnected = false;
				this.aiConnState = 'closed';
				this.aiResetGenerationState();
				this._aiConnReason = null;
				this._aiReconnectAttempts = 0;
				this.aiHideConnTip();
			},
			/**
			 * 复位"正在生成/停止中"的本地状态。
			 * <p>
			 * 断开、重连时本地无法知道服务端的真实进度，一律先复位；重连后服务端会下发
			 * status 帧，把生成状态纠正为真实值（此时"停止中"不该保留：按钮应回到"停止"，
			 * 让用户能对仍在进行的生成重新发起停止）。
			 * </p>
			 */
			aiResetGenerationState: function () {
				this.aiGenerating = false;
				this.aiStopping = false;
			},
			/**
			 * 更新连接状态并通知页面；非 connected 状态时顺带复位生成状态。
			 *
			 * @param {String} state  'connecting' | 'connected' | 'closed' | 'error'
			 * @param {String} [reason]
			 * @param {Number} [attempt]
			 */
			_aiSetConnState: function (state, reason, attempt) {
				this.aiConnState = state;
				if (state !== 'connected')
					this.aiResetGenerationState();
				this.aiOnConnState(state, reason, attempt);
			},
			_aiScheduleReconnect: function () {
				if (this._aiReconnectTimer || this._aiDestroyed)
					return;
				var attempt = this._aiReconnectAttempts || 0;
				var delay = attempt < RECONNECT_FAST_TRIES ? 0 : RECONNECT_DELAY;
				this._aiReconnectTimer = setTimeout(() => {
					this._aiReconnectTimer = null;
					if (this.aiSocket)
						return;
					this._aiReconnectAttempts = attempt + 1;
					console.log('[AI] 重连（第 ' + this._aiReconnectAttempts + ' 次）');
					this._aiShowConnTip(null, this._aiReconnectAttempts);
					this._aiOpenSocket(this.aiSocketUrl());
				}, delay);
			},
			aiStartHeartbeat: function () {
				var hb = this.aiInitHeartbeat();
				if (hb)
					hb.start();
			},
			aiStopHeartbeat: function () {
				if (this._aiHeartbeat)
					this._aiHeartbeat.stop();
			},
			aiOnHeartbeatTimeout: function () {
				console.warn('[AI] 心跳超时，判定连接已断开，主动重连');
				this._aiConnReason = 'timeout';
				this._aiShowConnTip('timeout');
				this.aiConnected = false;
				this._aiOpenSocket(this.aiSocketUrl());
			},
			/** 发送一帧；对象会被序列化 */
			aiSend: function (payload) {
				var ws = this.aiSocket;
				if (!ws || ws.readyState !== 1) {
					console.warn('[AI] 连接未就绪，消息未发送', payload);
					return false;
				}
				ws.send(typeof payload === 'string' ? payload : JSON.stringify(payload));
				return true;
			},
			aiIsOpen: function () {
				return !!this.aiSocket && this.aiSocket.readyState === 1;
			},
			_aiShowConnTip: function (reason, attempt) {
				if (reason)
					this._aiConnReason = reason;
				if (attempt != null)
					this._aiReconnectAttemptsShown = attempt;
				if (!this._aiConnReason)
					this._aiConnReason = 'closed';
				var times = attempt != null ? attempt : this._aiReconnectAttemptsShown || 1;
				this.connTipKind = this._aiConnReason;      // timeout | error | closed，供样式区分
				this.connTipAttempt = times;
				this.connTip = connTipText(this._aiConnReason, times);
			},
			aiHideConnTip: function () {
				if (!this.connTip)
					return false;
				this.connTip = null;
				this.connTipKind = 'closed';
				this.connTipAttempt = 0;
				this._aiConnReason = null;
				this._aiReconnectAttemptsShown = 0;
				return true;
			},

			/* ================= 帧路由 ================= */

			/**
			 * 统一帧入口：公共帧在这里处理完，其余交给页面钩子。
			 *
			 * @param {Object} frame 下行帧
			 */
			aiHandleFrame: function (frame) {
				if (!frame || typeof frame !== 'object')
					return;
				var type = frame.type;
				if (type === 'pong') {
					if (this._aiHeartbeat)
						this._aiHeartbeat.onPong(frame);
					return;
				}
				switch (type) {
					case 'status':
						this.aiApplyStatus(frame);
						this.aiOnStatus(frame);
						return;
					case 'done':
						this.aiGenerating = false;
						// 本轮结束：退出"停止中"，按钮恢复可用
						this.aiStopping = false;
						if (frame.price != null)
							this.aiPrice = formatPrice(frame.price);
						// 本轮配额用量（累计 getEquivantTokens 的增加值），只有数值、不带明细
						if (frame.tokens != null)
							this.aiTurnTokens = Number(frame.tokens) || 0;
						this.aiOnDone(frame);
						return;
					case 'batch_messages':
						this.aiHandleBatch(frame);
						return;
					case 'sendResult':
						// 服务端对某次发送的确认/拒收（只发给发起连接）
						this.aiHandleSendResult(frame);
						return;
					case 'notice':
						this.aiOnNotice(frame.content);
						return;
					case 'sendbox':
						// 回填请求：带 msgId/requestId，由公共层做条件恢复，不再无条件覆盖输入框
						this.aiApplySendbox(frame);
						return;
					case 'model':
						this.aiModel = frame.model || '';
						this.aiOnModel(frame.model);
						return;
					case 'voice':
						if (frame.isVoiceEnabled != null)
							this.aiVoiceEnabled = !!frame.isVoiceEnabled;
						if (frame.isVoiceSupported != null)
							this.aiVoiceSupported = !!frame.isVoiceSupported;
						if (frame.isVoiceUsable != null)
							this.aiVoiceUsable = !!frame.isVoiceUsable;
						this.aiOnVoice(frame);
						return;
					case 'scene':
						this.aiOnScene(frame.scene, frame.scene_data);
						return;
					case 'input':
						this.aiOnInput(frame.input, frame.prompt);
						return;
					case 'toolApproval':
						this.aiOnToolApproval(frame);
						return;
					case 'toolApprovalResult':
						this.aiOnToolApprovalResult(frame);
						return;
					case 'local_request':
						this.aiOnLocalRequest(frame);
						return;
					case 'thinking':
						this.aiOnThinking(frame);
						return;
					case 'tool_call':
						this.aiOnToolCall(frame);
						return;
					case 'message':
						// 服务端已把这条用户消息写进历史：消掉对应的 Outbox 项
						this.aiReconcileOutboxMessage(frame);
						this.aiOnMessage(frame);
						return;
					case 'content':
						this.aiOnContent(frame);
						return;
					case 'remove':
						this.aiOnRemove(frame);
						return;
					case 'audio':
						this.aiOnAudio(frame);
						return;
					default:
						this.aiOnFrame(frame);
				}
			},
			/** 结构化内容数组 -> 纯文本 */
			aiContentToText: function (content) {
				return contentToText(content);
			},
			/**
			 * 把状态帧写入公共状态变量（页面的 aiOnStatus 只需处理额外的页面专属逻辑）。
			 *
			 * @param {Object} frame status 帧
			 */
			aiApplyStatus: function (frame) {
				if (frame.generating != null) {
					this.aiGenerating = !!frame.generating;
					// 服务端确认本轮已结束（例如停止请求到达时已经没有输出）：退出"停止中"
					if (!this.aiGenerating)
						this.aiStopping = false;
				}
				if (frame.price != null)
					this.aiPrice = formatPrice(frame.price);
				if (frame.tokens != null)
					this.aiTurnTokens = Number(frame.tokens) || 0;
				if (frame.isVoiceEnabled != null)
					this.aiVoiceEnabled = !!frame.isVoiceEnabled;
				if (frame.isVoiceSupported != null)
					this.aiVoiceSupported = !!frame.isVoiceSupported;
				if (frame.isVoiceUsable != null)
					this.aiVoiceUsable = !!frame.isVoiceUsable;
				if (frame.model !== undefined)
					this.aiModel = frame.model || '';
				if (frame.modelTree)
					this.aiModelTree = frame.modelTree;
			},
			/** 用户角色的显示名，用于没有 role 字段的老数据 */
			aiUserTitle: '你',
			/** 判断某条消息是否来自用户 */
			aiIsUserMessage: function (msg) {
				return isUserMessage(msg, this.aiUserTitle);
			},
			/**
			 * 为一条消息分配本地稳定 key（消息整个生命周期内不再变化）。
			 * <p>
			 * 流式消息先以匿名形态出现（还没有历史 id），服务端随后才把正式条目的 id 补下来；
			 * 如果 v-for 的 key 由 _id 推导，补 id 的那一刻 key 会变化，Vue 会销毁并重建节点，
			 * 页面上的"AI 框"就会闪一下（出现→消失→出现，入场动画重放）。
			 * 因此消息对象在创建时分配 _key，模板统一按 _key 渲染：补 id 只改 _id，不动节点。
			 * </p>
			 *
			 * @param {Number} [id] 历史记录 id；有 id 时按 id 派生，便于分页时复用同一节点
			 * @return {String} 稳定的 key
			 */
			aiMessageKey: function (id) {
				if (id != null)
					return 'm' + id;
				this._aiMsgKeySeq = (this._aiMsgKeySeq || 0) + 1;
				return 'k' + this._aiMsgKeySeq;
			},
			/** 把一批历史记录标准化为 {id,title,role,text,content,thinking,audioId,tokenCost} */
			aiNormalizeBatchItems: function (messages) {
				var items = [];
				if (!Array.isArray(messages))
					return items;
				for (var i = 0; i < messages.length; i++) {
					var m = messages[i] || {};
					items.push({
						id: m.id != null ? m.id : null,
						title: m.title || '',
						role: m.role || (isUserMessage(m, this.aiUserTitle) ? 'user' : 'assistant'),
						content: m.content || [],
						text: contentToText(m.content),
						thinking: Array.isArray(m.thinking) ? m.thinking : [],
						audioId: m.audioId != null ? m.audioId : null,
						// 该条记录所属轮次的配额用量（服务端历史字段 token_cost）
						tokenCost: Number(m.token_cost) || 0,
					});
				}
				return items;
			},

			/* ================= 历史记录分页 ================= */

			/** 向上滚动到顶部附近时请求更早的一页 */
			onHistoryScroll: function (e) {
				var c = (e && e.target) || this.aiHistoryContainer();
				if (!c)
					return;
				this.aiOnScroll(c);
				// 内容增长本身不触发 scroll 事件，这里只在真实滚动时记录"是否在顶部"
				if (c.scrollTop > HISTORY_TOP_THRESHOLD) {
					this._aiAtHistoryTop = false;
					return;
				}
				this._aiAtHistoryTop = true;
				if (this.hasMoreHistory)
					this.loadMoreHistory();
				else {
					this.historyTopReached = true;
					this.aiOnHistoryTop();
				}
			},
			/** 请求更早的一页；beforeId 缺省时取本地最旧的一条 */
			loadMoreHistory: function () {
				if (this.isLoadingMore || !this.hasMoreHistory)
					return;
				if (!this.aiIsOpen())
					return;
				var beforeId = this.aiOldestMessageId();
				this.isLoadingMore = true;
				this.aiRequestHistory(beforeId);
			},
			/** 发送分页请求（payload 使用统一的上行协议） */
			aiRequestHistory: function (beforeId) {
				this.aiSend({ type: 'requestBackLog', before_id: beforeId == null ? 0 : beforeId });
			},
			/** 页面重写：返回本地最旧的一条记录 id（无则返回 0） */
			aiOldestMessageId: function () { return 0; },
			/** 切换会话时复位分页状态与未确认发送（Outbox 按会话隔离） */
			aiResetHistory: function () {
				this.isLoadingMore = false;
				this.hasMoreHistory = false;
				this.historyTopReached = false;
				this.suppressAutoScroll = false;
				this._aiAtHistoryTop = false;
				// 先把当前会话的未确认发送写回旧键，再清空内存；新连接会按新会话的键恢复。
				// 首次加载时还没从存储里恢复，不能在这里写空记录，否则会把上次未发送的内容抹掉。
				if (this._aiOutboxLoaded || this._aiOutbox.length)
					this.aiOutboxPersist();
				if (this._aiResolveTimer) {
					clearTimeout(this._aiResolveTimer);
					this._aiResolveTimer = null;
				}
				this._aiResolvePending = false;
				this._aiOutbox = [];
				this._aiRecentSends = {};
				this._aiOutboxLoaded = false;
				this._aiOutboxKey = null;
				this._aiDraftMsgId = null;
				this._aiDraftText = null;
				this._aiPendingRefill = null;
				this.aiHideRefillChip();
			},
			/**
			 * 处理一批历史记录：paging 状态在这里收敛，列表变更交给 aiOnBatch，
			 * 向上翻页时由本方法负责视口锚定（插入后保持阅读位置）。
			 */
			aiHandleBatch: function (frame) {
				var meta = { prepend: frame.prepend === true, hasMore: frame.hasMore === true };
				var items = this.aiNormalizeBatchItems(frame.messages);
				var container = this.aiHistoryContainer();
				var prevHeight = container ? container.scrollHeight : 0;
				var prevTop = container ? container.scrollTop : 0;
				this.hasMoreHistory = meta.hasMore;
				this.isLoadingMore = false;
				// 先用最新一页历史对账未确认的发送；结算留给定时器，给同一批 in-flight 的
				// message 帧留出到达时间，避免把刚写入历史的消息又还给用户
				this.aiReconcileOutbox(items, meta);
				if (meta.prepend) {
					this.suppressAutoScroll = true;
					this.aiOnBatch(items, meta);
					this.$nextTick(() => {
						if (container)
							container.scrollTop = container.scrollHeight - prevHeight + prevTop;
						this.suppressAutoScroll = false;
					});
				} else {
					this.historyTopReached = false;
					this.aiOnBatch(items, meta);
				}
			},
		},
	};

	/*
	 * 全局注册连接提示条组件：各页只需在 Vue 根元素内放一个 <ai-conn-tip></ai-conn-tip>，
	 * 文案与样式由公共层统一维护（连接超时 / 连接错误 / 连接断开 + 重试次数），
	 * 不再各页自己写提示、也不再使用 alert。
	 */
	if (window.Vue && !window.Vue.options.components['ai-conn-tip']) {
		window.Vue.component('ai-conn-tip', {
			render: function (h) {
				var root = this.$root;
				if (!root || !root.connTip)
					return h();
				return h('div', {
					class: ['ai-conn-tip', 'is-' + (root.connTipKind || 'closed')],
					attrs: { role: 'status', 'aria-live': 'polite' },
				}, [
					h('span', { class: 'ai-conn-spinner' }),
					h('span', { class: 'ai-conn-text' }, root.connTip),
				]);
			},
		});
	}

	/*
	 * 全局注册统一对话框组件：各页只需在 Vue 根元素内放一个 <ai-dialog></ai-dialog>，
	 * 由公共层的 aiPrompt() / aiConfirm() 驱动，替代浏览器 prompt / confirm。
	 */
	if (window.Vue && !window.Vue.options.components['ai-dialog']) {
		window.Vue.component('ai-dialog', {
			render: function (h) {
				var root = this.$root;
				var dialog = root && root.aiDialog;
				if (!dialog || !dialog.open)
					return h();
				var self = this;
				var children = [];
				if (dialog.title)
					children.push(h('div', { class: 'ai-dialog-title' }, dialog.title));
				if (dialog.message)
					children.push(h('div', { class: 'ai-dialog-message' }, dialog.message));
				if (dialog.mode === 'prompt') {
					children.push(h('input', {
						ref: 'input',
						class: 'ai-dialog-input',
						attrs: { type: 'text', placeholder: dialog.placeholder || '' },
						domProps: { value: dialog.value },
						on: {
							input: function (e) { root.aiDialogInput(e.target.value); },
							keydown: function (e) {
								if (e.key === 'Enter' || e.keyCode === 13) {
									e.preventDefault();
									root.aiDialogOk();
								}
							},
						},
					}));
				}
				children.push(h('div', { class: 'ai-dialog-actions' }, [
					h('button', {
						class: 'ai-dialog-btn is-cancel',
						attrs: { type: 'button' },
						on: { click: function () { root.aiDialogCancel(); } },
					}, dialog.cancelText || '取消'),
					h('button', {
						ref: 'ok',
						class: ['ai-dialog-btn', 'is-ok', dialog.kind ? 'is-' + dialog.kind : ''],
						attrs: { type: 'button' },
						on: { click: function () { root.aiDialogOk(); } },
					}, dialog.okText || '确定'),
				]));
				return h('div', {
					class: 'ai-dialog-mask',
					attrs: { role: 'dialog', 'aria-modal': 'true' },
					on: {
						click: function (e) {
							if (e.target === e.currentTarget)
								root.aiDialogCancel();     // 点遮罩 = 取消
						},
					},
				}, [h('div', { class: 'ai-dialog' }, children)]);
			},
			mounted: function () {
				var self = this;
				this._aiDialogKeydown = function (e) {
					var dialog = self.$root && self.$root.aiDialog;
					if (!dialog || !dialog.open)
						return;
					if (e.key === 'Escape' || e.keyCode === 27) {
						e.preventDefault();
						self.$root.aiDialogCancel();
					}
				};
				if (typeof document !== 'undefined')
					document.addEventListener('keydown', this._aiDialogKeydown);
			},
			beforeDestroy: function () {
				if (typeof document !== 'undefined' && this._aiDialogKeydown)
					document.removeEventListener('keydown', this._aiDialogKeydown);
			},
			updated: function () {
				// 每次打开时自动聚焦（输入框优先，其次确定按钮）
				var root = this.$root;
				var dialog = root && root.aiDialog;
				if (!dialog || !dialog.open) {
					this._aiDialogFocused = 0;
					return;
				}
				if (this._aiDialogFocused === root._aiDialogSeq)
					return;
				this._aiDialogFocused = root._aiDialogSeq;
				var el = this.$refs.input || this.$refs.ok;
				if (el && el.focus)
					el.focus();
				if (this.$refs.input && this.$refs.input.select)
					this.$refs.input.select();
			},
		});
	}

	/* ======================= 站点地址 ======================= */
	/*
	 * 站点域名不再写死在页面里，取值顺序：
	 *   1. 服务端 site-config.js 注入的 window.AI_SITE.host
	 *      （对应启动参数 --site-host=example.com 或 -Dsite_host=example.com）；
	 *   2. 未配置时回退到当前页面的 location.host / location.protocol，
	 *      因此自建部署、反代、本地调试都不需要改代码。
	 * 注意：site-config.js 加载失败（例如直接以 file:// 打开）时同样走回退逻辑。
	 *
	 * WebSocket 地址与网页地址分开：
	 *   - 网页 / HTTP 地址用 host / secure；
	 *   - WebSocket 地址用 wsHost / wsSecure（site.json 的 "ws_host"），
	 *     wsHost 未配置时自动与 host 相同。于是网页可以走 example.com，
	 *     而 WebSocket 走 ws.example.com 或独立端口。
	 */

	/**
	 * 站点域名（host[:port]）。
	 *
	 * @return {String} 站点域名
	 */
	function siteHost() {
		var cfg = global.AI_SITE;
		if (cfg && typeof cfg.host === 'string' && cfg.host)
			return cfg.host;
		return global.location && global.location.host ? global.location.host : 'localhost';
	}

	/**
	 * 站点是否使用加密协议（wss / https）。
	 *
	 * @return {Boolean} 是否加密
	 */
	function siteSecure() {
		var cfg = global.AI_SITE;
		if (cfg && typeof cfg.host === 'string' && cfg.host)
			return cfg.secure !== false;
		return !!(global.location && global.location.protocol === 'https:');
	}

	/**
	 * WebSocket 专用域名（host[:port]）。
	 * <p>优先取 site-config.js 的 {@code wsHost}；未配置时与站点域名一致。</p>
	 *
	 * @return {String} WebSocket 域名
	 */
	function siteWsHost() {
		var cfg = global.AI_SITE;
		if (cfg && typeof cfg.wsHost === 'string' && cfg.wsHost)
			return cfg.wsHost;
		return siteHost();
	}

	/**
	 * WebSocket 是否使用加密协议（wss）。
	 * <p>取 site-config.js 的 {@code wsSecure}；未单独配置 {@code wsHost} 时与站点一致。</p>
	 *
	 * @return {Boolean} 是否加密
	 */
	function siteWsSecure() {
		var cfg = global.AI_SITE;
		if (cfg && typeof cfg.wsHost === 'string' && cfg.wsHost)
			return cfg.wsSecure !== false;
		return siteSecure();
	}

	/**
	 * 站点 WebSocket 地址前缀。
	 *
	 * @return {String} 形如 wss://example.com
	 */
	function aiSiteWsBase() {
		return (siteWsSecure() ? 'wss:' : 'ws:') + '//' + siteWsHost();
	}

	/**
	 * 站点 HTTP 地址前缀。
	 *
	 * @return {String} 形如 https://example.com
	 */
	function aiSiteHttpBase() {
		return (siteSecure() ? 'https:' : 'http:') + '//' + siteHost();
	}

	global.aiSiteHost = siteHost;
	global.aiSiteSecure = siteSecure;
	global.aiSiteWsHost = siteWsHost;
	global.aiSiteWsSecure = siteWsSecure;
	global.aiSiteWsBase = aiSiteWsBase;
	global.aiSiteHttpBase = aiSiteHttpBase;
	global.AIProtocolMixin = AIProtocolMixin;
	global.aiContentToText = contentToText;
})(window);
