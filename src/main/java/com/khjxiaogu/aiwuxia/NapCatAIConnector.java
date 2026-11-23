/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.AIApplicationRegistry;
import com.khjxiaogu.aiwuxia.apps.AIGroupApplication;
import com.khjxiaogu.aiwuxia.chat.ChatAdapter;
import com.khjxiaogu.aiwuxia.chat.ChatButton;
import com.khjxiaogu.aiwuxia.chat.ChatEventHandler;
import com.khjxiaogu.aiwuxia.chat.ChatInteraction;
import com.khjxiaogu.aiwuxia.chat.AutoReplyJudge;
import com.khjxiaogu.aiwuxia.chat.IncomingContent;
import com.khjxiaogu.aiwuxia.chat.IncomingMessage;
import com.khjxiaogu.aiwuxia.chat.NapcatAdapter;
import com.khjxiaogu.aiwuxia.chat.QqOfficialAdapter;
import com.khjxiaogu.aiwuxia.codex.CodexConfig;
import com.khjxiaogu.aiwuxia.codex.CodexReviewHandler;
import com.khjxiaogu.aiwuxia.codex.CodexRuntime;
import com.khjxiaogu.aiwuxia.codex.CodexService;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.doclib.DocAgent;
import com.khjxiaogu.aiwuxia.doclib.DocLibraryConfig;
import com.khjxiaogu.aiwuxia.doclib.DocLibraryMcp;
import com.khjxiaogu.aiwuxia.doclib.DocLibraryRegistry;
import com.khjxiaogu.aiwuxia.doclib.DocLibraryRuntime;
import com.khjxiaogu.aiwuxia.mcp.chat.MessageMcp;
import com.khjxiaogu.aiwuxia.mcp.chat.GroupChatHost;
import com.khjxiaogu.aiwuxia.mcp.chat.GroupChatMcp;
import com.khjxiaogu.aiwuxia.mcp.knowledge.KnowledgeBaseMcp;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelGateway;
import com.khjxiaogu.aiwuxia.modelproxy.ModelGenerationResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallApprover;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPermission;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolTask;
import com.khjxiaogu.aiwuxia.mcp.AgentPingMcp;
import com.khjxiaogu.aiwuxia.mcp.CodexMcp;
import com.khjxiaogu.aiwuxia.mcp.CrontabMcp;
import com.khjxiaogu.aiwuxia.mcp.DeepseekSearchMcp;
import com.khjxiaogu.aiwuxia.mcp.FetchMcp;
import com.khjxiaogu.aiwuxia.mcp.H3VideoAgentMcp;
import com.khjxiaogu.aiwuxia.mcp.H3VideoMcp;
import com.khjxiaogu.aiwuxia.mcp.HistorySearchMcp;
import com.khjxiaogu.aiwuxia.mcp.MediaOutputMcp;
import com.khjxiaogu.aiwuxia.mcp.MultiModalMcp;
import com.khjxiaogu.aiwuxia.mcp.MusicMcp;
import com.khjxiaogu.aiwuxia.mcp.QQMcp;
import com.khjxiaogu.aiwuxia.mcp.SDXLAgentMcp;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp.LoraConfiguration;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp.LoraConfigurations;
import com.khjxiaogu.aiwuxia.mcp.SeedreamMcp;
import com.khjxiaogu.aiwuxia.mcp.SvsMcp;
import com.khjxiaogu.aiwuxia.mcp.QwenLocalModelMcp;
import com.khjxiaogu.aiwuxia.objectstorage.DelegatingStorage;
import com.khjxiaogu.aiwuxia.objectstorage.LocalStorage;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.objectstorage.TOStorage;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.AudioContent;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;
import com.khjxiaogu.aiwuxia.tools.QqToolApprovalManager;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ToolSettings;
import com.khjxiaogu.aiwuxia.utils.ConfigPaths;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.GroupOutputGate;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.utils.SiteConfig;
import com.khjxiaogu.aiwuxia.vision.ImageNoise;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;

/**
 * 通用聊天机器人控制器。
 * <p>
 * 负责与会话相关的全部业务：会话历史、技能/工具注册、工具调用审核、回复生成、语音回复、
 * 工具附件与群内发送顺序。所有平台相关的收发都通过 {@link ChatAdapter} 完成，因此换一个
 * 适配器实现即可接入不同协议的聊天平台（当前默认是 {@link NapcatAdapter}，即 NapCat /
 * OneBot v11）。
 * </p>
 * <p>
 * 类名沿用 {@code NapCatAIConnector} 以保持调用方不变，但本类不再直接依赖任何 NapCat 协议字段：
 * OneBot 事件解析、echo 回执、群消息发送都在 {@link NapcatAdapter} 里。
 * </p>
 * <p>
 * 本类只做公屏群聊：私聊接管（白名单、防抖、send_private、私聊移交）是多 agent 架构的能力，
 * 见 {@link com.khjxiaogu.aiwuxia.multi.MultiAgentConnector}。私聊消息在本类里只用于
 * 工具审核 / Codex 审批与 ask_user 的答复，其余交给 {@link #handleMultiPrivateMessage} 扩展点。
 * </p>
 * <p>
 * 本地模型不由本类执行：SDXL / SVS / Qwen 的客户端与部署端点都在
 * {@link com.khjxiaogu.aiwuxia.LocalModelConnectionService} 进程里。本类只启动一个
 * {@link com.khjxiaogu.aiwuxia.modelproxy.LocalModelGateway 本机模型网关}（默认
 * {@code ws://127.0.0.1:8997}），模型服务既连配置的远端、也连这个网关；本类注册的工具
 * （sdxl / svs / qwen / 语音等）通过 {@code LocalModel} 通道使用这些模型。
 * </p>
 */
public class NapCatAIConnector implements GroupChatHost {
	/** 协议适配器：平台相关的连接与收发全部经过它。 */
	private final ChatAdapter adapter;
	List<AIGroupSession> states=new ArrayList<>();

