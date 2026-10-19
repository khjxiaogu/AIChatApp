package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.providers.deepseek.DeepseekSearchUsage;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.MCPTools;
import com.khjxiaogu.webserver.loging.SimpleLogger;

/**
 * DeepSeek 网页搜索（Search API）MCP 模块。
 * <p>
 * 按官方文档 {@code POST https://api.deepseek.com/v1/tools/search} 实现，鉴权与对话接口相同
 * （启动参数 {@code -Ddeepseektoken=...}）。向模型提供 {@code web_search} 工具，
 * 返回标题、链接、发布时间、权威度与长摘要。
 * </p>
 * <p>
 * <b>计费</b>：Search 接口按成功请求次数计费（每 1000 次 5 元），与返回条数无关；只有 HTTP 200
 * 且 {@code results} 非空的请求才会计费。因此这里只在拿到“非空结果”后写入一次
 * {@link DeepseekSearchUsage}，失败、超时、空结果一律不记账，保证按官方口径准确计费。
 * </p>
 */
public class DeepseekSearchMcp {
	/** Search 接口地址，与对话接口共用同一个域与 Bearer 鉴权。 */
	private static final String ENDPOINT = "https://api.deepseek.com/v1/tools/search";
	/** 官方 count 上限为 50，这里收紧到 20：每次请求价格固定，条数越多只会越费下游上下文。 */
	private static final int MAX_COUNT = 20;
	/** 默认返回条数（官方默认 10）。 */
	private static final int DEFAULT_COUNT = 10;
	/** query 的最大字符数（官方限制 128）。 */
	private static final int MAX_QUERY_LENGTH = 128;
	/** 连接超时（毫秒）。 */
	private static final int CONNECT_TIMEOUT = 10000;
	/** 读取超时（毫秒）：搜索要实时抓取并生成摘要，比普通接口慢。 */
	private static final int READ_TIMEOUT = 45000;
	/** 最大尝试次数（首次 + 2 次重试），按官方建议只对 408/429/5xx 重试。 */
	private static final int MAX_ATTEMPTS = 3;
	/** 重试等待基数（毫秒），第 n 次重试等待 n * 该值。 */
	private static final long RETRY_BACKOFF = 800L;
	/** 错误正文在提示里的最大长度，避免把整段响应塞给模型。 */
	private static final int MAX_ERROR_TEXT = 400;

	private static final SimpleLogger logger = new SimpleLogger("DeepseekSearch");

	/**
	 * 创建本模块的工具集合。
	 * <p>
	 * 未配置 {@code deepseektoken} 时返回空集合：接口必然 401，不注册工具可以避免模型白调、
	 * 也避免把错误提示喂进上下文。
	 * </p>
	 *
	 * @param state 当前会话（用于写入用量，可为 null，为 null 时只提供结果不计账）
	 * @return 工具集合，可能为空
	 */
	public static MCPTools create(AISession state) {
		MCPTools tools = new MCPTools();
		if (token().isEmpty()) {
			return tools;
		}
		tools.register(new ToolData.Builder("web_search",
				"用 DeepSeek 搜索引擎搜索网页，返回标题、链接、发布时间、权威度与摘要。"
						+ "需要最新信息、新闻、事实核查或资料查找时使用；常识与闲聊不要调用。"
						+ "query 支持 site:/ -site:/ -关键词/ \"精确短语\"/ lang:/ after:/ before: 等语法；")
				.putParam("query", "搜索词，1～128 个字符，不能全为空白，支持 site:/lang:/after:/before:/\"精确短语\" 等语法")
				.putParam("count", "返回结果条数，1～20，不确定时填 10")
				.presenter(ToolCallPresenter.ofArgument("搜索网页", "query"))
				.tool(params -> runTool(state, params))
				.build());
		return tools;
	}

	/**
	 * 执行一次网页搜索，结果已格式化为便于模型阅读的文本。
	 * <p>
	 * 计费只发生在“HTTP 200 且 results 非空”的成功请求上，每次记一次
	 * {@link DeepseekSearchUsage}（折算 2500 等效 token，即官方 0.005 元）。
	 * </p>
	 *
	 * @param state 当前会话，可为 null；为 null 时只返回结果、不记账
	 * @param query 搜索词
	 * @param count 期望条数（会被限制在 1～20）
	 * @return 给模型看的结果文本，失败时为中文错误说明
	 */
	public static String search(AISession state, String query, int count) {
		return searchInternal(state == null ? null : state::addUsage, query, count);
	}

