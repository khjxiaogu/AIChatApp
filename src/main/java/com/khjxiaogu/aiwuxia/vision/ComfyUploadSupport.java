package com.khjxiaogu.aiwuxia.vision;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.objectstorage.LocalStorage;
import com.khjxiaogu.aiwuxia.utils.ContentHash;

/**
 * ComfyUI 输入文件上传缓存。
 * <p>
 * H3 / Qwen 每次生成都要把首帧、参考图或视频上传到 ComfyUI 的 {@code input} 目录。文件内容相同的
 * 请求重复上传同一份字节没有意义：这里按“ComfyUI 地址 + 子目录 + 内容键”记住已经上传过的文件名，
 * 下一次先做一次很轻的 {@code /view} 存在性检查，文件还在就直接复用，不再上传。
 * </p>
 * <p>
 * 上传文件名统一成内容键 + 扩展名（服务端传进来的文件名只用来决定扩展名），因此同一内容在
 * ComfyUI 里始终是同一个文件；ComfyUI 重启/清空 input 目录后存在性检查会失败，自动重新上传。
 * 映射本身只在内存里，不落盘：重启后每个内容最多重新上传一次，随后继续复用。
 * </p>
 */
public final class ComfyUploadSupport {

	/** 已确认可用的上传结果：key = ComfyUI 地址 + 子目录 + 内容键。 */
	private static final Map<String, String> UPLOADED = new ConcurrentHashMap<>();

	private ComfyUploadSupport() {
	}

	/**
	 * 确保输入文件已经在 ComfyUI 的 input 目录里。
	 *
	 * @param baseUrl           ComfyUI 地址（不带末尾斜杠）
	 * @param subfolder         上传子目录（例如 {@code h3} / {@code qwen}）
	 * @param data              文件内容
	 * @param suggestedFilename 服务端建议的文件名，只用来推断扩展名
	 * @param boundaryPrefix    multipart 边界前缀
	 * @return 节点里应填写的文件名（可能带子目录前缀）
	 * @throws IOException 上传失败
	 */
	public static String ensureUploaded(String baseUrl, String subfolder, byte[] data, String suggestedFilename,
			String boundaryPrefix) throws IOException {
		if (data == null || data.length == 0) {
			throw new IllegalArgumentException("参考图不能为空");
		}
		String folder = subfolder == null ? "" : subfolder.trim();
		String key = ContentHash.keyOf(data);
		String scope = baseUrl + "|" + folder + "|" + key;
		String known = UPLOADED.get(scope);
		if (known != null) {
			if (exists(baseUrl, known)) {
				System.out.println("[ComfyUpload] 命中已上传缓存：" + known + "（" + data.length + "B）");
				return known;
			}
			System.out.println("[ComfyUpload] 缓存的输入文件已不在 ComfyUI，重新上传：" + known);
		}
		String name = key + extensionOf(suggestedFilename, data);
		String target = folder.isEmpty() ? name : folder + "/" + name;
		if (exists(baseUrl, target)) {
			UPLOADED.put(scope, target);
			System.out.println("[ComfyUpload] 命中已上传缓存：ComfyUI 已有 " + target + "（" + data.length + "B）");
			return target;
		}
		System.out.println("[ComfyUpload] 未命中已上传缓存，开始上传：" + target + "（" + data.length + "B）");
		String uploaded = upload(baseUrl, folder, name, data, boundaryPrefix);
		UPLOADED.put(scope, uploaded);
		System.out.println("[ComfyUpload] 已上传输入文件：" + uploaded + "（" + data.length + "B）");
		return uploaded;
	}

	/** 推断扩展名：优先用服务端给的文件名，其次按内容魔数。 */
	private static String extensionOf(String suggestedFilename, byte[] data) {
		if (suggestedFilename != null) {
			String name = new File(suggestedFilename.trim()).getName();
			int dot = name.lastIndexOf('.');
			if (dot >= 0 && dot < name.length() - 1) {
				String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
				if (ext.matches("[a-z0-9]{1,5}")) {
					return "." + ext;
				}
			}
		}
		String mime = LocalStorage.detectMimeType(data, null);
		switch (mime) {
			case "image/png":
				return ".png";
			case "image/jpeg":
				return ".jpg";
			case "image/gif":
				return ".gif";
			case "image/webp":
				return ".webp";
			case "video/mp4":
				return ".mp4";
			case "video/webm":
				return ".webm";
			case "audio/mpeg":
				return ".mp3";
			case "audio/wav":
				return ".wav";
			case "audio/ogg":
				return ".ogg";
			default:
				return ".bin";
		}
	}

