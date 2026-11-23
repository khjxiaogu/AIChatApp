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
package com.khjxiaogu.aiwuxia.mcp;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallDisplay;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.utils.TextCounter;

/**
 * 记录检索 MCP：在历史记录里按关键字搜索，并按 id 读取记录全文。
 * <p>
 * 搜索范围只包含<b>没有被删除、但已经从上下文中隐藏</b>的条目：上下文压缩/清理
 * （{@code HistoryCompactor}）会把旧对话总结进摘要后用
 * {@link HistoryHolder#setValidContext(HistoryItem, boolean)} 把它们标记为无效上下文，
 * 这些记录不再发给模型，但仍然完整保存在存储里。被撤回/删除的条目（
 * {@link HistoryItem#isDeleted()}）永远不会出现在搜索结果里。
 * </p>
 * <p>
 * 只检索 {@link Role#USER}、{@link Role#ASSISTANT}、{@link Role#TOOL} 三种角色的记录：
 * 系统提示、应用状态这类 {@link Role#SYSTEM} / {@link Role#APPLICATION} 记录既不参与搜索，
 * 也不允许通过 {@code history_get} 读取，避免把内部提示词当作对话内容取回。
 * </p>
 * <p>
 * 搜索按<b>从新到旧</b>进行，结果也按这个顺序给出：第一页永远是最新的匹配，
 * 更旧的匹配通过 {@code page} 翻页获取。
 * </p>
 * <p>
 * 提供的工具：
 * </p>
 * <ul>
 *   <li>{@code history_search}：按关键字搜索隐藏记录，返回 {@code #id}、角色、命中字段与片段，
 *       前端会把这次搜索显示为“关键词 + 页码”；</li>
 *   <li>{@code history_get}：按 id 读取记录全文，可选是否附带思维链（CoT），
 *       前端会显示这次读取的消息 id。</li>
 * </ul>
 * <p>
 * 两个工具都只读，不修改历史，也不产生用量。
 * </p>
 */
public final class HistorySearchMcp {

	/** 搜索工具名称。 */
	public static final String SEARCH_TOOL = "history_search";
	/** 读取工具名称。 */
	public static final String GET_TOOL = "history_get";

	/** 默认返回条数。 */
	public static final int DEFAULT_MAX_RESULTS = 5;
	/** 单次搜索最多返回条数。 */
	public static final int MAX_RESULTS = 50;
	/** 默认页码（从 1 开始）。 */
	public static final int DEFAULT_PAGE = 1;
	/** 片段中命中位置之前保留的字符数。 */
	private static final int SNIPPET_BEFORE = 60;
	/** 片段中命中位置之后保留的字符数。 */
	private static final int SNIPPET_AFTER = 120;

	/** 命中字段名称：正文。 */
	private static final String FIELD_DISPLAY = "正文";
	/** 命中字段名称：上下文内容。 */
	private static final String FIELD_CONTEXT = "上下文";
	/** 命中字段名称：思维链。 */
	private static final String FIELD_REASONING = "思维链";

	/** 历史容器来源；每次调用时解析，避免会话重新加载后指向旧对象。 */
	private final Supplier<HistoryHolder> historySupplier;

	private HistorySearchMcp(Supplier<HistoryHolder> historySupplier) {
		this.historySupplier = historySupplier;
	}

	/**
	 * 为一组固定的历史记录创建搜索/查询工具。
	 *
	 * @param history 历史容器
	 * @return 工具集
	 */
	public static ToolProvider forHistory(HistoryHolder history) {
		return forHistory(() -> history);
	}

	/**
	 * 为动态获取的历史容器创建搜索/查询工具。
	 *
	 * @param historySupplier 历史容器来源，可为 null（调用时返回“没有历史记录”）
	 * @return 工具集
	 */
	public static ToolProvider forHistory(Supplier<HistoryHolder> historySupplier) {
		HistorySearchMcp mcp = new HistorySearchMcp(historySupplier);
		return ToolProvider.of(mcp.searchTool(), mcp.getTool());
	}

