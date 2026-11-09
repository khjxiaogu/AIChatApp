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
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
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
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import com.khjxiaogu.aiwuxia.chat.ChatEventHandler;
import com.khjxiaogu.aiwuxia.chat.IncomingContent;
import com.khjxiaogu.aiwuxia.chat.IncomingMessage;
import com.khjxiaogu.aiwuxia.chat.NapcatAdapter;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallApprover;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPermission;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolTask;
import com.khjxiaogu.aiwuxia.mcp.AgentPingMcp;
import com.khjxiaogu.aiwuxia.mcp.CrontabMcp;
import com.khjxiaogu.aiwuxia.mcp.DeepseekSearchMcp;
import com.khjxiaogu.aiwuxia.mcp.FetchMcp;
import com.khjxiaogu.aiwuxia.mcp.MultiModalMcp;
import com.khjxiaogu.aiwuxia.mcp.MusicMcp;
import com.khjxiaogu.aiwuxia.mcp.QQMcp;
import com.khjxiaogu.aiwuxia.mcp.QQMediaMcp;
import com.khjxiaogu.aiwuxia.mcp.SDXLAgentMcp;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp.LoraConfiguration;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp.LoraConfigurations;
import com.khjxiaogu.aiwuxia.mcp.SeedreamMcp;
import com.khjxiaogu.aiwuxia.mcp.SvsMcp;
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
import com.khjxiaogu.aiwuxia.utils.SiteConfig;
import com.khjxiaogu.aiwuxia.vision.ImageNoise;
import com.khjxiaogu.aiwuxia.vision.SDXLLocalModelClient;
import com.khjxiaogu.aiwuxia.voice.ModelGenerationResult;
import com.khjxiaogu.aiwuxia.voice.SvsLocalModelClient;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;
import com.khjxiaogu.aiwuxia.voice.VoiceModelLocalServer;
import com.khjxiaogu.webserver.builder.BasicWebServerBuilder;

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
 */
public class NapCatAIConnector {
	/** 协议适配器：平台相关的连接与收发全部经过它。 */
	private final ChatAdapter adapter;
	List<AIGroupSession> states=new ArrayList<>();

