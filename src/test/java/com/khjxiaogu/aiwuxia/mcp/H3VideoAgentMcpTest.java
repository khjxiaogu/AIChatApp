package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;

/**
 * H3 视频子 agent 的离线自检。
 * <p>
 * 只检查工具输入面（参数、异步声明）、系统提示词的默认值与文件覆盖、以及离线时的失败提示，
 * 不访问大模型、不生成视频。直接用 {@code java} 运行本类的 {@link #main(String[])} 即可。
 * </p>
 */
public class H3VideoAgentMcpTest {

	/**
	 * 运行全部用例。
	 *
	 * @param args 忽略
	 */
	public static void main(String[] args) throws Exception {
		testToolInput();
		testDefaultPrompt();
		testCustomPrompt();
		testOfflineFailure();
		System.out.println("H3VideoAgentMcpTest: 全部用例通过");
	}

	/** 输入：参考图可选、提示词必填；工具声明为异步。 */
	private static void testToolInput() throws Exception {
		ToolData tool = H3VideoAgentMcp.createVideo(null, null, null);
		check(H3VideoAgentMcp.TOOL_AGENT.equals(tool.name), "工具名应为 h3_video_agent，实际：" + tool.name);
		check(tool.params.keySet().equals(Set.of("reference", "video", "prompt")),
				"参数应为 reference/video/prompt，实际：" + tool.params.keySet());
		check(tool.optionalParams.equals(Set.of("reference", "video")),
				"参考图、参考视频可选，实际：" + tool.optionalParams);
		check(tool.async, "视频生成耗时长，子 agent 工具应声明为异步");
		check(tool.description.contains("子 agent"), "工具描述应说明这是子 agent");
		check(tool.getParamLabel("prompt").equals("提示词"), "prompt 的显示名应为“提示词”");
	}

	/** 没配置提示词文件时使用内置提示词，并追加硬性要求。 */
	private static void testDefaultPrompt() throws Exception {
		String system = H3VideoAgentMcp.buildSystemPrompt(null);
		check(system.contains("视频导演"), "内置提示词应说明视频导演角色");
		check(system.contains("h3_gen_video"), "内置提示词应指导子 agent 调用 h3_gen_video");
		check(system.contains("h3_gen_anim"), "内置提示词应指导子 agent 调用 h3_gen_anim");
		check(system.contains("无声") || system.contains("禁用音频"), "内置提示词应说明视频无声");
		check(system.contains("17") || system.contains("步数"), "内置提示词应说明技术参数由代码固定");
		check(system.contains("硬性要求"), "应始终追加硬性要求");
		check(system.contains("视频 id"), "应要求输出包含视频 id");
	}

	/** role/charaset/rules 存在时按顺序拼接，不再使用内置提示词。 */
	private static void testCustomPrompt() throws Exception {
		Path dir = Files.createTempDirectory("h3-agent-test");
		try {
			write(dir, "role.txt", "自定义角色");
			write(dir, "charaset.txt", "\n自定义人物");
			write(dir, "rules.txt", "\n自定义规则");
			String system = H3VideoAgentMcp.buildSystemPrompt(dir.toFile());
			check(system.startsWith("自定义角色"), "自定义提示词应排在最前，实际：" + system.substring(0, 8));
			check(system.contains("自定义人物") && system.contains("自定义规则"), "应拼接人物设定与规则");
			check(!system.contains("视频导演"), "配置了自定义提示词时不应再混入内置提示词");
			check(system.contains("硬性要求"), "自定义提示词也要追加硬性要求");
		} finally {
			deleteRecursively(dir.toFile());
		}
	}

	/** 本地模型离线时给出明确失败提示，而不是抛异常。 */
	private static void testOfflineFailure() throws Exception {
		ToolData tool = H3VideoAgentMcp.createVideo(null, null, null);
		ToolResult result = tool.tool.run("{\"prompt\":\"测试\"}", null, null).get();
		check(!result.isSuccess(), "离线时应标记为失败结果");
		check(result.getText().contains("本地模型服务未连接"), "离线提示应指向本地模型服务，实际：" + result.getText());
	}

	private static void write(Path dir, String name, String content) throws Exception {
		Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
	}

	private static void deleteRecursively(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		file.delete();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
