package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.AudioContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.voice.LocalModel;
import com.khjxiaogu.aiwuxia.voice.ModelGenerationResult;

import kotlin.text.Charsets;

public class MusicMcp implements ToolProvider {
	private static final String DEFAULT_BASE_URL = "http://127.0.0.1:8998/aichat";
	private static final String INVALID_FILENAME = "[\\\\/:*?\"<>|\\x00-\\x1F]";

	private final File localFolder;
	/** 合成后的乐曲输出目录：{@code music/output}。 */
	private final File outputFolder;
	/** 服务端公开音乐文件的地址前缀。 */
	private final String urlbase;
	private final String voiceId;
	private final String name;
	private final ResourceLock lock;
	private final ObjectStorageProvider tos;
	private final File svsVoicesConfig;

	public MusicMcp(File localFolder, String voiceId, String name, ResourceLock lock) {
		this(localFolder, voiceId, name, lock, null, null);
	}

	public MusicMcp(File localFolder, String voiceId, String name, ResourceLock lock, ObjectStorageProvider tos,
			File svsVoicesConfig) {
		this(localFolder, voiceId, name, lock, tos, svsVoicesConfig,
				System.getProperty("musicBaseUrl", DEFAULT_BASE_URL));
	}

	public MusicMcp(File localFolder, String voiceId, String name, ResourceLock lock, ObjectStorageProvider tos,
			File svsVoicesConfig, String urlbase) {
		this.localFolder = localFolder;
		this.outputFolder = new File(localFolder, "output");
		this.urlbase = stripTrailingSlash(urlbase == null || urlbase.trim().isEmpty() ? DEFAULT_BASE_URL : urlbase);
		this.voiceId = voiceId;
		this.name = name;
		this.lock = lock;
		this.tos = tos;
		this.svsVoicesConfig = svsVoicesConfig;
		localFolder.mkdirs();
		outputFolder.mkdirs();
	}

	private static String stripTrailingSlash(String value) {
		String out = value.trim();
		while (out.endsWith("/")) {
			out = out.substring(0, out.length() - 1);
		}
		return out;
	}

	@Override
	public Collection<ToolData> getTools() {
		List<ToolData> all = createInternalTools();
		List<ToolData> exposed = new ArrayList<>();
		for (ToolData tool : all) {
			// 主模型只保留发送本地歌曲；查找、查看已有歌曲、合成统一交给 find_music
			if ("send_music".equals(tool.name)) {
				exposed.add(tool);
			}
		}
		exposed.add(createAgentTool(all));
		return exposed;
	}

