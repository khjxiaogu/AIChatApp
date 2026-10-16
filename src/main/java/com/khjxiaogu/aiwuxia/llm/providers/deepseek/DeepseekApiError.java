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
package com.khjxiaogu.aiwuxia.llm.providers.deepseek;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * DeepSeek 接口错误信息解析器。
 * <p>
 * DeepSeek 的 OpenAI 兼容接口在请求失败时通常返回如下响应体：
 *
 * <pre>
 * {"error":{"message":"...","type":"...","param":null,"code":"..."}}
 * </pre>
 *
 * 该响应体由 {@link com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder} 作为
 * {@link IOException} 的消息抛出，HTTP 状态码则保留在异常链的底层异常里。
 * 本类负责把这两处信息重新解析出来，生成便于排查的中文错误描述：
 * 优先展示服务端 <code>error.message</code>，并补充错误类型、错误代码、
 * 常见原因的处置建议以及原始响应内容。
 * </p>
 */
public class DeepseekApiError {
	/**
	 * 匹配 HTTP 状态码，兼容 "Server returned HTTP response code: 401 for URL: ..."（JDK 自带）
	 * 与 "HTTP 401" / "HTTP status code: 401" 等自造描述。
	 */
	private static final Pattern HTTP_STATUS = Pattern.compile(
			"(?:Server returned HTTP response code:\\s*|HTTP(?:/\\d(?:\\.\\d)?)?\\s*(?:status(?:\\s*code)?)?\\s*[:：]?\\s*)(\\d{3})",
			Pattern.CASE_INSENSITIVE);
	/** 原始响应与消息的最大展示长度，避免把整个响应体写进日志 */
	private static final int MAX_TEXT_LENGTH = 1200;

	/** 出错阶段描述，例如“流式对话请求” */
	private String phase;
	/** HTTP 状态码，未解析到时为 0 */
	private int httpStatus;
	/** 服务端返回的错误消息 */
	private String message;
	/** 服务端返回的错误类型，如 authentication_error */
	private String type;
	/** 服务端返回的错误代码，如 invalid_request_error */
	private String code;
	/** 服务端指出的出错参数 */
	private String param;
	/** 原始响应文本（无法解析为 JSON 时为完整原文） */
	private String raw;
	/** 根据状态码与错误内容推断出的处置建议，可能为 null */
	private String hint;

	private DeepseekApiError() {
	}

	/**
	 * 从调用过程中抛出的异常解析 DeepSeek 错误信息。
	 * <p>
	 * 会遍历整条异常链：服务端响应体通常在异常自身的消息里（由工具类包装），
	 * 而 HTTP 状态码通常在底层原因（{@code Server returned HTTP response code: ...}）里。
	 * </p>
	 *
	 * @param error 调用过程中捕获的异常，可为 null
	 * @param phase 出错阶段的中文描述，如“流式对话请求”
	 * @return 解析结果，永不为 null
	 */
	public static DeepseekApiError from(Throwable error, String phase) {
		DeepseekApiError res = new DeepseekApiError();
		res.phase = phase;
		List<String> texts = collectMessages(error);
		// 状态码：可能出现在异常链的任意一层
		for (String text : texts) {
			Matcher matcher = HTTP_STATUS.matcher(text);
			if (matcher.find()) {
				res.httpStatus = Integer.parseInt(matcher.group(1));
				break;
			}
		}
		// 响应体：优先取能解析出结构化错误的那一层，其次退化为原始文本
		String fallback = null;
		for (String text : texts) {
			if (res.parseBody(text)) {
				fallback = null;
				break;
			}
			if (fallback == null)
				fallback = text;
		}
		if (res.message == null && fallback != null) {
			res.raw = fallback;
			res.message = fallback;
		}
		res.hint = res.buildHint(error);
		return res;
	}

	/**
	 * 从服务端直接下发的错误文本解析错误信息。
	 * <p>
	 * 用于流式响应中直接出现的错误对象（形如 {@code {"error":{...}}}），
	 * 这类错误发生在 HTTP 200 之后，不会以异常形式抛出。
	 * </p>
	 *
	 * @param text  服务端返回的原始文本，可为 JSON 或普通文本
	 * @param phase 出错阶段的中文描述
	 * @return 解析结果，永不为 null
	 */
	public static DeepseekApiError fromText(String text, String phase) {
		DeepseekApiError res = new DeepseekApiError();
		res.phase = phase;
		String trimmed = text == null ? "" : text.trim();
		if (!res.parseBody(trimmed)) {
			res.raw = trimmed;
			res.message = trimmed;
		}
		res.hint = res.buildHint(null);
		return res;
	}

