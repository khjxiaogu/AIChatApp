package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;
import com.khjxiaogu.aiwuxia.utils.ConfigPaths;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * 即梦视频工具测试。
 * <p>
 * 前四个用例全部离线：只检查工具输入、系统提示词、附加参考图工具和外部参考图解析，不访问网络。
 * 最后一个用例 {@link #testSimulatedCall()} 会<b>真实调用大模型生成提示词</b>，只把视频生成接口换成记录器；
 * 没有模型 token 时自动跳过。直接用 {@code java} 运行本类的 {@link #main(String[])} 即可。
 * </p>
 */
public class SeedreamMcpVideoTest {
	private static final String BUILTIN_REF = "小明";
	private static final String BUILTIN_REF_2 = "小红";
	/** 即梦接口允许的参考图数量上限，与工具实现保持一致。 */
	private static final int MAX_REFERENCE_IMAGES = 14;

	/**
	 * 运行全部用例。
	 *
	 * @param args 忽略
	 */
	public static void main(String[] args) throws Exception {
		testInput();
		testSystemPrompt();
		testAttachReference();
		testExternalReference();
		testSimulatedCall();
		System.out.println("SeedreamMcpVideoTest: 全部用例通过");
	}

	/** 输入：想法必填，另外允许补充非内置的72位图片id。 */
	private static void testInput() {
		ToolData video = SeedreamMcp.createVideo(jimengConfig(), refImages(), new FakeStorage(), "测试提示词");
		check("jimeng_video".equals(video.name), "工具名应为 jimeng_video，实际：" + video.name);
		check(video.params.keySet().equals(Set.of("prompt", "reference")),
				"输入参数应为 prompt 加可选的 reference，实际：" + video.params.keySet());
		check(video.async, "视频工具应当声明为异步");
		check(video.description.contains("72位图片id"), "工具描述应说明调用者只能补充72位图片id的外部参考图");
	}

	/** 系统提示词由配置的即梦视频提示词加内置参考图清单组成。 */
	private static void testSystemPrompt() {
		Map<String, String> refs = refImages();
		String built = SeedreamMcp.buildVideoSystemPrompt("自定义视频提示词", refs);
		check(built.startsWith("自定义视频提示词"), "配置的提示词应作为系统提示词主体");
		for (String name : refs.keySet())
			check(built.contains(name), "系统提示词应列出内置参考图：" + name);
		check(built.contains("不要提问"), "系统提示词应声明本次调用是单向的");

		String fallback = SeedreamMcp.buildVideoSystemPrompt("   ", refs);
		check(fallback.contains("演出设计师"), "提示词为空时应退回内置兜底提示词");

		String noRefs = SeedreamMcp.buildVideoSystemPrompt("自定义视频提示词", new LinkedHashMap<>());
		check(!noRefs.contains("可用的内置参考图"), "没有内置参考图时不应追加清单");

		// 配置文件存在时，其内容应原样成为系统提示词主体
		File promptFile = ConfigPaths.jimengVideo(new File("save"));
		if (promptFile.isFile()) {
			try {
				String content = FileUtil.readString(promptFile).trim();
				String fromFile = SeedreamMcp.buildVideoSystemPrompt(content, refs);
				check(fromFile.startsWith(content), "配置文件的提示词应作为系统提示词主体（" + promptFile.getPath() + "）");
			} catch (IOException e) {
				throw new AssertionError("读取即梦视频提示词失败：" + promptFile.getPath(), e);
			}
		}
	}

	/** 附加参考图工具：编号、错误和上限。 */
	private static void testAttachReference() {
		Map<String, String> refs = refImages();
		String existingId = repeat('a', 72);
		String missingId = repeat('b', 72);
		FakeStorage tos = new FakeStorage().put(existingId, "https://tos.example/" + existingId);
		List<String> links = new ArrayList<>();
		ToolData attach = SeedreamMcp.createAttachReference(refs, tos, links);

		check("已附加为图1。".equals(run(attach, referenceParam(BUILTIN_REF))), "内置参考图应附加为图1");
		check(links.size() == 1 && refs.get(BUILTIN_REF).equals(links.get(0)), "图1应使用内置参考图的链接");
		check("已附加为图2。".equals(run(attach, referenceParam(existingId))), "图片id应附加为图2");
		check(links.size() == 2 && ("https://tos.example/" + existingId).equals(links.get(1)),
				"图2应使用图片id的公开链接");
		check(run(attach, referenceParam("不存在的人")).contains("不是内置参考图"), "未知名字应返回错误");
		check(run(attach, referenceParam(missingId)).contains("已清理或不存在"), "不存在的图片id应返回错误");
		check(run(attach, "{}").contains("缺少reference"), "缺少参数应返回错误");
		check(run(attach, "不是json").contains("缺少reference"), "参数不是 JSON 时应返回错误");
		check(links.size() == 2, "出错不应改动已附加的参考图");

		// 上限：最多 14 张
		List<String> full = new ArrayList<>();
		ToolData limited = SeedreamMcp.createAttachReference(refs, new FakeStorage(), full);
		for (int i = 1; i <= MAX_REFERENCE_IMAGES; i++)
			check(("已附加为图" + i + "。").equals(run(limited, referenceParam(BUILTIN_REF))),
					"第" + i + "张参考图应附加成功");
		check(run(limited, referenceParam(BUILTIN_REF)).contains("上限"), "超过上限应返回错误");
		check(full.size() == MAX_REFERENCE_IMAGES, "超过上限后参考图数量应保持不变");
	}

	/** 调用者可以额外附加非内置的72位图片id，编号从图1开始。 */
	private static void testExternalReference() {
		String id1 = repeat('a', 72);
		String id2 = repeat('c', 72);
		FakeStorage tos = new FakeStorage().put(id1, "https://tos.example/" + id1)
				.put(id2, "https://tos.example/" + id2);

		List<String> links = new ArrayList<>();
		check(SeedreamMcp.attachExternalReferences(null, tos, links, null) == null, "没有外部参考图时应成功");
		check(SeedreamMcp.attachExternalReferences("   ", tos, links, null) == null, "空的外部参考图应成功");
		check(links.isEmpty(), "空参数不应附加任何参考图");

		check(SeedreamMcp.attachExternalReferences(id1 + "," + id2, tos, links, null) == null, "合法的外部参考图应附加成功");
		check(links.size() == 2, "应附加2张外部参考图");
		check(("https://tos.example/" + id1).equals(links.get(0))
				&& ("https://tos.example/" + id2).equals(links.get(1)), "外部参考图应保持传入顺序");

		// 外部参考图占据图1、图2，脚本 agent 继续附加的内置参考图接在图3
		ToolData attach = SeedreamMcp.createAttachReference(refImages(), tos, links);
		check("已附加为图3。".equals(run(attach, referenceParam(BUILTIN_REF))), "内置参考图应接在外部参考图之后，编号为图3");

		// 外部参考图只接受72位图片id
		List<String> invalid = new ArrayList<>();
		check(SeedreamMcp.attachExternalReferences(BUILTIN_REF, tos, invalid, null).contains("72位"),
				"内置参考图名字不应通过外部参考图传入");
		check(SeedreamMcp.attachExternalReferences(repeat('a', 71), tos, invalid, null).contains("72位"),
				"非72位的内容应返回错误");
		check(SeedreamMcp.attachExternalReferences(repeat('b', 72), tos, invalid, null).contains("已清理或不存在"),
				"不存在的图片id应返回错误");
		check(invalid.isEmpty(), "出错不应改动参考图列表");

		// 上限与脚本 agent 共享
		List<String> full = new ArrayList<>();
		StringBuilder many = new StringBuilder();
		FakeStorage bigTos = new FakeStorage();
		for (int i = 0; i < MAX_REFERENCE_IMAGES; i++) {
			String id = repeat('a', 71) + Integer.toHexString(i);
			bigTos.put(id, "https://tos.example/" + id);
			if (i > 0)
				many.append(',');
			many.append(id);
		}
		check(SeedreamMcp.attachExternalReferences(many.toString(), bigTos, full, null) == null,
				"刚好" + MAX_REFERENCE_IMAGES + "张外部参考图应附加成功");
		check(full.size() == MAX_REFERENCE_IMAGES, "外部参考图数量应为" + MAX_REFERENCE_IMAGES);
		check(SeedreamMcp.attachExternalReferences(repeat('d', 72), bigTos, full, null).contains("上限"),
				"超过上限应返回错误");
	}

	/**
	 * 模拟一次真实调用：<b>脚本提示词由真实大模型生成</b>，只跳过视频生成接口。
	 * <p>
	 * 走完整的 jimeng_video 工具：真实脚本 agent（{@link LLMConnector}，含多轮工具调用，可用
	 * {@code attach_reference} 附加内置参考图）产出脚本，视频生成器换成记录器，只保存最终提示词和
	 * 参考图并返回伪地址，不发出即梦请求。
	 * </p>
	 * <p>
	 * 需要模型 token（例如 {@code -Ddeepseektoken=...}），没有任何 token 时打印跳过信息而不判失败。
	 * </p>
	 *
	 * @throws Exception 真实模型调用失败时
	 */
	private static void testSimulatedCall() throws Exception {
		if (!hasAnyModelToken()) {
			System.out.println("SeedreamMcpVideoTest: 跳过真实模型调用（需要形如 -Ddeepseektoken=... 的 token）");
			return;
		}
		LLMConnector.initDefault();

		// 系统提示词取真实配置
		String systemPrompt = "";
		File promptFile = ConfigPaths.jimengVideo(new File("save"));
		if (promptFile.isFile())
			systemPrompt = FileUtil.readString(promptFile);

		Map<String, String> refs = refImages();
		String externalId = repeat('a', 72);
		String externalUrl = "https://tos.example/" + externalId;
		final String videoUrl = "https://video.example/live-out.mp4";

		String[] capturedSystem = new String[1];
		String[] capturedUser = new String[1];
		List<String> submittedLinks = new ArrayList<>();
		String[] submittedPrompt = new String[1];

		ToolData video = SeedreamMcp.createVideo(refs, new FakeStorage().put(externalId, externalUrl), systemPrompt,
				// 真实脚本 agent：先记录请求内容，再交给模型
				request -> {
					capturedSystem[0] = request.history.get(0).getContextContent().toText();
					capturedUser[0] = request.history.get(1).getContextContent().toText();
					Map<String, ToolData> tools = request.getTools();
					check(tools.containsKey("attach_reference") && tools.containsKey("image_recognition"),
							"脚本 agent 应同时拿到 attach_reference 与 image_recognition，实际：" + tools.keySet());
					return SeedreamMcp.callScriptAgent(request);
				},
				// 假视频生成器：只记录，不请求即梦
				(links, prompt) -> {
					submittedLinks.addAll(links);
					submittedPrompt[0] = prompt;
					return CompletableFuture.completedFuture(videoUrl);
				});

		JsonObject params = new JsonObject();
		params.addProperty("prompt", "小明在雨夜的霓虹街头奔跑，8秒，9:16竖版");
		params.addProperty("reference", externalId);

		ToolResult result;
		try {
			result = video.tool.run(params.toString(), null, new ToolCallManager()).get(5, TimeUnit.MINUTES);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (hasCause(cause, ModelRouteException.class)) {
				System.out.println("SeedreamMcpVideoTest: 跳过真实模型调用（没有可用模型路由）：" + cause.getMessage());
				return;
			}
			throw new AssertionError("真实模型调用失败", cause);
		} catch (TimeoutException e) {
			throw new AssertionError("真实模型调用超时（5分钟）", e);
		}

		System.out.println("---- 大模型生成的视频提示词 ----");
		System.out.println(submittedPrompt[0]);
		System.out.println("---- 提交的参考图 ----");
		for (int i = 0; i < submittedLinks.size(); i++)
			System.out.println("图" + (i + 1) + "：" + submittedLinks.get(i));
		System.out.println("----------------------------");

		// 与模型具体决策无关的断言
		check("视频生成成功".equals(result.getText()), "工具结果应为成功，实际：" + result.getText());
		check(result.getAttachments().size() == 1, "应返回1个视频附件");
		MessageContent attachment = result.getAttachments().get(0);
		check(attachment instanceof VideoContent, "附件应为 VideoContent，实际：" + attachment.getType());
		check(videoUrl.equals(((VideoContent) attachment).getVideoUrl()), "附件应为伪视频地址");

		check(submittedPrompt[0] != null && submittedPrompt[0].trim().length() >= 20,
				"大模型应生成一段完整的视频提示词，实际：" + submittedPrompt[0]);
		check(!submittedLinks.isEmpty() && externalUrl.equals(submittedLinks.get(0)),
				"外部参考图应排在图1，实际：" + submittedLinks);
		check(submittedLinks.size() <= 14, "参考图数量不应超过上限，实际：" + submittedLinks.size());
		check(capturedSystem[0].contains("即梦") || capturedSystem[0].contains("视频"),
				"系统提示词应带上即梦视频提示词，实际：" + capturedSystem[0]);
		check(capturedUser[0].contains("调用者已附加1张外部参考图"), "用户消息应说明已附加的外部参考图");
		check(capturedUser[0].contains(externalId), "用户消息应给出外部参考图id，便于脚本 agent 查看");
		check(capturedUser[0].contains("女孩") || capturedUser[0].contains("小明"), "用户消息应包含用户想法");
	}

	private static boolean hasAnyModelToken() {
		String[] names = { "deepseektoken", "mimotoken", "volcmodeltoken", "groktoken", "silicontoken" };
		for (String name : names) {
			String token = System.getProperty(name);
			if (token != null && !token.isEmpty())
				return true;
		}
		return false;
	}

	private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
		for (Throwable t = error; t != null; t = t.getCause())
			if (type.isInstance(t))
				return true;
		return false;
	}

	private static Map<String, String> refImages() {
		Map<String, String> refs = new LinkedHashMap<>();
		refs.put(BUILTIN_REF, "https://img.example/xiaoming.png");
		refs.put(BUILTIN_REF_2, "https://img.example/xiaohong.png");
		return refs;
	}

	private static JsonObject jimengConfig() {
		return JsonParser.parseString("{\"accessKey\":\"test\",\"secretKey\":\"test\"}").getAsJsonObject();
	}

	private static String referenceParam(String reference) {
		JsonObject jo = new JsonObject();
		jo.addProperty("reference", reference);
		return jo.toString();
	}

	/** 直接执行工具（同步工具会立即完成，不涉及网络）。 */
	private static String run(ToolData tool, String params) {
		ToolResult result = tool.tool.run(params, null, new ToolCallManager()).join();
		return result.getText();
	}

	private static String repeat(char c, int count) {
		StringBuilder sb = new StringBuilder(count);
		for (int i = 0; i < count; i++)
			sb.append(c);
		return sb.toString();
	}

	private static void check(boolean ok, String message) {
		if (!ok)
			throw new AssertionError(message);
	}

	/** 只做本地查表的内存对象存储，避免测试访问对象存储服务。 */
	private static final class FakeStorage implements ObjectStorageProvider {
		private final Map<String, String> publicUrls = new LinkedHashMap<>();

		FakeStorage put(String name, String url) {
			publicUrls.put(name, url);
			return this;
		}

		@Override
		public boolean exists(String fn, Consumer<UsageIntf<?>> addUsage) {
			return publicUrls.containsKey(fn);
		}

		@Override
		public String upload(String fn, byte[] data, Consumer<UsageIntf<?>> addUsage) {
			throw new UnsupportedOperationException("离线测试不上传文件");
		}

		@Override
		public String getUrl(String fn, Consumer<UsageIntf<?>> addUsage) throws IOException {
			return getPublicUrl(fn, addUsage);
		}

		@Override
		public String getPublicUrl(String fn, Consumer<UsageIntf<?>> addUsage) throws IOException {
			return publicUrls.get(fn);
		}
	}
}
