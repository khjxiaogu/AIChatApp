package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;

import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.MemoryHistory;
import com.khjxiaogu.aiwuxia.state.history.SqliteHistory;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;

/**
 * {@link HistorySearchMcp} 的自检：只搜“未删除但从上下文隐藏”的记录，按 id 读全文，可选思维链。
 * <p>
 * 不依赖 JUnit，直接用 {@code java} 运行本类的 {@link #main(String[])}。
 * </p>
 */
public class HistorySearchMcpTest {

	/** 运行全部用例。 */
	public static void main(String[] args) throws Exception {
		testSearchOnlyHidden();
		testSearchNothing();
		testRoleFilter();
		testGetFullText();
		testGetDeleted();
		testSqliteHistory();
		System.out.println("HistorySearchMcpTest: 全部用例通过");
	}

	/** 只有未删除且隐藏的记录能被搜到，可见与已删除记录都不出现。 */
	private static void testSearchOnlyHidden() {
		Fixture fixture = new Fixture();
		String result = fixture.call(HistorySearchMcp.SEARCH_TOOL, "{\"keyword\":\"暗号\"}");
		check(result.contains("#2"), "应搜到隐藏记录 #2，实际：" + result);
		check(!result.contains("#1"), "不应该搜到仍在上下文里的 #1，实际：" + result);
		check(!result.contains("#3"), "不应该搜到已删除的 #3，实际：" + result);
		check(result.contains("assistant"), "结果应带角色，实际：" + result);
	}

	/** 找不到时给出明确提示，而不是空串。 */
	private static void testSearchNothing() {
		Fixture fixture = new Fixture();
		String result = fixture.call(HistorySearchMcp.SEARCH_TOOL, "{\"keyword\":\"当前这段不该出现\"}");
		check(result.startsWith("没有在"), "应提示没有匹配，实际：" + result);
	}

	/** role 过滤只影响搜索，且非法角色会报错。 */
	private static void testRoleFilter() {
		Fixture fixture = new Fixture();
		String assistant = fixture.call(HistorySearchMcp.SEARCH_TOOL,
				"{\"keyword\":\"暗号\",\"role\":\"assistant\"}");
		check(assistant.contains("#2"), "role=assistant 应命中 #2，实际：" + assistant);
		String user = fixture.call(HistorySearchMcp.SEARCH_TOOL, "{\"keyword\":\"暗号\",\"role\":\"user\"}");
		check(user.startsWith("没有在"), "role=user 不应命中 #2，实际：" + user);
		String bad = fixture.call(HistorySearchMcp.SEARCH_TOOL, "{\"keyword\":\"暗号\",\"role\":\"nobody\"}");
		check(bad.contains("无法识别的角色"), "非法角色应报错，实际：" + bad);
	}

	/** 按 id 读取全文：默认不含思维链，include_reasoning=true 时附带。 */
	private static void testGetFullText() {
		Fixture fixture = new Fixture();
		String without = fixture.call(HistorySearchMcp.GET_TOOL, "{\"id\":\"2\"}");
		check(without.contains("隐藏的暗号是 12345"), "应返回正文全文，实际：" + without);
		check(without.contains("已从上下文隐藏"), "应标明记录状态，实际：" + without);
		check(!without.contains("我在想暗号"), "默认不应包含思维链，实际：" + without);
		check(without.contains("include_reasoning=true"), "应提示可以附带思维链，实际：" + without);

		String with = fixture.call(HistorySearchMcp.GET_TOOL,
				"{\"id\":\"2\",\"include_reasoning\":\"true\"}");
		check(with.contains("我在想暗号"), "include_reasoning=true 应包含思维链，实际：" + with);
		check(with.contains("── 思维链 ──"), "应带思维链小节，实际：" + with);

		String truncated = fixture.call(HistorySearchMcp.GET_TOOL, "{\"id\":\"2\",\"max_chars\":\"10\"}");
		check(truncated.contains("已截断"), "超过 max_chars 应提示截断，实际：" + truncated);
	}