	Deque<Mid2Bid> mbid=new ArrayDeque<>(200);
	ObjectStorageProvider tos;
	private final File dataFolder;
	static class Mid2Bid{
		String mid;
		long bid;
		Mid2Bid(String mid, long bid) {
			super();
			this.mid = mid;
			this.bid = bid;
		}
	}
	JsonObject imgKey = new JsonObject();
	/**
	 * 准备/收尾线程池：只做语音合成、消息组装、回执等待等“不关心顺序”的活。
	 * 真正的发送由各群的 {@link GroupOutputGate} 串行执行，因此这里阻塞不会再卡住发送顺序。
	 */
	ExecutorService sendMessage=Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "qq-send-prepare");
		t.setDaemon(true);
		return t;
	});
	/** 每个群一个发送顺序闸门：群内按输入顺序发送，群间互不影响。 */
	private final Map<Long, GroupOutputGate> outputGates = new ConcurrentHashMap<>();
	/**
	 * 工具附件的暂存区（按会话区分）。
	 * <p>
	 * 工具产生的图片/视频/音频不立即发送，而是先暂存在这里，等本轮回复真正拿到发送顺序时，
	 * 在回复文本/语音之前一并发出，保证它们和本轮输出同属一个顺序槽位。
	 * </p>
	 */
	private final Map<AIGroupSession, Queue<MessageContent>> pendingToolAttachments = new ConcurrentHashMap<>();
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
	/** 工具调用审核：需要审核的工具私聊管理员确认。 */
	private final QqToolApprovalManager toolApproval;
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
		this(dataFolder, new NapcatAdapter(url, token), states);
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
		// 本地模型部署地址：站点域名默认读 save/site.json，可用 --site-host / -Dsite_host 覆盖
		SDXLLocalModelClient lmc=new SDXLLocalModelClient(URI.create(SiteConfig.resolve(dataFolder).webSocketUrl(SiteConfig.DEFAULT_HOST, "/aichat/kh$localModelDeploy")), resourceLock, true, 1000);
		SvsLocalModelClient svs=new SvsLocalModelClient(URI.create(SiteConfig.resolve(dataFolder).webSocketUrl(SiteConfig.DEFAULT_HOST, "/aichat/kh$localModelDeploy")), resourceLock, true, 1000);
		lmc.connectBlocking();
		svs.connectBlocking();
		try {
			File tosConfigFile = ConfigPaths.objectStorage(dataFolder);
			if (tosConfigFile.exists()) {
				JsonObject tosCfg = JsonParser.parseString(FileUtil.readString(tosConfigFile)).getAsJsonObject();
				tos = new DelegatingStorage(new File(dataFolder, "ircimages"), new TOStorage(tosCfg));
				System.out.println("绘画图片存储: 本地+TOS");
			} else {
				tos = new LocalStorage(new File(dataFolder, "ircimages"));
				System.out.println("绘画图片存储: 仅本地");
			}
		} catch (Exception e) {
			tos = new LocalStorage(new File(dataFolder, "ircimages"));
			System.out.println("绘画图片存储: 仅本地 (TOS初始化失败)");
		}
		imgKey = JsonParser.parseString(FileUtil.readString(ConfigPaths.imageGeneration(dataFolder))).getAsJsonObject();
		this.toolApproval = new QqToolApprovalManager(this::sendPrivateText,
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
			public void onGroupMessage(IncomingMessage message) {
				NapCatAIConnector.this.handleGroupMessage(message);
			}

			@Override
			public void onPrivateMessage(IncomingMessage message) {
				NapCatAIConnector.this.handlePrivateMessage(message);
			}
		});
		System.out.println("聊天适配器：" + adapter.getName());
		connectAdapter();
		for(AIGroupSession sess:states) {

			// 历史里的图片只保存对象存储 ID，模型调用层用本会话的存储把它们解析成可访问地址
			sess.setObjectStorage(tos);
			registerSkills(sess,dataFolder,sess.config);
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
	 * 连接适配器，把协议层的异常统一成构造器声明的 {@link IOException}。
	 */
	private void connectAdapter() throws IOException, InterruptedException {
		try {
			adapter.connect();
		} catch (IOException e) {
			throw e;
		} catch (InterruptedException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("聊天适配器连接失败：" + adapter.getName(), e);
		}
	}

	Object mbidLock=new Object();
	public void appendMid(String mid,long bid) {
		if(mid==null||mid.isEmpty())
			return;
		synchronized(mbidLock) {
			mbid.addLast(new Mid2Bid(mid,bid));
			while(mbid.size()>150) {
				mbid.pollFirst();
			}
		}
	}

	/** 查一条自己发出的消息属于哪个机器人，找不到返回 -1。 */
	private long findBid(String mid) {
		synchronized(mbidLock) {
			for(Mid2Bid mbidItem:mbid) {
				if(mid.equals(mbidItem.mid))
					return mbidItem.bid;
			}
		}
		return -1;
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

	/** 暂存区里还没有发出去的工具附件。 */
	private List<MessageContent> drainToolAttachments(AIGroupSession state) {
		Queue<MessageContent> queue = pendingToolAttachments.get(state);
		if (queue == null || queue.isEmpty()) {
			return Collections.emptyList();
		}
		List<MessageContent> drained = new ArrayList<>();
		MessageContent content;
		while ((content = queue.poll()) != null) {
			drained.add(content);
		}
		return drained;
	}

	/**
	 * 图片附件里保存的是图片 id（对象存储键）；也兼容历史数据里可能出现的 http(s) 链接或
	 * data URL。
	 */
	private static boolean isDirectImageUrl(String ref) {
		return ref != null && (ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("data:"));
	}

	/**
	 * 把图片 id 解析成可直接交给 NapCat 的 {@code file} 字段。
	 * <p>
	 * 已经是 URL / data URL 的原样返回；图片 id 则从对象存储取数据并转成 data URL。
	 * </p>
	 */
	private String imageFileForSend(String ref) throws IOException {
		if (isDirectImageUrl(ref)) {
			return ref;
		}
		if (ref == null || ref.isEmpty()) {
			throw new IOException("图片 id 为空");
		}
		return tos.getUrl(ref, null);
	}

	/**
	 * 图片 id 对应的可公开访问链接，用于受限图片的“查看原图”页面。
	 * <p>
	 * 只有拿到真正的公网地址时才返回；本地存储只能给出 data URL，这种情况返回 null
	 * （把整张图塞进链接没有意义，也会让 QQ 消息体爆掉）。
	 * </p>
	 */
	private String publicImageUrl(String ref) {
		if (ref == null || ref.isEmpty()) {
			return null;
		}
		if (ref.startsWith("http://") || ref.startsWith("https://")) {
			return ref;
		}
		if (ref.startsWith("data:")) {
			return null;
		}
		try {
			String url = tos.getPublicUrl(ref, null);
			return url != null && !url.startsWith("data:") ? url : null;
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * 读取图片原始字节，支持图片 id、http(s) 链接与 data URL。
	 */
	private byte[] loadImageBytes(String ref) throws IOException {
		if (ref == null || ref.isEmpty()) {
			throw new IOException("图片内容为空");
		}
		if (ref.startsWith("data:")) {
			int comma = ref.indexOf(',');
			if (comma < 0) {
				throw new IOException("图片 data URL 格式错误");
			}
			return Base64.getDecoder().decode(ref.substring(comma + 1));
		}
		if (ref.startsWith("http://") || ref.startsWith("https://")) {
			try (InputStream in = FileUtil.fetch(ref)) {
				return FileUtil.readAll(in);
			}
		}
		return tos.download(ref, null);
	}

	/** 会话在适配器里的聊天标识（与 {@code AIGroupSession.groupId} 对应）。 */
	private static String chatId(AIGroupSession state) {
		return String.valueOf(state.groupId);
	}

	/** 组装一条纯文本回复的发送动作。 */
	private SendCall textSend(AIGroupSession state, String text) {
		final String target = chatId(state);
		final String content = text == null ? "" : text;
		return () -> adapter.sendText(target, content);
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
		List<SendCall> sends = new ArrayList<>(2);
		if (content instanceof ImageContent) {
			ImageContent image = (ImageContent) content;
			String ref = image.getImageUrl();
			if (image.isCensored()) {
				String readerUrl = publicImageUrl(ref);
				try {
					byte[] raw = loadImageBytes(ref);
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
					sends.add(() -> adapter.sendImage(target, thumbUrl, caption));
				} catch (Exception e) {
					e.printStackTrace();
					sends.add(textSend(state, readerUrl != null ? readerUrl : "受限图片发送失败：图片不存在"));
				}
			} else {
				try {
					final String file = imageFileForSend(ref);
					sends.add(() -> adapter.sendImage(target, file, null));
				} catch (Exception e) {
					e.printStackTrace();
					sends.add(textSend(state, "图片发送失败：图片不存在"));
				}
			}
		} else if (content instanceof VideoContent) {
			final String file = ((VideoContent) content).getVideoUrl();
			sends.add(() -> adapter.sendVideo(target, file));
		} else if (content instanceof AudioContent) {
			final String file = ((AudioContent) content).getAudioUrl();
			sends.add(() -> adapter.sendRecord(target, file));
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
		if (censored) {
			String readerUrl = SiteConfig.resolve(dataFolder).readerUrl(SiteConfig.DEFAULT_READER_HOST,
					"/works/imgView.html?img=" + URLEncoder.encode(image, StandardCharsets.UTF_8));
			try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
				try (InputStream bais = FileUtil.fetch(image)) {
					BufferedImage bi = ImageIO.read(bais);
					BufferedImage bo = shrinkToQuarter(bi);
					ImageIO.write(bo, "jpg", baos);
				}
				String mid = adapter.sendImage(target, FileUtil.toDataUrl(baos.toByteArray()), readerUrl).get();
				appendMid(mid, state.botId);
				return "发送图片成功，消息id：" + mid;
			} catch (Exception e2) {
				e2.printStackTrace();
				try {
					String mid = adapter.sendText(target, readerUrl).get();
					appendMid(mid, state.botId);
					return "发送图片成功，消息id：" + mid;
				} catch (Exception e3) {
					e3.printStackTrace();
					return "发送失败";
				}
			}
		}
		String mid = adapter.sendImage(target, image, null).get();
		appendMid(mid, state.botId);
		return "发送图片消息id：" + mid;
	}

	/** 发送视频消息。 */
	public String sendVideoMessage(AIGroupSession state, String video) throws Exception {
		String mid = adapter.sendVideo(chatId(state), video).get();
		appendMid(mid, state.botId);
		return "发送生成视频消息成功，消息id：" + mid;
	}

	/** 发送音频消息。 */
	public String sendAudioMessage(AIGroupSession state, String audio) throws Exception {
		String mid = adapter.sendRecord(chatId(state), audio).get();
		appendMid(mid, state.botId);
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
		if(config.has("emojis")) {
			JsonObject jo=config.get("emojis").getAsJsonObject();
			for(String je:jo.keySet()) {
				emojis.put(je,jo.get(je).getAsString());
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
			ToolData avatar = QQMcp.createAvatarFetch(tos);
			state.registerTool(avatar);
			recordTool(toolNamesBySkill, "qq", avatar);
			ToolData groupAvatar = QQMcp.createAvatarGroupRecognition("" + state.groupId, tos);
			state.registerTool(groupAvatar);
			recordTool(toolNamesBySkill, "qq", groupAvatar);
			ToolData sendImage = QQMediaMcp.createSendImage(tos);
			state.registerTool(sendImage);
			recordTool(toolNamesBySkill, "qq", sendImage);
			ToolData sendEmoji = QQMediaMcp.createSendEmojiImage(tos, emojis);
			state.registerTool(sendEmoji);
			recordTool(toolNamesBySkill, "qq", sendEmoji);
		}

		if (toolSettings.isEnabled("sdxl")) {
			SDXLMcp sdxl = SDXLMcp.createLocal(tos, lora, false, resourceLock);
			state.registerTools(sdxl);
			recordTools(toolNamesBySkill, "sdxl", sdxl.getTools());
			if (charas != null) {
				ToolData searchCharacter = SDXLMcp.createSearchCharacter(charas);
				state.registerTool(searchCharacter);
				recordTool(toolNamesBySkill, "sdxl", searchCharacter);
			}
		}
		if (toolSettings.isEnabled("sdxl-nsfw")) {
			SDXLMcp sdxl = SDXLMcp.createLocal(tos, lora, true, resourceLock);
			state.registerTools(sdxl);
			recordTools(toolNamesBySkill, "sdxl-nsfw", sdxl.getTools());
			if (charas != null) {
				ToolData searchCharacter = SDXLMcp.createSearchCharacter(charas);
				state.registerTool(searchCharacter);
				recordTool(toolNamesBySkill, "sdxl-nsfw", searchCharacter);
			}
		}

		if (toolSettings.isEnabled("sdxl-agent")) {
			ToolData agent = SDXLAgentMcp.createImage(ConfigPaths.sdxlAgent(dataFolder), tos, lora, charas, false,
					resourceLock);
			state.registerTool(agent);
			recordTool(toolNamesBySkill, "sdxl-agent", agent);
		}
		if (toolSettings.isEnabled("sdxl-agent-nsfw")) {
			ToolData agent = SDXLAgentMcp.createImage(ConfigPaths.sdxlAgent(dataFolder), tos, lora, charas, true,
					resourceLock);
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
			MusicMcp music = new MusicMcp(new File(dataFolder, "music"), config.get("voice").getAsString(),
					state.getRoleName(Role.ASSISTANT), resourceLock, tos, ConfigPaths.svsVoices(dataFolder));
			state.registerTools(music);
			recordTools(toolNamesBySkill, "music", music.getTools());
		}
		if (toolSettings.isEnabled("svs")) {
			// SVS 独立于音乐平台：输入音频链接，voice 传角色名，由配置文件映射到音频 ID
			SvsMcp svs = new SvsMcp(tos, resourceLock, ConfigPaths.svsVoices(dataFolder));
			state.registerTools(svs);
			recordTools(toolNamesBySkill, "svs", svs.getTools());
		}
		if (toolSettings.isEnabled("seedimage")) {
			ToolData image = SeedreamMcp.createImage(imgKey, refimage, tos);
			state.registerTool(image);
			recordTool(toolNamesBySkill, "seedimage", image);
		}
		if (toolSettings.isEnabled("seedvideo")) {
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
		applyAgentTurnLimits(state, toolSettings);
		applyToolApproval(state, config, toolSettings, toolNamesBySkill);
	}

	/**
	 * 子 agent 单回合调用上限：SDXLAgent 与 Music 子 agent 每回合最多调用一次，
	 * 避免模型在同一回合反复启动重量级子 agent。
	 */
	private static void applyAgentTurnLimits(AIGroupSession state, ToolSettings toolSettings) {
		if (toolSettings.isEnabled("sdxl-agent") || toolSettings.isEnabled("sdxl-agent-nsfw")) {
			state.getToolCallManager().setToolCallLimit("sdxl_agent", AGENT_TURN_LIMIT);
		}
		if (toolSettings.isEnabled("music")) {
			state.getToolCallManager().setToolCallLimit("find_music", AGENT_TURN_LIMIT);
		}
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

	/**
	 * 按工具配置接入审核：{@code tools} 里带 {@code approval:true} 的工具在真正执行前私聊管理员确认。
	 * <p>
	 * 配置里写的是技能名（{@code seedvideo}）或真实工具名（{@code jimeng_video}）都可以：
	 * 技能名会展开成该技能实际注册的全部工具名。没有配置 {@code adminId} 时失败关闭：
	 * 需要审核的工具一律拒绝，并回传给模型明确原因。
	 * </p>
	 *
	 * @param state            会话
	 * @param config           agent 配置
	 * @param toolSettings     已解析的工具配置
	 * @param toolNamesBySkill 技能名到实际工具名的映射
	 */
	private void applyToolApproval(AIGroupSession state, JsonObject config, ToolSettings toolSettings,
			Map<String, Set<String>> toolNamesBySkill) {
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
				System.out.println("[QQ] 工具审核配置未匹配到已注册工具：" + toolSettings.getApproval());
			}
			return;
		}
		long adminId = readAdminId(config);
		state.getToolCallManager().addPermissionListener(ToolCallPermission.requireApproval(needApproval,
				"该工具会产生外部副作用或消耗额度，需要管理员审核"));
		// 统一未批准时回传给模型的文案，避免落到 ToolCallManager 的默认提示
		state.getToolCallManager().setUnapprovedMessage(
				"管理员未批准本次工具调用，请改用其它方式或先询问用户。");
		if (adminId <= 0) {
			String denyReason = "需要审核的工具未配置管理员（adminId），已拒绝执行。";
			// 用带原因的失败关闭：拒绝原因会直接回传给模型，而不是只落在通用提示里
			state.getToolCallManager().setApprover(ToolCallApprover.denyAll(denyReason));
			state.getToolCallManager().setUnapprovedMessage(denyReason);
			System.out.println("[QQ] 工具审核已启用，但未配置 adminId，需要审核的工具将被拒绝：" + needApproval);
			return;
		}
		long timeout = readApprovalTimeout(config);
		state.getToolCallManager().setApprover(toolApproval.approverFor(state, adminId, timeout));
		System.out.println("[QQ] 工具审核已启用：管理员 QQ " + adminId + "，超时 " + timeout + "ms，工具 " + needApproval);
	}

	/** 读取 agent 配置里的管理员 QQ；缺省时回退到 {@code -DadminId=...}。 */
	private static long readAdminId(JsonObject config) {
		JsonElement configured = config == null ? null : config.get("adminId");
		if (configured != null && !configured.isJsonNull() && configured.isJsonPrimitive()) {
			try {
				String raw = configured.getAsString().trim();
				if (!raw.isEmpty()) {
					return Math.max(0, Long.parseLong(raw));
				}
			} catch (RuntimeException e) {
				// 配置写错时继续尝试系统属性
			}
		}
		try {
			return Math.max(0, Long.parseLong(System.getProperty("adminId", "0").trim()));
		} catch (RuntimeException e) {
			return 0;
		}
	}

	/** 读取审核超时：agent 配置 {@code approvalTimeoutMs} 优先，其次 {@code -Dtool_approval_timeout_ms}。 */
	private static long readApprovalTimeout(JsonObject config) {
		JsonElement configured = config == null ? null : config.get("approvalTimeoutMs");
		if (configured != null && !configured.isJsonNull() && configured.isJsonPrimitive()) {
			try {
				String raw = configured.getAsString().trim();
				if (!raw.isEmpty()) {
					return Math.max(5000, Long.parseLong(raw));
				}
			} catch (RuntimeException e) {
				// 配置写错时继续尝试系统属性
			}
		}
		try {
			long value = Long.parseLong(System.getProperty("tool_approval_timeout_ms", "120000").trim());
			return Math.max(5000, value);
		} catch (RuntimeException e) {
			return QqToolApprovalManager.DEFAULT_TIMEOUT_MILLIS;
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
	 * @param qq   QQ 号
	 * @param text 文本
	 * @throws Exception 连接不可用等发送失败
	 */
	public void sendPrivateText(long qq, String text) throws Exception {
		if (text == null || text.isEmpty()) {
			return;
		}
		adapter.sendPrivateText(String.valueOf(qq), text);
	}

	/**
	 * 处理私聊事件：只有管理员对工具审核的「允许/拒绝」会被消费，其它私聊一律忽略。
	 *
	 * @param message 归一化后的私聊消息
	 */
	private void handlePrivateMessage(IncomingMessage message) {
		long selfId = parseLong(message.getSelfId(), 0);
		long userId = parseLong(message.getSenderId(), 0);
		String text = message.getPlainText();
		if (text.isEmpty()) {
			return;
		}
		toolApproval.answer(selfId, userId, text);
	}

	/** 协议 id 转数字，非数字（或空）时退回默认值。 */
	private static long parseLong(String value, long fallback) {
		if (value == null || value.isEmpty()) {
			return fallback;
		}
		try {
			return Long.parseLong(value.trim());
		} catch (RuntimeException e) {
			return fallback;
		}
	}
	/**
	 * 处理群消息：归一化内容 -> 会话历史 -> 触发回复。
	 * <p>
	 * 这里只关心业务：消息属于哪个会话、是否 @ 了机器人、要不要触发一轮回复。协议字段
	 * （message 段、raw.elements、@ 列表、回复 id）已经在适配器里归一化。
	 * </p>
	 *
	 * @param msg 归一化后的群消息
	 */
	private void handleGroupMessage(IncomingMessage msg) {
		long groupId = parseLong(msg.getChatId(), 0);
		// 先解析 @ 列表与“回复了我”：没有被显式 @ 时，回复消息才算 @
		Set<Long> containsAt = new HashSet<>();
		for (String at : msg.getMentionedIds()) {
			long atId = parseLong(at, -1);
			if (atId > 0) {
				containsAt.add(atId);
			}
		}
		String replyMsgId = msg.getReplyToMessageId();
		long replyId = -1;
		if (replyMsgId != null && !replyMsgId.isEmpty()) {
			replyId = findBid(replyMsgId);
			if (containsAt.isEmpty() && replyId != -1) {
				containsAt.add(replyId);
			}
		}
		outer: for (AIGroupSession state : states) {
			if (groupId != state.groupId) {
				continue;
			}
			long senderid = parseLong(msg.getSenderId(), 0);
			// 忽略机器人自己发的消息
			if (senderid == state.botId) {
				continue;
			}
			boolean containsAtMe = containsAt.contains(state.botId);
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
					if (replyId == -1 && quote.getSenderId().equals(String.valueOf(state.botId))) {
						containsAtMe = true;
					}
				} else if (content instanceof IncomingContent.TextBlock) {
					hasText = true;
					String messageLine = ((IncomingContent.TextBlock) content).getText();
					textContent.append(messageLine);
					if (!containsAtMe && messageLine.contains("@" + state.getRoleName(Role.ASSISTANT))) {
						containsAtMe = true;
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
			if (senderid == 1905387052L) {
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
				state.addMessage(mes, false);
			}
			// int hours=new Date().getHours();
			// if(hours<6)
			// containsAtMe=false;
			if (containsAtMe) {
				// 记录本轮触发者，工具审核时告诉管理员是谁发起的
				state.lastSpeakerId = senderid;
				state.lastSpeakerName = sender;

				adapter.react(msg.getMessageId(), "424");
				submitMessage(state);
			}
		}
	}
	public void notifyOtherAgents(AIGroupSession state,String msg,String mid) {
		for(AIGroupSession sess:states) {
			if(state!=sess&&state.groupId==sess.groupId)
				sess.addMessage(Arrays.asList(()->new PlainText("<message senderName=\"" + state.getRoleName(Role.ASSISTANT) + "\" id=\"" + mid + "\">"+msg)),true);
		}
	}
	/** 取（或创建）某个群的发送顺序闸门。 */
	private GroupOutputGate gateFor(long groupId) {
		return outputGates.computeIfAbsent(groupId, id -> new GroupOutputGate(String.valueOf(id)));
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
		final List<MessageContent> attachments=drainToolAttachments(state);
		try {
			// 准备阶段不占顺序：慢的语音合成只影响本轮自己的就绪时间
			sendMessage.submit(()->prepareReply(state,gate,seq,reply,toWritePrice,attachments));
		} catch (Throwable t) {
			t.printStackTrace();
			gate.complete(seq,()->emitPayload(state,textSend(state,reply+toWritePrice),reply));
		}
	}

	/** 组装本轮要发送的动作（含暂存的工具附件）。此方法可以很慢，但不接触顺序闸门。 */
	private void prepareReply(AIGroupSession state,GroupOutputGate gate,long seq,String msg,String toWritePrice,
			List<MessageContent> attachments) {
		SendCall payload;
		try {
			beforeSendMessage(state);
			if(msg==null||msg.isEmpty()) {
				payload=textSend(state,"生成完成。"+toWritePrice);
			}else {
				payload=replySend(state,msg,toWritePrice);
			}
		} catch (Throwable e) {
			e.printStackTrace();
			payload=textSend(state,(msg==null?"":msg)+toWritePrice);
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
		final String notifyText=msg==null?"":msg;
		final List<SendCall> sends=attachmentSends;
		gate.complete(seq,()->{
			// 先发本轮的附件，再发本轮的正文/语音；附件各自最多等 5 秒回执，
			// 回执先到立即继续，超时不再等待也不报错，直接发下一条。
			for(SendCall attachment:sends) {
				emitAttachment(state,attachment);
			}
			emitPayload(state,toSend,notifyText);
		});
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
			sendMessage.submit(()->afterSent(state,sent,notifyText));
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}

	/** 发送成功后的本地簿记：消息 id、状态、通知同群其它 agent。附件消息不写状态、不通知。 */
	private void afterSent(AIGroupSession state,CompletableFuture<String> sent,String notifyText) {
		try {
			String mid=sent.get();
			appendMid(mid,state.botId);
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
	}

	/**
	 * 语音回复：合成在这里完成（不占顺序），失败或超时则退回文本，仍然按本轮顺序发出。
	 */
	private SendCall replySend(AIGroupSession state,String msg,String toWritePrice) {
		try {
			CompletableFuture<ModelGenerationResult> dataFuture =VoiceModelHandler.getAudioData("", state.getRoleName(Role.ASSISTANT),"qbot", msg.replaceAll("枫茜", "枫西").replaceAll("茜茜", "西西"),UUID.randomUUID().toString(), state::addUsage);
			ModelGenerationResult audio=dataFuture.get();
			if(audio!=null&&audio.bodyData!=null&&audio.bodyData.length>0) {
				final String target=chatId(state);
				final String dataUrl=FileUtil.toDataUrl(audio.bodyData);
				return () -> adapter.sendRecord(target,dataUrl);
			}
		} catch (Throwable e) {
			if(e instanceof InterruptedException)
				Thread.currentThread().interrupt();
			if(!(e instanceof ModelRouteException))
				e.printStackTrace();
		}
		return textSend(state,msg+toWritePrice);
	}

	Object globalLock=new Object();

	public void beforeSendMessage(AIGroupSession state) {
		
	}

	public void submitMessage(AIGroupSession state) {
		final GroupOutputGate gate=gateFor(state.groupId);
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
			new Thread(() -> {
				try {
					BasicWebServerBuilder.build().createURIRoot().createWrapper(new VoiceModelLocalServer())
							.rule("/aichat").complete().complete().setNotFound(new File(new File("save"), "404.html"))
							.compile().serverHttp(8998).info("http服务端已开启");
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}).start();
			// 协议适配器可替换：换一个 ChatAdapter 实现，就能用同一个控制器接入别的聊天平台
			ChatAdapter adapter = new NapcatAdapter(System.getProperty("napcat_url"),
					System.getProperty("napcat_token"));
			new NapCatAIConnector(dataFolder, adapter, states);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

	}

}
