package com.khjxiaogu.aiwuxia.vision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.modelproxy.api.SdxlAction;
import com.khjxiaogu.aiwuxia.modelproxy.api.SdxlParams;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;

/**
 * Stable Diffusion WebUI（SDXL）的 API 调用集合。
 * <p>
 * 与 H3 / Qwen 的处理器同一套架构：<b>服务器只下发 {@link SdxlParams} 语义参数</b>，
 * <b>真正的 SD WebUI 调用在本地模型客户端执行</b>（{@link SDXLLocalModelHandler}，
 * 浏览器端由 {@code save/localmodel.html} / {@code save/painter-local.html} 实现同样规则）。
 * </p>
 * <p>
 * 参考图以附件形式存放在服务器，接收端先拉取字节，再由本类编码成 SD WebUI 需要的 Base64；
 * 服务器直连模式（{@code mcp.SDXLMcp#localChannel}）也用同一个 {@link #dispatch}。
 * </p>
 */
public final class SDXLApi {

	/** WD1.4 反推模型名。 */
	private static final String TAGGER_MODEL = "wd-v1-4-moat-tagger.v2";

	/**
	 * SD WebUI 请求端点：入参是 SD WebUI 的路径与请求体，出参是解析好的响应 JSON。
	 */
	@FunctionalInterface
	public interface Endpoint {
		/**
		 * 向 SD WebUI 发一次 POST。
		 *
		 * @param path    SD WebUI 路径，例如 {@code /sdapi/v1/txt2img}
		 * @param payload 请求体 JSON 文本
		 * @return 解析好的响应 JSON
		 * @throws IOException 连接失败或响应无法解析
		 */
		JsonObject request(String path, String payload) throws IOException;
	}

	/** 只用静态方法，不允许实例化。 */
	private SDXLApi() {
	}

	/**
	 * 直接访问 SD WebUI 的端点。
	 *
	 * @param baseUrl SD WebUI 地址，例如 {@code 127.0.0.1:7860}（可带 http/https 前缀）
	 * @return 端点实现
	 */
	public static Endpoint direct(String baseUrl) {
		String value = baseUrl == null ? "" : baseUrl.trim();
		if (value.isEmpty()) {
			throw new IllegalStateException("未配置 SD WebUI 地址（-DsdwebuiUrl）");
		}
		String scheme = "http";
		if (value.regionMatches(true, 0, "https://", 0, 8)) {
			scheme = "https";
			value = value.substring(8);
		} else if (value.regionMatches(true, 0, "http://", 0, 7)) {
			value = value.substring(7);
		}
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		final String host = value;
		final String protocol = scheme;
		return (path, payload) -> HttpRequestBuilder.create(protocol, host)
				.header("Accept", "application/json")
				.header("Content-Type", "application/json; utf-8")
				.url(path)
				.post()
				.send(payload == null ? "" : payload)
				.readJson();
	}

	/**
	 * 执行一次 SDXL 调用，返回结果字节。
	 * <p>
	 * 图片类动作返回图片字节（PNG/JPEG），{@link SdxlAction#INTERROGATE} 返回提示词 JSON 的
	 * UTF-8 字节。参考图来自附件：{@code imageBytes} 非空时编码成 Base64，
	 * 否则 {@code params.image} 视为链接或已是 Base64 的兜底值。
	 * </p>
	 *
	 * @param params     SDXL 参数
	 * @param imageBytes 参考图字节（从附件拉取），可为 null
	 * @param endpoint   SD WebUI 端点
	 * @return 结果字节
	 * @throws IOException SD WebUI 调用失败或没有返回图片
	 */
	public static byte[] dispatch(SdxlParams params, byte[] imageBytes, Endpoint endpoint) throws IOException {
		if (params == null) {
			throw new IllegalArgumentException("SDXL 参数为空");
		}
		params.validate();
		String image = imageBytes == null ? params.image : Base64.getEncoder().encodeToString(imageBytes);
		if (params.action == SdxlAction.TXT2IMG) {
			return requireImage(generateImage(params.prompt, params.negativePrompt, params.steps, params.width,
					params.height, endpoint));
		}
		if (params.action == SdxlAction.IMG2IMG) {
			return requireImage(generateImage2Image(image, params.prompt, params.negativePrompt, params.steps,
					params.width, params.height, (float) params.denoising, endpoint));
		}
		if (params.action == SdxlAction.REGIONAL) {
			if (image == null || image.isEmpty()) {
				return requireImage(generateRegionalImage(params.prompt, params.negativePrompt, params.steps,
						params.width, params.height, params.isRow, params.ratio, endpoint));
			}
			return requireImage(generateRegionalImage2Image(image, params.prompt, params.negativePrompt, params.steps,
					params.width, params.height, params.isRow, params.ratio, (float) params.denoising, endpoint));
		}
		if (params.action == SdxlAction.INTERROGATE) {
			if (image == null || image.isEmpty()) {
				throw new IOException("反推提示词需要参考图");
			}
			return interrogate(image, endpoint).toString().getBytes(StandardCharsets.UTF_8);
		}
		throw new IllegalArgumentException("未知的 SDXL 动作：" + params.action);
	}

