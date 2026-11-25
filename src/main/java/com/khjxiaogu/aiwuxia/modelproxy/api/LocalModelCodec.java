package com.khjxiaogu.aiwuxia.modelproxy.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.modelproxy.ModelType;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;

/**
 * 本地模型协议的唯一编解码器。
 * <p>
 * 服务端只调用 {@link #encode(LocalModelCall)}，客户端只调用 {@link #decode(JsonObject)}；
 * 各处理器不再直接读写 {@code JsonObject}。报文形态：
 * </p>
 *
 * <pre>
 * {"api":2,"type":"sdxl","reqid":"...",
 *  "attachments":[{"id":"a1","kind":"image","format":"png","size":123}],
 *  "params":{"action":"txt2img","prompt":"...","steps":24,...}}
 * </pre>
 * <p>
 * 没有 {@code api} 或 {@code api<2} 的报文按旧格式解析（字段平铺），用于兼容旧客户端；
 * 旧格式的 {@code data}/{@code path} 等 SDXL 内联字段不再使用。
 * </p>
 */
public final class LocalModelCodec {

	private LocalModelCodec() {
	}

	/**
	 * 把调用编码成报文。
	 *
	 * @param call 调用
	 * @return 报文字符串
	 */
	public static String encode(LocalModelCall<?> call) {
		return encodeJson(call).toString();
	}

	/**
	 * 把调用编码成 JSON 对象。
	 *
	 * @param call 调用
	 * @return 报文对象
	 */
	public static JsonObject encodeJson(LocalModelCall<?> call) {
		if (call == null || call.type == null) {
			throw new LocalModelApiException("本地模型调用缺少类型");
		}
		JsonObject root = new JsonObject();
		root.addProperty("api", LocalModelCall.API_VERSION);
		root.addProperty("type", call.type.toString());
		root.addProperty("reqid", call.reqid);
		if (!call.attachments.isEmpty()) {
			JsonArray attachments = new JsonArray();
			for (AttachmentRef ref : call.attachments) {
				JsonObject item = new JsonObject();
				item.addProperty("id", ref.id);
				item.addProperty("kind", ref.kind);
				item.addProperty("format", ref.format);
				item.addProperty("filename", ref.filename);
				item.addProperty("size", ref.size);
				// 内容键可选：接收端据此复用磁盘缓存，跳过附件拉取；旧客户端会忽略该字段
				if (ref.key != null && !ref.key.isEmpty()) {
					item.addProperty("key", ref.key);
				}
				attachments.add(item);
			}
			root.add("attachments", attachments);
		}
		root.add("params", encodeParams(call));
		return root;
	}

	/**
	 * 把调用压回旧格式（字段平铺），供尚未迁移到统一对象的处理器使用。
	 *
	 * @param call 调用
	 * @return 旧格式报文
	 */
	public static JsonObject encodeLegacyJson(LocalModelCall<?> call) {
		if (call == null || call.type == null) {
			throw new LocalModelApiException("本地模型调用缺少类型");
		}
		JsonObject root = new JsonObject();
		root.addProperty("type", call.type.toString());
		root.addProperty("reqid", call.reqid);
		for (Map.Entry<String, JsonElement> entry : encodeParams(call).entrySet()) {
			root.add(entry.getKey(), entry.getValue());
		}
		return root;
	}

	/**
	 * 解析报文；兼容旧格式。
	 *
	 * @param root 报文
	 * @return 调用
	 * @throws LocalModelApiException 缺少类型、参数未知
	 */
	public static LocalModelCall<?> decode(JsonObject root) {
		if (root == null) {
			throw new LocalModelApiException("本地模型请求为空");
		}
		ModelType type = ModelType.fromString(JsonConfig.getString(root, "type"));
		if (type == null) {
			throw new LocalModelApiException("本地模型请求缺少合法的 type");
		}
		String reqid = JsonConfig.getStringOrEmpty(root, "reqid");
		int api = JsonConfig.getInt(root, "api", 1);
		JsonObject params;
		if (api >= LocalModelCall.API_VERSION && root.has("params") && root.get("params").isJsonObject()) {
			params = root.getAsJsonObject("params");
		} else {
			// 旧格式：参数字段平铺在根对象上
			params = root;
		}
		List<AttachmentRef> attachments = decodeAttachments(root);
		Object decoded = decodeParams(type, params);
		return new LocalModelCall<Object>(type, reqid, decoded, attachments);
	}

