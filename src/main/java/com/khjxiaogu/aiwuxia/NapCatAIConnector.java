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
package com.khjxiaogu.aiwuxia;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.AIApplicationRegistry;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.DirectHistoryItem;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.message.ImageContent;
import com.khjxiaogu.aiwuxia.llm.message.MessageContent;
import com.khjxiaogu.aiwuxia.llm.message.MessageContents;
import com.khjxiaogu.aiwuxia.llm.message.PlainText;
import com.khjxiaogu.aiwuxia.objectstorage.TOStorage;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.SavedData;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.voice.LocalVoiceModel;
import com.khjxiaogu.aiwuxia.voice.VoiceGenerationResult;
import com.khjxiaogu.aiwuxia.voice.VoiceModelLocalServer;
import com.khjxiaogu.aiwuxia.voice.VoiceTagger;
import com.khjxiaogu.webserver.builder.BasicWebServerBuilder;

public class NapCatAIConnector extends WebSocketClient {
	AIGroupSession state;
	File saveData;
	long botId;
	long groupId;
	String token;
	String url;
	Object lock = new Object();
	VoiceTagger vt;
	TOStorage tos;

	public NapCatAIConnector(File dataFolder, AIGroupSession state, File saveData, long botId, long groupId, String url,
			String token) throws JsonSyntaxException, IOException {
		super(URI.create("ws://" + url), Map.of("Authorization", "Bearer " + token));
		tos = new TOStorage(
				JsonParser.parseString(FileUtil.readString(new File(dataFolder, "tos.json"))).getAsJsonObject());
		this.state = state;
		this.saveData = saveData;
		this.botId = botId;
		this.groupId = groupId;
		this.token = token;
		this.url = url;
		addTools();
		try {
			vt = new VoiceTagger(dataFolder);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void addTools() {
		state.addTool(new ToolData.Builder("image_recognition", "使用多模态模型查看图片并返回图片描述。")
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身，不得包含任何其他内容").tool((data) -> {
					Pattern patt = Pattern.compile("\"picture_id\"\\s*:\\s*\"([0-9a-fA-F]{72})\"");
					Matcher mtch = patt.matcher(data);
					if (mtch.find()) {
						String id = mtch.group(1);
						return recognizeImage(id);

					}
					return "参数格式错误";
				}).build());
		state.addTool(new ToolData.Builder("avatar_recognition", "使用多模态模型查看用户头像并返回图片描述，24小时内对同一个人只能调用该工具一次。")
				.putParam("user_id", "用户的id，可以从消息的senderId中取得。").tool((data) -> {
					Pattern patt = Pattern.compile("\"user_id\"\\s*:\\s*\"([0-9]+)\"");
					Matcher mtch = patt.matcher(data);
					if (mtch.find()) {
						String id = mtch.group(1);

						try {
							byte[] picture = HttpRequestBuilder.create("q1.qlogo.cn").defUA()
									.url("/g?b=qq&nk=" + id + "&s=640").get().readBytes();
							String fn = tos.uploadIfNotExists(picture);

							return recognizeImage(fn);
						} catch (IOException e) {
							e.printStackTrace();
							return "头像获取失败";
						}

					}
					return "参数格式错误";
				}).build());
		state.addTool(new ToolData.Builder("web_view", "获取链接指定的内容，只支持ftp/http/https链接").putParam("url", "网页链接")
				.tool((data) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						if (jo.has("url")) {
							String url = jo.get("url").getAsString();
							if (url.startsWith("ftp") || url.startsWith("http"))
								return fetch(url);

						}
					} catch (Throwable err) {
						err.printStackTrace();
					}
					return "参数格式错误";
				}).build());
		state.addTool(new ToolData.Builder("moegirl_search", "搜索萌娘百科").putParam("keyword", "关键词").tool((data) -> {
			try {
				JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
				if (jo.has("keyword")) {
					String keyword = jo.get("keyword").getAsString();
					return fetch("https://zh.moegirl.org.cn/index.php?search="
							+ URLEncoder.encode(keyword, StandardCharsets.UTF_8)
							+ "&title=Special%3A%E6%90%9C%E7%B4%A2");

				}
			} catch (Throwable err) {
				err.printStackTrace();
			}
			return "参数格式错误";
		}).build());

		state.addTool(
				new ToolData.Builder("avatar_group_recognition", "使用多模态模型查看群头像并返回图片描述，24小时内只能调用该工具一次。").tool((data) -> {

					try {
						byte[] picture = HttpRequestBuilder.create("p.qlogo.cn").defUA()
								.url("/gh/" + groupId + "/" + groupId + "/640").get().readBytes();
						String fn = tos.uploadIfNotExists(picture);

						return recognizeImage(fn);
					} catch (IOException e) {
						e.printStackTrace();
						return "头像获取失败";
					}

				}).build());
		Map<String, int[]> resolutions = new HashMap<>();
		resolutions.put("16:9", new int[] { 1280, 720 });
		resolutions.put("9:16", new int[] { 720, 1280 });
		resolutions.put("3:4", new int[] { 768, 1024 });
		resolutions.put("4:3", new int[] { 1024, 768 });
		state.addTool(new ToolData.Builder("sdxl_gen_image",
				"使用Stable Diffusion XL生成图片并发送，不得包含对发色、瞳色的描述。角色的名字是\"xinghan\"，在提示词中包含该内容以生成角色图片，不需要包含画质提示词，使用纯英文提示词。")
				.putParam("positive", "正面提示词").putParam("resolution", "画面比例，必须是16:9/9:16/4:3/3:4之一").tool((data) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					String resolution = jo.get("resolution").getAsString();
					int[] its = resolutions.get(resolution);
					if (its == null)
						return "不支持的分辨率设置";
					String prompt=jo.get("positive").getAsString();
					try {
						if(prompt.contains("xinghan")) {
							prompt="<lora:xinghandx:0.8>"+prompt;
						}
						byte[] image=generateImage("(masterpiece,best quality,absurdres),"+prompt,
								"low quality,bad anatomy,unnatural hair color,bad feet,malformed hands,bad hands,missing fingers,fused fingers,too many fingers,poorly drawn hands,malformed limbs,missing limb,mutated hands,extra arms,extra limb,mutated hands and fingers,extra legs,floating limbs,disconnected limbs, trademark,artist's name, username, watermark,signature, watermark,text, words,"
								,25,
								its[0],
								its[1]);
						this.send(JsonBuilder.object().add("action", "send_group_msg")
								.object("params").add("group_id", groupId).object("message")
								.add("type", "image").object("data")
								.add("file", toDataUrl(image)).end().end()
								.end().end().toString());
					} catch (IOException e) {
						e.printStackTrace();
						return "图片生成失败";
					}
					return "图片发送成功";
				}).build());

	}

	public static byte[] generateImage(String prompt, String negativePrompt, int steps, int width, int height) throws IOException {
		HttpURLConnection conn = null;
		try {
			JsonObject payload = new JsonObject();
			payload.addProperty("prompt", prompt);
			payload.addProperty("negative_prompt", negativePrompt);
			payload.addProperty("steps", steps);
			payload.addProperty("width", width);
			payload.addProperty("height", height);
			payload.addProperty("restore_faces", true);
			payload.addProperty("sampler_name", "DPM++ 2M");

			URL url = new URL(System.getProperty("sdwebuiUrl") + "/sdapi/v1/txt2img");
			conn = (HttpURLConnection) url.openConnection();
			conn.setRequestMethod("POST");
			conn.setRequestProperty("Content-Type", "application/json; utf-8");
			conn.setRequestProperty("Accept", "application/json");
			conn.setDoOutput(true);

			try (OutputStream os = conn.getOutputStream()) {
				byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
				os.write(input, 0, input.length);
			}

			int responseCode = conn.getResponseCode();
			if (responseCode != HttpURLConnection.HTTP_OK) {
				System.err.println("HTTP 错误: " + responseCode);
				try (BufferedReader br = new BufferedReader(
						new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
					StringBuilder error = new StringBuilder();
					String line;
					while ((line = br.readLine()) != null) {
						error.append(line);
					}
					System.err.println("错误详情: " + error);
				}
				throw new IOException("server responsed"+responseCode);
			}


			JsonObject jsonResponse = JsonParser.parseString(FileUtil.readString(conn.getInputStream())).getAsJsonObject();
			JsonArray images = jsonResponse.get("images").getAsJsonArray();
			for (int i = 0; i < images.size(); i++) {
				String base64Image = images.get(i).getAsString();

				if (base64Image.contains(",")) {
					base64Image = base64Image.split(",", 2)[1];
				}
				byte[] imageBytes = Base64.getDecoder().decode(base64Image);
				return imageBytes;
			}

		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
		return null;
	}



	@Override
	public void onMessage(ByteBuffer bytes) {

	}

	@Override
	public void onOpen(ServerHandshake serverHandshake) {
		System.out.println("opened connection");
	}

	public byte[] getImageBytes(String fileId) throws Exception {
		// 1. 构造请求 JSON：{"file_id":urls "..."}
		return FileUtil.readAll(FileUtil.fetch(fileId));
	}

	@Override
	public void onMessage(String message) {
		synchronized (lock) {
			try {
				JsonObject msg = JsonParser.parseString(message).getAsJsonObject();
				if (msg.has("message_type") && "group".equals(msg.get("message_type").getAsString())) {
					if (msg.get("group_id").getAsLong() == groupId) {
						Map<String, String> urls = new HashMap<>();
						for (JsonElement je : msg.get("message").getAsJsonArray()) {
							JsonObject nmsg = je.getAsJsonObject();
							if (nmsg.get("type").getAsString().equals("image")) {
								JsonObject data = nmsg.get("data").getAsJsonObject();

								urls.put(data.get("file").getAsString(), data.get("url").getAsString());
							}
						}
						String sender = "";
						JsonObject senderObj = msg.get("sender").getAsJsonObject();
						if (senderObj.has("card"))
							sender = senderObj.get("card").getAsString();
						if (sender.isEmpty())
							sender = senderObj.get("nickname").getAsString();
						long senderid = senderObj.get("user_id").getAsLong();
						if (senderid != botId) {
							JsonArray ja = msg.get("raw").getAsJsonObject().get("elements").getAsJsonArray();
							boolean containsAtMe = false;
							List<Supplier<MessageContent>> mes = new ArrayList<>();
							String senderName = "<message senderName=\"" + sender + "\" senderId=\"" + senderid
									+ "\" id=\"" + msg.get("real_seq").getAsString() + "\">";
							mes.add(() -> new PlainText(senderName));
							boolean hasText = false;
							for (JsonElement je : ja) {
								JsonObject melm = je.getAsJsonObject();
								if (!melm.get("replyElement").isJsonNull()) {
									JsonObject reply = melm.get("replyElement").getAsJsonObject();
									StringBuilder msgBuilder = new StringBuilder();
									msgBuilder.append("<quote messageId=\"" + reply.get("replayMsgSeq").getAsString()
											+ "\" senderId=\"" + reply.get("senderUid").getAsString() + "\">“");
									for (JsonElement replys : reply.get("sourceMsgTextElems").getAsJsonArray()) {
										if (replys.getAsJsonObject().get("textElemContent").isJsonPrimitive())
											msgBuilder.append(
													replys.getAsJsonObject().get("textElemContent").getAsString());
									}
									msgBuilder.append("”</quote>");
									mes.add(() -> new PlainText(msgBuilder.toString()));
									if (reply.get("senderUid").getAsString().equals(String.valueOf(botId))) {
										containsAtMe = true;
									}
									continue;
								}
								if (!melm.get("textElement").isJsonNull()) {
									hasText = true;
									JsonObject text = melm.get("textElement").getAsJsonObject();
									if (text.get("atType").getAsInt() != 0) {
										if (text.get("atUid").getAsString().equals(String.valueOf(botId))) {
											mes.add(() -> new PlainText("@" + state.getRoleName(Role.ASSISTANT)));
											containsAtMe = true;
											continue;
										}
									}
									String messageLine = text.get("content").getAsString();
									if ((!containsAtMe)
											&& messageLine.contains("@" + state.getRoleName(Role.ASSISTANT)))
										containsAtMe = true;
									mes.add(() -> new PlainText(messageLine));

								} else if (!melm.get("picElement").isJsonNull()) {
									JsonObject pic = melm.get("picElement").getAsJsonObject();
									String summary = "（" + pic.get("summary").getAsString() + "）";
									System.out.println(pic);
									String fid = urls.get(pic.get("fileName").getAsString());
									hasText = true;
									mes.add(() -> {
										try {
											String path = tos.uploadIfNotExists(getImageBytes(fid));
											return new PlainText("<image id=\"" + path + "\" />");
										} catch (Exception e) {
											e.printStackTrace();
										}
										return new PlainText(summary);

									});
								}
							}
							if (hasText) {
								mes.add(() -> new PlainText("</message>"));

								state.addMessage(mes);
							}
							// int hours=new Date().getHours();
							// if(hours<6)
							// containsAtMe=false;
							if (containsAtMe) {
								state.getCommandExec().submit(() -> {
									state.getAiapp().handleSpeech(state, state.getPrompt());
									try {
										AIApplication.saveToJson(state, saveData);
									} catch (IOException e) {
										e.printStackTrace();
									}
									HistoryItem hi = state.getLast();
									if (hi.getRole() == Role.ASSISTANT) {

										try {
											CompletableFuture<VoiceGenerationResult> dataFuture = vt
													.extractTalkContent(state.getRoleName(Role.ASSISTANT),
															state.getLast().getDisplayContent().toString().trim(),
															state)
													.thenCompose(t -> LocalVoiceModel.requireAudio("mx",
															UUID.randomUUID().toString(), t));
											this.send(JsonBuilder.object().add("action", "send_group_msg")
													.object("params").add("group_id", groupId).object("message")
													.add("type", "record").object("data")
													.add("file", toDataUrl(dataFuture.get().audioData)).end().end()
													.end().end().toString());
										} catch (InterruptedException | ExecutionException e) {
											// TODO Auto-generated catch block
											e.printStackTrace();
											this.send(JsonBuilder.object().add("action", "send_group_msg")
													.object("params").add("group_id", groupId)
													.add("message",
															state.getLast().getDisplayContent().toString().trim())
													.end().end().toString());

										}

									}

								});
							}

						}
					}

				}
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}
		System.out.println("received message: " + message);
	}

	public static String toDataUrl(byte[] data) {
		if (data == null) {
			throw new IllegalArgumentException("字节数组不能为 null");
		}
		String base64 = Base64.getEncoder().encodeToString(data);
		return "data:;base64," + base64;
	}

	@Override
	public void onClose(int code, String reason, boolean remote) {
	}

	@Override
	public void onError(Exception e) {
		close(CloseFrame.NORMAL, e.toString());
	}

	public String recognizeImage(String id) {
		if (tos.exists(id)) {
			Builder builder = AIRequest.builder("picutreTool").taskType(TaskType.STORY)
					.multimodal(MultimodalType.IMAGE_ONLY);
			builder.addHistoryItem(Role.SYSTEM,
					"请观察图片，详细具体客观描述其中的内容，文字，人物，细节特征，位置等信息，并原样提供图片中所有文本原文内容。注意仅忠实描述图片外观，禁止进行分析。");
			builder.addHistoryItem(
					new DirectHistoryItem(Role.USER, new MessageContents(new ImageContent(tos.getUrl(id)))));
			try {
				return FileUtil.printAndCollectContent(
						LLMConnector.call(builder.temperature(1.3f).maxTokens(3000).build()).getContent());
			} catch (ModelRouteException | IOException e) {
				e.printStackTrace();
				return "服务暂不可用";
			}
		} else {
			return "图片不存在或被清理";
		}
	}

	/**
	 * 从输入流中读取文本，使用指定字符集（若未指定则用 UTF-8）
	 */
	private String readText(InputStream inputStream, String charsetName) throws IOException {
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
	public String fetch(String urlStr) {
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
				return recognizeImage(tos.uploadIfNotExists(imageData));
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
	private String extractMimeType(String contentType) {
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
	private String extractCharset(String contentType) {
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
	private boolean isTextType(String mimeType) {
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
	private boolean isImageType(String mimeType) {
		return "image/jpeg".equals(mimeType) || "image/jpg".equals(mimeType) || "image/png".equals(mimeType);
	}

	public static void main(String[] args) throws Throwable {
		try {
			LLMConnector.initDefault();
			String name = "xinghanirc";
			File dataFolder = new File("save");
			File saveData = new File(new File(dataFolder, "saveData"), "savegroup-" + name + ".json");
			File modelFolder = new File(dataFolder, "apps/" + name);
			AIApplication main = AIApplicationRegistry.createInstance(dataFolder, modelFolder);
			AIGroupSession aistate = null;
			if (saveData.exists()) {
				aistate = new AIGroupSession("appuser", AIApplication.saveDataFromJson(saveData), main);
			} else {
				aistate = new AIGroupSession("appuser", new SavedData(), main);
				aistate.provideInitialHint();
			}

			new Thread(() -> {
				try {
					BasicWebServerBuilder.build().createURIRoot().createWrapper(new VoiceModelLocalServer())
							.rule("/aichat").complete().complete().setNotFound(new File(new File("save"), "404.html"))
							.compile().serverHttp(8998).info("http服务端已开启");
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}).start();
			new NapCatAIConnector(dataFolder, aistate, saveData, Long.parseLong(System.getProperty("bot")),
					Long.parseLong(System.getProperty("group")), System.getProperty("napcat_url"),
					System.getProperty("napcat_token")).connectBlocking();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

}