	/** 已删除或不存在的 id 都不能读出内容。 */
	private static void testGetDeleted() {
		Fixture fixture = new Fixture();
		String deleted = fixture.call(HistorySearchMcp.GET_TOOL, "{\"id\":\"3\"}");
		check(deleted.contains("没有找到记录"), "已删除记录应拒绝查询，实际：" + deleted);
		String missing = fixture.call(HistorySearchMcp.GET_TOOL, "{\"id\":\"999\"}");
		check(missing.contains("没有找到记录"), "不存在的 id 应给出提示，实际：" + missing);
		String bad = fixture.call(HistorySearchMcp.GET_TOOL, "{\"id\":\"abc\"}");
		check(bad.contains("必须是整数"), "非数字 id 应报错，实际：" + bad);
	}

	/** SQLite 实现走 should_send/deleted 查询，重新打开后依然能搜到隐藏记录并读出思维链。 */
	private static void testSqliteHistory() throws Exception {
		File dir = new File("target/verify-tmp/history-search-" + System.nanoTime());
		dir.mkdirs();
		try {
			SqliteHistory history = new SqliteHistory(dir);
			try {
				history.add(Role.USER, new MutableMessageContents("当前上下文里的 SQLITE-000"), null, null, true);
				history.add(Role.ASSISTANT, new MutableMessageContents("藏起来的 SQLITE-777"),
						new MutableMessageContents("上下文里的 SQLITE-777"),
						new MutableMessageContents("思维链里的 SQLITE-777"), false);
				HistoryItem deleted = history.add(Role.USER, new MutableMessageContents("已删除的 SQLITE-777"),
						null, null, false);
				history.setDeleted(deleted, true);
			} finally {
				history.close();
			}

			SqliteHistory reopened = new SqliteHistory(dir);
			try {
				ToolProvider provider = HistorySearchMcp.forHistory(reopened);
				String search = call(provider, HistorySearchMcp.SEARCH_TOOL, "{\"keyword\":\"sqlite-777\"}");
				check(search.contains("#2"), "SQLite 搜索应命中 #2，实际：" + search);
				check(!search.contains("#1"), "SQLite 搜索不应命中仍可见的 #1，实际：" + search);
				check(!search.contains("#3"), "SQLite 搜索不应命中已删除的 #3，实际：" + search);

				String withReasoning = call(provider, HistorySearchMcp.GET_TOOL,
						"{\"id\":\"2\",\"include_reasoning\":true}");
				check(withReasoning.contains("藏起来的 SQLITE-777"), "应读出正文，实际：" + withReasoning);
				check(withReasoning.contains("思维链里的 SQLITE-777"), "应读出思维链，实际：" + withReasoning);
				check(withReasoning.contains("已从上下文隐藏"), "应标明隐藏状态，实际：" + withReasoning);
			} finally {
				reopened.close();
			}
		} finally {
			deleteRecursively(dir);
		}
	}

	/** 递归删除测试目录。 */
	private static void deleteRecursively(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				deleteRecursively(child);
			}
		}
		file.delete();
	}

	/** 调用指定工具并返回文本结果。 */
	private static String call(ToolProvider provider, String toolName, String arguments) {
		for (ToolData tool : provider.getTools()) {
			if (toolName.equals(tool.name)) {
				return tool.tool.run(arguments, null, null).join().getText();
			}
		}
		throw new AssertionError("没有注册工具：" + toolName);
	}

	/** 构造历史并调用工具的测试夹具。 */
	private static final class Fixture {
		final MemoryHistory history = new MemoryHistory();
		final ToolProvider provider;

		Fixture() {
			// #1：仍在上下文中，不能被搜到
			history.add(Role.USER, new MutableMessageContents("当前这段内容还在上下文里"), null, null, true);
			// #2：已从上下文隐藏但未删除，带思维链
			HistoryItem hidden = history.add(Role.ASSISTANT,
					new MutableMessageContents("隐藏的暗号是 12345"),
					new MutableMessageContents("上下文里的隐藏的暗号是 12345"),
					new MutableMessageContents("我在想暗号 12345 对不对"), false);
			check(!hidden.isValidContext(), "隐藏记录应标记为无效上下文");
			// #3：已删除，即使隐藏也不能被搜到/读出
			HistoryItem deleted = history.add(Role.USER,
					new MutableMessageContents("已删除的秘密 67890"), null, null, false);
			history.setDeleted(deleted, true);
			provider = HistorySearchMcp.forHistory(history);
		}

		String call(String toolName, String arguments) {
			return HistorySearchMcpTest.call(provider, toolName, arguments);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
