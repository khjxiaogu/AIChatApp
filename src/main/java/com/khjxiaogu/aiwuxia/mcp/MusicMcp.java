package com.khjxiaogu.aiwuxia.mcp;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.MCPTools;
import com.khjxiaogu.aiwuxia.voice.LocalVoiceModel;
import com.khjxiaogu.aiwuxia.voice.VoiceGenerationResult;

import kotlin.text.Charsets;

public class MusicMcp {
	public static MCPTools create(File localFolder,String voiceId,String name,Consumer<File> sendMusic,Consumer<String> genMusic,Function<String,String> sendMusicUrl) {
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
					;
					sendMusic.accept(new File(localFolder,fn));
					return "发送成功";
				}).build());
		String svsHost=System.getProperty("svsHost");
		if(svsHost!=null) {
			String regex = "[\\\\/:*?\"<>|\\x00-\\x1F]";
			
			tools.register(new ToolData.Builder("download_music", "从音乐平台搜索歌曲列表，可用于歌唱。")
					.putParam("keyword", "歌曲搜索关键词，需要包含歌名，歌手等信息。")
					.tool((data) -> {
						
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String keyword=jo.get("keyword").getAsString();
						try {
							JsonArray ss =HttpRequestBuilder.create("u.y.qq.com")
									.url("/cgi-bin/musicu.fcg")
									.defUA()
									.referer("https://y.qq.com")
									.post()
									.send(JsonBuilder.object()
											.object("music.search.SearchCgiService")
												.add("module", "music.search.SearchCgiService")
												.add("method", "DoSearchForQQMusicDesktop")
												.object("param")
													.add("query", keyword)
													.add("num_per_page", 5)
													.add("search_type", 0)
													.add("page_num", 1)
												.end()
											.end()
										.toString())
									.readJson()
									.getAsJsonObject()
									.get("music.search.SearchCgiService").getAsJsonObject()
									.get("data").getAsJsonObject()
									.get("body").getAsJsonObject()
									.get("song").getAsJsonObject()
									.get("list").getAsJsonArray();
									StringBuilder sb=new StringBuilder("搜索结果：\n");
									for(int i=0;i<ss.size();i++) {
										JsonObject song = ss.get(i).getAsJsonObject();
										String mid = song.get("mid").getAsString();
										String desc;
										try {
											JsonArray singers = song.get("singer").getAsJsonArray();
											StringBuilder sgs = new StringBuilder();
											for (JsonElement je : singers) {
												sgs.append(je.getAsJsonObject().get("name").getAsString());
												sgs.append(";");
											}
											sgs.deleteCharAt(sgs.length() - 1);
											desc = sgs.toString();
										} catch (Exception e) {
											desc = song.get("album").getAsJsonObject().get("name").getAsString();
										}
										sb.append(" 歌名：").append(song.get("title").getAsString()).append(" 歌手：").append(desc).append(" 歌曲id：").append(mid);
									}
									
									return sb.toString();
						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
							return "没有搜索到音乐。";
						}
						
					}).build());
			tools.register(new ToolData.Builder("sing_music", "从音乐平台下载歌曲，并生成当前角色歌唱版本，放入到歌曲列表。")
					.putParam("music_id", "歌曲id")
					.tool((data) -> {
						
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String mid=jo.get("music_id").getAsString();
						String desc;
						JsonObject song;
						try {
							song = querySongDetail(mid);
						
						JsonArray singers = song.get("singer").getAsJsonArray();
						StringBuilder sgs = new StringBuilder();
						for (JsonElement je : singers) {
							sgs.append(je.getAsJsonObject().get("name").getAsString());
							sgs.append(";");
						}
						sgs.deleteCharAt(sgs.length() - 1);
						desc = sgs.toString();
						}catch(Exception ex) {
							ex.printStackTrace();
							return "音乐ID错误";
						}
						String url=queryRealUrl(mid);
						if(!FileUtil.isExistent(url))
							return "该音乐无法下载";
						String fnout=(song.get("title").getAsString()+"（"+desc+"）").replaceAll(regex, "");
						File fileout=new File(localFolder,fnout+"（"+name+"）.mp3");
						if(fileout.exists()) {
							return "歌曲已存在："+fileout.getName();
						}
						try {
							
					        String reqid=UUID.randomUUID().toString();
					        CompletableFuture<VoiceGenerationResult> cf=LocalVoiceModel.registerTask(reqid);
					        CompletableFuture.runAsync(()->{
								try {
									System.out.println(HttpRequestBuilder.create("http",svsHost).url("?url="+URLEncoder.encode(url, Charsets.UTF_8)+"&name="+URLEncoder.encode(fnout, Charsets.UTF_8)+"&voice="+voiceId+"&reqid="+reqid).get().readString());
								} catch (IOException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
					        });
					        cf.thenApply(vg->{
					        	try {
						        	
						        	FileUtil.transfer(vg.audioData, fileout);
						        	return fileout.getName();
					        	} catch (IOException e) {
									e.printStackTrace();
									throw new RuntimeException(e);
								}
					        	
					        }).thenAccept(genMusic);
						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
							return "处理失败";
						}
						return "已提交任务，请等待。";
					}).build());
			tools.register(new ToolData.Builder("send_net_music", "直接发送音乐平台歌曲。")
					.putParam("music_id", "歌曲id")
					.tool((data) -> {
						try {
							System.out.println(data);
							JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
							String mid=jo.get("music_id").getAsString();
							String url=queryRealUrl(mid);
							if(!FileUtil.isExistent(url))
								return "该音乐无法下载";
							return sendMusicUrl.apply(url);
						} catch (Exception e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
							return "音乐id错误";
						}
						
						
					}).build());
		}
		return tools;
	}

	public static JsonObject querySongDetail(String songmid) {
		try {
			JsonObject out =HttpRequestBuilder.create("u.y.qq.com")
			.url("/cgi-bin/musicu.fcg?format=json&data=%7B%22req_0%22%3A%7B%22module%22%3A%22music.pf_song_detail_svr%22%2C%22method%22%3A%22get_song_detail_yqq%22%2C%22param%22%3A%7B%22song_mid%22%3A%22")
			.url(songmid)
			.url("%22%7D%7D%2C%22comm%22%3A%7B%22uin%22%3A%221905222%22%2C%22format%22%3A%22json%22%2C%22ct%22%3A24%2C%22cv%22%3A0%7D%7D")
			.defUA()
			.get()
			.readJson();
			if (out.get("code").getAsInt() != 0) {
				return null;
			}
			return out.get("req_0").getAsJsonObject().get("data").getAsJsonObject().get("track_info").getAsJsonObject();
		} catch (Throwable e) {
			e.printStackTrace();
		}
		return null;
	}

	public static String queryRealUrl(String songmid) {
		try {
			JsonObject out =HttpRequestBuilder.create("u.y.qq.com")
			.url("/cgi-bin/musicu.fcg?format=json&data=%7B%22req_0%22%3A%7B%22module%22%3A%22vkey.GetVkeyServer%22%2C%22method%22%3A%22CgiGetVkey%22%2C%22param%22%3A%7B%22guid%22%3A%22358840384%22%2C%22songmid%22%3A%5B%22")
			.url(songmid)
			.url(
					"%22%5D%2C%22songtype%22%3A%5B0%5D%2C%22uin%22%3A%221443481947%22%2C%22loginflag%22%3A1%2C%22platform%22%3A%2220%22%7D%7D%2C%22comm%22%3A%7B%22uin%22%3A%2218585073516%22%2C%22format%22%3A%22json%22%2C%22ct%22%3A24%2C%22cv%22%3A0%7D%7D")
			.defUA()
			.get()
			.readJson();
			if (out.get("code").getAsInt() != 0) {
				return null;
			}
			StringBuilder sb = new StringBuilder(out.get("req_0").getAsJsonObject().get("data").getAsJsonObject()
					.get("sip").getAsJsonArray().get(0).getAsString());

			sb.append(out.get("req_0").getAsJsonObject().get("data").getAsJsonObject().get("midurlinfo")
					.getAsJsonArray().get(0).getAsJsonObject().get("purl").getAsString());
			return sb.toString();
		} catch (Throwable e) {
			e.printStackTrace();
		}
		return null;
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
