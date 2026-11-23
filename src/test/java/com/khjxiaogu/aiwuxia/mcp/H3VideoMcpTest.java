package com.khjxiaogu.aiwuxia.mcp;

import java.util.Set;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.vision.H3VideoApi;
import com.khjxiaogu.aiwuxia.vision.H3VideoUsage;

/**
 * MiniMax H3 视频 MCP 的离线自检。
 * <p>
 * 只检查工具输入面（参数、可选参数、异步声明）、比例换算、时长校验、帧数吸附与工作流图结构，
 * 不访问 ComfyUI，也不需要本地模型服务。直接用 {@code java} 运行本类的 {@link #main(String[])} 即可。
 * </p>
 */
public class H3VideoMcpTest {

	/**
	 * 运行全部用例。
	 *
	 * @param args 忽略
	 */
	public static void main(String[] args) throws Exception {
		testToolInput();
		testDimensions();
		testDuration();
		testFrameSnap();
		testGraph();
		testGraphWithFrames();
		testGraphNvfp4();
		testFrameGraph();
		testVideoToVideoGraph();
		testUsage();
		System.out.println("H3VideoMcpTest: 全部用例通过");
	}

	/** 两种模式各暴露提示词、参考图、画面比例和时长；prompt 必填。 */
	private static void testToolInput() {
		H3VideoMcp provider = new H3VideoMcp(null);
		ToolData h3 = null;
		ToolData nvfp4 = null;
		for (ToolData tool : provider.getTools()) {
			if (H3VideoMcp.TOOL_GENERATE.equals(tool.name)) {
				h3 = tool;
			} else if (H3VideoMcp.TOOL_ANIM.equals(tool.name)) {
				nvfp4 = tool;
			}
		}
		check(h3 != null && nvfp4 != null, "应同时提供 h3_gen_video 与 h3_gen_anim");
		ToolData frameTool = null;
		for (ToolData tool : provider.getTools()) {
			if (H3VideoMcp.TOOL_FRAME.equals(tool.name)) {
				frameTool = tool;
			}
		}
		check(frameTool != null, "应提供 video_extract_frame 视频截帧工具");
		check(frameTool.params.keySet().equals(Set.of("video", "frame")),
				"截帧参数应为 video/frame，实际：" + frameTool.params.keySet());
		check(frameTool.optionalParams.equals(Set.of("frame")), "截帧只有 frame 可选");
		for (ToolData tool : new ToolData[] { h3, nvfp4 }) {
			check(tool.params.keySet()
					.equals(Set.of("prompt", "reference", "video", "first", "last", "resolution", "duration")),
					"参数集合不正确，实际：" + tool.params.keySet());
			check(tool.optionalParams
					.equals(Set.of("reference", "video", "first", "last", "resolution", "duration")),
					"只有 prompt 是必填，实际可选参数：" + tool.optionalParams);
			check(tool.async, "视频生成耗时长，工具应声明为异步");
			check(tool.description.contains("步数"), "工具描述应说明步数等参数由代码固定");
			check(tool.description.contains("无声"), "工具描述应说明禁止音频");
		}
		check(h3.description.contains("H3 视频制作"), "h3_gen_video 应标明视频制作");
		check(nvfp4.description.contains("简单动图"), "h3_gen_anim 应标明简单动图");
		check(H3VideoMcp.splitReferences("a，b").size() == 2, "中文逗号也应拆分参考图");
		check(H3VideoMcp.splitReferences("").isEmpty(), "空参考图应解析为空列表");
		check(H3VideoMcp.splitReferences("a, ,b").size() == 2, "空白项应被忽略");
	}

