package com.khjxiaogu.aiwuxia.tts;

import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpException;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpProtocol;
import com.khjxiaogu.aiwuxia.mcp.server.McpAccessTokenStore;
import com.khjxiaogu.aiwuxia.mcp.server.McpServer;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModel;
import com.khjxiaogu.aiwuxia.modelproxy.ModelGenerationResult;
import com.khjxiaogu.aiwuxia.modelproxy.ModelType;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.voice.LocalVoiceApi;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;
import com.khjxiaogu.webserver.web.CallBack;
import com.khjxiaogu.webserver.web.lowlayer.HttpOutputStream;
import com.khjxiaogu.webserver.web.lowlayer.Request;
import com.khjxiaogu.webserver.web.lowlayer.Response;

/**
 * 兼容 OpenAI API 的 TTS 接口套件。
 * <p>
 * 由 {@code AIChatService#tts} 转发，挂载在 {@code /aichat/tts}，对外路径与 OpenAI 官方一致：
 * </p>
 * <ul>
 *   <li>{@code POST /aichat/tts/v1/audio/speech} —— 文本转语音，返回二进制音频流；</li>
 *   <li>{@code GET /aichat/tts/v1/audio/voices} —— 列出本地语音配置中的角色名（扩展端点）；</li>
 *   <li>{@code GET /aichat/tts/v1/models} —— 列出可作为 {@code model} 传入的模型 ID；</li>
 *   <li>{@code GET /aichat/tts/v1} —— 接口索引。</li>
 * </ul>
 * <p>
 * 鉴权复用 MCP 访问令牌（{@code Authorization: Bearer mcp_xxx}）。语音合成只接入本地语音后端
 * （{@link LocalVoiceApi}），并沿用项目内朗读链路：先由
 * {@link com.khjxiaogu.aiwuxia.voice.VoiceTagger} 做分句与情感标注，再交给本地语音模型合成。
 * </p>
 * <p>
 * 合成分两阶段：情感标注（VoiceTagger 的 LLM 调用）在请求线程内同步等待，失败时返回准确的
 * 4xx/5xx；本地语音合成若立即完成也走同步路径，耗时较长时才用
 * {@link Response#openStream(int)} 接管响应，立即返回并在线程池里等待结果、分块写出音频，
 * 避免长时间占用 Netty 事件循环。并发由 {@code -DttsMaxConcurrent} 限制。
 * </p>
 */
public final class TtsApiEndpoint implements CallBack {

	/** 接口挂载点（相对站点根）。 */
	public static final String MOUNT_PATH = "/aichat/tts";
	/** OpenAI 兼容路径前缀，客户端的 {@code base_url} 应填这个地址。 */
	public static final String BASE_PATH = MOUNT_PATH + "/v1";

	/** 本地语音后端对应的模型 ID。 */
	public static final String LOCAL_MODEL_ID = "gptsovits";

	/** 对外暴露的模型 ID：真实 ID + OpenAI 官方 TTS 模型名，方便现成客户端直接使用。 */
	private static final List<String> MODEL_IDS = Collections.unmodifiableList(
			Arrays.asList(LOCAL_MODEL_ID, "tts-1", "tts-1-hd", "gpt-4o-mini-tts"));

	/** 可接受的模型别名（大小写不敏感）。 */
	private static final List<String> MODEL_ALIASES = Collections.unmodifiableList(Arrays.asList("local",
			"aiwuxia", "aiwuxia-local", "gptsovits"));

	/** 模型列表里使用的固定创建时间，避免每次请求都变化。 */
	private static final long MODEL_CREATED = 1735689600L;

	/** 计费时写入 {@code mcp_tool_call_log} 的工具名。 */
	private static final String TOOL_NAME = "tts_speech";

	private static final String CONTENT_TYPE_JSON = "application/json; charset=utf-8";

	/** 默认单次请求文本长度上限，与 OpenAI 官方一致。 */
	private static final int DEFAULT_MAX_INPUT_LENGTH = 4096;
	/** 默认朗读风格提示长度上限。 */
	private static final int DEFAULT_MAX_INSTRUCTIONS_LENGTH = 500;
	/** 默认等待本地语音合成的上限：本地 HTTP TTS 的读超时为 120 秒，这里取同一量级。 */
	private static final long DEFAULT_TIMEOUT_MS = 120_000L;
	/** 默认并发上限：语音合成是重任务，超过后直接 429，避免请求堆积。 */
	private static final int DEFAULT_MAX_CONCURRENT = 4;