	/** 构造搜索、试听、合成等内部工具，供 find_music 子 agent 使用。 */
	private List<ToolData> createInternalTools() {
		List<ToolData> tools = new ArrayList<>();
		tools.add(new ToolData.Builder("list_music",
				"获取本地歌曲列表：原曲目录（用于合成）和合成输出目录（用于发送）。")
				.tool((data) -> {
					StringBuilder files = new StringBuilder("原曲：\n");
					appendFiles(files, localFolder);
					files.append("合成：\n");
					appendFiles(files, outputFolder);
					return files.toString();
				}).build());
		tools.add(new ToolData.Builder("search_local_music",
				"按关键词搜索原曲目录和合成目录中的音乐文件名，用于快速定位歌名、歌手或已有合成歌曲。")
				.putParam("keyword", "搜索关键词，可匹配文件名中的歌名、歌手等。", "关键词")
				.tool((data) -> {
					String keyword;
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						keyword = jo.has("keyword") && !jo.get("keyword").isJsonNull()
								? jo.get("keyword").getAsString().trim() : "";
					} catch (Throwable ex) {
						return "信息：参数格式错误，需要 keyword。";
					}
					if (keyword.isEmpty()) {
						return "信息：缺少搜索关键词 keyword。";
					}
					StringBuilder matches = new StringBuilder();
					appendMatches(matches, "原曲", localFolder, keyword);
					appendMatches(matches, "合成", outputFolder, keyword);
					if (matches.length() == 0) {
						return "信息：原曲和合成目录中没有找到包含“" + keyword + "”的音乐。";
					}
					return matches.toString();
				}).build());
		tools.add(new ToolData.Builder("send_music", "发送歌曲文件")
				.putParam("file_name", "要发送的文件名，包含扩展名；会自动在原曲和合成目录中查找", "文件名")
				.orderSensitive()
				.tool((data, output, manager) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					String fn = jo.get("file_name").getAsString();
					File file = resolveMusicFile(fn);
					if (file == null)
						return CompletableFuture.completedFuture(ToolResult.text("文件不存在，请查询文件列表并检查输入"));
					try {
						return CompletableFuture.completedFuture(ToolResult.of("音频已提交发送，文件名：" + fn,
								new AudioContent(FileUtil.toDataUrl(FileUtil.readAll(file)), fn)));
					} catch (IOException e) {
						e.printStackTrace();
						return CompletableFuture.completedFuture(ToolResult.text("发送失败"));
					}
				}).build());
		String svsHost = System.getProperty("svsHost");
		if (svsHost != null) {
			tools.add(new ToolData.Builder("search_music", "从音乐平台搜索歌曲列表。")
					.putParam("keyword", "歌曲搜索关键词，需要包含歌名，歌手等信息。", "歌曲关键词")
					.tool((data) -> {

						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String keyword = jo.get("keyword").getAsString();
						try {
							JsonArray ss = HttpRequestBuilder.create("u.y.qq.com")
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
							StringBuilder sb = new StringBuilder("搜索结果：\n");
							for (int i = 0; i < ss.size(); i++) {
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
								sb.append(" 歌名：").append(song.get("title").getAsString()).append(" 歌手：").append(desc)
										.append(" 歌曲id：").append(mid);
							}

							return sb.toString();
						} catch (Exception e) {
							e.printStackTrace();
							return "没有搜索到音乐。";
						}

					}).build());
			tools.add(new ToolData.Builder("download_platform_music",
					"把音乐平台的歌曲下载到原曲目录，供后续合成使用。先由 download_music 获取歌曲 id。")
					.putParam("music_id", "歌曲id", "歌曲ID")
					.tool((data) -> {
						try {
							JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
							String mid = jo.get("music_id").getAsString();
							JsonObject song = querySongDetail(mid);
							if (song == null) {
								return "信息：音乐ID错误或歌曲不存在。";
							}
							String desc = singersOf(song);
							String url = queryRealUrl(mid);
							if (!FileUtil.isExistent(url)) {
								return "信息：该音乐无法下载。";
							}
							String fn = safeFileName(song.get("title").getAsString() + "（" + desc + "）")
									+ extensionOf(url, ".mp3");
							File fileout = new File(localFolder, fn);
							if (!fileout.isFile()) {
								byte[] bytes = new HttpRequestBuilder(url).defUA().get().readBytes();
								FileUtil.transfer(bytes, fileout);
							}
							return "文件名：" + fileout.getName() + "\n信息：已下载到原曲目录。";
						} catch (Exception ex) {
							ex.printStackTrace();
							return "信息：下载音乐失败，" + ex.getMessage();
						}
					}).build());
			tools.add(new ToolData.Builder("download_url_music",
					"下载任意 http/https 音频链接到原曲目录，供后续合成使用。")
					.putParam("url", "音频链接，必须是 http/https。", "音频链接")
					.putParam("file_name", "可选保存文件名；留空则从 URL 推导。", "保存文件名")
					.tool((data) -> {
						try {
							JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
							String url = jo.has("url") && !jo.get("url").isJsonNull()
									? jo.get("url").getAsString().trim() : "";
							if (!isHttpUrl(url)) {
								return "信息：url 必须是 http/https 音频链接。";
							}
							String requested = jo.has("file_name") && !jo.get("file_name").isJsonNull()
									? jo.get("file_name").getAsString().trim() : "";
							String fn;
							if (!requested.isEmpty()) {
								fn = safeFileName(requested);
								if (extensionOf(fn, "").isEmpty()) {
									fn += extensionOf(url, ".mp3");
								}
							} else {
								fn = safeFileName(fileNameFromUrl(url)) + extensionOf(url, ".mp3");
							}
							File fileout = new File(localFolder, fn);
							if (!fileout.isFile()) {
								byte[] bytes = new HttpRequestBuilder(url).defUA().get().readBytes();
								FileUtil.transfer(bytes, fileout);
							}
							return "文件名：" + fileout.getName() + "\n信息：已下载到原曲目录。";
						} catch (Exception ex) {
							ex.printStackTrace();
							return "信息：下载链接失败，" + ex.getMessage();
						}
					}).build());
			tools.add(new ToolData.Builder("sing_local_music",
					"把原曲目录（或合成目录）中已有的音频转换为当前角色歌声，自动放入合成目录。")
					.putParam("file_name", "原曲/合成目录中的文件名，包含扩展名。", "文件名")
					.async()
					.tool((data, output, manager) -> {
						try {
							JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
							String fn = jo.get("file_name").getAsString().trim();
							File source = resolveMusicFile(fn);
							if (source == null) {
								return CompletableFuture.completedFuture(ToolResult.text("信息：原曲文件不存在。"));
							}
							String folder = source.getParentFile().equals(outputFolder) ? "output" : "original";
							String baseName = stripExtension(source.getName());
							File fileout = new File(outputFolder, safeFileName(baseName) + "（" + name + "）.mp3");
							return singFromUrl(musicUrl(folder, source.getName()), fileout, output);
						} catch (Exception ex) {
							ex.printStackTrace();
							return CompletableFuture.completedFuture(ToolResult.text("信息：处理失败，" + ex.getMessage()));
						}
					}).build());
			tools.add(new ToolData.Builder("send_net_music", "直接发送音乐平台歌曲。")
					.putParam("music_id", "歌曲id", "歌曲ID")
					.orderSensitive()
					.tool((data, output, manager) -> {
						try {
							System.out.println(data);
							JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
							String mid = jo.get("music_id").getAsString();
							String url = queryRealUrl(mid);
							if (!FileUtil.isExistent(url))
								return CompletableFuture.completedFuture(ToolResult.text("该音乐无法下载"));
							return CompletableFuture.completedFuture(
									ToolResult.of("音频已提交发送", new AudioContent(url, mid)));
						} catch (Exception e) {
							e.printStackTrace();
							return CompletableFuture.completedFuture(ToolResult.text("音乐id错误"));
						}
					}).build());
		}
		return tools;
	}

	/** 调用 localemp 把指定 HTTP 音频转换为角色歌声，并写入合成目录。 */
	private CompletableFuture<ToolResult> singFromUrl(String url, File fileout, AIOutput output) {
		if (fileout.isFile()) {
			return CompletableFuture.completedFuture(ToolResult.text(
					"文件名：" + fileout.getName() + "\n信息：合成目录中已存在同名歌曲，未重复生成。"));
		}
		String svsHost = System.getProperty("svsHost");
		if (svsHost == null || svsHost.trim().isEmpty()) {
			return CompletableFuture.completedFuture(ToolResult.text("信息：未配置 svsHost，无法合成。"));
		}
		if (!SvsMcp.isSvsServiceOnline(svsHost)) {
			return CompletableFuture.completedFuture(ToolResult.text("信息：本地歌声合成服务不在线，请先启动 localemp.py。"));
		}
		String reqid = UUID.randomUUID().toString();
		CompletableFuture<ModelGenerationResult> cf = LocalModel.registerTask(reqid);
		CompletableFuture.runAsync(() -> {
			try (ResourcePermit l = lock.acquire(4)) {
				System.out.println(HttpRequestBuilder.create("http", svsHost)
						.url("?url=" + URLEncoder.encode(url, Charsets.UTF_8) + "&voice="
								+ URLEncoder.encode(voiceId, Charsets.UTF_8) + "&reqid=" + reqid)
						.get().readString());
			} catch (IOException e) {
				e.printStackTrace();
				cf.completeExceptionally(e);
			}
		});
		return cf.thenApply(vg -> {
			try {
				FileUtil.transfer(vg.bodyData, fileout);
				return ToolResult.text("文件名：" + fileout.getName() + "\n信息：角色歌声已生成并放入合成目录。");
			} catch (IOException e) {
				e.printStackTrace();
				return ToolResult.failure("信息：保存合成歌曲失败，" + e.getMessage());
			}
		});
	}

	/** 把本地文件转换为服务端公开的 HTTP 链接，供 localemp 下载。 */
	private String musicUrl(String folder, String fileName) {
		try {
			return urlbase + "/music/" + folder + "?file=" + URLEncoder.encode(fileName, Charsets.UTF_8);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	/** 在原曲/合成目录中查找文件，不递归、不允许路径分隔符。 */
	private File resolveMusicFile(String fileName) {
		if (fileName == null || fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\")
				|| fileName.contains("..")) {
			return null;
		}
		File output = new File(outputFolder, fileName);
		if (output.isFile()) {
			return output;
		}
		File original = new File(localFolder, fileName);
		return original.isFile() ? original : null;
	}

	/** 列出目录下的普通文件。 */
	private static void appendFiles(StringBuilder sb, File folder) {
		File[] files = folder.listFiles();
		if (files == null) {
			return;
		}
		for (File file : files) {
			if (file.isFile()) {
				sb.append("- ").append(file.getName()).append("\n");
			}
		}
	}

	/** 按关键词（不区分大小写）列出目录中匹配的文件。 */
	private static void appendMatches(StringBuilder sb, String label, File folder, String keyword) {
		File[] files = folder.listFiles();
		if (files == null) {
			return;
		}
		String needle = keyword.toLowerCase(Locale.ROOT);
		for (File file : files) {
			if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).contains(needle)) {
				sb.append(label).append("：").append(file.getName()).append("\n");
			}
		}
	}

	private static String singersOf(JsonObject song) {
		try {
			JsonArray singers = song.get("singer").getAsJsonArray();
			StringBuilder sgs = new StringBuilder();
			for (JsonElement je : singers) {
				if (sgs.length() > 0) {
					sgs.append(";");
				}
				sgs.append(je.getAsJsonObject().get("name").getAsString());
			}
			return sgs.toString();
		} catch (Exception e) {
			try {
				return song.get("album").getAsJsonObject().get("name").getAsString();
			} catch (Exception ignored) {
				return "";
			}
		}
	}

	private static String safeFileName(String value) {
		String out = value == null ? "" : value.replaceAll(INVALID_FILENAME, "").trim();
		if (out.isEmpty()) {
			out = "未命名";
		}
		if (out.length() > 120) {
			out = out.substring(0, 120);
		}
		return out;
	}

	private static String stripExtension(String value) {
		int dot = value == null ? -1 : value.lastIndexOf('.');
		return dot > 0 ? value.substring(0, dot) : value;
	}

	private static String extensionOf(String value, String fallback) {
		if (value == null) {
			return fallback;
		}
		String path = value;
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		int slash = path.lastIndexOf('/');
		int dot = path.lastIndexOf('.');
		if (dot > slash && dot >= 0 && dot < path.length() - 1) {
			return path.substring(dot);
		}
		return fallback;
	}

	private static String fileNameFromUrl(String url) {
		try {
			String path = url;
			int query = path.indexOf('?');
			if (query >= 0) {
				path = path.substring(0, query);
			}
			int slash = path.lastIndexOf('/');
			String name = slash >= 0 ? path.substring(slash + 1) : path;
			return name.isEmpty() ? "网络音频" : stripExtension(name);
		} catch (Exception ex) {
			return "网络音频";
		}
	}

	private static boolean isHttpUrl(String value) {
		return value != null && (value.regionMatches(true, 0, "http://", 0, 7)
				|| value.regionMatches(true, 0, "https://", 0, 8));
	}

	/**
	 * 创建 find_music 子 agent 工具。
	 * <p>
	 * 主模型只提交自然语言请求；子 agent 自己完成查找已有歌曲、搜索、选择、下载与角色歌声合成，
	 * 成功后返回文件名和歌曲信息，找不到时只返回信息。
	 * </p>
	 */
	private ToolData createAgentTool(List<ToolData> internalTools) {
		String system = "你是一个音乐子 agent，负责在原曲目录和合成目录中查找歌曲，并生成当前角色的歌唱版本。\n"
				+ "可用工具：list_music 查看原曲/合成目录；search_local_music 按关键词搜索本地原曲/合成音乐；"
				+ "search_music 搜索音乐平台；download_platform_music 把平台歌曲下载到原曲目录；"
				+ "download_url_music 把任意网络音频下载到原曲目录；"
				+ "sing_local_music 把原曲/合成目录中的文件合成为角色歌声并写入合成目录；"
				+ "svs_convert 将外部音频转换为角色音色。\n"
				+ "规则：\n"
				+ "1. 不要直接和用户对话，只返回结果。\n"
				+ "2. 先检查 list_music 或 search_local_music：原曲或合成目录已有合适歌曲时直接返回其文件名，不要重复合成。\n"
				+ "3. 平台歌曲先 search_music 搜 id，再 download_platform_music 下载到原曲目录，"
				+ "然后用 sing_local_music 合成。\n"
				+ "4. 任意网络链接用 download_url_music 下载到原曲目录，再用 sing_local_music 合成。\n"
				+ "5. 没有合适结果时不要强行合成；查找失败只返回信息。\n"
				+ "6. 生成成功或找到已有歌曲时必须返回两行：\n"
				+ "文件名：<完整文件名>\n"
				+ "信息：<歌名、歌手、角色名以及其它必要说明>\n"
				+ "7. 找不到歌曲、无法下载或合成失败时只返回一行：\n"
				+ "信息：<具体原因>\n"
				+ "8. 不要编造文件名，不要调用发送类工具，不要重复合成同一首歌。\n"
				+ "9. 尽可能找女声版或者女声翻唱的歌，如果是男声需要提高至少3个半音。\n"
				+ "10. 所有工具调用必须等待返回真实结果后才能继续或结束；禁止提前返回、禁止声称“稍后通知”"
				+ "或把未完成的任务当成完成。\n";
		return new ToolData.Builder("find_music",
				"查找音乐：让音乐子 agent 查找本地已有歌曲，或搜索歌曲并生成内置角色的歌唱版本。"
						+ "成功返回文件名和歌曲信息；找不到歌曲或失败时只返回原因信息。")
				.putParam("request", "自然语言请求，例如“找一首周杰伦的晴天并用当前角色唱”或“有没有适合的歌”。", "音乐请求")
				.async()
				.tool((data, output, manager) -> {
					String request;
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						request = jo.has("request") && !jo.get("request").isJsonNull()
								? jo.get("request").getAsString().trim() : "";
					} catch (Throwable ex) {
						return CompletableFuture.completedFuture(ToolResult.text("参数错误：需要 request。"));
					}
					if (request.isEmpty()) {
						return CompletableFuture.completedFuture(ToolResult.text("参数错误：缺少 request。"));
					}
					ToolCallManager subManager = new ToolCallManager().setMaxRounds(8)
							.setDisableAsyncTasks(true)
							.setToolCallLimit("search_music", 3)
							.setToolCallLimit("search_local_music", 5)
							.setToolCallLimit("download_platform_music", 2)
							.setToolCallLimit("download_url_music", 1)
							.setToolCallLimit("sing_local_music", 2)
							.setToolCallLimit("svs_convert", 1);
					// 收集子 agent 内部工具产生的附件（例如 SVS 生成的音频），
					// 子 agent 结束后随最终工具结果一起返回。
					List<MessageContent> subAttachments = Collections.synchronizedList(new ArrayList<>());
					subManager.setAttachmentHandler(subAttachments::addAll);
					for (ToolData tool : internalTools) {
						if ("search_music".equals(tool.name) || "search_local_music".equals(tool.name)
								|| "download_platform_music".equals(tool.name)
								|| "download_url_music".equals(tool.name)
								|| "sing_local_music".equals(tool.name) || "list_music".equals(tool.name)) {
							subManager.registerTool(tool);
						}
					}
					// 子 agent 接入 SVS MCP：既可以转换外部音频，也可以查询可用音色
					SvsMcp svs = new SvsMcp(tos, lock, svsVoicesConfig);
					for (ToolData tool : svs.getTools()) {
						subManager.registerTool(tool);
					}
					Builder b = AIRequest.builder("musicAgent").modelHint("deepseek/pro")
							.taskType(TaskType.STORY).strength(ReasoningStrength.MEDIUM).temperature(0.2f)
							.maxTokens(8192);
					b.addHistoryItem(Role.SYSTEM, system);
					b.addHistoryItem(Role.USER, request);
					b.toolCallManager(subManager);
					AIRequest aiRequest = b.build();
					return CompletableFuture.supplyAsync(() -> {
						try {
							AIOutput inner = LLMConnector.call(aiRequest);
							FileUtil.printAndCollectContent(inner.getReasoner());
							String resultText = FileUtil.printAndCollectContent(inner.getContent()).toText();
							if (resultText == null || resultText.trim().isEmpty()) {
								// 子 agent 偶发返回空内容：追加一条用户消息再调用一轮
								b.addHistoryItem(Role.USER,
										"你上一轮没有返回任何结果。请根据已经完成的工具调用，立即按“文件名：… / 信息：…”"
												+ "的格式输出最终结果；如果找不到歌曲或失败，只输出“信息：原因”。不要返回空内容。");
								AIRequest retry = b.build();
								inner = LLMConnector.call(retry);
								FileUtil.printAndCollectContent(inner.getReasoner());
								resultText = FileUtil.printAndCollectContent(inner.getContent()).toText();
							}
							if (resultText == null || resultText.trim().isEmpty()) {
								resultText = "信息：音乐子 agent 未返回有效结果。";
							}
							return ToolResult.of(resultText, subAttachments);
						} catch (ModelRouteException | IOException e) {
							e.printStackTrace();
							return ToolResult.text("信息：音乐子 agent 调用失败，" + e.getMessage());
						}
					});
				}).build();
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

}
