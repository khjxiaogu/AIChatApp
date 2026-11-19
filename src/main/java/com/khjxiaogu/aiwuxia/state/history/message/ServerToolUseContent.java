package com.khjxiaogu.aiwuxia.state.history.message;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;

/**
 * DeepSeek 服务端联网工具（{@code server_tool_use}）的调用记录。
 * <p>
 * DeepSeek 的联网搜索由<b>服务端</b>执行：模型在响应里给出 {@code server_tool_use} 调用块与结果块，
 * 客户端既不需要执行工具，也不需要再发一轮请求。Chat Completions 协议把这些调用放在扩展字段
 * {@code message.server_tool_calls}/{@code delta.server_tool_calls} 里（每个元素是一步：
 * {@code {index, reasoning_content, calls:[...]}}）。
 * </p>
 * <p>
 * 本类原样保存这批调用，供两处使用：
 * </p>
 * <ul>
 *   <li><b>回传给 DeepSeek</b>：官方要求把上一轮的 {@code server_tool_calls} 原样带回，网页正文
 *       （{@code read_link} 的 {@code signature}）才能拼回上下文，见
 *       {@code HistoryRequestBuilder#createRequest(com.khjxiaogu.aiwuxia.llm.AIRequest, boolean)}。</li>
 *   <li><b>其它模型</b>：它们不认识 {@code server_tool_use}，{@link #toText()} 给出带截断的占位文本
 *       （例如「[联网搜索] 冰岛人口」），作为普通思考文本回传，既不报错也不会把原始块塞给模型。</li>
 * </ul>
 * <p>
 * 服务端联网工具由官方免费提供（搜索结果按模型 token 计费），因此本类<b>不产生任何用量</b>，
 * 不走 {@code DeepseekSearchUsage} 的按次记账。
 * </p>
 */
public class ServerToolUseContent implements MessageContent {

	/** 一次搜索最多列出几个查询词，其余折叠成“等 N 个查询”。 */
	private static final int MAX_QUERIES = 4;
	/** 单个查询词的最大展示长度。 */
	private static final int MAX_QUERY_LENGTH = 60;
	/** 链接的最大展示长度。 */
	private static final int MAX_URL_LENGTH = 120;
	/** 页内查找词的最大展示长度。 */
	private static final int MAX_PATTERN_LENGTH = 40;
	/** 占位文本最多保留几条调用，其余折叠成一行。 */
	private static final int MAX_LINES = 6;

	/**
	 * 官方返回的 {@code server_tool_calls} 原始结构，不做任何变形，回传时保持一致。
	 * <p>Gson 反序列化时可能为 null（旧数据或字段缺失）。</p>
	 */
	private JsonArray serverToolCalls;

	public ServerToolUseContent() {
		super();
	}

	/**
	 * 使用官方下发的 {@code server_tool_calls} 构造内容。
	 *
	 * @param serverToolCalls 调用步骤数组，可为 null（等价于没有调用）
	 */
	public ServerToolUseContent(JsonArray serverToolCalls) {
		super();
		this.serverToolCalls = serverToolCalls;
	}

	/**
	 * 原始调用步骤（{@code [{index, reasoning_content, calls:[...]}]}），用于原样回传给 DeepSeek。
	 *
	 * @return 调用步骤数组，永不为 null
	 */
	public JsonArray getServerToolCalls() {
		return serverToolCalls == null ? new JsonArray() : serverToolCalls;
	}

	/** 是否没有任何调用。 */
	public boolean isEmpty() {
		return getServerToolCalls().size() == 0;
	}

	/**
	 * 首条调用对应的能力简述，用于前端展示（例如「联网搜索」「深度搜索」「阅读链接」）。
	 *
	 * @return 展示用简述，无法判断时为「联网工具」
	 */
	public String getBrief() {
		for (JsonElement step : getServerToolCalls()) {
			for (JsonElement call : callsOf(step)) {
				if (!call.isJsonObject()) {
					continue;
				}
				String label = briefOf(JsonConfig.getStringOrEmpty(call.getAsJsonObject(), "kind"));
				if (!label.isEmpty()) {
					return label;
				}
			}
		}
		return "联网工具";
	}

	/**
	 * 首条调用对应的工具名（{@code deepseek_web_search} 或 {@code deepseek_read_url}）。
	 *
	 * @return 工具名；没有调用时为空串
	 */
	public String getToolName() {
		for (JsonElement step : getServerToolCalls()) {
			for (JsonElement call : callsOf(step)) {
				if (!call.isJsonObject()) {
					continue;
				}
				String type = JsonConfig.getStringOrEmpty(call.getAsJsonObject(), "type");
				if (!type.isEmpty()) {
					return type;
				}
			}
		}
		return "";
	}