	private static List<AttachmentRef> decodeAttachments(JsonObject root) {
		List<AttachmentRef> list = new ArrayList<>();
		JsonArray array = JsonConfig.getArray(root, "attachments");
		if (array == null) {
			return list;
		}
		for (JsonElement element : array) {
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			JsonObject item = element.getAsJsonObject();
			String id = JsonConfig.getStringOrEmpty(item, "id");
			if (id.isEmpty()) {
				continue;
			}
			list.add(new AttachmentRef(id, JsonConfig.getString(item, "kind"), JsonConfig.getString(item, "format"),
					JsonConfig.getString(item, "filename"), JsonConfig.getLong(item, "size", -1L),
					JsonConfig.getString(item, "key")));
		}
		return list;
	}

	// ==================== 编码 ====================

	private static JsonObject encodeParams(LocalModelCall<?> call) {
		switch (call.type) {
			case SDXL:
				return encodeSdxl(call.requireParams(SdxlParams.class));
			case AUDIO:
				return encodeAudio(call.requireParams(AudioParams.class));
			case SVS:
				return encodeSvs(call.requireParams(SvsParams.class));
			case QWEN:
				return encodeQwen(call.requireParams(QwenParams.class));
			case H3:
				return encodeH3(call.requireParams(H3Params.class));
			default:
				throw new LocalModelApiException("未支持的本地模型类型：" + call.type);
		}
	}

	private static JsonObject encodeSdxl(SdxlParams params) {
		params.validate();
		JsonObject json = new JsonObject();
		json.addProperty("action", params.action.wireName);
		json.addProperty("prompt", params.prompt);
		json.addProperty("negative_prompt", params.negativePrompt);
		json.addProperty("steps", params.steps);
		json.addProperty("width", params.width);
		json.addProperty("height", params.height);
		if (params.image != null) {
			json.addProperty("image", params.image);
		}
		json.addProperty("denoising_strength", params.denoising);
		json.addProperty("is_row", params.isRow);
		json.addProperty("ratio", params.ratio);
		return json;
	}

	private static JsonObject encodeAudio(AudioParams params) {
		JsonObject json = new JsonObject();
		json.addProperty("chara", params.chara);
		JsonArray content = new JsonArray();
		for (Map<String, String> segment : params.content) {
			JsonObject item = new JsonObject();
			for (Map.Entry<String, String> entry : segment.entrySet()) {
				item.addProperty(entry.getKey(), entry.getValue());
			}
			content.add(item);
		}
		json.add("content", content);
		return json;
	}

	private static JsonObject encodeSvs(SvsParams params) {
		params.validate();
		JsonObject json = new JsonObject();
		json.addProperty("url", params.url);
		if (params.name != null && !params.name.isEmpty()) {
			json.addProperty("name", params.name);
		}
		params.options.applyTo(json);
		return json;
	}

	private static JsonObject encodeQwen(QwenParams params) {
		params.validate();
		JsonObject json = new JsonObject();
		json.addProperty("action", params.action);
		json.addProperty("prompt", params.prompt);
		json.addProperty("negative", params.negative);
		json.addProperty("width", params.width);
		json.addProperty("height", params.height);
		json.addProperty("steps", params.steps);
		json.addProperty("cfg", params.cfg);
		json.addProperty("seed", params.seed);
		json.addProperty("fast", params.fast);
		JsonArray images = new JsonArray();
		for (String image : params.images) {
			images.add(image);
		}
		json.add("images", images);
		return json;
	}

	private static JsonObject encodeH3(H3Params params) {
		JsonObject json = new JsonObject();
		json.addProperty("action", params.action);
		json.addProperty("prompt", params.prompt);
		json.addProperty("negative", params.negative);
		json.addProperty("model", params.model);
		json.addProperty("steps", params.steps);
		json.addProperty("sparse", params.sparse);
		json.addProperty("width", params.width);
		json.addProperty("height", params.height);
		json.addProperty("frames", params.frames);
		json.addProperty("seed", params.seed);
		json.addProperty("seconds", params.seconds);
		putIfPresent(json, "first_frame", params.firstFrame);
		putIfPresent(json, "last_frame", params.lastFrame);
		putIfPresent(json, "first_video", params.firstVideo);
		putIfPresent(json, "last_video", params.lastVideo);
		putIfPresent(json, "video", params.video);
		json.addProperty("frame", params.frame);
		json.addProperty("first_start", params.firstStart);
		json.addProperty("first_length", params.firstLength);
		json.addProperty("last_start", params.lastStart);
		json.addProperty("last_length", params.lastLength);
		json.addProperty("start", params.start);
		json.addProperty("length", params.length);
		return json;
	}

	private static void putIfPresent(JsonObject json, String key, String value) {
		if (value != null && !value.isEmpty()) {
			json.addProperty(key, value);
		}
	}

	// ==================== 解码 ====================