	/**
	 * 为会话创建搜索/查询工具，工具始终读取该会话当前的历史容器。
	 *
	 * @param session 会话，可为 null
	 * @return 工具集
	 */
	public static ToolProvider forSession(AISession session) {
		if (session == null) {
			return forHistory((HistoryHolder) null);
		}
		return forHistory((Supplier<HistoryHolder>) session::getHistory);
	}

	// ==================== 工具定义 ====================

	/** 搜索工具：只搜隐藏且未删除的记录，只返回 id 与片段，按从新到旧分页。 */
	private ToolData searchTool() {
		return new ToolData.Builder(SEARCH_TOOL,
				"在上下文压缩后隐藏的历史记录里按关键字搜索，结果按从新到旧排序。"
						+ "用本工具可以找回当时的内容；仍在上下文中的记录不会被搜到。"
						+ "只返回记录 id、角色、命中字段和片段，还需要全文时用 history_get(id) 读取。"
						+ "关键字不区分大小写，多个中英文/同义词用 | 分隔。")
				.putParam("keyword", "要搜索的关键字（不区分大小写）；多个中英文/同义词用 | 分隔，如 生日|birthday。", "关键字")
				.putOptionalParam("max_results", "最多返回多少条，默认 5，最多 50。", "返回条数")
				.putOptionalParam("page", "页码，从 1 开始，默认 1；一页装不下更旧的匹配时用 page 往后翻。", "页码")
				.putOptionalParam("role", "只看某个角色：user / assistant / tool；省略为全部。", "角色")
				.presenter(HistorySearchMcp::searchDisplay)
				.toolText((params, output, manager) -> {
					JsonObject json = json(params);
					String keyword = JsonConfig.getNonBlankString(json, "keyword");
					if (keyword == null) {
						return "缺少搜索关键字 keyword";
					}
					int max = JsonConfig.getInt(json, "max_results", DEFAULT_MAX_RESULTS);
					max = Math.max(1, Math.min(MAX_RESULTS, max));
					int page = Math.max(DEFAULT_PAGE, JsonConfig.getInt(json, "page", DEFAULT_PAGE));
					String roleText = JsonConfig.getNonBlankString(json, "role");
					Role role = null;
					if (roleText != null && !"all".equalsIgnoreCase(roleText) && !"全部".equals(roleText)) {
						role = parseRole(roleText);
						if (role == null) {
							return "无法识别的角色：" + roleText
									+ "（可用：user / assistant / tool，或省略）";
						}
						if (!isSearchable(role)) {
							return "只能搜索 user / assistant / tool 的记录，不能搜索 "
									+ role.getRoleName() + " 的记录";
						}
					}
					try {
						return search(keyword, max, role, page);
					} catch (Throwable t) {
						return "搜索失败：" + describe(t);
					}
				})
				.build();
	}

	/**
	 * 搜索工具的前端显示：关键词 + 页码。
	 * <p>
	 * 只读调用参数，不关心搜索结果；页码缺省时按 {@value #DEFAULT_PAGE} 显示，
	 * 与工具实际取值的归一化规则一致。
	 * </p>
	 *
	 * @param call 原始工具调用
	 * @return 用户友好显示
	 */
	private static ToolCallDisplay searchDisplay(ToolCall call) {
		String keyword = ToolCallPresenter.argument(call, "keyword");
		String page = ToolCallPresenter.argument(call, "page");
		StringBuilder text = new StringBuilder();
		if (keyword != null && !keyword.trim().isEmpty()) {
			text.append("关键词：").append(keyword.trim()).append('\n');
		}
		text.append("页码：").append(page == null || page.trim().isEmpty() ? DEFAULT_PAGE : page.trim());
		return ToolCallDisplay.of("搜索历史记录", new MutableMessageContents(text.toString()));
	}