	/**
	 * 将异常转换为携带完整错误描述的新异常，便于沿现有 IOException 通道向上抛出。
	 *
	 * @param error 原始异常，可为 null
	 * @param phase 出错阶段的中文描述
	 * @return 携带解析后错误信息的异常
	 */
	public static IOException toIOException(Throwable error, String phase) {
		return from(error, phase).toException(error);
	}

	/**
	 * 生成单行摘要，用于日志输出。
	 *
	 * @return 形如“DeepSeek 接口调用失败（流式对话请求，HTTP 401）：... [authentication_error]”的描述
	 */
	public String getSummary() {
		StringBuilder sb = new StringBuilder("DeepSeek 接口调用失败");
		if (phase != null && !phase.isEmpty())
			sb.append("（").append(phase).append("）");
		if (httpStatus > 0)
			sb.append("，HTTP ").append(httpStatus);
		sb.append("：");
		sb.append(message == null || message.trim().isEmpty() ? "服务端未返回错误详情" : shorten(message));
		if (type != null || code != null) {
			sb.append(" [");
			sb.append(type == null ? "" : type);
			if (code != null)
				sb.append(type == null ? "" : "/").append(code);
			sb.append("]");
		}
		return sb.toString();
	}

	/**
	 * 生成完整错误描述（含错误参数、处置建议与原始响应），用作异常消息。
	 *
	 * @return 多行错误描述
	 */
	public String getDetail() {
		StringBuilder sb = new StringBuilder(getSummary());
		if (param != null && !param.trim().isEmpty() && !"null".equals(param))
			sb.append("\n  错误参数: ").append(shorten(param));
		if (hint != null)
			sb.append("\n  处理建议: ").append(hint);
		if (raw != null && !raw.equals(message))
			sb.append("\n  原始响应: ").append(shorten(raw));
		return sb.toString();
	}

	/**
	 * 构造携带完整错误描述的异常。
	 *
	 * @param cause 原始异常，可为 null
	 * @return 新异常
	 */
	public IOException toException(Throwable cause) {
		return new IOException(getDetail(), cause);
	}

	/** @return HTTP 状态码，未解析到时为 0 */
	public int getHttpStatus() {
		return httpStatus;
	}

	/** @return 服务端返回的错误消息，可能为 null */
	public String getMessage() {
		return message;
	}

	/** @return 服务端返回的错误类型，可能为 null */
	public String getType() {
		return type;
	}

	/** @return 服务端返回的错误代码，可能为 null */
	public String getCode() {
		return code;
	}

	/** @return 服务端指出的出错参数，可能为 null */
	public String getParam() {
		return param;
	}

	/** @return 根据错误内容推断的处置建议，可能为 null */
	public String getHint() {
		return hint;
	}

	/** @return 原始响应文本，可能为 null */
	public String getRaw() {
		return raw;
	}

	/** @return 是否为请求参数类错误（可通过打印请求体辅助排查） */
	public boolean isRequestParameterError() {
		return httpStatus == 400 || httpStatus == 422;
	}

	/**
	 * 尝试把一段文本解析为 DeepSeek 错误响应体。
	 *
	 * @param text 待解析文本
	 * @return 解析出有效错误信息时返回 true，并填充本对象字段
	 */
	private boolean parseBody(String text) {
		String trimmed = text == null ? "" : text.trim();
		if (!trimmed.startsWith("{"))
			return false;
		JsonObject body;
		try {
			JsonElement parsed = JsonParser.parseString(trimmed);
			if (!parsed.isJsonObject())
				return false;
			body = parsed.getAsJsonObject();
		} catch (RuntimeException e) {
			// 不是合法 JSON，按纯文本处理
			return false;
		}
		JsonObject err = body;
		if (body.has("error")) {
			JsonElement elem = body.get("error");
			if (elem.isJsonObject()) {
				err = elem.getAsJsonObject();
			} else if (elem.isJsonPrimitive()) {
				// 少数网关会把 error 直接写成字符串
				this.message = elem.getAsString();
				this.raw = trimmed;
				return true;
			} else {
				return false;
			}
		} else if (!body.has("message") && !body.has("msg") && !body.has("error_msg") && !body.has("code")) {
			// 与错误无关的 JSON（例如正常的增量响应），不作处理
			return false;
		}
		String msg = firstString(err, "message", "msg", "error_msg", "error_description");
		String type = firstString(err, "type", "error_type");
		String code = firstString(err, "code", "error_code");
		if (msg == null && type == null && code == null)
			return false;
		this.message = msg != null ? msg : (code != null ? code : type);
		this.type = type;
		this.code = code;
		this.param = firstString(err, "param", "parameter", "invalid_param");
		this.raw = trimmed;
		return true;
	}