	/** 消息归属表的容量上限：只保留最近这么多条自己发出的消息。 */
	private static final int MESSAGE_OWNER_CAPACITY = 150;
	/**
	 * 自己发出的消息 id → 发出它的机器人账号 id。
	 * <p>
	 * 用于判断一条回复引用的是哪个机器人；只保留最近 {@value #MESSAGE_OWNER_CAPACITY} 条，
	 * 避免长时间运行后无限增长。所有访问都同步在 {@code messageOwner} 本身上。
	 * </p>
	 */
	private final LinkedHashMap<String, String> messageOwner = new LinkedHashMap<String, String>(200, 0.75f, false) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
			return size() > MESSAGE_OWNER_CAPACITY;
		}
	};
	ObjectStorageProvider tos;
	private final File dataFolder;

	/** 等待用户回答的询问。 */
	private static final class PendingQuestion {
		final String id;
		final AIGroupSession state;
		final boolean group;
		final String target;
		final String botId;
		final List<String> options;
		final boolean allowFreeText;
		final boolean multiSelect;
		final java.util.Set<Integer> selected = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
		final CompletableFuture<ToolResult> future = new CompletableFuture<>();
		ScheduledFuture<?> timeout;

		PendingQuestion(String id, AIGroupSession state, boolean group, String target, String botId,
				List<String> options, boolean allowFreeText, boolean multiSelect) {
			this.id = id;
			this.state = state;
			this.group = group;
			this.target = target;
			this.botId = botId;
			this.options = options;
			this.allowFreeText = allowFreeText;
			this.multiSelect = multiSelect;
		}
	}

	/** 显式发送模式的会话：正文不自动发出，必须调用发送工具。 */
	private final Set<AIGroupSession> explicitSessions = ConcurrentHashMap.newKeySet();
	/** 询问用户后等待回答的最长时间：5 分钟。 */
	private static final long QUESTION_TIMEOUT_MS = 300_000L;
	private final Map<String, PendingQuestion> pendingQuestions = new ConcurrentHashMap<>();
	private final ScheduledExecutorService questionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "qq-question");
		t.setDaemon(true);
		return t;
	});
	JsonObject imgKey = new JsonObject();
	/**
	 * 准备/收尾线程池：只做语音合成、消息组装、回执等待等“不关心顺序”的活。
	 * 真正的发送由各群的 {@link GroupOutputGate} 串行执行，因此这里阻塞不会再卡住发送顺序。
	 */
	ExecutorService sendMessage=Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "chat-send-prepare");
		t.setDaemon(true);
		return t;
	});
	/** 每个群一个发送顺序闸门：群内按输入顺序发送，群间互不影响。 */
	private final Map<String, GroupOutputGate> outputGates = new ConcurrentHashMap<>();
	/**
	 * 工具附件的暂存区（按会话区分）。
	 * <p>
	 * 工具产生的图片/视频/音频不立即发送，而是先暂存在这里，等本轮回复真正拿到发送顺序时，
	 * 在回复文本/语音之前一并发出，保证它们和本轮输出同属一个顺序槽位。
	 * </p>
	 */
	private final Map<AIGroupSession, Queue<MessageContent>> pendingToolAttachments = new ConcurrentHashMap<>();
	/** 每个会话最近成功发出的消息 id，供撤回/删除使用。 */
	private final Map<AIGroupSession, Deque<String>> recentSentMessageIds = new ConcurrentHashMap<>();
	private static final int MAX_RECENT_SENT_MESSAGES = 20;
	/** 单个会话最多暂存的工具附件数量，防止一直排不到发送时无限堆积。 */
	private static final int MAX_PENDING_TOOL_ATTACHMENTS = 16;
	/** 重量级子 agent 每回合最多调用一次。 */
	private static final int AGENT_TURN_LIMIT = 1;
	/** 每个 AI 最多持有的未完成异步任务数。 */
	private static final int MAX_PENDING_ASYNC_TASKS = 2;
	/**
	 * 单个附件发送后等待回执的上限。
	 * <p>
	 * 只用于让“发送完成”的先后与发送顺序对齐：回执在 5 秒内到达就立即继续；超过 5 秒
	 * 不再等待、不报错，直接发下一条。
	 * </p>
	 */
	private static final long ATTACHMENT_ACK_WAIT_MS = 5_000L;
	private final ResourceLock resourceLock = new ResourceLock(12);
	/**
	 * 本机模型网关：把本地模型部署通道开在回环端口，供 {@code LocalModelConnectionService} 接入。
	 * <p>
	 * 端口默认 {@value LocalModelGateway#DEFAULT_PORT}，可用 {@code -DlocalModelPort} 覆盖；
	 * 端口被占用时只记日志，不影响聊天本身，只是本地模型工具会提示服务未连接。
	 * </p>
	 */
	private final LocalModelGateway localModelGateway;
	/** 工具调用审核：需要审核的工具私聊管理员确认。 */
	private final QqToolApprovalManager toolApproval;
	/**
	 * 群聊主动回复判定：按时间或消息条数把累积的对话交给轻量模型判断是否需要发言。
	 * <p>
	 * 默认关闭，由 agent 配置里的 {@code autoReply} 段开启；判定为需要发言时走
	 * {@link #submitMessage(AIGroupSession)} 的正常回复流程。
	 * </p>
	 */
	private final AutoReplyJudge autoReply = new AutoReplyJudge(this::submitMessage);
	/**
	 * 一次交给发送顺序闸门执行的发送动作。
	 * <p>
	 * 只包含一次协议发送：适配器返回消息 id 的 future，等待回执的策略由调用方决定。
	 * </p>
	 */
	@FunctionalInterface
	private interface SendCall {
		CompletableFuture<String> send();
	}

	/**
	 * NapCat 专用的便捷构造：内部创建 {@link NapcatAdapter}。
	 *
	 * @param dataFolder 运行数据目录（save/）
	 * @param url        NapCat 正向 WS 地址
	 * @param token      OneBot 访问令牌
	 * @param states     要驱动的会话
	 */
	public NapCatAIConnector(File dataFolder, String url,
			String token, AIGroupSession... states) throws JsonSyntaxException, IOException, InterruptedException {
		this(dataFolder, new NapcatAdapter(url, token, dataFolder), states);
	}

	/**
	 * 通用构造：任意协议适配器都可以接进来。
	 *
	 * @param dataFolder 运行数据目录（save/）
	 * @param adapter    协议适配器，构造过程中会完成连接
	 * @param states     要驱动的会话
	 */
	public NapCatAIConnector(File dataFolder, ChatAdapter adapter, AIGroupSession... states)
			throws JsonSyntaxException, IOException, InterruptedException {
		this.adapter = adapter;
		this.dataFolder = dataFolder;
		LocalModelGateway gateway = null;
		try {
			gateway = LocalModelGateway.start(dataFolder);
		} catch (Throwable t) {
			System.err.println("[LocalModel] 本机模型网关启动失败（" + t
					+ "），本地模型工具将提示服务未连接；可用 -D" + LocalModelGateway.PORT_PROPERTY + " 换端口");
		}
		this.localModelGateway = gateway;
		try {
			File tosConfigFile = ConfigPaths.objectStorage(dataFolder);
			if (tosConfigFile.exists()) {
				JsonObject tosCfg = JsonParser.parseString(FileUtil.readString(tosConfigFile)).getAsJsonObject();
				tos = new DelegatingStorage(new File(dataFolder, "ircimages"), new TOStorage(tosCfg));
				System.out.println("绘画图片存储: 本地+TOv");
			} else {
				tos = new LocalStorage(new File(dataFolder, "ircimages"));
				System.out.println("绘画图片存储: 仅本地");
			}
		} catch (Exception e) {
			tos = new LocalStorage(new File(dataFolder, "ircimages"));
			System.out.println("绘画图片存储: 仅本地 (TOv初始化失败)");
		}
		try {
			imgKey = JsonParser.parseString(FileUtil.readString(ConfigPaths.imageGeneration(dataFolder))).getAsJsonObject();
		} catch (Exception e) {
			imgKey = null;
			System.out.println("找不到配置文件，seedimage已禁用");
		}
		this.toolApproval = new QqToolApprovalManager(this::sendPrivateText,
				(userId, markdown, buttons) -> {
					if (!adapter.supportsCard()) {
						return false;
					}
					adapter.sendPrivateCard(userId, markdown, buttons).get(10, TimeUnit.SECONDS);
					return true;
				},
				QqToolApprovalManager.DEFAULT_TIMEOUT_MILLIS);
		// 先注册事件处理器再连接，避免漏掉刚连上时的消息
		adapter.setEventHandler(new ChatEventHandler() {
			@Override
			public void onOpen() {
				System.out.println("opened connection");
			}

			@Override
			public void onClose(int code, String reason, boolean remote) {
				System.out.println("聊天连接关闭：" + code + " " + reason + (remote ? "（远端）" : ""));
			}

			@Override
			public void onError(Throwable error) {
				System.err.println("聊天连接异常：" + error);
			}

			@Override
			public void onGroupMessage(IncomingMessage msg) {
				// 处理群消息：归一化内容 -> 会话历史 -> 触发回复；协议字段（message 段、
				// raw.elements、@ 列表、回复 id）已经在适配器里归一化。
				// 有等待回答的询问时，群里的文字回复优先作为答案
				if (answerQuestionText(true, msg.getChatId(), msg.getSenderId(), msg.getPlainText())) {
					return;
				}
				// 多 agent 架构：配置过的公开群由 MultiAgentConnector 接管
				if (handleMultiGroupMessage(msg)) {
					return;
				}
				String groupId = msg.getChatId();
				// self_id 是这条输入来自哪个机器人；@ 列表里的机器人再和本群配置的 botId 比对。
				// 只有“被 @ 的那台机器人”的会话处理这条消息，避免 B 机器人替 A 抢答；
				// 没有 @ 任何机器人的消息（包括正文里写「@角色名」）仍交给各会话按角色名判断。
				final String selfId = msg.getSelfId();
				// 先解析 @ 列表与“回复了我”：没有被显式 @ 时，回复消息才算 @
				Set<String> containsAt = new HashSet<>();
				for (String at : msg.getMentionedIds()) {
					if (at != null && !at.isEmpty()) {
						containsAt.add(at);
					}
				}
				// 本群配置的全部 botId：用来区分“@ 了某个机器人”与“@ 了普通群友”。
				Set<String> groupBotIds = new HashSet<>();
				for (AIGroupSession candidate : states) {
					if (Objects.equals(groupId, candidate.groupId) && candidate.botId != null
							&& !candidate.botId.isEmpty()) {
						groupBotIds.add(candidate.botId);
					}
				}
				// 被 @ 的机器人 id：@ 列表里能对上配置的 botId 的才算。
				Set<String> atBotIds = new HashSet<>();
				for (String at : containsAt) {
					if (groupBotIds.contains(at)) {
						atBotIds.add(at);
					}
				}
				// 这条消息是否 @ 了本连接的机器人：适配器明确声明，或 @ 列表里直接是 self_id。
				final boolean atByAdapter = msg.isMentionsBot();
				final boolean selfMentioned = atByAdapter
						|| (selfId != null && !selfId.isEmpty() && containsAt.contains(selfId));
				boolean ambiguousBotAt = false;
				if (selfMentioned) {
					if (selfId == null || selfId.isEmpty()) {
						// 平台没给 self_id：无法归属，按本群所有会话来。
						ambiguousBotAt = true;
					} else if (groupBotIds.contains(selfId)) {
						atBotIds.add(selfId);
					} else if (atBotIds.isEmpty()) {
						// 配置里没有 self_id 这台机器人（例如官方 AppID 与机器人 mention 的 id 形态不同）：
						// 精确归属不了，按本群所有会话处理，至少保证“@ 机器人”能应答。
						ambiguousBotAt = true;
					}
				}
				final boolean anyBotAt = ambiguousBotAt || !atBotIds.isEmpty();
				if (anyBotAt) {
					System.out.println("[QQ] 群 " + groupId + " 的 @ 指向机器人：" + atBotIds
							+ (ambiguousBotAt ? "（无法精确归属，按本群会话处理）" : "") + "，self=" + selfId);
				}
				String replyMsgId = msg.getReplyToMessageId();
				String replyId = null;
				if (replyMsgId != null && !replyMsgId.isEmpty()) {
					synchronized (messageOwner) {
						replyId = messageOwner.get(replyMsgId);
					}
					if (containsAt.isEmpty() && replyId != null) {
						containsAt.add(replyId);
					}
				}
				outer: for (AIGroupSession state : states) {
					if (!Objects.equals(groupId, state.groupId)) {
						continue;
					}
					// @ 了机器人时只有被 @ 的那台机器人的会话参与；其它 botId 的会话连上下文都不进。
					boolean atMe = ambiguousBotAt || atBotIds.contains(state.botId);
					if (anyBotAt && !atMe) {
						continue;
					}
					String senderid = msg.getSenderId();
					// 忽略机器人自己发的消息
					if (Objects.equals(senderid, state.botId)) {
						continue;
					}
					boolean containsAtMe = atMe;
					String sender = msg.getSenderName();
					List<Supplier<MessageContent>> mes = new ArrayList<>();
					String senderName = "<message senderName=\"" + sender + "\" senderId=\"" + senderid
							+ "\" messageId=\"" + msg.getMessageId() + "\">";
					mes.add(() -> new PlainText(senderName));
					if (containsAtMe) {
						mes.add(() -> new PlainText("@" + state.getRoleName(Role.ASSISTANT)));
					}
					boolean hasText = false;
					StringBuilder textContent = new StringBuilder();
					for (IncomingContent content : msg.getContents()) {
						if (content instanceof IncomingContent.QuoteBlock) {
							IncomingContent.QuoteBlock quote = (IncomingContent.QuoteBlock) content;
							String quoteId = quote.getMessageId().isEmpty()
									? (replyMsgId == null ? "" : replyMsgId)
									: quote.getMessageId();
							String quoteText = "<quote messageId=\"" + quoteId + "\" senderId=\"" + quote.getSenderId()
									+ "\">" + quote.getText() + "</quote>";
							mes.add(() -> new PlainText(quoteText));
							// 引用的是机器人自己的消息（且不是本次连接发出去的）也算 @
							if (replyId == null && Objects.equals(quote.getSenderId(), state.botId)) {
								containsAtMe = true;
							}
						} else if (content instanceof IncomingContent.TextBlock) {
							hasText = true;
							String messageLine = ((IncomingContent.TextBlock) content).getText();
							textContent.append(messageLine);
							if (!containsAtMe && !anyBotAt
									&& messageLine.contains("@" + state.getRoleName(Role.ASSISTANT))) {
								containsAtMe = true;
								System.out.println("[QQ] 正文 @角色名 触发：" + state.getRoleName(Role.ASSISTANT)
										+ "（群 " + groupId + "）");
							}
							mes.add(() -> new PlainText(messageLine));
						} else if (content instanceof IncomingContent.ImageBlock) {
							IncomingContent.ImageBlock image = (IncomingContent.ImageBlock) content;
							hasText = true;
							String summary = "（" + image.getSummary() + "）";
							String ref = image.getUrl();
							mes.add(() -> {
								try {
									byte[] data = adapter.fetchMedia(ref);
									String path = tos.uploadIfNotExists(data, state::addUsage);
									return new PlainText("<image id=\"" + path + "\" />");
								} catch (Exception e) {
									e.printStackTrace();
								}
								return new PlainText(summary);
							});
						}
					}
					// 管理员指令：配置了管理员、且发送者确实是其中之一时才处理
					boolean isAdmin = false;
					for (String id : JsonConfig.getIds(state.config, "adminId", System.getProperty("adminId", ""))) {
						if (id.equals(senderid)) {
							isAdmin = true;
							break;
						}
					}
					if (isAdmin) {
						if (containsAtMe) {
							if (textContent.toString().contains("##清理上下文")) {
								try {
									((AIGroupApplication) state.getAiapp()).compactHistory(state);
									// 不等待回执：这里运行在协议事件线程上，等回执会把自己卡住
									adapter.sendText(chatId(state), "已处理");
								} catch (Exception e) {
									e.printStackTrace();
								}
								continue outer;
							}
						}
					}
					if (hasText) {
						mes.add(() -> new PlainText("</message>"));
						state.addMessage(msg.getMessageId(), mes, false);
						// 主动回复判定只累积文本；纯图片消息用占位文本表示
						String judgeText = textContent.toString().trim();
						if (judgeText.isEmpty()) {
							judgeText = "（图片或媒体消息）";
						}
						autoReply.onMessage(state, sender, senderid, judgeText);
					}
					// int hours=new Date().getHours();
					// if(hours<6)
					// containsAtMe=false;
					if (containsAtMe) {
						// 记录本轮触发者，工具审核时告诉管理员是谁发起的；未知发送者用 null 表示
						state.lastSpeakerId = senderid == null || senderid.isEmpty() ? null : senderid;
						state.lastSpeakerName = sender;

						adapter.react(msg.getMessageId(), "424");
						submitMessage(state);
					}
				}
			}

			@Override
			public void onPrivateMessage(IncomingMessage message) {
				// 处理私聊事件：审批/询问答复优先消费；其余交给多 agent 扩展点（基类不做私聊接管）
				String text = message.getPlainText();
				if (text.isEmpty()) {
					return;
				}
				// 平台不提供自身账号时用 null 表示“未知”，而不是 0 或空串
				String selfId = message.getSelfId().isEmpty() ? null : message.getSelfId();
				// Codex 审批答复优先消费：只有管理员本人、且有对应待审批任务时才会命中
				if (CodexRuntime.answerApproval(selfId == null ? "" : selfId, message.getSenderId(), text)) {
					return;
				}
				if (toolApproval.answer(selfId, message.getSenderId(), text)) {
					return;
				}
				// 有等待回答的询问时，私聊文字优先作为答案
				if (answerQuestionText(false, null, message.getSenderId(), text)) {
					return;
				}
				// 多 agent 架构：配置过的私聊用户交给对应的私人 agent（私聊路由/白名单由子类实现）
				handleMultiPrivateMessage(message);
			}

			@Override
			public void onInteraction(ChatInteraction interaction) {
				// 处理按钮互动：按钮 data 已编码审批类型、任务 id 和决定
				if (interaction == null) {
					return;
				}
				String data = interaction.getButtonData();
				String operator = interaction.getOperatorId();
				if (data == null || data.isEmpty() || operator == null || operator.isEmpty()) {
					return;
				}
				if (data.startsWith("ask:")) {
					String[] parts = data.split(":", 3);
					if (parts.length < 3) {
						return;
					}
					PendingQuestion pending = pendingQuestions.get(parts[1]);
					if (pending == null) {
						return;
					}
					if ("done".equals(parts[2])) {
						if (!pending.multiSelect) {
							return;
						}
						String selected = selectedLabels(pending);
						if (selected.isEmpty()) {
							sendQuestionText(pending.group, pending.target, "还没有选择任何选项。");
						} else {
							answerQuestion(parts[1], "用户选择：" + selected);
						}
						return;
					}
					int index;
					try {
						index = Integer.parseInt(parts[2]);
					} catch (NumberFormatException ex) {
						return;
					}
					if (index < 0 || index >= pending.options.size()) {
						return;
					}
					if (pending.multiSelect) {
						pending.selected.add(index);
						sendQuestionText(pending.group, pending.target,
								"已选择：" + selectedLabels(pending) + "；选好后回复/点击「确认」。");
					} else {
						answerQuestion(parts[1], "用户选择：" + pending.options.get(index));
					}
					return;
				}
				if (data.startsWith("tool:")) {
					toolApproval.answerButton(interaction.getSelfId(), operator, data);
					return;
				}
				if (data.startsWith("codex:")) {
					String[] parts = data.split(":", 3);
					if (parts.length < 3) {
						return;
					}
					CodexService service = CodexRuntime.getService();
					if (service != null) {
						service.answerApproval(interaction.getSelfId(), operator, parts[2], parts[1]);
					}
				}
			}

			@Override
			public void onGroupRecall(String groupId, String messageId, String operatorId, String senderId) {
				// 群消息撤回：从各会话的待处理缓冲区移除（已提交给 AI 的不处理）
				long gid = parseLong(groupId, -1);
				if (gid <= 0 || messageId == null || messageId.isEmpty()) {
					return;
				}
				for (AIGroupSession state : states) {
					if (state.groupId != null && state.groupId.equals(String.valueOf(gid))
							&& state.removeQueuedMessage(messageId)) {
						System.out.println("[QQ] 撤回消息已从缓冲区移除：" + messageId + "（群 " + gid + "）");
					}
				}
			}

			@Override
			public void onPrivateRecall(String userId, String messageId) {
				// 私聊撤回：交给子类处理（多 agent 架构会从待触发缓冲区里移除）
				handleMultiPrivateRecall(userId, messageId);
			}
		});
		System.out.println("聊天适配器：" + adapter.getName());
		try {
			adapter.connect();
		} catch (IOException | InterruptedException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("聊天适配器连接失败：" + adapter.getName(), e);
		}
		for(AIGroupSession sess:states) {

			// 历史里的图片只保存对象存储 ID，模型调用层用本会话的存储把它们解析成可访问地址
			sess.setObjectStorage(tos);
			registerSkills(sess,dataFolder,sess.config);
			autoReply.register(sess, sess.config);
			this.states.add(sess);
			sess.reasonerConsumer=data->{
				try {
					final String reasoning = data == null ? "" : data.toString();
					final CompletableFuture<String> sent = adapter.sendText(chatId(sess), reasoning);
					// 回执等待放到准备线程，避免阻塞调用方（命令线程/生成线程）
					sendMessage.submit(()->{
						try {
							String mid = sent.get();
							appendMid(mid,sess.botId);
							sess.addStatus("思维链消息id："+mid+"\n");
						} catch (Throwable e) {
							e.printStackTrace();
						}
					});
				} catch (Throwable e) {
					e.printStackTrace();
				}
				
			};
			HistoryItem hi=sess.getHistory().peekLast();
			if(hi!=null&&hi.getRole()==Role.USER) {
				GroupOutputGate gate=gateFor(sess.groupId);
				long seq=gate.register();
				try {
					float priceBefore=sess.getTokens();
					sess.getAiapp().doRegen(sess);
					sendLastMessage(sess,gate,seq,sess.getTokens()-priceBefore);
				} catch (Throwable t) {
					t.printStackTrace();
					gate.cancel(seq);
				}
			}
			
		}
	}

	/**
	 * 记录一条刚发出的消息及其发送方机器人。
	 *
	 * @param mid 消息 id，为空时忽略
	 * @param bid 发出该消息的机器人账号 id，为空时忽略
	 */
	public void appendMid(String mid, String bid) {
		if (mid == null || mid.isEmpty() || bid == null || bid.isEmpty())
			return;
		synchronized (messageOwner) {
			messageOwner.put(mid, bid);
		}
	}

	/** 协议层暴露的适配器，便于运行期查询连接状态或替换实现。 */
	public ChatAdapter getAdapter() {
		return adapter;
	}

    public static BufferedImage shrinkToQuarter(BufferedImage original) {
        if (original == null) {
            throw new IllegalArgumentException("原始图像不能为null");
        }
        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();
        
        // 计算新尺寸，至少保证1x1
        int newWidth = Math.max(1, originalWidth / 8);
        int newHeight = Math.max(1, originalHeight / 8);
        
        // 选择新图像类型: 支持透明度则使用ARGB，否则RGB
        int imageType = original.getColorModel().hasAlpha() ?
                BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage scaledImage = new BufferedImage(newWidth, newHeight, imageType);
        
        Graphics2D g2d = scaledImage.createGraphics();
        try {
            // 设置高质量缩放算法
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            
            // 绘制缩放后的图像
            g2d.drawImage(original, 0, 0, newWidth, newHeight, 0, 0,
                    originalWidth, originalHeight, null);
        } finally {
            g2d.dispose();
        }
        ImageNoise.addLaplaceNoise(scaledImage, 17);
        return scaledImage;
    }
	/**
	 * 会话处理工具附件：由聊天会话调用。
	 * <p>
	 * 这里只把附件暂存起来，不立即发送；本轮回复准备好之后，会在拿到本群发送顺序时
	 * 先把暂存的附件按产生顺序发出，再发出回复本身。
	 * </p>
	 *
	 * @param state   会话
	 * @param content 工具产生的附件
	 */
	public void sendToolAttachment(AIGroupSession state, MessageContent content) {
		if (content == null) {
			return;
		}
		Queue<MessageContent> queue = pendingToolAttachments.computeIfAbsent(state, s -> new ConcurrentLinkedQueue<>());
		queue.add(content);
		// 防御：本轮一直发不出去时避免附件无限堆积（音频 data URL 体积很大）
		while (queue.size() > MAX_PENDING_TOOL_ATTACHMENTS) {
			MessageContent dropped = queue.poll();
			if (dropped == null) {
				break;
			}
			System.err.println("[TOOL-ATTACHMENT] 会话 " + state.user + " 暂存附件超过 " + MAX_PENDING_TOOL_ATTACHMENTS
					+ " 个，丢弃最早的一个：" + dropped.getClass().getSimpleName());
		}
	}

	/**
	 * 媒体附件里保存的是对象存储 id（对象存储键）；也兼容历史数据里可能出现的 http(s) 链接或
	 * data URL。
	 */
	private static boolean isDirectMediaUrl(String ref) {
		return ref != null && (ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("data:"));
	}

	/** 会话在适配器里的聊天标识（与 {@code AIGroupSession.groupId} 对应）。 */
	private static String chatId(AIGroupSession state) {
		return state.groupId;
	}

	/** 私聊会话的 user id（{@code private:<user>}），群会话返回 null。 */
	private static String privateUserId(AIGroupSession state) {
		String id = state.groupId;
		return id != null && id.startsWith("private:") ? id.substring("private:".length()) : null;
	}

	/** 记录本会话成功发出的消息 id。 */
	protected void recordSentMessage(AIGroupSession state, String messageId) {
		if (state == null || messageId == null || messageId.isEmpty()) {
			return;
		}
		Deque<String> ids = recentSentMessageIds.computeIfAbsent(state,
				key -> new ConcurrentLinkedDeque<>());
		ids.addLast(messageId);
		while (ids.size() > MAX_RECENT_SENT_MESSAGES) {
			ids.pollFirst();
		}
	}

	/** 这条消息是否是本会话发出的（撤回只允许删自己的消息）。 */
	public boolean wasSentBy(AIGroupSession state, String messageId) {
		Deque<String> ids = recentSentMessageIds.get(state);
		return ids != null && messageId != null && ids.contains(messageId);
	}

	/** 本会话最近发出的消息 id，最新的在前。 */
	public List<String> recentSentMessages(AIGroupSession state) {
		Deque<String> ids = recentSentMessageIds.get(state);
		if (ids == null || ids.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> list = new ArrayList<>(ids);
		Collections.reverse(list);
		return list;
	}

	/**
	 * 撤回本会话发出的一条消息。
	 *
	 * @param state     会话
	 * @param messageId 消息 id；为空时撤回最近一条
	 * @return 给调用方看的结果
	 */
	public String recallMessage(AIGroupSession state, String messageId) throws Exception {
		if (!adapter.supportsRecall()) {
			return "当前协议不支持撤回消息";
		}
		String target = messageId == null ? "" : messageId.trim();
		if (target.isEmpty()) {
			List<String> recent = recentSentMessages(state);
			if (recent.isEmpty()) {
				return "没有可撤回的消息";
			}
			target = recent.get(0);
		}
		if (!wasSentBy(state, target)) {
			return "只能撤回本会话自己发送的消息";
		}
		String privateUser = privateUserId(state);
		if (privateUser != null) {
			adapter.recallPrivateMessage(privateUser, target).get(30, TimeUnit.SECONDS);
		} else {
			adapter.recallMessage(chatId(state), target).get(30, TimeUnit.SECONDS);
		}
		Deque<String> ids = recentSentMessageIds.get(state);
		if (ids != null) {
			ids.remove(target);
		}
		return "已撤回消息：" + target;
	}

	/** 组装一条纯文本回复的发送动作。 */
	private SendCall textSend(AIGroupSession state, String text) {
		final String target = chatId(state);
		final String privateUser = privateUserId(state);
		final String content = text == null ? "" : text;
		if (privateUser != null) {
			return () -> privateTextSend(privateUser, content);
		}
		return () -> adapter.sendText(target, content);
	}

	/** 私聊文本转成 future，供附件发送失败时走统一的发送队列。 */
	private CompletableFuture<String> privateTextSend(String userId, String text) {
		try {
			adapter.sendPrivateText(userId, text);
			return CompletableFuture.completedFuture(null);
		} catch (Throwable t) {
			CompletableFuture<String> failed = new CompletableFuture<>();
			failed.completeExceptionally(t);
			return failed;
		}
	}

	/**
	 * 把一个工具附件转换成一条或多条发送动作。
	 * <p>
	 * 图片裁剪、取图、base64 等耗时操作都在这里完成（准备线程），发送动作本身只负责入队。
	 * 图片附件保存的是图片 id，这里再按 id 从对象存储取图，兼容历史数据里的 URL / data URL。
	 * </p>
	 */
	private List<SendCall> buildToolAttachmentSends(AIGroupSession state, MessageContent content) {
		final String target = chatId(state);
		final String privateUser = privateUserId(state);
		List<SendCall> sends = new ArrayList<>(2);
		if (content instanceof ImageContent) {
			ImageContent image = (ImageContent) content;
			String ref = image.getImageUrl();
			if (image.isCensored()) {
				// 受限图片只发缩略图；「查看原图」链接只在拿到真正的公网地址时才有
				final String readerUrl;
				if (ref == null || ref.isEmpty() || ref.startsWith("data:")) {
					readerUrl = null;
				} else if (ref.startsWith("http://") || ref.startsWith("https://")) {
					readerUrl = ref;
				} else {
					String url;
					try {
						url = tos.getHttpUrl(ref, null);
					} catch (Throwable t) {
						url = null;
					}
					readerUrl = url != null && !url.startsWith("data:") ? url : null;
				}
				try {
					// 取原图字节：兼容图片 id、http(s) 链接与 data URL
					if (ref == null || ref.isEmpty()) {
						throw new IOException("图片内容为空");
					}
					final byte[] raw;
					if (ref.startsWith("data:")) {
						int comma = ref.indexOf(',');
						if (comma < 0) {
							throw new IOException("图片 data URL 格式错误");
						}
						raw = Base64.getDecoder().decode(ref.substring(comma + 1));
					} else if (ref.startsWith("http://") || ref.startsWith("https://")) {
						try (InputStream in = FileUtil.fetch(ref)) {
							raw = FileUtil.readAll(in);
						}
					} else {
						raw = tos.download(ref, null);
					}
					BufferedImage bi = ImageIO.read(new ByteArrayInputStream(raw));
					if (bi == null) {
						throw new IOException("无法解析图片内容");
					}
					byte[] thumb;
					try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
						ImageIO.write(shrinkToQuarter(bi), "jpg", baos);
						thumb = baos.toByteArray();
					}
					final String thumbUrl = FileUtil.toDataUrl(thumb);
					final String caption = readerUrl;
					sends.add(() -> privateUser != null
							? adapter.sendPrivateImage(privateUser, thumbUrl, caption)
							: adapter.sendImage(target, thumbUrl, caption));
				} catch (Exception e) {
					e.printStackTrace();
					sends.add(privateUser != null
							? () -> privateTextSend(privateUser, readerUrl != null ? readerUrl : "受限图片发送失败：图片不存在")
							: textSend(state, readerUrl != null ? readerUrl : "受限图片发送失败：图片不存在"));
				}
			} else {
				try {
					// 图片 id 换成可直接下载的地址；已经是 URL / data URL 的原样返回
					final String file;
					if (isDirectMediaUrl(ref)) {
						file = ref;
					} else if (ref == null || ref.isEmpty()) {
						throw new IOException("图片 id 为空");
					} else {
						file = tos.getPreferredUrl(ref, null);
					}
					sends.add(() -> privateUser != null
							? adapter.sendPrivateImage(privateUser, file, null)
							: adapter.sendImage(target, file, null));
				} catch (Exception e) {
					e.printStackTrace();
					sends.add(privateUser != null
							? () -> privateTextSend(privateUser, "图片发送失败：图片不存在")
							: textSend(state, "图片发送失败：图片不存在"));
				}
			}
		} else if (content instanceof VideoContent) {
			try {
				// 视频 id 换成可直接下载的地址；已有的 http(s) 链接原样返回
				String videoRef = ((VideoContent) content).getVideoUrl();
				final String file;
				if (isDirectMediaUrl(videoRef)) {
					file = videoRef;
				} else if (videoRef == null || videoRef.isEmpty()) {
					throw new IOException("视频 id 为空");
				} else {
					file = tos.getPreferredUrl(videoRef, null);
				}
				sends.add(() -> privateUser != null
						? adapter.sendPrivateVideo(privateUser, file)
						: adapter.sendVideo(target, file));
			} catch (Exception e) {
				e.printStackTrace();
				sends.add(privateUser != null
						? () -> privateTextSend(privateUser, "视频发送失败：视频不存在")
						: textSend(state, "视频发送失败：视频不存在"));
			}
		} else if (content instanceof AudioContent) {
			final String file = ((AudioContent) content).getAudioUrl();
			sends.add(() -> privateUser != null
					? adapter.sendPrivateRecord(privateUser, file)
					: adapter.sendRecord(target, file));
		}
		return sends;
	}

	/**
	 * 异步工具任务完成：在会话命令线程里暂存附件、写入工具通知并触发下一轮。
	 * <p>
	 * 附件由 {@link #sendToolAttachment(AIGroupSession, MessageContent)} 暂存，等新一轮拿到
	 * 发送顺序时随本轮输出一起发出。
	 * </p>
	 *
	 * @param state 会话
	 * @param task  已完成的任务
	 */
	public void handleToolTaskCompletion(AIGroupSession state, ToolTask task) {
		state.getCommandExec().submit(() -> {
			try {
				String text;
				String status;
				if (task.isFailed()) {
					status = "failed";
					Throwable error = task.getError();
					text = "失败，原因：" + (error == null || error.getMessage() == null ? "未知错误" : error.getMessage());
				} else {
					status = "success";
					text = task.getResult() == null ? "" : task.getResult().getText();
					if (task.getResult() != null) {
						// 会话统一处理附件：写入当前上下文并按需发送
						state.handleToolAttachments(task.getResult().getAttachments());
					}
				}
				String notice = "<tool name=\"" + task.getToolName() + "\" task_id=\"" + task.getId()
						+ "\" status=\"" + status + "\">" + text + "</tool>";
				state.addMessage(Arrays.asList(() -> new PlainText(notice)), true);
				submitMessage(state);
			} catch (Throwable ex) {
				ex.printStackTrace();
				state.addStatus("<tool status=\"failed\">异步任务处理失败：" + ex.getMessage() + "</tool>");
			}
		});
	}

	/** 发送图片消息，返回消息 id 或失败说明。 */
	public String sendImageMessage(AIGroupSession state, String image, String description, boolean censored)
			throws Exception {
		final String target = chatId(state);
		final String privateUser = privateUserId(state);
		if (censored) {
			String readerUrl = SiteConfig.resolve(dataFolder).readerUrl(SiteConfig.DEFAULT_READER_HOST,
					"/works/imgView.html?img=" + URLEncoder.encode(image, StandardCharsets.UTF_8));
			try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
				try (InputStream bais = FileUtil.fetch(image)) {
					BufferedImage bi = ImageIO.read(bais);
					BufferedImage bo = shrinkToQuarter(bi);
					ImageIO.write(bo, "jpg", baos);
				}
				String mid = privateUser != null
						? adapter.sendPrivateImage(privateUser, FileUtil.toDataUrl(baos.toByteArray()), readerUrl).get()
						: adapter.sendImage(target, FileUtil.toDataUrl(baos.toByteArray()), readerUrl).get();
				appendMid(mid, state.botId);
				recordSentMessage(state, mid);
				return "发送图片成功，消息id：" + mid;
			} catch (Exception e2) {
				e2.printStackTrace();
				try {
					if (privateUser != null) {
						adapter.sendPrivateText(privateUser, readerUrl);
						return "发送图片成功";
					}
					String mid = adapter.sendText(target, readerUrl).get();
					appendMid(mid, state.botId);
					return "发送图片成功，消息id：" + mid;
				} catch (Exception e3) {
					e3.printStackTrace();
					return "发送失败";
				}
			}
		}
		String mid = privateUser != null
				? adapter.sendPrivateImage(privateUser, image, null).get()
				: adapter.sendImage(target, image, null).get();
		appendMid(mid, state.botId);
		recordSentMessage(state, mid);
		return "发送图片消息id：" + mid;
	}

	/** 发送视频消息。 */
	public String sendVideoMessage(AIGroupSession state, String video) throws Exception {
		String privateUser = privateUserId(state);
		String mid = privateUser != null
				? adapter.sendPrivateVideo(privateUser, video).get()
				: adapter.sendVideo(chatId(state), video).get();
		appendMid(mid, state.botId);
		recordSentMessage(state, mid);
		return "发送生成视频消息成功，消息id：" + mid;
	}

	/** 发送音频消息。 */
	public String sendAudioMessage(AIGroupSession state, String audio) throws Exception {
		String privateUser = privateUserId(state);
		String mid = privateUser != null
				? adapter.sendPrivateRecord(privateUser, audio).get()
				: adapter.sendRecord(chatId(state), audio).get();
		appendMid(mid, state.botId);
		recordSentMessage(state, mid);
		return "发送音频消息id：" + mid;
	}
	public void registerSkills(AIGroupSession state,File dataFolder,JsonObject config) throws IOException {
		ToolSettings toolSettings = ToolSettings.parse(config);
		state.getToolCallManager().setMaxPendingAsyncTasks(MAX_PENDING_ASYNC_TASKS);
		Map<String, LoraConfigurations> lora = new LinkedHashMap<>();
		File lfn=ConfigPaths.sdxlLoras(dataFolder);
		if(lfn.exists()) {
			JsonObject refimg=JsonParser.parseString(FileUtil.readString(lfn)).getAsJsonObject();
			for(String key:refimg.keySet()) {
				if(refimg.get(key).isJsonObject()) {
					JsonObject jo=refimg.get(key).getAsJsonObject();
					lora.put(key, new LoraConfigurations(new LoraConfiguration(jo)));
				}else if(refimg.get(key).isJsonArray()) {
					JsonArray ja=refimg.get(key).getAsJsonArray();
					lora.put(key, new LoraConfigurations(ja));
				}
			}
		}
		Map<String, String> refimage = new LinkedHashMap<>();
		File rfn=ConfigPaths.sdxlReferenceImages(dataFolder);
		if(rfn.exists()) {
			JsonObject refimg=JsonParser.parseString(FileUtil.readString(rfn)).getAsJsonObject();
			for(String key:refimg.keySet()) {
				refimage.put(key, refimg.get(key).getAsString());
			}
		}
		Map<String,String> emojis=new HashMap<>();
		JsonObject emojiConfig = JsonConfig.getObject(config, "emojis");
		if (emojiConfig != null) {
			for (String emojiName : emojiConfig.keySet()) {
				emojis.put(emojiName, emojiConfig.get(emojiName).getAsString());
			}
		}
		state.setToolAttachmentHandler(content -> sendToolAttachment(state, content));
		state.setToolTaskCompletionHandler(task -> handleToolTaskCompletion(state, task));
		List<String> charas = SDXLMcp.readLinesFromFile(ConfigPaths.sdxlCharacterTags(dataFolder));
		// 技能名 -> 实际注册的工具名。工具的审核配置写在技能条目上（例如 seedvideo），
		// 但权限判定用的是真实工具名（jimeng_video），这里顺手把对应关系记下来。
		Map<String, Set<String>> toolNamesBySkill = new LinkedHashMap<>();
		if (toolSettings.isEnabled("fetch")) {
			ToolData webView = FetchMcp.createWebView(tos);
			state.registerTool(webView);
			recordTool(toolNamesBySkill, "fetch", webView);
			ToolData moegirl = FetchMcp.createMoegirlSearch(tos);
			state.registerTool(moegirl);
			recordTool(toolNamesBySkill, "fetch", moegirl);
			// DeepSeek Search API：仅在配置了 deepseektoken 时才会真正注册 web_search
			ToolData webSearch = DeepseekSearchMcp.createWebSearch();
			if (webSearch != null) {
				state.registerTool(webSearch);
				recordTool(toolNamesBySkill, "fetch", webSearch);
			}
		}

		if (toolSettings.isEnabled("multimodal")) {
			ToolData recognition = MultiModalMcp.createImageRecognition(tos);
			state.registerTool(recognition);
			recordTool(toolNamesBySkill, "multimodal", recognition);
		}

		if (toolSettings.isEnabled("qq")) {
			ToolData avatar = QQMcp.createAvatarFetch(adapter, tos);
			state.registerTool(avatar);
			recordTool(toolNamesBySkill, "qq", avatar);
			ToolData groupAvatar = QQMcp.createAvatarGroupRecognition(state.groupId, adapter, tos);
			state.registerTool(groupAvatar);
			recordTool(toolNamesBySkill, "qq", groupAvatar);
			ToolData recall = MessageMcp.recallMessage(this, state);
			state.registerTool(recall);
			recordTool(toolNamesBySkill, "qq", recall);
			ToolData sendImage = MediaOutputMcp.createSendImageForChat(tos);
			state.registerTool(sendImage);
			recordTool(toolNamesBySkill, "qq", sendImage);
			ToolData sendEmoji = MediaOutputMcp.createSendEmojiImage(emojis);
			state.registerTool(sendEmoji);
			recordTool(toolNamesBySkill, "qq", sendEmoji);
		}

		if (toolSettings.isEnabled("sdxl")) {
			// 通过本地模型通道调用：具体请求由 LocalModelConnectionService 的 sdxl 客户端执行
			SDXLMcp sdxl = SDXLMcp.createRemoteLocalModel(tos, lora, false);
			state.registerTools(sdxl);
			recordTools(toolNamesBySkill, "sdxl", sdxl.getTools());
			if (charas != null) {
				ToolData searchCharacter = SDXLMcp.createSearchCharacter(charas);
				state.registerTool(searchCharacter);
				recordTool(toolNamesBySkill, "sdxl", searchCharacter);
			}
		}
		if (toolSettings.isEnabled("sdxl-nsfw")) {
			SDXLMcp sdxl = SDXLMcp.createRemoteLocalModel(tos, lora, true);
			state.registerTools(sdxl);
			recordTools(toolNamesBySkill, "sdxl-nsfw", sdxl.getTools());
			if (charas != null) {
				ToolData searchCharacter = SDXLMcp.createSearchCharacter(charas);
				state.registerTool(searchCharacter);
				recordTool(toolNamesBySkill, "sdxl-nsfw", searchCharacter);
			}
		}

		if (toolSettings.isEnabled("sdxl-agent")) {
			ToolData agent = SDXLAgentMcp.createImage(ConfigPaths.sdxlAgent(dataFolder), tos, lora, charas, false);
			state.registerTool(agent);
			recordTool(toolNamesBySkill, "sdxl-agent", agent);
		}
		if (toolSettings.isEnabled("sdxl-agent-nsfw")) {
			ToolData agent = SDXLAgentMcp.createImage(ConfigPaths.sdxlAgent(dataFolder), tos, lora, charas, true);
			state.registerTool(agent);
			recordTool(toolNamesBySkill, "sdxl-agent-nsfw", agent);
		}
		CrontabMcp.setPath(ConfigPaths.crontab(dataFolder));
		if (toolSettings.isEnabled("cron")) {
			CrontabMcp cron = new CrontabMcp(state.botId, state);
			state.registerTools(cron);
			recordTools(toolNamesBySkill, "cron", cron.getTools());
		}
		if (toolSettings.isEnabled("music")) {
			MusicMcp music = new MusicMcp(new File(dataFolder, "music"),
					JsonConfig.getString(config, "voice", ""),
					state.getRoleName(Role.ASSISTANT), tos, ConfigPaths.svsVoices(dataFolder));
			state.registerTools(music);
			recordTools(toolNamesBySkill, "music", music.getTools());
		}
		if (toolSettings.isEnabled("svs")) {
			// SVS 独立于音乐平台：输入音频链接，voice 传角色名，由配置文件映射到音频 ID；
			// 生成统一走本地模型通道（LocalModel → SvsLocalModelHandler → localemp.py）。
			SvsMcp svs = new SvsMcp(tos, ConfigPaths.svsVoices(dataFolder));
			state.registerTools(svs);
			recordTools(toolNamesBySkill, "svs", svs.getTools());
		}
		if (toolSettings.isEnabled("qwen")) {
			// Qwen-Image 2.1 通过本地模型通道生成，聊天进程不需要访问 ComfyUI
			QwenLocalModelMcp qwen = new QwenLocalModelMcp(tos);
			state.registerTools(qwen);
			recordTools(toolNamesBySkill, "qwen", qwen.getTools());
		}
		if (toolSettings.isEnabled("h3video")) {
			// MiniMax H3 视频同样走本地模型通道（model_type=h3），与 Qwen 共用 ComfyUI；
			// 聊天进程不加锁：显存与排队由本地模型连接服务负责
			H3VideoMcp h3 = new H3VideoMcp(tos);
			state.registerTools(h3);
			recordTools(toolNamesBySkill, "h3video", h3.getTools());
		}
		if (toolSettings.isEnabled("h3video-agent")) {
			// 视频导演子 agent：扩写提示词、选比例/时长后调用内层 h3_gen_video
			ToolData agent = H3VideoAgentMcp.createVideo(ConfigPaths.h3Agent(dataFolder), tos, charas);
			state.registerTool(agent);
			recordTool(toolNamesBySkill, "h3video-agent", agent);
		}
		if (toolSettings.isEnabled("seedimage")&&imgKey!=null) {
			ToolData image = SeedreamMcp.createImage(imgKey, refimage, tos);
			state.registerTool(image);
			recordTool(toolNamesBySkill, "seedimage", image);
		}
		if (toolSettings.isEnabled("seedvideo")&&imgKey!=null) {
			// 即梦视频脚本提示词由 config/jimeng-video.txt 提供，缺失时工具内部退回内置提示词
			File videoPrompt = ConfigPaths.jimengVideo(dataFolder);
			ToolData video = SeedreamMcp.createVideo(imgKey, refimage, tos,
					videoPrompt.exists() ? FileUtil.readString(videoPrompt) : "");
			state.registerTool(video);
			recordTool(toolNamesBySkill, "seedvideo", video);
		}
		AgentPingMcp ping = new AgentPingMcp(state.getRoleName(Role.ASSISTANT), state);
		state.registerTools(ping);
		recordTools(toolNamesBySkill, "agent-ping", ping.getTools());
		// 记录检索：群聊/私聊、多 agent、codex 等会话本来就会经过这里注册工具，
		// 压缩历史后可用 history_search / history_get 找回已从上下文隐藏但没有删除的旧记录；
		// 没有启用工具的会话不会走到这里，保持原样。
		state.registerTools(HistorySearchMcp.forSession(state));
		if (toolSettings.isEnabled("codex")) {
			// Codex 编程代理：工具来自全局 CodexService（一个 app-server 进程），按「机器人+群」隔离线程；
			// 审批走私聊管理员，答复由私聊事件处理器（onPrivateMessage）消费。
			try {
				List<String> codexAdminIds = JsonConfig.getIds(config, "adminId", System.getProperty("adminId", ""));
				// 多 agent 私聊会话没有显式 adminId 时，默认审核人就是该私聊用户
				if (codexAdminIds.isEmpty() && state.groupId != null
						&& state.groupId.startsWith("private:")) {
					String user = state.groupId.substring("private:".length()).trim();
					if (!user.isEmpty()) {
						codexAdminIds.add(user);
					}
				}
				String codexAdminId = codexAdminIds.isEmpty() ? "" : codexAdminIds.get(0);
				CodexRuntime.setReviewHandler(new CodexReviewHandler() {
					@Override
					public void onReview(CodexReviewHandler.Notice notice) {
						if (notice == null) {
							return;
						}
						AIGroupSession target = findByOwner(notice.owner);
						if (target == null) {
							CodexService service = CodexRuntime.getService();
							if (service != null) {
								service.escalateReview(notice.taskId, "no-session");
							}
							return;
						}
						StringBuilder sb = new StringBuilder();
						sb.append("<codex-approval task_id=\"").append(escapeXmlText(notice.taskId))
								.append("\" thread=\"").append(escapeXmlText(notice.threadId))
								.append("\" kind=\"").append(escapeXmlText(notice.kind)).append("\">\n");
						sb.append("Codex 请求").append("file".equals(notice.kind) ? "修改文件" : "执行命令").append("：\n");
						if (notice.title != null && !notice.title.isEmpty()) {
							sb.append(notice.title).append('\n');
						}
						if (notice.detail != null && !notice.detail.isEmpty()) {
							sb.append(notice.detail).append('\n');
						}
						sb.append("逐字核对命令/参数/路径是否真正正确、是否安全、是否与当前任务一致：\n");
						sb.append("- 合理且安全：调用 codex_review_approval，task_id=\"").append(escapeXmlText(notice.taskId))
								.append("\"，decision=\"approve\"，reason 写关键依据；\n");
						sb.append("- 不确定/危险/越界/联网/读取凭据：decision=\"ask_user\" 交给用户；\n");
						sb.append("- 明显错误或恶意：decision=\"reject\"。\n");
						sb.append("</codex-approval>");
						try {
							target.addMessage(Arrays.asList(() -> new PlainText(sb.toString())), true);
							submitMessage(target);
						} catch (Throwable t) {
							t.printStackTrace();
							CodexService service = CodexRuntime.getService();
							if (service != null) {
								service.escalateReview(notice.taskId, "notify-failed");
							}
						}
					}

					@Override
					public void onResolved(CodexReviewHandler.Notice notice, String decision, String note) {
						if (notice == null || "agent".equals(note)) {
							return;
						}
						AIGroupSession target = findByOwner(notice.owner);
						if (target == null) {
							return;
						}
						String label;
						switch (decision == null ? "" : decision) {
							case "accept":
								label = "已允许";
								break;
							case "acceptForSession":
								label = "已允许（本会话记住）";
								break;
							case "decline":
								label = "已拒绝";
								break;
							case "cancel":
								label = "已拒绝并中断";
								break;
							default:
								label = "已处理（" + decision + "）";
								break;
						}
						StringBuilder text = new StringBuilder();
						text.append("<codex-approval-result task_id=\"").append(escapeXmlText(notice.taskId))
								.append("\" thread=\"").append(escapeXmlText(notice.threadId))
								.append("\" decision=\"").append(escapeXmlText(decision))
								.append("\" note=\"").append(escapeXmlText(note)).append("\">\n");
						text.append("Codex 审批结果：").append(label).append('\n');
						if (notice.title != null && !notice.title.isEmpty()) {
							text.append(notice.title).append('\n');
						}
						text.append("</codex-approval-result>");
						try {
							target.addMessage(Arrays.asList(() -> new PlainText(text.toString())), true);
						} catch (Throwable t) {
							t.printStackTrace();
						}
					}
				});
				CodexConfig codexConfig = CodexConfig.fromAgent(dataFolder, config);
				String codexOwner = "qq:" + state.botId + ":" + state.groupId;
				// 审批私聊发送器：官方机器人支持卡片时先发按钮卡片
				CodexRuntime.PrivateTextSender codexTextSender = new CodexRuntime.PrivateTextSender() {
					@Override
					public void send(String userId, String text) throws Exception {
						sendPrivateText(userId, text);
					}

					@Override
					public boolean sendCard(String userId, String markdown, List<ChatButton> buttons) throws Exception {
						if (!adapter.supportsCard()) {
							return false;
						}
						adapter.sendPrivateCard(userId, markdown, buttons).get(10, TimeUnit.SECONDS);
						return true;
					}
				};
				CodexMcp codex = CodexRuntime.mcpFor(codexOwner,
						codexConfig, codexTextSender, codexAdminId, true, tos);
				if (codex != null) {
					state.registerTools(codex);
					recordTools(toolNamesBySkill, "codex", codex.getTools());
					if (CodexRuntime.getService() != null) {
						CodexRuntime.getService().setOwnerAdmins(codexOwner, codexAdminIds);
						if (codexConfig != null) {
							CodexRuntime.getService().setOwnerOpenApproval(codexOwner, codexConfig.openApproval);
						}
					}
					System.out.println("[QQ] Codex 已接入：群 " + state.groupId + "，管理员 " + codexAdminIds
							+ "，开放审核 " + (codexConfig != null && codexConfig.openApproval)
							+ "，工作目录 " + codex.getService().getConfig().cwd.getPath());
				} else {
					System.out.println("[QQ] codex 技能已启用，但 Codex 配置被禁用（检查 codex 段或 -Dcodex_enabled）");
				}
			} catch (Throwable ex) {
				ex.printStackTrace();
				System.out.println("[QQ] Codex 初始化失败：" + ex.getMessage());
			}
		}
		if (toolSettings.isEnabled("knowledge")) {
			File root = knowledgeRoot(state);
			File personal = personalKnowledgeRoot(state);
			if (root != null) {
				ToolProvider knowledge = personal == null
						? KnowledgeBaseMcp.forProject(root)
						: KnowledgeBaseMcp.forPrivate(root, personal);
				state.registerTools(knowledge);
				recordTools(toolNamesBySkill, "knowledge", knowledge.getTools());
				System.out.println("[QQ] 知识库已接入：" + state.getRoleName(Role.ASSISTANT)
						+ "，目录 " + root.getPath());
			}
		}
		if (toolSettings.isEnabled("doclib")) {
			try {
				DocLibraryConfig dlCfg = DocLibraryConfig.forAgent(dataFolder, config);
				DocLibraryRuntime lib = DocLibraryRegistry.open(dataFolder, dlCfg);
				DocAgent dlAgent = lib.register(state, dlCfg, () -> submitMessage(state));
				ToolProvider doclib = DocLibraryMcp.forAgent(lib, dlAgent, dlCfg);
				state.registerTools(doclib);
				recordTools(toolNamesBySkill, "doclib", doclib.getTools());
				System.out.println("[QQ] 文档库已接入：" + dlAgent.displayName
						+ "，库 " + dlCfg.id + "，目录 " + lib.root().getPath());
			} catch (Throwable t) {
				t.printStackTrace();
				System.out.println("[QQ] 文档库接入失败：" + t.getMessage());
			}
		}
		if (isExplicitSend(state, config)) {
			explicitSessions.add(state);
			ToolProvider chat = createChatTools(state, config);
			if (chat != null) {
				state.registerTools(chat);
				recordTools(toolNamesBySkill, "chat", chat.getTools());
				List<String> chatToolNames = new ArrayList<>();
				for (ToolData tool : chat.getTools()) {
					chatToolNames.add(tool.name);
				}
				System.out.println("[QQ] 聊天工具已注册：" + chatToolNames);
				System.out.println("[QQ] 显式发送已启用：" + agentName(state));
			}
		}
		// 子 agent 单回合调用上限：SDXLAgent 与 Music 子 agent 每回合最多调用一次，
		// 避免模型在同一回合反复启动重量级子 agent。
		if (toolSettings.isEnabled("sdxl-agent") || toolSettings.isEnabled("sdxl-agent-nsfw")) {
			state.getToolCallManager().setToolCallLimit("sdxl_agent", AGENT_TURN_LIMIT);
		}
		if (toolSettings.isEnabled("music")) {
			state.getToolCallManager().setToolCallLimit("find_music", AGENT_TURN_LIMIT);
		}
		// 审核名单里可能写技能名，也可能直接写工具名；统一展开成真实工具名
		Set<String> needApproval = new LinkedHashSet<>();
		for (String configured : toolSettings.getApproval()) {
			Set<String> names = toolNamesBySkill.get(configured);
			if (names != null && !names.isEmpty()) {
				needApproval.addAll(names);
			} else if (state.getToolCallManager().hasTool(configured)) {
				needApproval.add(configured);
			}
		}
		if (needApproval.isEmpty()) {
			if (!toolSettings.getApproval().isEmpty()) {
				System.out.println("[聊天] 工具审核配置未匹配到已注册工具：" + toolSettings.getApproval());
			}
		} else {
			List<String> adminIds = JsonConfig.getIds(config, "adminId", System.getProperty("adminId", ""));
			state.getToolCallManager().addPermissionListener(ToolCallPermission.requireApproval(needApproval,
					"该工具会产生外部副作用或消耗额度，需要管理员审核"));
			// 统一未批准时回传给模型的文案，避免落到 ToolCallManager 的默认提示
			state.getToolCallManager().setUnapprovedMessage(
					"管理员未批准本次工具调用，请改用其它方式或先询问用户。");
			if (adminIds.isEmpty()) {
				String denyReason = "需要审核的工具未配置管理员（adminId），已拒绝执行。";
				// 用带原因的失败关闭：拒绝原因会直接回传给模型，而不是只落在通用提示里
				state.getToolCallManager().setApprover(ToolCallApprover.denyAll(denyReason));
				state.getToolCallManager().setUnapprovedMessage(denyReason);
				System.out.println("[聊天] 工具审核已启用，但未配置 adminId，需要审核的工具将被拒绝：" + needApproval);
			} else {
				// 审核超时：agent 配置 approvalTimeoutMs 优先，其次 -Dtool_approval_timeout_ms
				long timeout;
				String raw = JsonConfig.getNonBlankString(config, "approvalTimeoutMs");
				Long configuredTimeout = null;
				if (raw != null) {
					try {
						configuredTimeout = Long.valueOf(Math.max(5000, Long.parseLong(raw)));
					} catch (RuntimeException e) {
						// 配置写错时继续尝试系统属性
					}
				}
				if (configuredTimeout != null) {
					timeout = configuredTimeout.longValue();
				} else {
					try {
						timeout = Math.max(5000,
								Long.parseLong(System.getProperty("tool_approval_timeout_ms", "120000").trim()));
					} catch (RuntimeException e) {
						timeout = QqToolApprovalManager.DEFAULT_TIMEOUT_MILLIS;
					}
				}
				state.getToolCallManager().setApprover(toolApproval.approverFor(state, adminIds, timeout));
				System.out.println("[聊天] 工具审核已启用：管理员 " + adminIds + "，超时 " + timeout + "ms，工具 "
						+ needApproval);
			}
		}
	}

	// ==================== 显式发送 ====================

	/** 会话是否处于「正文不自动发送、必须用工具发」的模式。 */
	private static boolean isExplicitSend(AIGroupSession state, JsonObject config) {
		// 以会话自身判断为准：配置 explicitSend，或 codex agent 默认开启
		return state.isExplicitSend();
	}

	/**
	 * 显式发送模式下注册的聊天工具。
	 * <p>
	 * 基类只提供公屏发送与询问用户；需要私聊能力的子类（多 agent 架构）覆盖本方法换成
	 * 私聊工具集。
	 * </p>
	 *
	 * @param state  会话
	 * @param config 会话配置
	 * @return 工具集；返回 null 表示不注册
	 */
	protected ToolProvider createChatTools(AIGroupSession state, JsonObject config) {
		return GroupChatMcp.create(this, state);
	}

	/** agent 名称：优先配置 name，其次应用名。 */
	protected static String agentName(AIGroupSession state) {
		return JsonConfig.getNonBlankString(state.config, "name", state.getAiapp().getName());
	}

	/** 显式发送：公屏（当前群）。 */
	public String sendExplicitGroup(AIGroupSession state, String text) throws Exception {
		return sendExplicitGroupTo(state, state.groupId, text);
	}

	/** 显式发送：公屏（指定群）；私聊 agent 只能发到自己有上下文权限的群。 */
	public String sendExplicitGroupTo(AIGroupSession state, String groupId, String text) throws Exception {
		if (text == null || text.trim().isEmpty()) {
			throw new IllegalArgumentException("群消息内容为空");
		}
		if (groupId == null || groupId.trim().isEmpty()) {
			throw new IllegalArgumentException("群 id 为空");
		}
		String targetGroup = groupId.trim();
		if (state instanceof com.khjxiaogu.aiwuxia.multi.MultiAgentSession
				&& !((com.khjxiaogu.aiwuxia.multi.MultiAgentSession) state).canSeeGroup(targetGroup)) {
			throw new IllegalArgumentException("当前会话没有该群的上下文权限");
		}
		String mid = adapter.sendText(targetGroup, text).get(60, TimeUnit.SECONDS);
		appendMid(mid, state.botId);
		recordSentMessage(state, mid);
		onMultiGroupMessageSent(state, targetGroup, text);
		return "已发送到群 " + targetGroup + (mid == null || mid.isEmpty() ? "" : "，消息id：" + mid);
	}

	private static String escapeXmlText(String text) {
		if (text == null) {
			return "";
		}
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
				.replace("\"", "&quot;");
	}

	/** 字符串 id 转 long；空/非法时返回默认值。 */
	private static long parseLong(String value, long fallback) {
		if (value == null || value.trim().isEmpty()) {
			return fallback;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (RuntimeException ex) {
			return fallback;
		}
	}

	/** 按 {@code qq:botId:groupId} 找会话。 */
	private AIGroupSession findByOwner(String owner) {
		if (owner == null || owner.isEmpty()) {
			return null;
		}
		for (AIGroupSession state : states) {
			if (owner.equals("qq:" + state.botId + ":" + state.groupId)) {
				return state;
			}
		}
		return null;
	}

	/** 多 agent 扩展点：某个 agent 的一轮 AI 处理结束（无论成功失败）。 */
	protected void onAiTurnFinished(AIGroupSession state) {
	}

	/** 多 agent 扩展点：某个 agent 成功向指定群发送一条消息。 */
	protected void onMultiGroupMessageSent(AIGroupSession from, String groupId, String text) {
		onMultiGroupMessageSent(from, text);
	}

	/** 多 agent 扩展点：某个 agent 成功向公屏发送一条消息。 */
	protected void onMultiGroupMessageSent(AIGroupSession from, String text) {
	}

	/** 知识库根目录：优先 config 的 knowledgeRoot，其次 codex cwd，最后进程工作目录。 */
	protected File knowledgeRoot(AIGroupSession state) {
		JsonObject config = state == null ? null : state.config;
		String configured = JsonConfig.getNonBlankString(config, "knowledgeRoot");
		if (configured != null) {
			return new File(configured);
		}
		CodexConfig codex = CodexConfig.fromAgent(dataFolder, config);
		if (codex != null) {
			return codex.cwd;
		}
		return new File(System.getProperty("user.dir", "."));
	}

	/** 当前会话的个人知识库目录；配置 {@code personalKnowledge} 时启用可写个人库。 */
	protected File personalKnowledgeRoot(AIGroupSession state) {
		JsonObject config = state == null ? null : state.config;
		String path = JsonConfig.getNonBlankString(config, "personalKnowledge");
		return path == null ? null : new File(path);
	}

	/**
	 * 多 agent 扩展点：返回 true 表示这条群消息已被接管，普通群聊逻辑不再处理。
	 *
	 * @param msg 群消息
	 * @return 是否已处理
	 */
	protected boolean handleMultiGroupMessage(IncomingMessage msg) {
		return false;
	}

	/**
	 * 多 agent 扩展点：返回 true 表示这条私聊已被接管，普通私聊逻辑不再处理。
	 *
	 * @param msg 私聊消息
	 * @return 是否已处理
	 */
	protected boolean handleMultiPrivateMessage(IncomingMessage msg) {
		return false;
	}

	/**
	 * 多 agent 扩展点：私聊撤回事件（用于把被撤回的消息从待触发缓冲区里移除）。
	 *
	 * @param userId    私聊对象
	 * @param messageId 被撤回的消息 id
	 */
	protected void handleMultiPrivateRecall(String userId, String messageId) {
	}

	/** 当前会话列表（多 agent 子类用于路由）。 */
	protected List<AIGroupSession> getSessions() {
		return states;
	}

	/** 对象存储（多 agent 子类保存媒体用）。 */
	protected ObjectStorageProvider getStorage() {
		return tos;
	}

	/** 记录技能注册出来的一个工具。 */
	private static void recordTool(Map<String, Set<String>> toolNamesBySkill, String skill, ToolData tool) {
		if (tool == null || tool.name == null) {
			return;
		}
		Set<String> names = toolNamesBySkill.get(skill);
		if (names == null) {
			names = new LinkedHashSet<>();
			toolNamesBySkill.put(skill, names);
		}
		names.add(tool.name);
	}

	/** 记录技能注册出来的一组工具。 */
	private static void recordTools(Map<String, Set<String>> toolNamesBySkill, String skill,
			Collection<ToolData> tools) {
		if (tools == null) {
			return;
		}
		for (ToolData tool : tools) {
			recordTool(toolNamesBySkill, skill, tool);
		}
	}

	/** 读取媒体（图片等）原始字节，具体协议由适配器解释引用。 */
	public byte[] getImageBytes(String fileId) throws Exception {
		return adapter.fetchMedia(fileId);
	}

	/**
	 * 发送私聊文本消息（不等待回执）。
	 * <p>
	 * 工具审核的问题与结果都走私聊。这里刻意不注册回执回调，因为管理员答复是在
	 * 协议读线程上处理的，如果在读线程里同步等待回执会卡死自己。
	 * </p>
	 *
	 * @param userId 对方账号 id
	 * @param text   文本
	 * @throws Exception 连接不可用等发送失败
	 */
	public void sendPrivateText(String userId, String text) throws Exception {
		if (text == null || text.isEmpty() || userId == null || userId.isEmpty()) {
			return;
		}
		adapter.sendPrivateText(userId, text);
	}

	/**
	 * 兼容旧调用：QQ 号是数字时转成字符串。
	 *
	 * @param qq   QQ 号
	 * @param text 文本
	 * @throws Exception 发送失败
	 */
	public void sendPrivateText(long qq, String text) throws Exception {
		sendPrivateText(String.valueOf(qq), text);
	}

	/**
	 * 询问用户：发一张带选项按钮的卡片（适配器支持时），用户点按钮或直接回复；
	 * 结果通过异步工具任务的 future 返回，随后由系统触发下一轮 AI。
	 */
	public CompletableFuture<ToolResult> askUser(AIGroupSession state, String question, List<String> options,
			boolean allowFreeText, boolean multiSelect) {
		CompletableFuture<ToolResult> failed = new CompletableFuture<>();
		boolean group = state != null && state.groupId != null && !state.groupId.startsWith("private:");
		String target = group ? state.groupId : (state == null ? "" : state.groupId);
		if (!group && target.startsWith("private:")) {
			target = target.substring("private:".length());
		}

		if (target == null || target.isEmpty()) {
			failed.complete(ToolResult.text("找不到询问对象"));
			return failed;
		}
		String id = "ask-" + UUID.randomUUID().toString().replace("-", "");
		PendingQuestion pending = new PendingQuestion(id, state, group, target,
				state == null ? "" : state.botId, options == null ? new ArrayList<>() : new ArrayList<>(options),
				allowFreeText, multiSelect);
		pendingQuestions.put(id, pending);

		StringBuilder text = new StringBuilder(question == null ? "请选择：" : question).append('\n');
		for (int i = 0; i < pending.options.size(); i++) {
			text.append(i + 1).append(". ").append(pending.options.get(i)).append('\n');
		}
		if (allowFreeText) {
			text.append("也可以直接回复文字。");
		}
		if (multiSelect && !pending.options.isEmpty()) {
			text.append("可多选；选好后点「确认」或回复「确认」。");
		}
		List<ChatButton> buttons = new ArrayList<>();
		for (int i = 0; i < pending.options.size(); i++) {
			buttons.add(new ChatButton("ask-" + i, pending.options.get(i), "已选择",
					"ask:" + id + ":" + i, 1));
		}
		if (multiSelect && !buttons.isEmpty()) {
			buttons.add(new ChatButton("ask-done", "确认", "已确认", "ask:" + id + ":done", 2));
		}
		final String targetId = target;
		if (adapter.supportsCard() && !buttons.isEmpty()) {
			CompletableFuture<String> sent = group
					? adapter.sendGroupCard(targetId, text.toString(), buttons)
					: adapter.sendPrivateCard(targetId, text.toString(), buttons);
			sent.whenComplete((mid, err) -> {
				if (err != null) {
					sendQuestionText(group, targetId, text.toString());
				}
			});
		} else {
			sendQuestionText(group, targetId, text.toString());
		}
		pending.timeout = questionScheduler.schedule(
				() -> answerQuestion(id, "（等待回答超时）"), QUESTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		return pending.future;
	}

	private void sendQuestionText(boolean group, String target, String text) {
		try {
			if (group) {
				adapter.sendText(target, text);
			} else {
				adapter.sendPrivateText(target, text);
			}
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	/** 完成一个询问；text 为空表示超时。 */
	public boolean answerQuestion(String id, String text) {
		PendingQuestion pending = pendingQuestions.remove(id);
		if (pending == null) {
			return false;
		}
		if (pending.timeout != null) {
			pending.timeout.cancel(false);
		}
		pending.future.complete(ToolResult.text(text == null ? "用户已取消" : text));
		return true;
	}

	/** 用户文字回复时消费等待中的询问；返回 true 表示已消费。 */
	private boolean answerQuestionText(boolean group, String groupId, String userId, String text) {
		if (text == null || text.trim().isEmpty()) {
			return false;
		}
		String value = text.trim();
		for (PendingQuestion pending : pendingQuestions.values()) {
			if (pending.group != group) {
				continue;
			}
			if (group && (groupId == null || !pending.target.equals(groupId))) {
				continue;
			}
			if (!group && (userId == null || !pending.target.equals(userId))) {
				continue;
			}
			if (pending.multiSelect) {
				if ("确认".equals(value) || "done".equalsIgnoreCase(value)) {
					String selected = selectedLabels(pending);
					if (selected.isEmpty()) {
						sendQuestionText(group, pending.target, "还没有选择任何选项。");
					} else {
						return answerQuestion(pending.id, "用户选择：" + selected);
					}
					return true;
				}
				// 解析多选：序号（可多个，用逗号/顿号/空白分隔）或选项文案
				java.util.List<Integer> indices = new ArrayList<>();
				for (String token : value.split("[,，、\\s|]+")) {
					String tokenValue = token.trim();
					if (tokenValue.isEmpty()) {
						continue;
					}
					try {
						int index = Integer.parseInt(tokenValue) - 1;
						if (index >= 0 && index < pending.options.size() && !indices.contains(index)) {
							indices.add(index);
						}
						continue;
					} catch (NumberFormatException ignored) {
						// 继续按文案匹配
					}
					for (int i = 0; i < pending.options.size(); i++) {
						String option = pending.options.get(i);
						if (option.equalsIgnoreCase(tokenValue) || option.contains(tokenValue)
								|| tokenValue.contains(option)) {
							if (!indices.contains(i)) {
								indices.add(i);
							}
							break;
						}
					}
				}
				if (!indices.isEmpty()) {
					pending.selected.addAll(indices);
					sendQuestionText(group, pending.target,
							"已选择：" + selectedLabels(pending) + "；选好后回复/点击「确认」。");
					return true;
				}
				if (pending.allowFreeText) {
					return answerQuestion(pending.id, "用户回复：" + value);
				}
				sendQuestionText(group, pending.target, "请回复选项序号（可多个，用逗号分隔），选好后回复「确认」。");
				return true;
			}
			// 单选：先按序号，再按选项文案匹配
			String selected = null;
			try {
				int index = Integer.parseInt(value) - 1;
				if (index >= 0 && index < pending.options.size()) {
					selected = pending.options.get(index);
				}
			} catch (NumberFormatException ignored) {
				// 不是序号，继续按文本匹配
			}
			if (selected == null) {
				for (String option : pending.options) {
					if (option.equalsIgnoreCase(value) || option.contains(value) || value.contains(option)) {
						selected = option;
						break;
					}
				}
			}
			if (selected != null) {
				return answerQuestion(pending.id, "用户选择：" + selected);
			}
			if (pending.allowFreeText) {
				return answerQuestion(pending.id, "用户回复：" + value);
			}
			sendQuestionText(group, pending.target, "请从上面的选项中选择；如需自由回复，请让 agent 允许自由回复。");
			return true;
		}
		return false;
	}

	private static String selectedLabels(PendingQuestion pending) {
		StringBuilder text = new StringBuilder();
		for (int index : pending.selected) {
			if (index < 0 || index >= pending.options.size()) {
				continue;
			}
			if (text.length() > 0) {
				text.append("、");
			}
			text.append(pending.options.get(index));
		}
		return text.toString();
	}

	public void notifyOtherAgents(AIGroupSession state,String msg,String mid) {
		for(AIGroupSession sess:states) {
			if(state!=sess&&Objects.equals(state.groupId,sess.groupId))
				sess.addMessage(Arrays.asList(()->new PlainText("<message senderName=\"" + state.getRoleName(Role.ASSISTANT) + "\" id=\"" + mid + "\">"+msg)),true);
		}
	}
	/** 取（或创建）某个群的发送顺序闸门。 */
	private GroupOutputGate gateFor(String groupId) {
		return outputGates.computeIfAbsent(groupId, GroupOutputGate::new);
	}

	/**
	 * 本轮回复的发送入口。
	 * <p>
	 * 只负责把“已准备好的内容”交给本群的顺序闸门；语音合成、回执等待等阻塞操作都在闸门之外，
	 * 因此不会出现“拿着顺序令牌等外部服务”的情况。
	 * </p>
	 */
	public void sendLastMessage(AIGroupSession state,GroupOutputGate gate,long seq,float price) {
		state.printReasoning();
		HistoryItem hi = state.getLast();
		if (hi == null || hi.getRole() != Role.ASSISTANT) {
			gate.cancel(seq);
			return;
		}
		String msg=hi.getDisplayContent().getPlainText().trim();
		String priceStr="";
		if(state.outputPrice) {
			priceStr+="token:"+Math.round(price);
		}
		
		final String toWritePrice=priceStr;
		final String reply=msg;
		// 本轮产生的工具附件一起随本轮发出：在回复之前先发附件
		Queue<MessageContent> pendingAttachments=pendingToolAttachments.get(state);
		final List<MessageContent> attachments;
		if(pendingAttachments==null||pendingAttachments.isEmpty()) {
			attachments=Collections.emptyList();
		}else {
			attachments=new ArrayList<>();
			MessageContent drained;
			while((drained=pendingAttachments.poll())!=null) {
				attachments.add(drained);
			}
		}
		try {
			// 准备阶段不占顺序：慢的语音合成只影响本轮自己的就绪时间
			sendMessage.submit(()->{
				// 显式发送模式：正文不自动发出，只把工具附件按原顺序发出去
				if (explicitSessions.contains(state)) {
					List<SendCall> attachmentSends = new ArrayList<>();
					if (attachments != null) {
						for (MessageContent attachment : attachments) {
							try {
								attachmentSends.addAll(buildToolAttachmentSends(state, attachment));
							} catch (Throwable e) {
								e.printStackTrace();
								state.addStatus("<tool status=\"failed\">附件发送失败：" + e.getMessage() + "</tool>");
							}
						}
					}
					gate.complete(seq, () -> {
						for (SendCall attachment : attachmentSends) {
							emitAttachment(state, attachment);
						}
					});
					return;
				}
				SendCall payload;
				try {
					beforeSendMessage(state);
					if(reply==null||reply.isEmpty()) {
						payload=textSend(state,"生成完成。"+toWritePrice);
					}else {
						// 语音回复：合成在这里完成（不占顺序），失败或超时则退回文本
						SendCall voice=null;
						try {
							CompletableFuture<ModelGenerationResult> dataFuture =VoiceModelHandler.getAudioData("", state.getRoleName(Role.ASSISTANT),"qbot", reply.replaceAll("枫茜", "枫西").replaceAll("茜茜", "西西"),UUID.randomUUID().toString(), state::addUsage);
							ModelGenerationResult audio=dataFuture.get();
							if(audio!=null&&audio.bodyData!=null&&audio.bodyData.length>0) {
								final String target=chatId(state);
								final String privateUser=privateUserId(state);
								final String dataUrl=FileUtil.toDataUrl(audio.bodyData);
								voice = privateUser != null
										? () -> adapter.sendPrivateRecord(privateUser,dataUrl)
										: () -> adapter.sendRecord(target,dataUrl);
							}
						} catch (Throwable e) {
							if(e instanceof InterruptedException)
								Thread.currentThread().interrupt();
							if(!(e instanceof ModelRouteException))
								e.printStackTrace();
						}
						payload = voice != null ? voice : textSend(state,reply+toWritePrice);
					}
				} catch (Throwable e) {
					e.printStackTrace();
					payload=textSend(state,(reply==null?"":reply)+toWritePrice);
				}
				List<SendCall> attachmentSends=new ArrayList<>();
				if(attachments!=null) {
					for(MessageContent attachment:attachments) {
						try {
							attachmentSends.addAll(buildToolAttachmentSends(state,attachment));
						} catch (Throwable e) {
							e.printStackTrace();
							state.addStatus("<tool status=\"failed\">附件发送失败：" + e.getMessage() + "</tool>");
						}
					}
				}
				final SendCall toSend=payload;
				final String notifyText=reply==null?"":reply;
				final List<SendCall> sends=attachmentSends;
				gate.complete(seq,()->{
					// 先发本轮的附件，再发本轮的正文/语音；附件各自最多等 5 秒回执，
					// 回执先到立即继续，超时不再等待也不报错，直接发下一条。
					for(SendCall attachment:sends) {
						emitAttachment(state,attachment);
					}
					emitPayload(state,toSend,notifyText);
				});
			});
		} catch (Throwable t) {
			t.printStackTrace();
			gate.complete(seq,()->emitPayload(state,textSend(state,reply+toWritePrice),reply));
		}
	}

	/**
	 * 发送一条工具附件，并等待回执最多 5 秒。
	 * <p>
	 * 回执在 5 秒内到达：记录消息 id 后立即返回，接着发下一条；
	 * 5 秒内没完成：不再等待、不报错，直接返回让调用方继续。
	 * </p>
	 */
	private void emitAttachment(AIGroupSession state,SendCall send) {
		CompletableFuture<String> sent;
		try {
			sent=send.send();
		} catch (Throwable t) {
			t.printStackTrace();
			return;
		}
		try {
			String mid=sent.get(ATTACHMENT_ACK_WAIT_MS, TimeUnit.MILLISECONDS);
			appendMid(mid,state.botId);
			recordSentMessage(state, mid);
		} catch (TimeoutException e) {
			// 5 秒内没完成发送：不管了，直接发下一条
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	/** 在闸门线程上执行一次发送；回执与通知交给准备线程，避免阻塞后续消息。 */
	private void emitPayload(AIGroupSession state,SendCall send,String notifyText) {
		CompletableFuture<String> sent;
		try {
			sent=send.send();
		} catch (Throwable t) {
			t.printStackTrace();
			return;
		}
		try {
			sendMessage.submit(()->{
				// 发送成功后的本地簿记：消息 id、状态、通知同群其它 agent。附件消息不写状态、不通知。
				try {
					String mid=sent.get();
					appendMid(mid,state.botId);
					recordSentMessage(state, mid);
					if(notifyText!=null) {
						// 协议不提供消息 id 时（mid 为 null）不写状态，避免写进一串 null
						if(mid!=null&&!mid.isEmpty())
							state.addStatus("上条消息id："+mid+"\n");
						notifyOtherAgents(state,notifyText,mid==null?"":mid);
					}
				} catch (Throwable e) {
					if(e instanceof InterruptedException)
						Thread.currentThread().interrupt();
					e.printStackTrace();
				}
			});
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	Object globalLock=new Object();

	public void beforeSendMessage(AIGroupSession state) {
		
	}

	public void submitMessage(AIGroupSession state) {
		submitMessage(state, state.groupId);
	}

	/**
	 * 按指定闸门键触发一轮回复；私聊可用 {@code private:botId:userId} 让每个私聊各自串行。
	 *
	 * @param state   会话
	 * @param gateKey 闸门键
	 */
	public void submitMessage(AIGroupSession state, String gateKey) {
		// 本轮回复即将开始：清空主动回复判定的累计内容，避免重复判定
		autoReply.onReply(state);
		final GroupOutputGate gate=gateFor(gateKey == null ? state.groupId : gateKey);
		// 在“输入被接纳”时就排队：顺序只取决于输入先后，与工具、模型、语音的快慢无关
		final long seq=gate.register();
		MessageContents prompt;
		try {
			prompt=state.getPrompt();
		} catch (Throwable t) {
			t.printStackTrace();
			gate.cancel(seq);
			return;
		}
		try {
			state.getCommandExec().submit(() -> {
				try {
					float priceBefore=state.getTokens();
					String lst=state.getAndClearStatus();
					MutableMessageContents mst=new MutableMessageContents(lst);
					for(MessageContent msg:prompt)
						mst.add(msg);
					state.getAiapp().handleSpeech(state, mst);
		
				
					sendLastMessage(state,gate,seq,state.getTokens()-priceBefore);
				} catch (Throwable t) {
					t.printStackTrace();
					gate.cancel(seq);
				} finally {
					onAiTurnFinished(state);
				}
			});
		} catch (Throwable t) {
			t.printStackTrace();
			gate.cancel(seq);
		}
	}

	@SuppressWarnings("resource")
	public static void main(String[] args) throws Throwable {
		try {
			// 站点域名：--site-host=example.com 或 -Dsite_host=example.com
			SiteConfig.applyArgs(args);
			LLMConnector.initDefault();
			
			File dataFolder = new File("save");
			VoiceModelHandler.init(dataFolder);
			// 多 agent 架构只有显式指定才开启：-Dmulti_agent=true 或 --multi-agent；即使存在
			// multi.json，默认也不开启
			boolean multiRequested = false;
			for (String propKey : new String[] {"multi_agent", "multiAgent", "multi"}) {
				String value = System.getProperty(propKey);
				if (value != null && ("true".equalsIgnoreCase(value) || "1".equals(value)
						|| "yes".equalsIgnoreCase(value))) {
					multiRequested = true;
					break;
				}
			}
			if (!multiRequested && args != null) {
				for (String arg : args) {
					if ("--multi-agent".equals(arg) || "--multi".equals(arg)
							|| "-Dmulti_agent=true".equalsIgnoreCase(arg)
							|| "-DmultiAgent=true".equalsIgnoreCase(arg)) {
						multiRequested = true;
						break;
					}
				}
			}
			if (multiRequested) {
				com.khjxiaogu.aiwuxia.multi.MultiAgentConfig multiConfig =
						com.khjxiaogu.aiwuxia.multi.MultiAgentConfig.load(dataFolder);
				if (multiConfig != null) {
					ChatAdapter multiAdapter = com.khjxiaogu.aiwuxia.multi.MultiAgentConnector
							.createAdapter(dataFolder, multiConfig);
					new com.khjxiaogu.aiwuxia.multi.MultiAgentConnector(dataFolder, multiAdapter, multiConfig);
					return;
				}
				System.out.println("[Multi] 已请求多 agent，但没有可用的 config/multi.json（需要 enabled=true）");
			}
			JsonArray jarr=JsonParser.parseString(FileUtil.readString(ConfigPaths.agents(dataFolder))).getAsJsonArray();
			AIGroupSession[] states=new AIGroupSession[jarr.size()];
			int i=0;
			for(JsonElement jelm:jarr) {
				JsonObject config=jelm.getAsJsonObject();
				String name = config.get("name").getAsString();
				File saveData = new File(new File(dataFolder, "saveData"), config.get("save").getAsString());
				File modelFolder = new File(dataFolder, "apps/" + name);
				
				AIApplication main = AIApplicationRegistry.createInstance(dataFolder, modelFolder);
				AIGroupSession aistate = null;
				aistate = new AIGroupSession("appuser", AIApplication.saveDataFromJson(saveData),saveData, main,config);

				states[i++]=aistate;
			}
			// 协议适配器可替换：-Dqqbot_appid/-Dqqbot_secret 走 QQ 官方机器人 API v2，否则走 NapCat
			ChatAdapter adapter;
			String qqbotAppId = System.getProperty("qqbot_appid", "").trim();
			if (!qqbotAppId.isEmpty()) {
				adapter = new QqOfficialAdapter(qqbotAppId,
						System.getProperty("qqbot_secret", ""), dataFolder);
			} else {
				adapter = new NapcatAdapter(System.getProperty("napcat_url"),
						System.getProperty("napcat_token"), dataFolder);
			}
			new NapCatAIConnector(dataFolder, adapter, states);
			// 主线程保持存活：部分协议/场景下连接线程退出后，不能让 JVM 顺手结束，
			// 否则适配器安排的重连任务不会执行。
			Thread.currentThread().join();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

	}

}