	/** 比例换算：全部是 32 的倍数；NVFP4 简单动图用更小的画布。 */
	private static void testDimensions() {
		int[][] expected = { { 864, 480 }, { 480, 864 }, { 640, 640 }, { 736, 544 }, { 544, 736 } };
		String[] ratios = { "16:9", "9:16", "1:1", "4:3", "3:4" };
		for (int i = 0; i < ratios.length; i++) {
			int[] size = H3VideoMcp.dimensions(ratios[i]);
			check(size[0] == expected[i][0] && size[1] == expected[i][1],
					ratios[i] + " 应为 " + expected[i][0] + "x" + expected[i][1] + "，实际：" + size[0] + "x" + size[1]);
			check(size[0] % H3VideoApi.SIZE_MULTIPLE == 0 && size[1] % H3VideoApi.SIZE_MULTIPLE == 0,
					ratios[i] + " 的宽高必须是 32 的倍数");
			int[] small = H3VideoMcp.dimensions(ratios[i], H3VideoMcp.Mode.NVFP4);
			check(small[0] % H3VideoApi.SIZE_MULTIPLE == 0 && small[1] % H3VideoApi.SIZE_MULTIPLE == 0,
					"NVFP4 " + ratios[i] + " 的宽高必须是 32 的倍数");
		}
		int[] fullWidth = H3VideoMcp.dimensions("16：9");
		check(fullWidth[0] == 864 && fullWidth[1] == 480, "全角冒号也应能识别");
		try {
			H3VideoMcp.dimensions("21:9");
			check(false, "不支持的比例应当报错");
		} catch (IllegalArgumentException expectedError) {
			// 预期
		}
	}

	/** 时长：H3 缺省 5 秒（1~15），NVFP4 缺省 3 秒（1~8）；其余拒绝。 */
	private static void testDuration() {
		check(H3VideoMcp.duration(json("{}")) == 5d, "H3 缺省时长应为 5 秒");
		check(H3VideoMcp.duration(json("{\"duration\":\"7.5\"}")) == 7.5d, "小数时长应被接受");
		check(H3VideoMcp.duration(json("{\"duration\":\"6s\"}")) == 6d, "带单位 s 的时长应被接受");
		check(H3VideoMcp.duration(json("{}"), H3VideoMcp.Mode.NVFP4) == 3d, "NVFP4 缺省时长应为 3 秒");
		for (String bad : new String[] { "0", "16", "-1", "abc" }) {
			try {
				H3VideoMcp.duration(json("{\"duration\":\"" + bad + "\"}"));
				check(false, "非法时长应当报错：" + bad);
			} catch (IllegalArgumentException expectedError) {
				// 预期
			}
		}
		for (String bad : new String[] { "0", "9", "-1" }) {
			try {
				H3VideoMcp.duration(json("{\"duration\":\"" + bad + "\"}"), H3VideoMcp.Mode.NVFP4);
				check(false, "NVFP4 非法时长应当报错：" + bad);
			} catch (IllegalArgumentException expectedError) {
				// 预期
			}
		}
	}

	/** 帧数：24fps 向上吸附到最近的 17k+5，且结果总是合法帧数。 */
	private static void testFrameSnap() {
		int[][] cases = { { 5, 124 }, { 10, 243 }, { 15, 362 }, { 2, 56 }, { 1, 39 } };
		for (int[] item : cases) {
			int frames = H3VideoApi.snapFrames(item[0]);
			check(frames == item[1], item[0] + " 秒应吸附到 " + item[1] + " 帧，实际：" + frames);
		}
		check(H3VideoApi.snapFrames(0) == 5, "非正时长应退到最短片段 5 帧");
		check(H3VideoApi.isValidFrameCount(H3VideoApi.snapFrames(7.5)), "吸附结果必须是 17k+5");
		check(!H3VideoApi.isValidFrameCount(125), "125 不在 17k+5 网格上");
		check(H3VideoApi.normalizeFrames(0) == 5, "normalizeFrames(0) 应为 5");
		check(H3VideoApi.normalizeFrames(124) == 124, "已经是合法帧数时不应改动");
	}