	/**
	 * 执行一次网页搜索，并把计费成功的请求交给 {@code addUsage} 记账。
	 *
	 * @param addUsage 用量接收方，可为 null（不计账）
	 * @param query    搜索词
	 * @param count    期望条数（会被限制在 1～20）
	 * @return 给模型看的结果文本，失败时为中文错误说明
	 */
	public static String search(Consumer<UsageIntf<?>> addUsage, String query, int count) {
		return searchInternal(addUsage, query, count);
	}

	private static String runTool(AISession state, String params) {
		String query;
		int count;
		try {
			JsonElement parsed = JsonParser.parseString(params);
			if (!parsed.isJsonObject()) {
				return "参数格式错误：需要一个包含 query 的 JSON 对象。";
			}
			JsonObject args = parsed.getAsJsonObject();
			query = str(args, "query");
			count = parseCount(str(args, "count"));
		} catch (RuntimeException e) {
			return "参数格式错误：请按 {\"query\":\"...\",\"count\":\"10\"} 的格式提供参数。";
		}
		if (query == null || query.trim().isEmpty()) {
			return "参数格式错误：query 不能为空。";
		}
		query = query.trim();
		if (query.length() > MAX_QUERY_LENGTH) {
			return "参数格式错误：query 最长 " + MAX_QUERY_LENGTH + " 个字符，当前 " + query.length() + " 个，请缩短后重试。";
		}
		return search(state, query, count);
	}