	/** 取 MCP 令牌存储（延迟取值：令牌存储可能在 AIChatService 构造完成后才初始化）。 */
	private final Supplier<McpAccessTokenStore> tokenStore;
	/** 并发许可：限制同时进行的合成数，避免把 Netty 事件循环全部占满。 */
	private final Semaphore slots;
	/** 本地合成阶段等待结果的线程池（情感标注阶段仍在请求线程内同步完成）。 */
	private final ExecutorService executor;

	/**
	 * 创建接口套件。
	 *
	 * @param tokenStore MCP 令牌存储提供者，可为 null（此时所有请求返回 503）
	 */
	public TtsApiEndpoint(Supplier<McpAccessTokenStore> tokenStore) {
		this.tokenStore = tokenStore;
		int concurrent = positiveInt("ttsMaxConcurrent", DEFAULT_MAX_CONCURRENT);
		this.slots = new Semaphore(concurrent, true);
		ThreadFactory factory = runnable -> {
			Thread thread = new Thread(runnable, "openai-tts");
			thread.setDaemon(true);
			return thread;
		};
		this.executor = Executors.newFixedThreadPool(concurrent, factory);
	}

	@Override
	public void call(Request request, Response response) {
		try {
			String method = methodOf(request);
			if ("OPTIONS".equals(method)) {
				writePreflight(response);
				return;
			}
			McpAccessTokenStore store = tokenStore == null ? null : tokenStore.get();
			if (store == null) {
				throw error(503, "server_error", null, "tts_unavailable",
						"TTS 接口未启用：MCP 访问令牌存储未初始化（检查 save/mcp.json）");
			}
			McpAccessTokenStore.Token token = authenticate(request, store);
			String path = normalizePath(request.getCurrentPath());
			// 同时接受带 /v1 与不带 /v1 的写法，base_url 填 /aichat/tts 或 /aichat/tts/v1 都能用
			if ("/v1".equals(path)) {
				path = "/";
			} else if (path.startsWith("/v1/")) {
				path = path.substring(3);
			}
			switch (path) {
			case "/":
				handleIndex(response);
				return;
			case "/audio/speech":
				handleSpeech(request, response, store, token);
				return;
			case "/audio/voices":
				handleVoices(request, response);
				return;
			case "/models":
				handleModels(request, response);
				return;
			default:
				throw error(404, "invalid_request_error", null, "not_found", "未知端点：" + path);
			}
		} catch (ApiError e) {
			writeError(response, e);
		} catch (Throwable t) {
			t.printStackTrace();
			if (!response.isWritten()) {
				writeError(response, error(500, "server_error", null, "internal_error", "服务端内部错误"));
			}
		}
	}

	// ==================== 端点实现 ====================

	/** {@code GET /}：接口索引，便于排查地址是否正确。 */
	private void handleIndex(Response response) {
		JsonObject endpoints = new JsonObject();
		endpoints.addProperty("speech", "POST " + BASE_PATH + "/audio/speech");
		endpoints.addProperty("voices", "GET " + BASE_PATH + "/audio/voices");
		endpoints.addProperty("models", "GET " + BASE_PATH + "/models");
		JsonObject result = new JsonObject();
		result.addProperty("object", "api");
		result.addProperty("name", "AIWuxia OpenAI 兼容 TTS");
		result.addProperty("base_url", BASE_PATH);
		result.addProperty("auth", "Authorization: Bearer mcp_xxx");
		result.addProperty("emotion_tagging", true);
		result.add("endpoints", endpoints);
		writeJson(response, 200, result);
	}

	/** {@code GET /models}：OpenAI 模型列表格式。 */
	private void handleModels(Request request, Response response) {
		requireMethod(request, response, "GET, OPTIONS", "GET");
		JsonArray data = new JsonArray();
		for (String id : MODEL_IDS) {
			JsonObject model = new JsonObject();
			model.addProperty("id", id);
			model.addProperty("object", "model");
			model.addProperty("created", MODEL_CREATED);
			model.addProperty("owned_by", "aiwuxia");
			data.add(model);
		}
		JsonObject result = new JsonObject();
		result.addProperty("object", "list");
		result.add("data", data);
		writeJson(response, 200, result);
	}

