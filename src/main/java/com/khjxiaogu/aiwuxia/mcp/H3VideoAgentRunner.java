package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.util.List;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModel;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelGateway;
import com.khjxiaogu.aiwuxia.modelproxy.ModelType;
import com.khjxiaogu.aiwuxia.objectstorage.DelegatingStorage;
import com.khjxiaogu.aiwuxia.objectstorage.LocalStorage;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.objectstorage.TOStorage;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;
import com.khjxiaogu.aiwuxia.utils.ConfigPaths;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.SiteConfig;

/**
 * H3 视频子 agent 的**真实调用**入口（不是干跑，也不做断言）。
 * <p>
 * 通过常量输入提示词和参考图，让 {@link H3VideoAgentMcp} 驱动真实子 agent，并调用真实的
 * {@code h3_gen_video} / {@code h3_gen_anim} 生成视频。需要同时满足：
 * </p>
 * <ul>
 *   <li>配置大模型凭证（例如 {@code -Ddeepseektoken=...}）；</li>
 *   <li>本地模型接收端（{@link LocalModelGateway}）由本入口启动，等待
 *       {@code LocalModelConnectionService} 进程连接；ComfyUI 与 H3 权重可用；</li>
 *   <li>{@code target/pic1.png} 存在（参考图）；</li>
 *   <li>有 TOS 配置时使用本地 + TOS，否则仅本地。</li>
 * </ul>
 * <p>
 * 运行：
 * </p>
 * <pre>
 * java -Ddeepseektoken=你的key -Dfile.encoding=UTF-8 \
 *      -cp "target/classes;target/aiwx_lib/*" \
 *      com.khjxiaogu.aiwuxia.mcp.H3VideoAgentRunner
 * </pre>
 */
public final class H3VideoAgentRunner {

	/** 固定的视频需求。 */
	private static final String PROMPT = "用参考图1作首帧，制作成一个 6秒的视频动画，要设计一套符合角色特征的可爱动画，镜头不要移动，不要生成音频。";
	/** 固定的参考图文件。 */
	private static final File REFERENCE_FILE = new File("target/pic6.png");
	/** 子 agent 角色提示词；为空时不注册 search_character。 */
	private static final List<String> CHARAS = List.of();
	/** 本地缓存目录（TOS 存在时作为本地缓存）。 */
	private static final File LOCAL_STORAGE = new File("target/h3-agent-run-storage");

	private H3VideoAgentRunner() {
	}

	/**
	 * 运行一次真实的 H3 视频子 agent。
	 *
	 * @param args 忽略
	 * @throws Exception 初始化、上传参考图或子 agent 执行失败
	 */
	public static void main(String[] args) throws Exception {
		System.out.println("=== H3Agent 真实调用（会实际生成视频）===");
		SiteConfig.applyArgs(args);
		File dataFolder = new File("save");
		if (!hasLlmCredentials()) {
			System.err.println("未检测到大模型凭证。请用 -Ddeepseektoken=... 等运行。");
			System.exit(2);
		}
		if (!REFERENCE_FILE.isFile()) {
			System.err.println("参考图不存在：" + REFERENCE_FILE.getAbsolutePath());
			System.exit(3);
		}
		// 与 NapCat 相同的 save 配置、站点参数和本地网关端口（-DlocalModelPort）
		LocalModelGateway gateway = startLocalModelGateway(dataFolder);
		if (gateway == null) {
			System.out.println("已跳过自动启动本地模型接收端（-DlocalModelAutoStart=false），使用现有网关");
		}
		LLMConnector.initDefault();
		if (!waitForH3()) {
			System.err.println("本地模型服务未连接（H3 通道）。请确认 ComfyUI、H3 权重和本地模型配置"
					+ "（-DlocalVoiceToken / -DqwenImageUrl / -Dh3VideoUrl）可用。");
			System.exit(4);
		}
		StorageSetup setup = createStorage();
		//setup.storage.getPreferredUrl("44d1d64f1489fc91d7bf9df7214ff9b804a05f7f7b4b55dbfe1fcb912594d47d000f2e68", t->{});
		
		//System.exit(4);
		System.out.println("对象存储：" + (setup.tos ? "本地 + TOS" : "仅本地"));
		String referenceId = setup.storage.uploadIfNotExists(FileUtil.readAll(REFERENCE_FILE), ".png", null);
		System.out.println("参考图已上传：" + referenceId);
		System.out.println("需求：" + PROMPT);

		ToolData agent = H3VideoAgentMcp.createVideo(null, setup.storage, CHARAS);
		JsonObject input = new JsonObject();
		input.addProperty("prompt", PROMPT);
		input.addProperty("reference", referenceId);
		ToolResult result = agent.tool.run(input.toString(), null, null).get();
		System.out.println("\n=== 子 agent 结果 ===");
		System.out.println("success=" + result.isSuccess());
		System.out.println("text=" + result.getText());
		if (result.hasAttachments()) {
			for (MessageContent content : result.getAttachments()) {
				if (content instanceof VideoContent) {
					System.out.println("视频id=" + ((VideoContent) content).getVideoUrl());
				} else {
					System.out.println("attachment=" + content);
				}
			}
		}
	}