	/** 读取工具：按 id 返回记录全文，可选是否附带思维链。 */
	private ToolData getTool() {
		return new ToolData.Builder(GET_TOOL,
				"按记录 id 查询历史记录的全文（正文与上下文内容；默认不含思维链）。可以读取已从上下文隐藏的记录。id 来自 history_search 返回的 #数字。")
				.putParam("id", "记录 id，例如 history_search 返回的 #128 里填 128。", "记录ID")
				.putOptionalParam("include_reasoning", "是否附带思维链（CoT），默认 false。", "含思维链")
				.putOptionalParam("max_chars", "最多返回多少字符，默认不限制；超过会截断并提示。", "字符上限")
				.presenter(HistorySearchMcp::getDisplay)
				.toolText((params, output, manager) -> {
					JsonObject json = json(params);
					String idText = JsonConfig.getNonBlankString(json, "id");
					if (idText == null) {
						return "缺少记录 id";
					}
					boolean includeReasoning = JsonConfig.getBool(json, "include_reasoning", false);
					int maxChars = Math.max(0, JsonConfig.getInt(json, "max_chars", 0));
					try {
						return get(idText, includeReasoning, maxChars);
					} catch (Throwable t) {
						return "查询失败：" + describe(t);
					}
				})
				.build();
	}

	/**
	 * 读取工具的前端显示：消息 id（记录 id）。
	 * <p>
	 * 显示的是模型请求读取的那条记录 id，与 {@code history_get} 返回的
	 * {@code 记录 #id} 一致；缺少 id 时只显示简述。
	 * </p>
	 *
	 * @param call 原始工具调用
	 * @return 用户友好显示
	 */
	private static ToolCallDisplay getDisplay(ToolCall call) {
		String id = ToolCallPresenter.argument(call, "id");
		if (id == null || id.trim().isEmpty()) {
			return ToolCallDisplay.of("读取历史记录");
		}
		return ToolCallDisplay.of("读取历史记录", new MutableMessageContents("消息ID：" + id.trim()));
	}

	// ==================== 搜索 ====================