	/** {@code GET /audio/voices}：列出本地语音配置里的角色名。 */
	private void handleVoices(Request request, Response response) {
		requireMethod(request, response, "GET, OPTIONS", "GET");
		boolean online = LocalModel.hasOnlineService(ModelType.AUDIO);
		JsonArray data = new JsonArray();
		for (String name : VoiceModelHandler.listLocalSpeakers()) {
			JsonObject voice = new JsonObject();
			voice.addProperty("id", name);
			voice.addProperty("object", "audio.voice");
			voice.addProperty("backend", LOCAL_MODEL_ID);
			voice.addProperty("available", online);
			data.add(voice);
		}
		JsonObject result = new JsonObject();
		result.addProperty("object", "list");
		result.add("data", data);
		writeJson(response, 200, result);
	}

	/**
	 * {@code POST /audio/speech}：OpenAI 兼容的文本转语音。
	 * <p>
	 * 校验完参数后把等待合成的工作交给线程池，响应在合成完成时写出。调用按 MCP 工具同一套规则
	 * 计费：先预扣固定调用费，成功后再按实际用量补扣，失败按已产生的用量结算或全额退回。
	 * </p>
	 *
	 * @param request  请求对象（只在本方法内使用，异步阶段不再访问）
	 * @param response 响应对象（异步阶段写回）
	 * @param store    MCP 令牌存储（计费）
	 * @param token    已认证的令牌
	 */
	private void handleSpeech(Request request, Response response, McpAccessTokenStore store,
			McpAccessTokenStore.Token token) {
		requireMethod(request, response, "POST, OPTIONS", "POST");
		String contentType = request.getHeaders().get("Content-Type");
		if (contentType != null && !contentType.toLowerCase(Locale.ROOT).contains("application/json")) {
			throw error(415, "invalid_request_error", null, "unsupported_media_type",
					"Content-Type 必须为 application/json");
		}
		JsonObject body = readJsonObject(request);

		String model = JsonConfig.getNonBlankString(body, "model");
		if (model == null) {
			model = LOCAL_MODEL_ID;
		}
		requireSupportedModel(model);

		String input = JsonConfig.getString(body, "input");
		if (input == null || input.trim().isEmpty()) {
			throw error(400, "invalid_request_error", "input", "input_required", "缺少要朗读的文本 input");
		}
		int maxInput = positiveInt("ttsMaxInputLength", DEFAULT_MAX_INPUT_LENGTH);
		if (input.length() > maxInput) {
			throw error(400, "invalid_request_error", "input", "input_too_long",
					"input 长度 " + input.length() + " 超过上限 " + maxInput);
		}

		String voice = JsonConfig.getNonBlankString(body, "voice");
		if (voice == null) {
			throw error(400, "invalid_request_error", "voice", "voice_required", "缺少音色 voice");
		}
		List<String> voices = VoiceModelHandler.listLocalSpeakers();
		if (!voices.contains(voice)) {
			throw error(400, "invalid_request_error", "voice", "voice_not_found",
					"voice 必须是本地语音配置中的角色名；当前可用：" + preview(voices));
		}

		String requestedFormat = TtsAudioFormat.normalize(JsonConfig.getString(body, "response_format"));
		if (requestedFormat != null && !TtsAudioFormat.isLocalSupported(requestedFormat)) {
			throw error(400, "invalid_request_error", "response_format", "unsupported_response_format",
					"本地语音后端只输出 mp3 或 wav，不支持 " + requestedFormat);
		}

		JsonElement speedElement = body.get("speed");
		if (speedElement != null && !speedElement.isJsonNull()) {
			double speed;
			try {
				speed = speedElement.getAsDouble();
			} catch (RuntimeException e) {
				throw error(400, "invalid_request_error", "speed", "invalid_speed", "speed 必须是数字");
			}
			if (Double.isNaN(speed) || speed < 0.25 || speed > 4.0) {
				throw error(400, "invalid_request_error", "speed", "invalid_speed",
						"speed 必须在 0.25 到 4.0 之间");
			}
			// 本地语音后端没有变速能力，这里只做参数校验，不改变语速
		}

		// instructions：作为朗读风格提示交给情感标注，只影响逐句情感/语气选择
		String instructions = JsonConfig.getString(body, "instructions");
		if (instructions != null) {
			instructions = instructions.trim();
			if (instructions.isEmpty()) {
				instructions = null;
			} else {
				int maxInstructions = positiveInt("ttsMaxInstructionsLength", DEFAULT_MAX_INSTRUCTIONS_LENGTH);
				if (instructions.length() > maxInstructions) {
					throw error(400, "invalid_request_error", "instructions", "instructions_too_long",
							"instructions 长度 " + instructions.length() + " 超过上限 " + maxInstructions);
				}
			}
		}

		LocalVoiceApi local = VoiceModelHandler.getLocalVoice();
		if (local == null) {
			throw error(503, "server_error", null, "tts_unavailable",
					"本地语音未启用：启动时需提供 -DlocalVoiceToken，并配置 save/config/local-voice.json");
		}
		if (!LocalModel.hasOnlineService(ModelType.AUDIO)) {
			throw error(503, "server_error", null, "local_tts_offline", "本地语音服务不在线，请先连接本地语音模型");
		}
		if (!slots.tryAcquire()) {
			response.setHeader("Retry-After", "5");
			throw error(429, "rate_limit_error", null, "too_many_requests", "TTS 并发已满，请稍后重试");
		}

		final String messageId = UUID.randomUUID().toString();
		final long deadline = System.currentTimeMillis() + positiveLong("ttsTimeoutMs", DEFAULT_TIMEOUT_MS);
		// 与 MCP 工具一致：先按固定调用费预扣，余额/额度不足直接拒绝，不执行合成
		McpAccessTokenStore.Charge charge;
		try {
			charge = store.charge(token, TOOL_NAME, callCost(store), remoteIp(request), messageId);
		} catch (McpException e) {
			slots.release();
			throw mapChargeError(e);
		}
		final Billing billing = new Billing(store, charge, input.length());
		boolean handedOver = false;
		try {
			Consumer<UsageIntf<?>> usageListener = billing.usageListener();
			// 第一阶段（同步）：VoiceTagger 分句 + 情感标注。这一步的失败属于请求级错误，
			// 同步等待才能返回准确的 4xx/5xx；同时保证响应不会晚于框架兜底逻辑写出。
			JsonArray segments;
			try {
				segments = local.extractTalk(voice, input, instructions, usageListener).get(remaining(deadline),
						TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				throw error(504, "server_error", null, "tts_timeout", "情感标注超时，请稍后重试");
			} catch (ExecutionException e) {
				throw mapSynthesisError(e.getCause() == null ? e : e.getCause());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw error(500, "server_error", null, "tts_interrupted", "语音合成被中断");
			}
			// 第二阶段：本地合成。若本地模型立刻返回（成功或失败），直接同步写响应，
			// 状态码与 Content-Type 都更准确；只有真正耗时的合成才转入异步流式阶段。
			String declaredFormat = requestedFormat == null ? TtsAudioFormat.MP3 : requestedFormat;
			CompletableFuture<ModelGenerationResult> future = local.synthesizeSegments(voice, segments, messageId,
					usageListener);
			ModelGenerationResult quick;
			try {
				quick = future.get(0, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				quick = null;
			} catch (ExecutionException e) {
				throw mapSynthesisError(e.getCause() == null ? e : e.getCause());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw error(500, "server_error", null, "tts_interrupted", "语音合成被中断");
			}
			if (quick != null) {
				writeCompletedAudio(response, quick, model);
				billing.commit();
			} else {
				// 合成耗时较长：用 HttpOutputStream 接管响应后立即返回，在线程池里等结果写音频，
				// 避免长时间占用 Netty 事件循环。响应头必须在 openStream 之前设置。
				writeAudioHeaders(response, declaredFormat, model);
				HttpOutputStream out = response.openStream(200);
				try {
					long timeoutMs = remaining(deadline);
					executor.execute(() -> streamAudio(out, future, declaredFormat, voice, timeoutMs, billing));
				} catch (Throwable t) {
					closeQuietly(out);
					throw t;
				}
				handedOver = true;
			}
		} catch (ApiError e) {
			billing.fail(e.getMessage());
			writeError(response, e);
		} catch (Throwable t) {
			ApiError mapped = mapSynthesisError(t);
			billing.fail(mapped.getMessage());
			writeError(response, mapped);
		} finally {
			if (!handedOver) {
				slots.release();
			}
		}
	}

	/**
	 * 等待本地合成结果并写入响应流（在线程池中执行）。
	 * <p>
	 * 进入本方法时响应头已经发出，因此无法再修改状态码或 Content-Type，只能写出音频或结束
	 * 一个空响应；失败原因记录到日志。
	 * </p>
	 *
	 * @param out            响应输出流
	 * @param future         本地合成结果
	 * @param declaredFormat 响应头里声明的格式
	 * @param voice          音色（仅用于日志）
	 * @param timeoutMs      等待上限
	 * @param billing        计费结算
	 */
	private void streamAudio(HttpOutputStream out, CompletableFuture<ModelGenerationResult> future,
			String declaredFormat, String voice, long timeoutMs, Billing billing) {
		boolean wrote = false;
		String failure = null;
		try {
			ModelGenerationResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
			if (result == null || result.bodyData == null || result.bodyData.length == 0) {
				failure = "本地语音没有返回音频数据";
				log(failure + "：voice=" + voice);
				return;
			}
			String actualFormat = TtsAudioFormat.normalize(result.format);
			if (actualFormat == null) {
				actualFormat = TtsAudioFormat.MP3;
			}
			if (!actualFormat.equals(declaredFormat)) {
				log("本地语音实际输出 " + actualFormat + "，但响应头已按 " + declaredFormat + " 声明（voice=" + voice
						+ "）");
			}
			out.write(result.bodyData);
			out.flush();
			wrote = true;
		} catch (TimeoutException e) {
			failure = "本地语音合成超时";
			log(failure + "：voice=" + voice + "，上限 " + timeoutMs + "ms");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			failure = "语音合成被中断";
			log(failure + "：voice=" + voice);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() == null ? e : e.getCause();
			failure = rootMessage(cause);
			log("本地语音合成失败：voice=" + voice + "，" + failure);
		} catch (Throwable t) {
			failure = rootMessage(t);
			log("写音频响应失败：voice=" + voice + "，" + failure);
		} finally {
			if (wrote) {
				billing.commit();
			} else {
				billing.fail(failure == null ? "音频写入失败" : failure);
			}
			closeQuietly(out);
			slots.release();
		}
	}

	// ==================== 响应写入 ====================

	/** 写音频响应头（必须在 {@link Response#openStream(int)} 之前调用）。 */
	private static void writeAudioHeaders(Response response, String format, String model) {
		response.setHeader("Content-Type", TtsAudioFormat.mimeOf(format));
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Access-Control-Expose-Headers", "X-TTS-Format, X-TTS-Model");
		response.setHeader("X-TTS-Format", format);
		response.setHeader("X-TTS-Model", model);
	}

	/** 同步写出已经完成的音频，Content-Type 使用本地模型的实际输出格式。 */
	private static void writeCompletedAudio(Response response, ModelGenerationResult result, String model) {
		if (result == null || result.bodyData == null || result.bodyData.length == 0) {
			throw error(500, "server_error", null, "tts_empty", "本地语音模型没有返回音频数据");
		}
		String format = TtsAudioFormat.normalize(result.format);
		if (format == null) {
			format = TtsAudioFormat.MP3;
		}
		writeAudioHeaders(response, format, model);
		response.write(200, result.bodyData);
	}

	/** 安静关闭响应流：客户端断开等情况下的关闭失败无需处理。 */
	private static void closeQuietly(HttpOutputStream out) {
		try {
			out.close();
		} catch (Throwable ignored) {
			// 忽略
		}
	}

	/** 距离截止时间还剩多少毫秒（至少 1 毫秒，避免 get(0) 立即超时）。 */
	private static long remaining(long deadline) {
		long left = deadline - System.currentTimeMillis();
		return left > 0 ? left : 1;
	}

	/** 写 OpenAI 风格错误信封：{@code {"error":{"message","type","param","code"}}}。 */
	private static void writeError(Response response, ApiError error) {
		if (response == null || response.isWritten()) {
			return;
		}
		JsonObject detail = new JsonObject();
		detail.addProperty("message", error.getMessage() == null ? "未知错误" : error.getMessage());
		detail.addProperty("type", error.type);
		if (error.param != null) {
			detail.addProperty("param", error.param);
		}
		if (error.code != null) {
			detail.addProperty("code", error.code);
		}
		JsonObject wrapper = new JsonObject();
		wrapper.add("error", detail);
		if (error.status == 401) {
			response.setHeader("WWW-Authenticate", "Bearer");
		}
		writeJson(response, error.status, wrapper);
	}

	/** 写 JSON 响应。 */
	private static void writeJson(Response response, int status, JsonElement body) {
		byte[] bytes = (body == null ? "{}" : body.toString()).getBytes(StandardCharsets.UTF_8);
		response.setHeader("Content-Type", CONTENT_TYPE_JSON);
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.write(status, bytes);
	}

	/** 写 CORS 预检响应。 */
	private static void writePreflight(Response response) {
		response.setHeader("Access-Control-Allow-Origin", "*");
		response.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
		response.setHeader("Access-Control-Allow-Headers",
				"Authorization, Content-Type, " + McpProtocol.HEADER_MCP_TOKEN);
		response.setHeader("Access-Control-Max-Age", "86400");
		response.write(204);
	}

	// ==================== 鉴权与参数工具 ====================

	/**
	 * 校验 MCP 访问令牌，兼容 {@code Bearer}、{@code Token} 与 {@code X-MCP-Token}。
	 *
	 * @param request 请求对象
	 * @param store   令牌存储
	 * @return 通过校验的令牌
	 * @throws ApiError 缺少令牌、令牌无效或已失效
	 */
	private static McpAccessTokenStore.Token authenticate(Request request, McpAccessTokenStore store) {
		String plaintext = null;
		String authorization = request.getHeaders().get(McpProtocol.HEADER_AUTHORIZATION);
		if (authorization != null) {
			String trimmed = authorization.trim();
			if (trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
				plaintext = trimmed.substring(7).trim();
			} else if (trimmed.regionMatches(true, 0, "Token ", 0, 6)) {
				plaintext = trimmed.substring(6).trim();
			} else if (!trimmed.isEmpty() && trimmed.indexOf(' ') < 0) {
				plaintext = trimmed;
			}
		}
		if (plaintext == null || plaintext.isEmpty()) {
			plaintext = request.getHeaders().get(McpProtocol.HEADER_MCP_TOKEN);
		}
		if (plaintext == null || plaintext.trim().isEmpty()) {
			throw error(401, "authentication_error", null, "missing_token",
					"缺少访问令牌，请在 Authorization 头中提供 Bearer mcp_xxx");
		}
		McpAccessTokenStore.Token token = store.findByPlaintext(plaintext.trim());
		if (token == null) {
			throw error(401, "authentication_error", null, "invalid_token", "访问令牌无效");
		}
		if (!token.isUsable(System.currentTimeMillis())) {
			throw error(401, "authentication_error", null, "token_expired", "访问令牌已停用或已过期");
		}
		return token;
	}

	/**
	 * 校验 HTTP 方法，不匹配时写 {@code Allow} 头并抛出 405。
	 *
	 * @param request  请求对象
	 * @param response 响应对象
	 * @param allow    {@code Allow} 头的值
	 * @param methods  允许的方法
	 */
	private static void requireMethod(Request request, Response response, String allow, String... methods) {
		String method = methodOf(request);
		for (String candidate : methods) {
			if (candidate.equals(method)) {
				return;
			}
		}
		response.setHeader("Allow", allow);
		throw error(405, "invalid_request_error", null, "method_not_allowed",
				"该端点不支持 " + method + " 方法，允许：" + allow);
	}

	/** 校验 model 字段是否可识别。 */
	private static void requireSupportedModel(String model) {
		String normalized = model.trim().toLowerCase(Locale.ROOT);
		if (MODEL_IDS.contains(normalized) || MODEL_ALIASES.contains(normalized)) {
			return;
		}
		throw error(400, "invalid_request_error", "model", "model_not_found",
				"未知模型 " + model + "；可用模型：" + String.join(", ", MODEL_IDS));
	}

	/** 读取请求体文本。 */
	private static String readBody(Request request) {
		try {
			return request.getBody() == null ? "" : request.getBody().toString(StandardCharsets.UTF_8);
		} catch (Throwable t) {
			return "";
		}
	}

	/** 读取 JSON 请求体。 */
	private static JsonObject readJsonObject(Request request) {
		String body = readBody(request);
		if (body.trim().isEmpty()) {
			throw error(400, "invalid_request_error", null, "invalid_json", "请求体不能为空，且必须是 JSON 对象");
		}
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(body);
		} catch (RuntimeException e) {
			throw error(400, "invalid_request_error", null, "invalid_json", "请求体不是合法的 JSON");
		}
		if (parsed == null || !parsed.isJsonObject()) {
			throw error(400, "invalid_request_error", null, "invalid_json", "请求体必须是 JSON 对象");
		}
		return parsed.getAsJsonObject();
	}

	/** 路径规范化：空串、带尾斜杠视为同一路径。 */
	private static String normalizePath(String path) {
		if (path == null || path.isEmpty()) {
			return "/";
		}
		String normalized = path;
		while (normalized.length() > 1 && normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized.isEmpty() ? "/" : normalized;
	}

	/** 取大写 HTTP 方法。 */
	private static String methodOf(Request request) {
		String method = request.getMethod();
		return method == null ? "" : method.toUpperCase(Locale.ROOT);
	}

	/** 把异常映射为对外错误，避免把内部栈信息原样暴露。 */
	private static ApiError mapSynthesisError(Throwable t) {
		if (t instanceof ApiError) {
			return (ApiError) t;
		}
		String message = rootMessage(t);
		if ("Empty input".equals(message)) {
			// VoiceTagger 会先去掉（……）括号旁白，如果整段都是旁白就会得到空分段
			return error(400, "invalid_request_error", "input", "input_empty_after_cleaning",
					"文本去掉括号旁白后为空，没有可朗读的内容");
		}
		if (t instanceof ModelRouteException) {
			return error(503, "server_error", null, "emotion_tagger_unavailable",
					"情感标注模型不可用（朗读链路需要可用的 LLM 路由，通常是 MiMo）：" + message);
		}
		if (t instanceof IllegalArgumentException) {
			return error(400, "invalid_request_error", null, "invalid_voice", message);
		}
		return error(500, "server_error", null, "tts_failed", "语音合成失败：" + message);
	}

	/** 取最内层异常的消息。 */
	private static String rootMessage(Throwable t) {
		Throwable current = t;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		String message = current.getMessage();
		if (message == null || message.trim().isEmpty()) {
			message = current.getClass().getSimpleName();
		}
		return message;
	}

	/** 生成可用音色预览，避免错误信息过长。 */
	private static String preview(List<String> voices) {
		if (voices.isEmpty()) {
			return "（无，检查 save/config/local-voice.json）";
		}
		int limit = Math.min(voices.size(), 20);
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < limit; i++) {
			if (i > 0) {
				builder.append('、');
			}
			builder.append(voices.get(i));
		}
		if (voices.size() > limit) {
			builder.append(" 等 ").append(voices.size()).append(" 个");
		}
		return builder.toString();
	}

	/** 读取正整数系统属性。 */
	private static int positiveInt(String key, int defaultValue) {
		try {
			int value = Integer.parseInt(System.getProperty(key, "").trim());
			return value > 0 ? value : defaultValue;
		} catch (RuntimeException e) {
			return defaultValue;
		}
	}

	/** 读取正长整数系统属性。 */
	private static long positiveLong(String key, long defaultValue) {
		try {
			long value = Long.parseLong(System.getProperty(key, "").trim());
			return value > 0 ? value : defaultValue;
		} catch (RuntimeException e) {
			return defaultValue;
		}
	}

	/** 日志输出（随 System.err 重定向到 latest.log）。 */
	private static void log(String message) {
		System.err.println("[TTS] " + message);
	}

	// ==================== 计费 ====================

	/**
	 * 本次调用的固定费用：与 MCP 工具一致，免费模式（独立进程）下为 0。
	 *
	 * @param store 令牌存储
	 * @return 固定调用费
	 */
	private static int callCost(McpAccessTokenStore store) {
		if (store == null || !store.isBillingEnabled()) {
			return 0;
		}
		return nonNegativeInt("ttsCallCost", McpServer.CALL_COST);
	}

	/**
	 * 把预扣失败映射为 OpenAI 风格错误。
	 *
	 * @param e 预扣异常
	 * @return 对外错误
	 */
	private static ApiError mapChargeError(McpException e) {
		if (e.getCode() == McpProtocol.ERROR_QUOTA_EXCEEDED) {
			return error(429, "rate_limit_error", null, "insufficient_quota", e.getMessage());
		}
		if (e.getCode() == McpProtocol.ERROR_UNAUTHORIZED) {
			return error(401, "authentication_error", null, "invalid_token", e.getMessage());
		}
		return error(500, "server_error", null, "billing_failed", "计费失败：" + e.getMessage());
	}

	/** 客户端 IP，用于消费记录。 */
	private static String remoteIp(Request request) {
		try {
			InetSocketAddress remote = request.getRemote();
			if (remote != null && remote.getAddress() != null) {
				return remote.getAddress().getHostAddress();
			}
		} catch (Throwable ignored) {
			// 无远端地址（本地文件等）时忽略
		}
		return null;
	}

	/** 读取非负整数系统属性（0 有含义，例如固定调用费为 0）。 */
	private static int nonNegativeInt(String key, int defaultValue) {
		try {
			int value = Integer.parseInt(System.getProperty(key, "").trim());
			return value >= 0 ? value : defaultValue;
		} catch (RuntimeException e) {
			return defaultValue;
		}
	}

	/**
	 * 一次 TTS 调用的计费结算。
	 * <p>
	 * 与 MCP 工具一致：成功时按 {@code Σ ceil(getEquivantTokens())} 补扣内部用量；失败时若已经产生
	 * 用量（例如情感标注成功、本地合成失败），退回固定调用费、只结算用量，否则全额退回。
	 * 结算只执行一次。
	 * </p>
	 */
	private static final class Billing {
		private final McpAccessTokenStore store;
		private final McpAccessTokenStore.Charge charge;
		private final AIOutput usage = new AIOutput.FilledAIOutput(MessageContents.EMPTY, "", (UsageIntf<?>) null);
		private final long startedAt = System.currentTimeMillis();
		private final int chars;
		private final AtomicBoolean settled = new AtomicBoolean();

		private Billing(McpAccessTokenStore store, McpAccessTokenStore.Charge charge, int chars) {
			this.store = store;
			this.charge = charge;
			this.chars = chars;
		}

		/** 用量接收方：交给朗读链路的两个阶段。 */
		private Consumer<UsageIntf<?>> usageListener() {
			return usage::addUsage;
		}

		/** 成功结算：固定调用费 + 内部用量。 */
		private void commit() {
			if (!settled.compareAndSet(false, true)) {
				return;
			}
			store.commit(charge, McpServer.usageCostOf(usage), System.currentTimeMillis() - startedAt, chars);
		}

		/** 失败结算：有内部用量时只收用量，否则全额退回固定调用费。 */
		private void fail(String reason) {
			if (!settled.compareAndSet(false, true)) {
				return;
			}
			int usageCost = McpServer.usageCostOf(usage);
			long duration = System.currentTimeMillis() - startedAt;
			if (usageCost > 0) {
				store.commit(charge, usageCost, duration, chars, false, false);
			} else {
				store.refund(charge, reason == null ? "TTS 调用失败" : reason);
			}
		}
	}

	/** 构造对外错误。 */
	private static ApiError error(int status, String type, String param, String code, String message) {
		return new ApiError(status, type, param, code, message);
	}

	/** 对外错误的内部载体。 */
	private static final class ApiError extends RuntimeException {
		private static final long serialVersionUID = 1L;

		private final int status;
		private final String type;
		private final String param;
		private final String code;

		private ApiError(int status, String type, String param, String code, String message) {
			super(message);
			this.status = status;
			this.type = type;
			this.param = param;
			this.code = code;
		}
	}
}