	/**
	 * 启动与 NapCat 同配置、同端口的本地模型连接服务。
	 * <p>
	 * 使用同一个 {@code save} 目录与站点参数；本机网关端口由 {@code -DlocalModelPort} 决定
	 * （默认 8997），回调 HTTP 端口与单独运行服务时相同（默认 8998）。
	 * 默认开启，可用 {@code -DlocalModelAutoStart=false} 跳过并使用已有服务。
	 * </p>
	 *
	 * @param dataFolder 数据目录（save）
	 * @return 启动的服务；跳过或失败时返回 null
	 */
	/**
	 * 启动与 NapCat 同配置、同端口的本地模型**接收端**（WebSocket 网关）。
	 * <p>
	 * 只负责监听模型连接，不启动模型客户端；真正的本地模型由独立的
	 * {@code LocalModelConnectionService} 进程连上来。端口与 NapCat 共用
	 * {@code -DlocalModelPort}（默认 8997，只监听 127.0.0.1）。
	 * 默认开启，可用 {@code -DlocalModelAutoStart=false} 跳过。
	 * </p>
	 *
	 * @param dataFolder 数据目录（save）
	 * @return 启动的网关；跳过或失败时返回 null
	 */
	private static LocalModelGateway startLocalModelGateway(File dataFolder) {
		String auto = System.getProperty("localModelAutoStart", "true").trim();
		if ("false".equalsIgnoreCase(auto) || "0".equals(auto) || "no".equalsIgnoreCase(auto)) {
			return null;
		}
		try {
			LocalModelGateway gateway = LocalModelGateway.start(dataFolder);
			System.out.println("[H3Runner] 本地模型接收端已监听：" + gateway.getDeployUri());
			System.out.println("[H3Runner] 等待 LocalModelConnectionService 连接该地址"
					+ "（端口与 NapCat 共用 -DlocalModelPort）");
			return gateway;
		} catch (Throwable t) {
			System.err.println("[H3Runner] 本地模型接收端启动失败：" + t);
			return null;
		}
	}

	/** 等待 H3 通道健康检查通过（最多 120 秒）。 */
	private static boolean waitForH3() throws InterruptedException {
		for (int i = 0; i < 60; i++) {
			if (LocalModel.hasOnlineService(ModelType.H3)) {
				return true;
			}
			Thread.sleep(2000L);
		}
		return false;
	}

	/** 是否配置了非空的大模型凭证。 */
	private static boolean hasLlmCredentials() {
		for (String property : new String[] { "deepseektoken", "mimotoken", "volcmodeltoken", "groktoken",
				"silicontoken" }) {
			String value = System.getProperty(property);
			if (value != null && !value.trim().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** 与生产一致：有 TOS 配置时本地 + TOS，否则仅本地。 */
	private static StorageSetup createStorage() {
		File dataFolder = new File("save");
		File config = ConfigPaths.objectStorage(dataFolder);
		if (config.isFile()) {
			try {
				com.google.gson.JsonObject cfg = com.google.gson.JsonParser
						.parseString(FileUtil.readString(config)).getAsJsonObject();
				ObjectStorageProvider storage = new DelegatingStorage(LOCAL_STORAGE, new TOStorage(cfg));
				return new StorageSetup(storage, true);
			} catch (Exception ex) {
				System.err.println("TOS 初始化失败，退回仅本地：" + ex.getMessage());
			}
		}
		return new StorageSetup(new LocalStorage(LOCAL_STORAGE), false);
	}

	/** 对象存储选择结果。 */
	private static final class StorageSetup {
		final ObjectStorageProvider storage;
		final boolean tos;

		StorageSetup(ObjectStorageProvider storage, boolean tos) {
			this.storage = storage;
			this.tos = tos;
		}
	}
}