	/**
	 * 在隐藏且未删除的记录里按<b>从新到旧</b>搜索，并只返回指定页的匹配。
	 * <p>
	 * 迭代器本身就是降序（{@link HistoryHolder#hiddenContextReverseIterator()}），因此先跳过
	 * 前 {@code (page - 1) * maxResults} 条匹配，再收集本页的 {@code maxResults} 条：
	 * 第一页永远是最新的一批匹配，翻页只会往更旧的记录走。
	 * </p>
	 * <p>
	 * 只有<b>确实又找到一条匹配</b>时才提示“还有更多”：本页装满后搜索会继续往后扫，
	 * 扫到下一条匹配就停下并标记还有更多，扫到末尾都没有就标记没有更多，
	 * 避免模型为了一页不存在的旧记录来回翻页。
	 * </p>
	 *
	 * @param keyword    关键字（已确认非空）
	 * @param maxResults 每页最多返回条数（已归一化到 1..{@link #MAX_RESULTS}）
	 * @param role       角色过滤，null 表示 user/assistant/tool 全部
	 * @param page       页码（从 1 开始，已归一化为不小于 1）
	 * @return 面向模型的搜索结果文本
	 */
	private String search(String keyword, int maxResults, Role role, int page) {
		HistoryHolder history = resolveHistory();
		if (history == null) {
			return "当前会话没有可搜索的历史记录";
		}
		String[] needles = terms(keyword);
		if (needles.length == 0) {
			return "缺少有效的搜索关键字";
		}
		int skip = (page - 1) * maxResults;
		List<Hit> hits = new ArrayList<>();
		int scanned = 0;
		int matched = 0;
		boolean hasMore = false;
		Iterator<HistoryItem> it = history.hiddenContextReverseIterator();
		while (it.hasNext()) {
			HistoryItem item = it.next();
			// 迭代器已经过滤，这里再防御一次，避免实现覆盖后语义跑偏
			if (item == null || item.isDeleted() || item.isValidContext()) {
				continue;
			}
			// 只检索 user / assistant / tool，系统提示、应用状态等记录一律不参与
			if (!isSearchable(item.getRole())) {
				continue;
			}
			scanned++;
			if (role != null && item.getRole() != role) {
				continue;
			}
			Hit hit = match(item, needles);
			if (hit == null) {
				continue;
			}
			matched++;
			if (matched <= skip) {
				continue;
			}
			if (hits.size() < maxResults) {
				hits.add(hit);
				continue;
			}
			// 本页已经装满：继续往后找，真的找到下一条匹配才敢说“还有更多”；
			// 后面都没有匹配时 hasMore 保持 false，不会让模型白翻一页。
			hasMore = true;
			break;
		}
		if (hits.isEmpty()) {
			StringBuilder text = new StringBuilder("没有在「已从上下文隐藏」的记录里找到「")
					.append(keyword).append("」。");
			if (matched > 0) {
				text.append("（第 ").append(page).append(" 页没有更多匹配，一共找到 ")
						.append(matched).append(" 条）");
			} else if (scanned > 0) {
				text.append("（第 ").append(page).append(" 页；已检查 ").append(scanned).append(" 条隐藏记录；")
						.append("仍在上下文中的记录不参与搜索）");
			} else {
				text.append("当前没有可搜索的旧记录：历史可能还没有压缩过。");
			}
			return text.toString();
		}
		StringBuilder text = new StringBuilder("在已从上下文隐藏的记录里找到 ")
				.append(hits.size()).append(" 条匹配（第 ").append(page).append(" 页");
		if (hasMore) {
			text.append("，达到每页上限 ").append(maxResults)
					.append("，更旧的匹配还有更多，翻页用 page=").append(page + 1);
		}
		text.append("）：\n");
		for (Hit hit : hits) {
			text.append('#').append(hit.item.getIdentifier()).append('｜')
					.append(roleName(hit.item.getRole())).append("｜匹配：").append(hit.field).append('\n');
			text.append("    ").append(hit.snippet).append('\n');
		}
		text.append("用 history_get(id=...) 读取全文；默认不含思维链，需要时加 include_reasoning=true。");
		return text.toString();
	}

	/**
	 * 判断一条记录是否命中关键字，并给出命中字段与片段。
	 * <p>按正文 → 上下文 → 思维链的顺序取第一个命中，避免同一条记录重复出现。</p>
	 *
	 * @param item    记录
	 * @param needles 小写关键字
	 * @return 命中信息，没有命中时返回 null
	 */
	private static Hit match(HistoryItem item, String[] needles) {
		String display = render(item.getDisplayContent());
		String snippet = find(display, needles);
		if (snippet != null) {
			return new Hit(item, FIELD_DISPLAY, snippet);
		}
		String context = render(item.getContextContent());
		if (!context.isEmpty() && !context.equals(display)) {
			snippet = find(context, needles);
			if (snippet != null) {
				return new Hit(item, FIELD_CONTEXT, snippet);
			}
		}
		String reasoning = render(item.getReasoningContent());
		if (!reasoning.isEmpty()) {
			snippet = find(reasoning, needles);
			if (snippet != null) {
				return new Hit(item, FIELD_REASONING, snippet);
			}
		}
		return null;
	}