	private static Object decodeParams(ModelType type, JsonObject params) {
		switch (type) {
			case SDXL:
				return decodeSdxl(params);
			case AUDIO:
				return decodeAudio(params);
			case SVS:
				return decodeSvs(params);
			case QWEN:
				return decodeQwen(params);
			case H3:
				return decodeH3(params);
			default:
				throw new LocalModelApiException("未支持的本地模型类型：" + type);
		}
	}

	private static SdxlParams decodeSdxl(JsonObject params) {
		return new SdxlParams(SdxlAction.of(JsonConfig.getTrimmed(params, "action")),
				JsonConfig.getStringOrEmpty(params, "prompt"), JsonConfig.getStringOrEmpty(params, "negative_prompt"),
				JsonConfig.getInt(params, "steps", 20), JsonConfig.getInt(params, "width", 1200),
				JsonConfig.getInt(params, "height", 1200), JsonConfig.getString(params, "image"),
				JsonConfig.getDouble(params, "denoising_strength", 1d), JsonConfig.getBool(params, "is_row", false),
				JsonConfig.getStringOrEmpty(params, "ratio"));
	}

	private static AudioParams decodeAudio(JsonObject params) {
		List<Map<String, String>> content = new ArrayList<>();
		JsonArray array = JsonConfig.getArray(params, "content");
		if (array != null) {
			for (JsonElement element : array) {
				Map<String, String> segment = new LinkedHashMap<>();
				if (element != null && element.isJsonObject()) {
					for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
						JsonElement value = entry.getValue();
						if (value != null && value.isJsonPrimitive()) {
							segment.put(entry.getKey(), value.getAsString());
						}
					}
				}
				content.add(segment);
			}
		}
		return new AudioParams(JsonConfig.getStringOrEmpty(params, "chara"), content);
	}

	private static SvsParams decodeSvs(JsonObject params) {
		SvsOptions options = new SvsOptions();
		options.pitch = JsonConfig.getInt(params, "pitch", 0);
		options.separate = JsonConfig.getBool(params, "separate", false);
		options.doSplit = JsonConfig.getBool(params, "do_split", true);
		options.timbre = JsonConfig.getString(params, "timbre");
		options.voice = JsonConfig.getString(params, "voice");
		options.filter = JsonConfig.getInt(params, "filter", SvsOptions.MIN_FILTER);
		options.filterRadius = JsonConfig.getInt(params, "filter_radius", 0);
		options.strength = JsonConfig.getDouble(params, "strength", SvsOptions.MAX_STRENGTH);
		options.protect = JsonConfig.getDouble(params, "protect", 0d);
		return new SvsParams(JsonConfig.getStringOrEmpty(params, "url"), JsonConfig.getString(params, "name"), options);
	}

	private static QwenParams decodeQwen(JsonObject params) {
		List<String> images = new ArrayList<>();
		JsonArray array = JsonConfig.getArray(params, "images");
		if (array != null) {
			for (JsonElement element : array) {
				if (element != null && element.isJsonPrimitive()) {
					images.add(element.getAsString());
				}
			}
		}
		return new QwenParams(JsonConfig.getTrimmed(params, "action"), JsonConfig.getStringOrEmpty(params, "prompt"),
				JsonConfig.getStringOrEmpty(params, "negative"), JsonConfig.getInt(params, "width", 1024),
				JsonConfig.getInt(params, "height", 1024), JsonConfig.getInt(params, "steps", 8),
				JsonConfig.getDouble(params, "cfg", 1d), JsonConfig.getLong(params, "seed", 0L),
				JsonConfig.getBool(params, "fast", false), images);
	}

	private static H3Params decodeH3(JsonObject params) {
		return new H3Params(JsonConfig.getTrimmed(params, "action"), JsonConfig.getStringOrEmpty(params, "prompt"),
				JsonConfig.getStringOrEmpty(params, "negative"), JsonConfig.getStringOrEmpty(params, "model"),
				JsonConfig.getInt(params, "steps", 0), JsonConfig.getBool(params, "sparse", true),
				JsonConfig.getInt(params, "width", 864), JsonConfig.getInt(params, "height", 480),
				JsonConfig.getInt(params, "frames", 0), JsonConfig.getLong(params, "seed", 0L),
				JsonConfig.getDouble(params, "seconds", 5d), JsonConfig.getString(params, "first_frame"),
				JsonConfig.getString(params, "last_frame"), JsonConfig.getString(params, "first_video"),
				JsonConfig.getString(params, "last_video"), JsonConfig.getString(params, "video"),
				JsonConfig.getInt(params, "frame", 0), JsonConfig.getInt(params, "first_start", 0),
				JsonConfig.getInt(params, "first_length", 0), JsonConfig.getInt(params, "last_start", 0),
				JsonConfig.getInt(params, "last_length", 0), JsonConfig.getDouble(params, "start", 0d),
				JsonConfig.getDouble(params, "length", 0d));
	}
}