	/** 文生视频图：默认 int8、8 步、稀疏注意力开、无声。 */
	private static void testGraph() {
		JsonObject graph = new H3VideoApi().buildVideo("测试提示词", 864, 480, 120, 7, null, null);
		Set<String> nodes = graph.keySet();
		check(nodes.toString().equals("[1, 2, 3, 5, 15, 6, 7, 8, 9, 10, 11, 13, 14, 16, 17, 18, 19]"),
				"文生视频节点应含稀疏节点 15 与首/末帧节点 16~19，实际：" + nodes);
		JsonObject video = graph.getAsJsonObject("5").getAsJsonObject("inputs");
		check("MiniMaxH3ImageToVideo".equals(graph.getAsJsonObject("5").get("class_type").getAsString()),
				"节点 5 应为 MiniMaxH3ImageToVideo");
		check(video.get("length").getAsInt() == 124, "120 帧应吸附成 124 帧");
		check(!video.has("first_frame") && !video.has("last_frame"), "文生视频不应带首/尾帧字段");
		check(graph.getAsJsonObject("8").getAsJsonObject("inputs").get("steps").getAsInt() == 8,
				"步数应固定为 8");
		check(!graph.getAsJsonObject("8").getAsJsonObject("inputs").has("cfg"), "H3 工作流不应出现 CFG");
		check(graph.getAsJsonObject("15").get("class_type").getAsString().equals("BlockSparseAttention"),
				"启用稀疏注意力时应包含节点 15");
		check(graph.getAsJsonObject("6").getAsJsonObject("inputs").get("model").toString().equals("[\"15\",0]"),
				"guider 的 model 应指向稀疏节点");
		check(graph.getAsJsonObject("8").getAsJsonObject("inputs").get("model").toString().equals("[\"15\",0]"),
				"scheduler 的 model 应指向稀疏节点");
		check(!graph.has("4") && !graph.has("12"), "禁用音频时不应有音频 VAE 与音频解码节点");
		check(!graph.getAsJsonObject("13").getAsJsonObject("inputs").has("audio"),
				"禁用音频时 CreateVideo 不应有 audio 输入");
		check(graph.getAsJsonObject("1").getAsJsonObject("inputs").get("unet_name").getAsString()
				.equals(H3VideoApi.DEFAULT_UNET), "默认应使用 int8 主模型");
		check(graph.getAsJsonObject("14").getAsJsonObject("inputs").get("filename_prefix").getAsString()
				.equals("video/MiniMax-H3"), "输出前缀应为 video/MiniMax-H3");
		check(graph.getAsJsonObject("16").getAsJsonObject("inputs").get("batch_index").getAsInt() == 0,
				"节点 16 应取首帧");
		check(graph.getAsJsonObject("17").getAsJsonObject("inputs").get("batch_index").getAsInt() == -1,
				"节点 17 应取末帧");
		check(graph.getAsJsonObject("18").getAsJsonObject("inputs").get("filename_prefix").getAsString()
				.endsWith("_first"), "节点 18 应保存首帧");
		check(graph.getAsJsonObject("19").getAsJsonObject("inputs").get("filename_prefix").getAsString()
				.endsWith("_last"), "节点 19 应保存末帧");
	}

	/** NVFP4 简单动图：换主模型、6 步、NVFP4 资源占 10 份。 */
	private static void testGraphNvfp4() {
		H3VideoApi api = new H3VideoApi().setModelTier(H3VideoApi.ModelTier.NVFP4);
		check(api.getSteps() == 6, "NVFP4 默认步数应为 6");
		check(H3VideoApi.ModelTier.NVFP4.resourceAmount() == 10, "NVFP4 运行时资源消耗应为 10");
		JsonObject graph = api.buildVideo("测试", 640, 352, 72, 1, null, null);
		check(graph.getAsJsonObject("1").getAsJsonObject("inputs").get("unet_name").getAsString()
				.equals(H3VideoApi.NVFP4_UNET), "应使用 NVFP4 主模型");
		check(graph.getAsJsonObject("8").getAsJsonObject("inputs").get("steps").getAsInt() == 6,
				"NVFP4 步数应为 6");
		H3VideoApi dense = new H3VideoApi().setModelTier(H3VideoApi.ModelTier.INT8).setSparse(false);
		JsonObject denseGraph = dense.buildVideo("测试", 864, 480, 124, 1, null, null);
		check(!denseGraph.has("15"), "关闭稀疏注意力时不应有节点 15");
		check(denseGraph.getAsJsonObject("6").getAsJsonObject("inputs").get("model").toString()
				.equals("[\"1\",0]"), "稠密时 guider 应直接接主模型");
	}

	/** 视频截帧图：LoadVideo → GetVideoComponents → ImageFromBatch → SaveImage。 */
	private static void testFrameGraph() {
		JsonObject graph = new H3VideoApi().buildVideoFrame("h3/clip.mp4", -1);
		check(grid(graph).equals("[1, 2, 3, 4]"), "截帧图节点应为 1~4，实际：" + graph.keySet());
		check(graph.getAsJsonObject("1").get("class_type").getAsString().equals("LoadVideo"), "节点 1 应为 LoadVideo");
		check(graph.getAsJsonObject("2").get("class_type").getAsString().equals("GetVideoComponents"),
				"节点 2 应为 GetVideoComponents");
		JsonObject batch = graph.getAsJsonObject("3").getAsJsonObject("inputs");
		check(batch.get("batch_index").getAsInt() == -1, "batch_index 应透传 -1");
		check(graph.getAsJsonObject("4").get("class_type").getAsString().equals("SaveImage"), "节点 4 应为 SaveImage");
	}