	/**
	 * 在文本里找最早的命中并截出片段。
	 *
	 * @param text    原文
	 * @param needles 小写关键字
	 * @return 片段，没有命中时返回 null
	 */
	private static String find(String text, String[] needles) {
		if (text == null || text.isEmpty()) {
			return null;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		int index = -1;
		int matchedLength = 0;
		for (String needle : needles) {
			int found = lower.indexOf(needle);
			if (found >= 0 && (index < 0 || found < index)) {
				index = found;
				matchedLength = needle.length();
			}
		}
		if (index < 0) {
			return null;
		}
		// 极少数语言大小写转换会改变长度：截取位置按原文长度夹紧即可
		int start = Math.max(0, Math.min(text.length(), index - SNIPPET_BEFORE));
		int end = Math.min(text.length(), start + SNIPPET_BEFORE + matchedLength + SNIPPET_AFTER);
		String snippet = text.substring(start, end).replaceAll("\\s+", " ").trim();
		return (start > 0 ? "…" : "") + snippet + (end < text.length() ? "…" : "");
	}

	// ==================== 查询 ====================

	/**
	 * 按 id 查询记录全文。
	 *
	 * @param idText            id 文本（数字）
	 * @param includeReasoning  是否附带思维链
	 * @param maxChars          字符上限，0 表示不限制
	 * @return 面向模型的记录全文
	 */
	private String get(String idText, boolean includeReasoning, int maxChars) {
		int id;
		try {
			id = Integer.parseInt(idText.trim());
		} catch (NumberFormatException ex) {
			return "记录 id 必须是整数：" + idText;
		}
		HistoryHolder history = resolveHistory();
		if (history == null) {
			return "当前会话没有历史记录";
		}
		HistoryItem item = history.getById(id);
		if (item == null) {
			return "没有找到记录 #" + id + "（不存在，或不属于当前会话）";
		}
		if (item.isDeleted()) {
			return "没有找到记录 #" + id + "（不存在或不可读取）";
		}
		// 与搜索一致：只允许读取 user / assistant / tool，避免系统提示、应用状态被当对话取回
		Role itemRole = item.getRole();
		if (!isSearchable(itemRole)) {
			return "记录 #" + id + " 的角色是 " + (itemRole == null ? "未知" : itemRole.getRoleName())
					+ "，只允许读取 user / assistant / tool 的记录";
		}
		String display = render(item.getDisplayContent());
		String context = render(item.getContextContent());
		String reasoning = render(item.getReasoningContent());

		StringBuilder text = new StringBuilder();
		text.append("记录 #").append(id)
				.append("｜角色：").append(roleName(item.getRole()))
				.append("｜状态：").append(item.isValidContext() ? "在上下文中" : "已从上下文隐藏")
				.append('\n');
		text.append("── 正文 ──\n");
		text.append(display.isEmpty() ? "（无文本）" : display.trim()).append('\n');
		if (!context.isEmpty() && !context.equals(display)) {
			text.append("── 上下文内容 ──\n").append(context.trim()).append('\n');
		}
		if (includeReasoning) {
			text.append("── 思维链 ──\n");
			text.append(reasoning.isEmpty() ? "（无）" : reasoning.trim()).append('\n');
		} else if (!reasoning.isEmpty()) {
			text.append("（该记录有思维链 ").append(TextCounter.countWords(reasoning))
					.append(" 字；需要时传 include_reasoning=true 一并返回）\n");
		}
		String result = text.toString().trim();
		int originalLength = result.length();
		if (maxChars > 0 && originalLength > maxChars) {
			result = result.substring(0, maxChars) + "\n…（已截断，原长 " + originalLength + " 字符）";
		}
		return result;
	}

	// ==================== 内容渲染 ====================

	/**
	 * 把内容集合渲染成完整文本。
	 * <p>
	 * 纯文本、图片/音频/视频的描述以及工具结果走各自的 {@link MessageContent#toText()}；
	 * 工具调用自身的文本表示是空串，这里补上“工具名 + 原始参数”，否则记录里的工具调用会整段丢失。
	 * </p>
	 *
	 * @param contents 内容集合，可为 null
	 * @return 渲染结果，永不为 null
	 */
	private static String render(MessageContents contents) {
		if (contents == null) {
			return "";
		}
		StringBuilder text = new StringBuilder();
		for (MessageContent content : contents) {
			if (content == null) {
				continue;
			}
			if (content instanceof ToolCallContent) {
				for (ToolCall call : ((ToolCallContent) content).getToolCalls()) {
					if (call == null || call.function == null) {
						continue;
					}
					text.append("[调用工具] ").append(call.function.name);
					String arguments = call.function.arguments;
					if (arguments != null && !arguments.trim().isEmpty()) {
						text.append(' ').append(arguments.trim());
					}
					text.append('\n');
				}
			} else {
				String part = content.toText();
				if (part != null && !part.isEmpty()) {
					text.append(part);
				}
			}
		}
		return text.toString();
	}

	/** 角色显示名，同时给出机器名便于模型判断。 */
	private static String roleName(Role role) {
		if (role == null) {
			return "未知";
		}
		String label;
		switch (role) {
			case USER:
				label = "用户";
				break;
			case ASSISTANT:
				label = "助手";
				break;
			case SYSTEM:
				label = "系统";
				break;
			case APPLICATION:
				label = "应用";
				break;
			case TOOL:
				label = "工具";
				break;
			default:
				label = role.name();
				break;
		}
		return label + "(" + role.getRoleName() + ")";
	}

	// ==================== 工具参数 ====================

	/** 解析参数文本；空串或非对象时返回空对象。 */
	private static JsonObject json(String params) {
		if (params == null || params.trim().isEmpty()) {
			return new JsonObject();
		}
		try {
			JsonElement element = JsonParser.parseString(params);
			return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
		} catch (RuntimeException ex) {
			return new JsonObject();
		}
	}

	/** 解析角色名（大小写不敏感）。 */
	private static Role parseRole(String text) {
		if (text == null) {
			return null;
		}
		for (Role role : Role.values()) {
			if (role.name().equalsIgnoreCase(text.trim())) {
				return role;
			}
		}
		return null;
	}

	/**
	 * 该角色的记录是否允许被检索/读取。
	 * <p>
	 * 只放行 {@link Role#USER}、{@link Role#ASSISTANT}、{@link Role#TOOL} 三种对话记录：
	 * {@link Role#SYSTEM}（系统提示）与 {@link Role#APPLICATION}（应用状态）属于内部数据，
	 * 既不出现在搜索结果里，也不能用 {@code history_get} 按 id 取回。
	 * </p>
	 *
	 * @param role 记录角色，可为 null
	 * @return 允许检索时返回 true
	 */
	private static boolean isSearchable(Role role) {
		return role == Role.USER || role == Role.ASSISTANT || role == Role.TOOL;
	}

	/** 多语言/同义词搜索：用 | 分隔，如 {@code 生日|birthday}。 */
	private static String[] terms(String keyword) {
		if (keyword == null) {
			return new String[0];
		}
		Set<String> terms = new LinkedHashSet<>();
		for (String part : keyword.split("\\s*\\|\\s*")) {
			String term = part.trim().toLowerCase(Locale.ROOT);
			if (!term.isEmpty()) {
				terms.add(term);
			}
		}
		return terms.toArray(new String[0]);
	}

	/** 取历史容器；来源缺失或抛异常时返回 null。 */
	private HistoryHolder resolveHistory() {
		Supplier<HistoryHolder> supplier = historySupplier;
		if (supplier == null) {
			return null;
		}
		try {
			return supplier.get();
		} catch (Throwable t) {
			return null;
		}
	}

	/** 取最内层异常信息。 */
	private static String describe(Throwable t) {
		Throwable cause = t;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
	}

	/** 一条搜索命中。 */
	private static final class Hit {
		/** 命中的记录。 */
		final HistoryItem item;
		/** 命中字段（正文/上下文/思维链）。 */
		final String field;
		/** 命中片段。 */
		final String snippet;

		Hit(HistoryItem item, String field, String snippet) {
			this.item = item;
			this.field = field;
			this.snippet = snippet;
		}
	}
}
