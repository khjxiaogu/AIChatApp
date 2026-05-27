package com.khjxiaogu.aiwuxia.mcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.MCPTools;

public class MusicMcp {
	public static MCPTools create(File localFolder,Consumer<File> sendMusic) {
		MCPTools tools=new MCPTools();
		tools.register(new ToolData.Builder("list_music", "获取本地歌曲列表，可以用于发送进行歌唱或者播放")
				.tool((data) -> {
					StringBuilder files=new StringBuilder();
					for(String fn:localFolder.list()) {
						files.append(fn).append("\n");
					};
					return files.toString();
				}).build());
		tools.register(new ToolData.Builder("send_music", "发送歌曲文件")
				.putParam("file_name", "要发送的文件名，包含扩展名")
				.tool((data) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					String fn=jo.get("file_name").getAsString();
					if(!new File(localFolder,fn).exists())
						return "文件不存在，请查询文件列表并检查输入";
					sendMusic.accept(new File(localFolder,fn));
					return "发送成功";
				}).build());
		return tools;
	}

	/**
	 * 从输入流中读取文本，使用指定字符集（若未指定则用 UTF-8）
	 */
	private static String readText(InputStream inputStream, String charsetName) throws IOException {
		Charset charset = StandardCharsets.UTF_8;
		if (charsetName != null && Charset.isSupported(charsetName)) {
			charset = Charset.forName(charsetName);
		}
		try (InputStreamReader reader = new InputStreamReader(inputStream, charset);
				BufferedReader bufferedReader = new BufferedReader(reader)) {
			StringBuilder sb = new StringBuilder();
			char[] buffer = new char[8192];
			int len;
			while ((len = bufferedReader.read(buffer)) != -1) {
				sb.append(buffer, 0, len);
			}
			return sb.toString();
		}
	}

	/**
	 * 根据URL获取内容
	 * 
	 * @param urlStr 目标URL
	 * @return 文本内容、图片识别结果或错误提示
	 */
	public static String fetch(String urlStr,ObjectStorageProvider tos) {
		HttpURLConnection connection = null;
		try {
			URL url = new URL(urlStr);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(5000); // 连接超时5秒
			connection.setReadTimeout(10000); // 读取超时10秒
			connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; MyFetcher/1.0)");
			connection.setInstanceFollowRedirects(true);

			int responseCode = connection.getResponseCode();
			if (responseCode != HttpURLConnection.HTTP_OK) {
				return "无法识别的内容"; // 非200状态码直接视为无法识别
			}

			String contentType = connection.getContentType();
			String mimeType = extractMimeType(contentType);

			// 文本类型：text/*, application/json, application/xml, text/xml,
			// application/javascript 等
			if (isTextType(mimeType)) {
				String charset = extractCharset(contentType);
				String text = readText(connection.getInputStream(), charset);
				return text;
			}
			// 图片类型：只处理 JPEG 和 PNG
			else if (isImageType(mimeType)) {
				byte[] imageData = FileUtil.readAll(connection.getInputStream());
				return "图片id："+tos.uploadIfNotExists(imageData);
			}
			// 其他不支持的类型，不下载正文
			else {
				return "无法识别的内容";
			}
		} catch (Exception e) {
			// 发生任何异常（网络错误、协议异常等）都返回无法识别
			return "无法识别的内容";
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/**
	 * 提取 MIME 类型（去除参数部分）
	 * 
	 * @param contentType 原始 Content-Type 头，例如 "text/html; charset=utf-8"
	 * @return MIME 类型，如 "text/html"
	 */
	private static String extractMimeType(String contentType) {
		if (contentType == null)
			return "";
		int semi = contentType.indexOf(';');
		if (semi == -1) {
			return contentType.trim().toLowerCase();
		} else {
			return contentType.substring(0, semi).trim().toLowerCase();
		}
	}

	/**
	 * 从 Content-Type 中提取 charset 参数
	 * 
	 * @param contentType 原始 Content-Type 头
	 * @return 字符集名称，未找到则返回 null
	 */
	private static String extractCharset(String contentType) {
		if (contentType == null)
			return null;
		Pattern pattern = Pattern.compile("charset\\s*=\\s*([^\\s;]+)", Pattern.CASE_INSENSITIVE);
		Matcher matcher = pattern.matcher(contentType);
		if (matcher.find()) {
			return matcher.group(1);
		}
		return null;
	}

	/**
	 * 判断是否为文本类型（html, json, text, xml, javascript 等）
	 */
	private static boolean isTextType(String mimeType) {
		if (mimeType.isEmpty())
			return false;
		if (mimeType.startsWith("text/"))
			return true;
		return "application/json".equals(mimeType) || "application/xml".equals(mimeType) || "text/xml".equals(mimeType)
				|| "application/javascript".equals(mimeType) || "application/x-javascript".equals(mimeType);
	}

	/**
	 * 判断是否为指定的图片类型（jpeg 和 png）
	 */
	private static boolean isImageType(String mimeType) {
		return "image/jpeg".equals(mimeType) || "image/jpg".equals(mimeType) || "image/png".equals(mimeType);
	}

}