	private static String searchInternal(Consumer<UsageIntf<?>> addUsage, String query, int count) {
		count = Math.max(1, Math.min(MAX_COUNT, count));
		JsonObject body = new JsonObject();
		body.addProperty("query", query);
		body.addProperty("count", count);
		// need_content 保持官方默认 false：正文动辄上万字，摘要已足够模型作答，
		// 需要细节时模型可以再用 web_view 打开单条链接。
		body.addProperty("need_content", false);
		String payload = body.toString();

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				JsonObject response = post(payload);
				// 正常响应里不会带 error；个别代理会在 200 里塞错误对象，这里统一按失败处理
				if (response.has("error")) {
					throw new SearchHttpException(0, response.toString());
				}
				JsonArray results = resultsOf(response);
				if (results.size() == 0) {
					// 官方口径：搜索结果为空不计费，这里也不记账
					return "没有找到与「" + query + "」相关的网页（本次搜索不计费）。";
				}
				if (addUsage != null) {
					addUsage.accept(new DeepseekSearchUsage(1));
				}
				return format(query, results);
			} catch (SearchHttpException e) {
				if (e.isRetryable() && attempt < MAX_ATTEMPTS) {
					logger.severe(e.getLogMessage() + "；第 " + attempt + " 次尝试失败，稍后重试。");
					sleep(RETRY_BACKOFF * attempt);
					if (Thread.currentThread().isInterrupted()) {
						return "搜索已中断。";
					}
					continue;
				}
				logger.severe(e.getLogMessage());
				return e.getUserMessage();
			} catch (IOException | JsonParseException e) {
				if (attempt < MAX_ATTEMPTS) {
					logger.severe("搜索请求异常：" + e.getMessage() + "；第 " + attempt + " 次尝试失败，稍后重试。");
					sleep(RETRY_BACKOFF * attempt);
					if (Thread.currentThread().isInterrupted()) {
						return "搜索已中断。";
					}
					continue;
				}
				logger.printStackTrace(e);
				return "搜索失败：无法连接 DeepSeek 搜索服务，请稍后重试。";
			}
		}
		return "搜索失败：请稍后重试。";
	}

	/**
	 * 发送一次搜索请求。
	 *
	 * @param payload 请求体 JSON
	 * @return 解析后的响应对象
	 * @throws SearchHttpException 服务端返回非 200
	 * @throws IOException        网络异常
	 */
	private static JsonObject post(String payload) throws IOException {
		HttpURLConnection connection = null;
		try {
			URL url = URI.create(ENDPOINT).toURL();
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(CONNECT_TIMEOUT);
			connection.setReadTimeout(READ_TIMEOUT);
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json");
			connection.setRequestProperty("Accept", "application/json");
			connection.setRequestProperty("Authorization", "Bearer " + token());
			try (OutputStream out = connection.getOutputStream()) {
				out.write(payload.getBytes(StandardCharsets.UTF_8));
			}
			int status = connection.getResponseCode();
			InputStream stream = status >= 200 && status < 300 ? connection.getInputStream()
					: connection.getErrorStream();
			String text;
			try (InputStream in = stream) {
				text = in == null ? "" : FileUtil.readString(in);
			}
			if (status != HttpURLConnection.HTTP_OK) {
				throw new SearchHttpException(status, text);
			}
			return JsonParser.parseString(text).getAsJsonObject();
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	private static JsonArray resultsOf(JsonObject response) {
		if (response == null) {
			return new JsonArray();
		}
		JsonElement results = response.get("results");
		return results != null && results.isJsonArray() ? results.getAsJsonArray() : new JsonArray();
	}

	/**
	 * 把结果格式化为模型易读的文本：标题、链接、时间/站点/权威度、摘要。
	 */
	private static String format(String query, JsonArray results) {
		StringBuilder sb = new StringBuilder();
		sb.append("搜索「").append(query).append("」共 ").append(results.size()).append(" 条结果：\n");
		int index = 0;
		for (JsonElement element : results) {
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			JsonObject result = element.getAsJsonObject();
			index++;
			String title = text(result, "title");
			sb.append('\n').append(index).append(". ").append(title.isEmpty() ? "(无标题)" : title).append('\n');
			String url = text(result, "url");
			if (!url.isEmpty()) {
				sb.append("   链接：").append(url).append('\n');
			}
			String meta = meta(result);
			if (!meta.isEmpty()) {
				sb.append("   信息：").append(meta).append('\n');
			}
			String summary = text(result, "summary");
			if (summary.isEmpty()) {
				summary = text(result, "snippet");
			}
			if (!summary.isEmpty()) {
				sb.append("   摘要：").append(summary).append('\n');
			}
		}
		sb.append("\n以上为搜索摘要，需要细节可打开对应链接。");
		return sb.toString();
	}

	/** 拼装单条结果的辅助信息：发布时间 · 站点 · 相关度 · 权威度。 */
	private static String meta(JsonObject result) {
		List<String> parts = new ArrayList<>();
		String date = text(result, "date");
		if (!date.isEmpty()) {
			parts.add(date.length() >= 10 ? date.substring(0, 10) : date);
		}
		String site = text(result, "site_name");
		if (!site.isEmpty()) {
			parts.add(site);
		}
		JsonElement score = result.get("rerank_score");
		if (score != null && score.isJsonPrimitive()) {
			parts.add("相关度 " + decimal(score.getAsDouble()));
		}
		JsonElement authority = result.get("authority");
		if (authority != null && authority.isJsonPrimitive() && authority.getAsInt() >= 0) {
			parts.add("权威度 " + authority.getAsInt());
		}
		return String.join(" · ", parts);
	}

	/** 结果里的字符串字段，缺失时返回空串。 */
	private static String text(JsonObject obj, String key) {
		String value = str(obj, key);
		return value == null ? "" : value.trim();
	}

	/** 取 JSON 字符串字段（只接受原始类型），缺失或类型不符返回 null。 */
	private static String str(JsonObject obj, String key) {
		if (obj == null || !obj.has(key)) {
			return null;
		}
		JsonElement element = obj.get(key);
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
			return null;
		}
		return element.getAsString();
	}

	/** 解析模型给出的 count：缺失、非法或非数字时取默认值，越界时截断。 */
	private static int parseCount(String raw) {
		if (raw == null) {
			return DEFAULT_COUNT;
		}
		try {
			return Math.max(1, Math.min(MAX_COUNT, Integer.parseInt(raw.trim())));
		} catch (NumberFormatException e) {
			return DEFAULT_COUNT;
		}
	}

	/** 数字格式化，固定用英文小数点，避免中文环境下出现逗号分隔。 */
	private static String decimal(double value) {
		DecimalFormat format = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
		return format.format(value);
	}

	/** 当前配置的 DeepSeek API Key，未配置时为空串。 */
	private static String token() {
		String token = System.getProperty("deepseektoken");
		return token == null ? "" : token.trim();
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 搜索接口返回非 200 时的异常，携带状态码与原始响应体。 */
	private static final class SearchHttpException extends IOException {
		private static final long serialVersionUID = 1L;
		private final int status;
		private final String body;

		SearchHttpException(int status, String body) {
			super("HTTP " + status);
			this.status = status;
			this.body = body;
		}

		/** 是否属于官方建议重试的错误（408 / 429 / 5xx）。 */
		boolean isRetryable() {
			return status == 408 || status == 429 || status >= 500;
		}

		String getLogMessage() {
			return "搜索接口调用失败（HTTP " + status + "）：" + getServerMessage();
		}

		/** 服务端返回的错误信息，取不到时退回截断后的响应正文。 */
		String getServerMessage() {
			JsonObject error = errorObject(body);
			if (error != null) {
				String message = str(error, "message");
				String type = str(error, "type");
				String code = str(error, "code");
				StringBuilder sb = new StringBuilder();
				if (message != null && !message.trim().isEmpty()) {
					sb.append(message.trim());
				}
				if (type != null && !type.trim().isEmpty()) {
					sb.append(sb.length() > 0 ? " [" : "[").append(type.trim()).append(']');
				}
				if (code != null && !code.trim().isEmpty() && !code.trim().equals(type)) {
					sb.append("（").append(code.trim()).append("）");
				}
				if (sb.length() > 0) {
					return sb.toString();
				}
			}
			String raw = body == null ? "" : body.trim().replaceAll("\\s*[\r\n]+\\s*", " ");
			return raw.length() > MAX_ERROR_TEXT ? raw.substring(0, MAX_ERROR_TEXT) + "……" : raw;
		}

		/** 回传给模型的中文错误说明，按状态码给出处置建议。 */
		String getUserMessage() {
			String message = getServerMessage();
			String suffix = message.isEmpty() ? "" : "：" + message;
			switch (status) {
			case 400:
				return "搜索参数不合法（HTTP 400）" + suffix + "。请调整 query 语法后重试。";
			case 401:
				return "DeepSeek API Key 无效或缺失（HTTP 401），请检查启动参数 -Ddeepseektoken。";
			case 402:
				return "DeepSeek 账户余额不足（HTTP 402），请前往开放平台充值后重试。";
			case 403:
				return "搜索请求被安全策略拒绝（HTTP 403）" + suffix + "。";
			case 404:
				return "搜索接口未返回可用内容（HTTP 404）" + suffix + "。";
			case 408:
				return "搜索请求超时（HTTP 408），请稍后重试。";
			case 413:
				return "搜索请求体过大（HTTP 413），请缩短 query。";
			case 429:
				return "搜索请求过于频繁（HTTP 429），已重试仍失败，请稍后再试。";
			default:
				if (status >= 500) {
					return "DeepSeek 搜索服务暂时不可用（HTTP " + status + "），已重试仍失败，请稍后再试。";
				}
				if (status <= 0) {
					return "搜索接口返回错误" + suffix + "。";
				}
				return "搜索请求失败（HTTP " + status + "）" + suffix + "。";
			}
		}

		/** 从错误响应中取出 error 对象，不是错误结构时返回 null。 */
		private static JsonObject errorObject(String body) {
			if (body == null) {
				return null;
			}
			try {
				JsonElement parsed = JsonParser.parseString(body.trim());
				if (!parsed.isJsonObject()) {
					return null;
				}
				JsonObject obj = parsed.getAsJsonObject();
				JsonElement error = obj.get("error");
				if (error != null && error.isJsonObject()) {
					return error.getAsJsonObject();
				}
				return obj.has("message") || obj.has("code") ? obj : null;
			} catch (RuntimeException e) {
				return null;
			}
		}
	}
}