	/** 视频生视频图：首/尾视频各自 LoadVideo → VideoTemporalCrop → GetVideoComponents → 取边帧。 */
	private static void testVideoToVideoGraph() {
		JsonObject graph = new H3VideoApi().buildVideoFromVideos("衔接", 864, 480, 124, 1,
				"h3/a.mp4", 0, 48, "h3/b.mp4", 0, 24);
		check(graph.has("30") && graph.has("31") && graph.has("32") && graph.has("33"), "应有首帧视频链 30~33");
		check(graph.has("40") && graph.has("41") && graph.has("42") && graph.has("43"), "应有尾帧视频链 40~43");
		JsonObject firstInputs = graph.getAsJsonObject("5").getAsJsonObject("inputs");
		check(firstInputs.get("first_frame").toString().equals("[\"33\",0]"), "first_frame 应接首帧视频链");
		check(firstInputs.get("last_frame").toString().equals("[\"43\",0]"), "last_frame 应接尾帧视频链");
		check(graph.getAsJsonObject("31").getAsJsonObject("inputs").get("length").getAsInt() == 48,
				"首帧片段长度应透传");
		check(graph.getAsJsonObject("43").getAsJsonObject("inputs").get("batch_index").getAsInt() == 0,
				"尾帧视频链应取片段首帧");
		check(graph.getAsJsonObject("33").getAsJsonObject("inputs").get("batch_index").getAsInt() == -1,
				"首帧视频链应取片段末帧");
		check(graph.getAsJsonObject("11").get("class_type").getAsString().equals("VAEDecodeTiled"),
				"视频生视频应用分块解码");
		JsonObject concat = graph.getAsJsonObject("50");
		check(concat != null && "ConcatenateVideo".equals(concat.get("class_type").getAsString()),
				"应使用 ConcatenateVideo 节点拼接首段+中段+尾段");
		JsonObject concatInputs = concat.getAsJsonObject("inputs");
		check(concatInputs.get("videos.video0").toString().equals("[\"31\",0]"), "video0 应为首段");
		check(concatInputs.get("videos.video1").toString().equals("[\"13\",0]"), "video1 应为中段");
		check(concatInputs.get("videos.video2").toString().equals("[\"41\",0]"), "video2 应为尾段");
		check(graph.getAsJsonObject("51").getAsJsonObject("inputs").get("filename_prefix").getAsString()
				.endsWith("_full"), "拼接结果应保存为 _full 前缀");
	}

	private static String grid(JsonObject graph) {
		return graph.keySet().toString();
	}