	/**
	 * 根据状态码、错误内容与异常类型推断处置建议。
	 *
	 * @param error 原始异常，可为 null（流内错误时没有异常）
	 * @return 中文处置建议，无法判断时返回 null
	 */
	private String buildHint(Throwable error) {
		String probe = ((message == null ? "" : message) + " " + (type == null ? "" : type) + " "
				+ (code == null ? "" : code)).toLowerCase(Locale.ROOT);
		if (httpStatus == 402 || contains(probe, "insufficient balance", "insufficient_quota",
				"exceeded your current quota", "余额不足"))
			return "账户余额或配额不足，请前往 DeepSeek 开放平台充值后重试。";
		if (httpStatus == 401 || contains(probe, "invalid api key", "authentication", "unauthorized", "api key"))
			return "API Key 无效、缺失或已过期，请检查启动参数 -Ddeepseektoken=你的密钥。";
		if (httpStatus == 403)
			return "该 API Key 无权访问此模型或接口，请确认账号权限与模型名称。";
		if (httpStatus == 404)
			return "接口路径或模型名不存在，请确认 DeepSeek 接口版本与模型名称。";
		if (httpStatus == 429 || contains(probe, "rate limit", "too many requests", "concurrency", "限流"))
			return "已触发限流或并发上限，请稍后重试，或减少同时进行的生成任务。";
		if (contains(probe, "context length", "context_length", "maximum context", "too long", "length limit",
				"max_tokens", "token limit"))
			return "上下文或 max_tokens 超出模型上限，请缩短历史对话或调小 maxToken。";
		if (contains(probe, "content filter", "content_filter", "safety"))
			return "请求内容被 DeepSeek 内容安全策略拦截，请调整提示词后重试。";
		if (contains(probe, "invalid_request_error", "bad request", "invalid", "unsupported")
				|| isRequestParameterError())
			return "请求参数不合法，请检查模型名称、消息格式与 tools 定义是否符合 DeepSeek 接口要求。";
		if (httpStatus >= 500)
			return "DeepSeek 服务端异常或繁忙，请稍后重试。";
		if (contains(probe, "timeout", "timed out") || hasCause(error, SocketTimeoutException.class))
			return "请求超时，请检查网络连通性后重试。";
		if (hasCause(error, UnknownHostException.class) || contains(probe, "unknownhost", "no such host"))
			return "无法解析 api.deepseek.com，请检查网络与 DNS 设置。";
		if (hasCause(error, ConnectException.class) || contains(probe, "connection refused", "connect timed out"))
			return "无法连接 api.deepseek.com，请检查网络、代理或防火墙设置。";
		if (hasCause(error, SSLException.class) || contains(probe, "ssl", "certificate"))
			return "TLS 连接失败，请检查代理设置或系统证书。";
		if (httpStatus <= 0 && (message == null || message.trim().isEmpty()))
			return "未获取到服务端返回内容，请检查网络连通性后重试。";
		return null;
	}

	/** 收集异常链上的所有非空消息（由外到内）。 */
	private static List<String> collectMessages(Throwable error) {
		List<String> res = new ArrayList<>();
		Throwable current = error;
		while (current != null) {
			String msg = current.getMessage();
			if (msg != null && !msg.trim().isEmpty() && !res.contains(msg))
				res.add(msg);
			Throwable cause = current.getCause();
			current = cause == current ? null : cause;
		}
		return res;
	}

	/** 异常链上是否存在指定类型的异常。 */
	private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
		Throwable current = error;
		while (current != null) {
			if (type.isInstance(current))
				return true;
			Throwable cause = current.getCause();
			current = cause == current ? null : cause;
		}
		return false;
	}

	/** 依次取第一个存在的字段值，忽略 null 与空对象。 */
	private static String firstString(JsonObject obj, String... keys) {
		for (String key : keys) {
			if (obj == null || !obj.has(key))
				continue;
			JsonElement elem = obj.get(key);
			if (elem == null || elem.isJsonNull())
				continue;
			if (elem.isJsonPrimitive())
				return elem.getAsString();
			if (elem.isJsonArray()) {
				if (elem.getAsJsonArray().size() > 0)
					return elem.getAsJsonArray().toString();
				continue;
			}
			return elem.toString();
		}
		return null;
	}

	/** 文本中是否包含任一关键字。 */
	private static boolean contains(String text, String... keywords) {
		for (String keyword : keywords) {
			if (text.contains(keyword))
				return true;
		}
		return false;
	}

	/** 把多行文本压成一行，避免日志被换行截断。 */
	private static String oneLine(String text) {
		return text == null ? "" : text.replaceAll("\\s*[\r\n]+\\s*", " ").trim();
	}

	/** 截断过长的文本，便于直接写入日志或异常消息。 */
	private static String shorten(String text) {
		String flat = oneLine(text);
		if (flat.length() <= MAX_TEXT_LENGTH)
			return flat;
		return flat.substring(0, MAX_TEXT_LENGTH) + "……（已截断）";
	}
}
