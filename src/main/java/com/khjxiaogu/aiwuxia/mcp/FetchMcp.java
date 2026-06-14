package com.khjxiaogu.aiwuxia.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.objectstorage.TOSUsage;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.MCPTools;

public class FetchMcp {
	public static MCPTools create(AISession state,ObjectStorageProvider tos) {
		MCPTools tools=new MCPTools();
		tools.register(new ToolData.Builder("web_view", "获取链接指定的内容，只支持ftp/http/https链接").putParam("url", "网页链接")
				.tool((data) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						if (jo.has("url")) {
							String url = jo.get("url").getAsString();
							if (url.startsWith("ftp") || url.startsWith("http"))
								return fetch(state,url,tos);

						}
					} catch (Throwable err) {
						err.printStackTrace();
					}
					return "参数格式错误";
				}).build());
		tools.register(new ToolData.Builder("moegirl_search", "搜索萌娘百科，如果有不知道的人物、角色或作品在这搜索。").putParam("keyword", "关键词").tool((data) -> {
			try {
				JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
				if (jo.has("keyword")) {
					String keyword = jo.get("keyword").getAsString();
					return fetch(state,"https://zh.moegirl.org.cn/index.php?search="
							+ URLEncoder.encode(keyword, StandardCharsets.UTF_8)
							+ "&title=Special%3A%E6%90%9C%E7%B4%A2",tos);

				}
			} catch (Throwable err) {
				err.printStackTrace();
			}
			return "参数格式错误";
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
	public static String fetch(AISession state,String urlStr,ObjectStorageProvider tos) {
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

				state.addUsage(new TOSUsage(imageData.length));
				return "图片id："+tos.uploadIfNotExists(imageData,state::addUsage);
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