	/** 轻量检查 ComfyUI input 目录里是否已有该文件（只读响应码，不读取内容）。 */
	private static boolean exists(String baseUrl, String uploadedName) {
		String name = uploadedName;
		String subfolder = "";
		int slash = name.lastIndexOf('/');
		if (slash >= 0) {
			subfolder = name.substring(0, slash);
			name = name.substring(slash + 1);
		}
		try {
			URL url = new URL(baseUrl + "/view?filename=" + encode(name) + "&subfolder=" + encode(subfolder)
					+ "&type=input");
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(5000);
			connection.setReadTimeout(5000);
			// 只需要知道文件在不在：拿响应码后立刻断开，不把整个视频读回本地
			connection.setRequestProperty("Range", "bytes=0-0");
			int code = connection.getResponseCode();
			connection.disconnect();
			return code == 200 || code == 206;
		} catch (IOException ex) {
			return false;
		}
	}

	/** 执行一次 multipart 上传，返回 ComfyUI 记录的文件名。 */
	private static String upload(String baseUrl, String subfolder, String filename, byte[] data,
			String boundaryPrefix) throws IOException {
		String boundary = boundaryPrefix + UUID.randomUUID().toString().replace("-", "");
		HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/upload/image").openConnection();
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		connection.setDoInput(true);
		connection.setConnectTimeout(30000);
		connection.setReadTimeout(120000);
		connection.setRequestProperty("Accept", "application/json");
		connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
		try (OutputStream out = connection.getOutputStream()) {
			writeFormField(out, boundary, "subfolder", subfolder);
			writeFormField(out, boundary, "overwrite", "true");
			String header = "--" + boundary + "\r\n"
					+ "Content-Disposition: form-data; name=\"image\"; filename=\"" + filename + "\"\r\n"
					+ "Content-Type: application/octet-stream\r\n\r\n";
			out.write(header.getBytes(StandardCharsets.UTF_8));
			out.write(data);
			out.write("\r\n".getBytes(StandardCharsets.UTF_8));
			out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		}
		int code = connection.getResponseCode();
		String body = readBody(code >= 200 && code < 300 ? connection.getInputStream()
				: connection.getErrorStream());
		connection.disconnect();
		if (code < 200 || code >= 300) {
			throw new IOException("上传输入文件失败 HTTP " + code + "：" + body);
		}
		JsonObject json = parseJsonObject(body);
		String name = json.has("name") && !json.get("name").isJsonNull() ? json.get("name").getAsString() : "";
		String responseFolder = json.has("subfolder") && !json.get("subfolder").isJsonNull()
				? json.get("subfolder").getAsString() : "";
		if (name.isEmpty()) {
			throw new IOException("上传输入文件失败：响应缺少 name");
		}
		return responseFolder.isEmpty() ? name : responseFolder + "/" + name;
	}

	private static void writeFormField(OutputStream out, String boundary, String name, String value)
			throws IOException {
		String field = "--" + boundary + "\r\n" + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
				+ value + "\r\n";
		out.write(field.getBytes(StandardCharsets.UTF_8));
	}

	private static String readBody(InputStream in) throws IOException {
		if (in == null) {
			return "";
		}
		try (InputStream input = in) {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				out.write(buffer, 0, read);
			}
			return out.toString(StandardCharsets.UTF_8.name());
		}
	}

	private static JsonObject parseJsonObject(String body) throws IOException {
		try {
			return JsonParser.parseString(body).getAsJsonObject();
		} catch (RuntimeException ex) {
			throw new IOException("上传输入文件的响应无法解析：" + body, ex);
		}
	}

	private static String encode(String value) {
		try {
			return URLEncoder.encode(value, "UTF-8");
		} catch (java.io.UnsupportedEncodingException ex) {
			return value;
		}
	}
}