	/** 图片结果为空时按失败处理，避免把 null 回传给服务器。 */
	private static byte[] requireImage(byte[] image) throws IOException {
		if (image == null || image.length == 0) {
			throw new IOException("SD WebUI 没有返回图片");
		}
		return image;
	}

	/**
	 * 按图片内容猜格式。
	 *
	 * @param data 图片字节
	 * @return {@code png} 或 {@code jpg}
	 */
	public static String imageFormat(byte[] data) {
		if (data != null && data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8
				&& (data[2] & 0xFF) == 0xFF) {
			return "jpg";
		}
		return "png";
	}

	// ==================== SD WebUI 调用 ====================

	/**
	 * 调用 WD1.4 反推图片提示词。
	 *
	 * @param image    图片内容（Base64，可带 {@code data:} 前缀）
	 * @param endpoint SD WebUI 端点
	 * @return 提示词 → 可能性，按可能性从大到小排列
	 * @throws IOException 服务异常
	 */
	public static JsonObject interrogate(String image, Endpoint endpoint) throws IOException {
		JsonObject payload = new JsonObject();
		payload.addProperty("image", image);
		payload.addProperty("model", TAGGER_MODEL);
		payload.addProperty("threshold", 0.2);

		JsonObject jsonResponse = endpoint.request("/tagger/v1/interrogate", payload.toString());
		try {
			// 反推模型占显存，拿到结果后立刻让 SD WebUI 卸载它。
			// 这个接口返回的是纯文本（例如 "Successfully unload 1 model(s)"），不能按 JSON 解析；
			// 卸载失败也不能影响已经拿到的反推结果。
			endpoint.request("/tagger/v1/unload-interrogators", "");
		} catch (IOException | RuntimeException ex) {
			System.err.println("[SDXL] 卸载反推模型失败：" + ex);
		}
		return jsonResponse.get("caption").getAsJsonObject().get("tag").getAsJsonObject();
	}

	/**
	 * 文生图。
	 *
	 * @param prompt         正面提示词
	 * @param negativePrompt 负面提示词
	 * @param steps          采样步数
	 * @param width          宽度
	 * @param height         高度
	 * @param endpoint       SD WebUI 端点
	 * @return 第一张图片的字节内容
	 * @throws IOException 服务异常
	 */
	public static byte[] generateImage(String prompt, String negativePrompt, int steps, int width, int height,
			Endpoint endpoint) throws IOException {
		JsonObject payload = new JsonObject();
		payload.addProperty("prompt", prompt);
		payload.addProperty("negative_prompt", negativePrompt);
		payload.addProperty("steps", steps);
		payload.addProperty("width", width);
		payload.addProperty("height", height);
		payload.addProperty("restore_faces", false);
		payload.addProperty("sampler_name", "Euler a");
		payload.addProperty("scheduler", "Karras");
		JsonObject jsonResponse = endpoint.request("/sdapi/v1/txt2img", payload.toString());
		return getFirstImage(jsonResponse.get("images").getAsJsonArray());
	}

	/**
	 * 图生图。
	 *
	 * @param baseImg        参考图（Base64）
	 * @param prompt         正面提示词
	 * @param negativePrompt 负面提示词
	 * @param steps          采样步数
	 * @param width          宽度
	 * @param height         高度
	 * @param denoising      重绘幅度
	 * @param endpoint       SD WebUI 端点
	 * @return 第一张图片的字节内容
	 * @throws IOException 服务异常
	 */
	public static byte[] generateImage2Image(String baseImg, String prompt, String negativePrompt, int steps,
			int width, int height, float denoising, Endpoint endpoint) throws IOException {
		JsonObject payload = new JsonObject();
		payload.add("init_images", JsonBuilder.array().add(baseImg).end());
		payload.addProperty("prompt", prompt);
		payload.addProperty("negative_prompt", negativePrompt);
		payload.addProperty("steps", steps);
		payload.addProperty("width", width);
		payload.addProperty("height", height);
		payload.addProperty("restore_faces", false);
		payload.addProperty("sampler_name", "DPM++ 2M");
		payload.addProperty("scheduler", "Karras");

		payload.addProperty("resize_mode", 0);
		payload.addProperty("denoising_strength", denoising);
		JsonObject jsonResponse = endpoint.request("/sdapi/v1/img2img", payload.toString());
		return getFirstImage(jsonResponse.get("images").getAsJsonArray());
	}

