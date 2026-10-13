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
 *   status{generating,[conversation_id,price,isVoiceEnabled,isVoiceUsable,models,modelTree,model]}
 *   model{model} / voice{isVoiceEnabled?|isVoiceUsable?} / notice{content} / sendbox{content}
 *   message{id,title,content[]} / content{id,content[]} / remove{id} / batch_messages{messages[],prepend,hasMore,oldestId,newestId}
 *   thinking{content} / tool_call{tool_name} / audio{id,audioId} / done{price} / scene{scene,scene_data}
 *   input{input,prompt} / toolApproval{...} / toolApprovalResult{...} / local_request{...} / pong{id}
 * 其中 content[] 为结构化内容数组（{type:"text",text}/{type:"image",image_url}/...）。
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
			return {
				aiConnected: false,        // 传输层是否已连接
				aiConnState: 'closed',     // 'connecting' | 'connected' | 'closed'
				connTip: null,             // 连接状态提示条文案，null 表示不显示
				// ---- 服务端状态帧（status / model / voice / done）维护的会话状态，页面直接读取，不再各自复制 ----
				aiGenerating: false,       // 服务端是否正在生成（status.generating，done 会复位）
				aiPrice: '0.00',           // 本会话消费价格（status.price / done.price）
				aiVoiceEnabled: false,     // 语音会话开关（status.isVoiceEnabled / voice.isVoiceEnabled）
				aiVoiceUsable: false,      // 当前是否可用语音（status.isVoiceUsable / voice.isVoiceUsable）
				aiModel: '',               // 当前生效模型标识（status.model / model.model，空为默认模型）
				aiModelTree: [],           // 可用模型目录树（status.modelTree）
				// ---- 历史记录分页 ----
				isLoadingMore: false,      // 是否正在加载更早的历史记录
				hasMoreHistory: false,     // 服务端是否还有更早的历史记录
				historyTopReached: false,  // 是否已经翻到最早一条
				suppressAutoScroll: false, // 插入历史记录期间抑制自动滚动
			};
		},
		mounted: function () {
			// popstate：全屏看图等覆盖层用后退键关闭（页面实现 aiOnPopState 时生效）
			this._aiOnPopState = () => this.aiOnPopState();
			if (global.addEventListener)
				global.addEventListener('popstate', this._aiOnPopState);
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
		},
		beforeDestroy: function () {
			if (global.removeEventListener && this._aiOnPopState)
				global.removeEventListener('popstate', this._aiOnPopState);
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
			/** 输入框回填 */
			aiOnSendbox: function (text) {},
			/** 模型切换回执 */
			aiOnModel: function (model) {},
			/** 语音开关变化 */
			aiOnVoice: function (frame) {},
			/** 生成结束 */
			aiOnDone: function (frame) {},
			/** 场景（立绘/背景）切换 */
			aiOnScene: function (scene, data) {},
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
			/** 轻提示，页面有更合适的实现时可重写 */
			aiToast: function (text) {
				if (text)
					console.log('[AI] ' + text);
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
			/** 用户发言 */
			aiSendMessage: function (text) {
				return this.aiSendFrame('message', { content: text });
			},
			/** 触发生成 */
			aiSendGenerate: function () {
				return this.aiSendFrame('generate');
			},
			/** 停止生成 */
			aiSendStop: function () {
				return this.aiSendFrame('stop');
			},
			/** 撤回最后一轮对话 */
			aiSendRevert: function () {
				return this.aiSendFrame('revert');
			},
			/** 重新生成最后一条回复 */
			aiSendRegen: function () {
				return this.aiSendFrame('regen');
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
				this.aiConnState = 'connecting';
				this.aiOnConnState('connecting', this._aiConnReason, this._aiReconnectAttempts);
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
					this.aiConnState = 'connected';
					this._aiReconnectAttempts = 0;
					this._aiConnReason = null;
					var wasDown = this.aiHideConnTip();
					this.aiStartHeartbeat();
					this.aiOnConnState('connected', null, 0);
					this.aiOnSocketOpen();
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
					this.aiOnConnState('error', 'error', this._aiReconnectAttempts);
				};
				ws.onclose = () => {
					this.aiConnected = false;
					this.aiConnState = 'closed';
					this.aiStopHeartbeat();
					this.aiSocket = null;
					// 心跳超时会先自行判定断开，此时不要覆盖成"连接断开"
					if (this._aiConnReason !== 'timeout')
						this._aiConnReason = 'closed';
					this._aiShowConnTip(this._aiConnReason);
					this.aiOnConnState('closed', this._aiConnReason, this._aiReconnectAttempts);
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
				this._aiConnReason = null;
				this._aiReconnectAttempts = 0;
				this.aiHideConnTip();
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
				this.connTip = connTipText(this._aiConnReason, attempt != null ? attempt : this._aiReconnectAttemptsShown || 1);
			},
			aiHideConnTip: function () {
				if (!this.connTip)
					return false;
				this.connTip = null;
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
						if (frame.price != null)
							this.aiPrice = formatPrice(frame.price);
						this.aiOnDone(frame);
						return;
					case 'batch_messages':
						this.aiHandleBatch(frame);
						return;
					case 'notice':
						this.aiOnNotice(frame.content);
						return;
					case 'sendbox':
						this.aiOnSendbox(frame.content);
						return;
					case 'model':
						this.aiModel = frame.model || '';
						this.aiOnModel(frame.model);
						return;
					case 'voice':
						if (frame.isVoiceEnabled != null)
							this.aiVoiceEnabled = !!frame.isVoiceEnabled;
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
				if (frame.generating != null)
					this.aiGenerating = !!frame.generating;
				if (frame.price != null)
					this.aiPrice = formatPrice(frame.price);
				if (frame.isVoiceEnabled != null)
					this.aiVoiceEnabled = !!frame.isVoiceEnabled;
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
			/** 把一批历史记录标准化为 {id,title,role,text,content,thinking,audioId} */
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
			/** 切换会话时复位分页状态 */
			aiResetHistory: function () {
				this.isLoadingMore = false;
				this.hasMoreHistory = false;
				this.historyTopReached = false;
				this.suppressAutoScroll = false;
				this._aiAtHistoryTop = false;
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

	global.AIProtocolMixin = AIProtocolMixin;
	global.aiContentToText = contentToText;
})(window);