	/** 计费：稠密比稀疏贵；n &lt; 12288 时稀疏自动按稠密估算；配额按 1,000,000/小时。 */
	private static void testUsage() {
		check(Math.abs(H3VideoApi.ModelTier.INT8.stepLinear() - 4.94e-4) < 1e-12,
				"INT8 a 应为 4.94e-4");
		check(Math.abs(H3VideoApi.ModelTier.INT8.stepQuadratic() - 3.86e-8) < 1e-15,
				"INT8 b 应为 3.86e-8");
		check(Math.abs(H3VideoApi.ModelTier.NVFP4.stepLinear() - 6.10e-4) < 1e-12,
				"NVFP4 a 应为 6.10e-4");
		check(Math.abs(H3VideoApi.ModelTier.NVFP4.stepQuadratic() - 4.80e-8) < 1e-15,
				"NVFP4 b 应为 4.80e-8");
		check(H3VideoUsage.textTokens("") == 500, "空提示词的文本 token 应为下限 500");
		check(H3VideoUsage.textTokens(repeat("字", 3000)) == 1500, "长提示词的文本 token 应封顶 1500");
		double sparse = H3VideoUsage.estimateSeconds(864, 480, 5, 8, true,
				H3VideoApi.ModelTier.INT8, 1000, 1000);
		double dense = H3VideoUsage.estimateSeconds(864, 480, 5, 8, false,
				H3VideoApi.ModelTier.INT8, 1000, 1000);
		check(sparse > 0 && Math.abs(sparse - dense) < 1e-9, "a/b 已包含稀疏配置，sparse 不再二次缩放");
		double smallSparse = H3VideoUsage.estimateSeconds(640, 352, 3, 6, true,
				H3VideoApi.ModelTier.NVFP4, 1000, 1000);
		double smallDense = H3VideoUsage.estimateSeconds(640, 352, 3, 6, false,
				H3VideoApi.ModelTier.NVFP4, 1000, 1000);
		check(Math.abs(smallSparse - smallDense) < 1e-9, "sparse 参数不再影响耗时估算");
		H3VideoUsage usage = new H3VideoUsage(864, 480, 5, 8, true, H3VideoApi.ModelTier.INT8, 1000);
		check(Math.abs(usage.getQuota() - usage.getEstimatedSeconds() / 3600d * 1_000_000d) < 1e-6,
				"配额应为 估算秒数 / 3600 * 1,000,000");
		check(usage.getEstimatedSeconds() > 38.4, "估算应包含 int8 冷加载时间");
		H3VideoUsage nvfp4 = new H3VideoUsage(640, 352, 3, 6, true,
				H3VideoApi.ModelTier.NVFP4, 1000);
		check(nvfp4.getEstimatedSeconds() > 30.55, "估算应包含 NVFP4 冷加载时间");
		check(nvfp4.getQuota() > 0, "NVFP4 配额应为正数");
		H3VideoUsage mapped = H3VideoMcp.createUsage(json("{\"prompt\":\"测试\",\"duration\":\"3\"}"),
				H3VideoMcp.Mode.NVFP4);
		check(mapped.getEstimatedSeconds() > 0, "NVFP4 模式应能算出累计计算时间");
		H3VideoUsage defaulted = H3VideoMcp.createUsage(json("{\"prompt\":\"测试\"}"), H3VideoMcp.Mode.H3);
		check(defaulted.getEstimatedSeconds() > 0, "H3 模式应能算出累计计算时间");
	}

	private static String repeat(String value, int count) {
		StringBuilder sb = new StringBuilder(value.length() * count);
		for (int i = 0; i < count; i++) {
			sb.append(value);
		}
		return sb.toString();
	}

	/** 首尾帧图：首/尾帧各接一个 LoadImage，并同步写进节点 5。 */
	private static void testGraphWithFrames() {
		H3VideoApi api = new H3VideoApi();
		JsonObject first = api.buildVideo("测试", 864, 480, 124, 1, "h3/a.png", null);
		JsonObject firstInputs = first.getAsJsonObject("5").getAsJsonObject("inputs");
		check(first.has("20") && !first.has("21"), "只有首帧时不应有节点 21");
		check("[\"20\",0]".equals(firstInputs.get("first_frame").toString()), "first_frame 应指向节点 20");
		JsonObject both = api.buildVideo("测试", 864, 480, 124, 1, "h3/a.png", "h3/b.png");
		JsonObject bothInputs = both.getAsJsonObject("5").getAsJsonObject("inputs");
		check(both.has("20") && both.has("21"), "首尾帧应同时有节点 20 和 21");
		check("[\"21\",0]".equals(bothInputs.get("last_frame").toString()), "last_frame 应指向节点 21");
		try {
			api.buildVideo("测试", 864, 480, 124, 1, null, "h3/b.png");
			check(false, "只给尾帧应当报错");
		} catch (IllegalArgumentException expectedError) {
			// 预期
		}
		try {
			api.buildVideo("测试", 810, 480, 124, 1, null, null);
			check(false, "宽高不是 32 的倍数应当报错");
		} catch (IllegalArgumentException expectedError) {
			// 预期
		}
	}

	/** 失败路径也要归还资源：本地模型离线时工具失败，但锁必须回到满容量。 */
	private static void testLockReleasedOnFailure() throws Exception {
		// 聊天进程已不持有资源锁：锁由本地模型连接服务统一管理，这里只验证工具离线失败
		ToolData tool = new H3VideoMcp(null).getTools().iterator().next();
		boolean success;
		try {
			success = tool.tool.run("{\"prompt\":\"测试\"}", null, null).get().isSuccess();
		} catch (Exception ex) {
			throw new AssertionError("工具执行不应抛出异常：" + ex);
		}
		check(!success, "本地模型离线时应返回失败结果");
	}

	private static JsonObject json(String raw) {
		return JsonParser.parseString(raw).getAsJsonObject();
	}	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