	/**
	 * 分区图生图（Regional Prompter）。
	 *
	 * @param baseImg        参考图（Base64）
	 * @param prompt         正面提示词（分区提示词用 {@code BREAK} 分隔）
	 * @param negativePrompt 负面提示词
	 * @param steps          采样步数
	 * @param width          宽度
	 * @param height         高度
	 * @param isRow          true 表示上下排布，false 表示左右排布
	 * @param ratio          区域比例（{@code x,y}）
	 * @param denoising      重绘幅度
	 * @param endpoint       SD WebUI 端点
	 * @return 第一张图片的字节内容
	 * @throws IOException 服务异常
	 */
	public static byte[] generateRegionalImage2Image(String baseImg, String prompt, String negativePrompt, int steps,
			int width, int height, boolean isRow, String ratio, float denoising, Endpoint endpoint)
			throws IOException {
		JsonObject payload = new JsonObject();
		payload.add("init_images", JsonBuilder.array().add(baseImg).end());
		payload.addProperty("prompt", prompt);
		payload.addProperty("negative_prompt", negativePrompt);
		payload.addProperty("steps", steps);
		payload.addProperty("width", width);
		payload.addProperty("height", height);
		payload.addProperty("restore_faces", false);
		payload.addProperty("sampler_name", "DPM++ 2M");
		payload.addProperty("scheduler", "Karras");
		payload.add("alwayson_scripts", regionalArgs(isRow, ratio));

		payload.addProperty("resize_mode", 0);
		payload.addProperty("denoising_strength", denoising);
		JsonObject jsonResponse = endpoint.request("/sdapi/v1/img2img", payload.toString());
		return getFirstImage(jsonResponse.get("images").getAsJsonArray());
	}

	/**
	 * 分区文生图（Regional Prompter）。
	 *
	 * @param prompt         正面提示词（分区提示词用 {@code BREAK} 分隔）
	 * @param negativePrompt 负面提示词
	 * @param steps          采样步数
	 * @param width          宽度
	 * @param height         高度
	 * @param isRow          true 表示上下排布，false 表示左右排布
	 * @param ratio          区域比例（{@code x,y}）
	 * @param endpoint       SD WebUI 端点
	 * @return 第一张图片的字节内容
	 * @throws IOException 服务异常
	 */
	public static byte[] generateRegionalImage(String prompt, String negativePrompt, int steps, int width, int height,
			boolean isRow, String ratio, Endpoint endpoint) throws IOException {
		JsonObject payload = new JsonObject();
		payload.addProperty("prompt", prompt);
		payload.addProperty("negative_prompt", negativePrompt);
		payload.addProperty("steps", steps);
		payload.addProperty("width", width);
		payload.addProperty("height", height);
		payload.addProperty("restore_faces", false);
		payload.addProperty("sampler_name", "DPM++ 2M");
		payload.addProperty("scheduler", "Karras");
		payload.add("alwayson_scripts", regionalArgs(isRow, ratio));
		JsonObject jsonResponse = endpoint.request("/sdapi/v1/txt2img", payload.toString());
		return getFirstImage(jsonResponse.get("images").getAsJsonArray());
	}

	/**
	 * Regional Prompter 的 {@code alwayson_scripts} 参数块，分区文生图与分区图生图共用。
	 *
	 * @param isRow true 表示上下排布，false 表示左右排布
	 * @param ratio 区域比例（{@code x,y}）
	 * @return {@code alwayson_scripts} 对象
	 */
	private static JsonObject regionalArgs(boolean isRow, String ratio) {
		return JsonBuilder.object().object("Regional Prompter").array("args")
				.add(true)
				.add(false)
				.add("Matrix")
				.add(isRow ? "Rows" : "Columns")
				.add("Mask")
				.add("Prompt")
				.add(ratio)
				.add("0.2")
				.add(false)
				.add(true)
				.add(false)
				.add("Attention")
				.array().add(false).end()
				.add("0")
				.add("0")
				.add("0.4")
				.add(JsonNull.INSTANCE)
				.add("0")
				.add("0")
				.add(false).end().end().end();
	}

	/**
	 * 取响应里的第一张图片。
	 *
	 * @param images Base64 图片数组
	 * @return 图片字节内容；没有可用图片时返回 null
	 */
	private static byte[] getFirstImage(JsonArray images) {
		for (int i = 0; i < images.size(); i++) {
			try {
				String base64Image = images.get(i).getAsString();

				if (base64Image.contains(",")) {
					base64Image = base64Image.split(",", 2)[1];
				}
				byte[] imageBytes = Base64.getDecoder().decode(base64Image);
				return imageBytes;
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		return null;
	}
}