	@Override
	public boolean isPlainText() {
		return false;
	}

	@Override
	public boolean isTextRepresentable() {
		return true;
	}

	@Override
	public String getType() {
		return "server_tool_use";
	}

	/**
	 * 供不认识 {@code server_tool_use} 的模型与历史检索使用的占位文本。
	 * <p>
	 * 每条调用一行，链接、查询词都做了截断与折叠，避免一次深度搜索（最多约 20 次调用）
	 * 把整段上下文撑满。
	 * </p>
	 */
	@Override
	public String toText() {
		StringBuilder text = new StringBuilder();
		int total = 0;
		int shown = 0;
		for (JsonElement step : getServerToolCalls()) {
			for (JsonElement call : callsOf(step)) {
				if (!call.isJsonObject()) {
					continue;
				}
				total++;
				if (shown >= MAX_LINES) {
					continue;
				}
				String line = describe(call.getAsJsonObject());
				if (line.isEmpty()) {
					continue;
				}
				if (text.length() > 0) {
					text.append('\n');
				}
				text.append(line);
				shown++;
			}
		}
		if (total > shown) {
			text.append('\n').append("……（其余 ").append(total - shown).append(" 次联网调用已省略）");
		}
		return text.toString();
	}

	@Override
	public MessageContent copy() {
		return this;
	}

	/** 一个步骤里的调用数组；结构不符时返回空数组。 */
	private static JsonArray callsOf(JsonElement step) {
		if (step == null || !step.isJsonObject()) {
			return new JsonArray();
		}
		JsonArray calls = JsonConfig.getArray(step.getAsJsonObject(), "calls");
		return calls == null ? new JsonArray() : calls;
	}

	/** 能力类型（{@code kind}）对应的中文简述。 */
	private static String briefOf(String kind) {
		if (kind == null) {
			return "";
		}
		switch (kind) {
		case "search":
			return "联网搜索";
		case "deep_search":
			return "深度搜索";
		case "image_search":
		case "deep_image_search":
			return "图片搜索";
		case "read_link":
			return "阅读链接";
		default:
			return "";
		}
	}

	/** 把一次调用渲染成一行占位文本。 */
	private static String describe(JsonObject call) {
		JsonObject action = JsonConfig.getObject(call, "action");
		String actionType = action == null ? "" : JsonConfig.getStringOrEmpty(action, "type");
		String kind = JsonConfig.getStringOrEmpty(call, "kind");
		StringBuilder line = new StringBuilder();
		if ("search".equals(actionType)) {
			String label = briefOf(kind);
			line.append('[').append(label.isEmpty() ? "联网搜索" : label).append(']');
			List<String> queries = JsonConfig.getStringList(action, "queries");
			if (queries.isEmpty()) {
				line.append(" 按图片搜索");
			} else {
				for (int i = 0; i < queries.size() && i < MAX_QUERIES; i++) {
					line.append(i == 0 ? " " : "；").append(clip(queries.get(i), MAX_QUERY_LENGTH));
				}
				if (queries.size() > MAX_QUERIES) {
					line.append(" 等 ").append(queries.size()).append(" 个查询");
				}
			}
		} else if ("open_page".equals(actionType)) {
			line.append("[打开网页] ").append(clip(JsonConfig.getStringOrEmpty(action, "url"), MAX_URL_LENGTH));
		} else if ("find_in_page".equals(actionType)) {
			line.append("[页内查找] ").append(clip(JsonConfig.getStringOrEmpty(action, "url"), MAX_URL_LENGTH));
			String pattern = clip(JsonConfig.getStringOrEmpty(action, "pattern"), MAX_PATTERN_LENGTH);
			if (!pattern.isEmpty()) {
				line.append(" 中的“").append(pattern).append('”');
			}
		} else if ("read_link".equals(actionType)) {
			line.append("[阅读链接] ").append(clip(JsonConfig.getStringOrEmpty(action, "url"), MAX_URL_LENGTH));
		} else {
			line.append("[联网工具] ").append(actionType.isEmpty() ? "调用" : actionType);
		}
		if ("failed".equals(JsonConfig.getStringOrEmpty(call, "status"))) {
			line.append("（未完成）");
		}
		return line.toString();
	}

	/** 去首尾空白、折叠空白并截断，用于占位文本。 */
	private static String clip(String text, int max) {
		if (text == null) {
			return "";
		}
		String trimmed = text.trim().replaceAll("\\s+", " ");
		if (trimmed.length() <= max) {
			return trimmed;
		}
		return trimmed.substring(0, max) + "…";
	}
}
