package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

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
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp.LoraConfigurations;
import com.khjxiaogu.aiwuxia.multimedia.MultimediaApi;
import com.khjxiaogu.aiwuxia.multimedia.SDXLAgentMultimediaApi;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.vision.LocalSDXLUsage;
import com.khjxiaogu.aiwuxia.vision.QwenImageApi;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;

/**
 * 出图子 agent 工具。
 * <p>
 * 本工具只提交一次后台出图任务：工具直接返回子 agent 的 future，由工具管理器统一登记为
 * 异步任务并在完成时通知会话。子 agent 的工具由独立的管理器注册，不依赖 {@code MCPTools}。
 * </p>
 */
public class SDXLAgentMcp {
	/** 子 agent 自身允许的工具调用轮次上限。 */
	private static final int SUBAGENT_MAX_ROUNDS = 6;
	/** 子 agent 内单次生成最多出图次数。 */
	private static final int SUBAGENT_IMAGE_LIMIT = 3;
	/** 子 agent 内图生图/分区出图的次数上限。 */
	private static final int SUBAGENT_IMAGE_EDIT_LIMIT = 2;

	/**
	 * 创建出图子 agent 工具。
	 *
	 * @param path   子 agent 配置目录
	 * @param tos    对象存储
	 * @param lora   Lora 配置
	 * @param charas 角色提示词
	 * @param isNsfw 是否允许不宜内容
	 * @param lock   资源锁
	 * @return 独立的工具定义
	 * @throws IOException 读取子 agent 配置失败
	 */
	public static ToolData createImage(File path, ObjectStorageProvider tos, Map<String, LoraConfigurations> lora,
			List<String> charas, boolean isNsfw, ResourceLock lock) throws IOException {
		SDXLMcp sdxl = SDXLMcp.createLocal(tos, lora, isNsfw, lock);
		// Qwen-Image 文生图作为子 agent 的“参考底图生成器”：与 SDXL 共用资源锁，每次占用 6 份
		QwenImageApi qwenApi = new QwenImageApi().setResourceLock(lock, 6);
		ToolData qwenTextToImage = withInterrogate(QwenImageMcp.createTextToImageTool(qwenApi, tos), tos, lock);

		String system = FileUtil.readString(new File(path, "role.txt")).replace("\r", "")
				+ FileUtil.readString(new File(path, "charaset.txt")).replace("\r", "")
				+ FileUtil.readString(new File(path, "rules.txt")).replace("\r", "")
				+ (isNsfw ? ""
						: "\n3.禁止返回被判定为NSFW的图片，如果遇到类似情况你可以多尝试能否生成正常图片，如果两次均不能生成则输出失败和原因。")
				+ "\n9.所有工具调用必须等待返回真实结果后才能继续或结束；禁止提前返回、禁止声称“稍后通知”或把未完成的任务当成完成。";

		return new ToolData.Builder("sdxl_agent",
				"调用多模态模型生成图片，同一张图片只能有0-2个角色。提示词只需描述画面大致内容即可，不要详细描述画面内容，涉及角色也只需描述角色名字，不要描述角色特征。调用后图片在后台生成，完成后结果会自动返回到对话中。")
				.putParam("reference", "参考图列表，包含多个图片id以英文逗号,分隔，只允许包含相关图片。", "参考图")
				.putParam("prompt", "提示词，使用中文自然语言详细描述整个画面的细节，不包含参考图的人物特征，使用“图一”“图二”等引用参考图，不得包含图片id，必须说明每个参考图的作用，描述人物时请写全名或者图片编号，禁止使用一切其他代称。比如“画面参考图2，图1角色身着图3所示服装。”", "提示词")
				.async()
				.tool((data, output, manager) -> {
					if (!isSdWebuiOnline()) {
						return CompletableFuture
								.completedFuture(ToolResult.failure("本地绘图服务不在线，请先启动并连接 SD WebUI。"));
					}
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					System.out.println(data);
					String promptText = jo.has("prompt") && !jo.get("prompt").isJsonNull()
							? jo.get("prompt").getAsString() : "";
					if (promptText.trim().isEmpty())
						return CompletableFuture.completedFuture(ToolResult.text("参数错误：缺少提示词prompt。"));
					String ref = "";
					if (jo.has("reference") && !jo.get("reference").isJsonNull())
						ref = jo.get("reference").getAsString();
					String[] refs = ref.split(",");
					List<String> links = new ArrayList<>();
					List<String> errors = new ArrayList<>();
					String refss = "";
					if (ref.length() > 0) {
						try {
							int i = 0;
							for (String refImg : refs) {
								if (refImg.length() == 72) {
									if (tos.exists(refImg.trim(), output == null ? null : output::addUsage)) {
										links.add(tos.getPublicUrl(refImg.trim(),
												output == null ? null : output::addUsage));
										refss += "图" + (++i) + "id:" + refImg.trim() + "\n";
									} else {
										errors.add("参考图" + refImg.trim() + "已清理或不存在；");
									}
								} else {
									errors.add("参考图id" + refImg.trim() + "长度不为72；");
								}

							}
						} catch (IOException e) {
							e.printStackTrace();
							return CompletableFuture.completedFuture(ToolResult.text("参考图处理失败"));
						}
						if (errors.size() > 0) {
							StringBuilder sb = new StringBuilder();
							for (String err : errors) {
								sb.append(err);
							}
							return CompletableFuture.completedFuture(ToolResult.text("参数错误：" + sb.toString()));
						}
					}
					Builder b = AIRequest.builder("sdxlAgent").modelHint("deepseek/pro").taskType(TaskType.STORY)
							.strength(ReasoningStrength.MEDIUM).temperature(0.2f).maxTokens(16384);
					b.addHistoryItem(Role.SYSTEM, system);
					b.addHistoryItem(Role.USER, refss + promptText);
					// 子 agent 的工具由独立管理器提供，图片工具等仍可设置自己的次数上限
					ToolCallManager subManager = new ToolCallManager()
							.registerTools(sdxl)
							.setMaxRounds(SUBAGENT_MAX_ROUNDS)
							.setDisableAsyncTasks(true)
							.setToolCallLimit("sdxl_gen_image", SUBAGENT_IMAGE_LIMIT)
							.setToolCallLimit("image_to_image", SUBAGENT_IMAGE_EDIT_LIMIT)
							.setToolCallLimit("regional_image", SUBAGENT_IMAGE_EDIT_LIMIT);
					subManager.registerTool(qwenTextToImage);
					subManager.registerTool(FetchMcp.createWebView(tos));
					subManager.registerTool(FetchMcp.createMoegirlSearch(tos));
					ToolData webSearch = DeepseekSearchMcp.createWebSearch();
					if (webSearch != null) {
						subManager.registerTool(webSearch);
					}
					subManager.registerTool(MultiModalMcp.createImageRecognition(tos));
					if (charas != null) {
						subManager.registerTool(SDXLMcp.createSearchCharacter(charas));
					}
					b.toolCallManager(subManager);
					AIRequest request = b.build();
					return CompletableFuture.supplyAsync(() -> {
						try {
							AIOutput inner = LLMConnector.call(request);
							FileUtil.printAndCollectContent(inner.getReasoner());
							return ToolResult.text(FileUtil.printAndCollectContent(inner.getContent()).toText());
						} catch (ModelRouteException | IOException e) {
							e.printStackTrace();
							return ToolResult.text("agent调用失败");
						}
					});
				}).build();
	}

	/**
	 * 包一层“生成后反推提示词”的工具：原工具结果不变，图片后追加 WD1.4 反推出的提示词。
	 */
	private static ToolData withInterrogate(ToolData source, ObjectStorageProvider tos, ResourceLock lock) {
		if (source == null) {
			return null;
		}
		ToolData.Builder builder = new ToolData.Builder(source.name,
				source.description + " 生成完成后会自动反推图片提示词，并把结果附加在图片结果后面。")
				.presenter(source.presenter)
				.async(source.async)
				.maxCalls(source.maxCalls)
				.sessionOnly(source.sessionOnly)
				.orderSensitive(source.orderSensitive);
		for (Map.Entry<String, String> param : source.params.entrySet()) {
			builder.putParam(param.getKey(), param.getValue(), source.getParamLabel(param.getKey()));
		}
		return builder.tool((data, output, manager) -> source.tool.run(data, output, manager)
				.thenApply(result -> appendInterrogate(result, tos, lock, output))).build();
	}

	/** 在工具结果的每张图片后面追加反推提示词。 */
	private static ToolResult appendInterrogate(ToolResult result, ObjectStorageProvider tos, ResourceLock lock,
			AIOutput output) {
		if (result == null || result.getContent() == null || result.getContent().isEmpty()) {
			return result;
		}
		MutableMessageContents content = new MutableMessageContents();
		boolean appended = false;
		for (MessageContent item : result.getContent()) {
			content.add(item);
			if (item instanceof ImageContent) {
				String tags = interrogateImage(((ImageContent) item).getImageUrl(), tos, lock, output);
				if (!tags.isEmpty()) {
					content.append("\n反推提示词：" + tags + "\n");
					appended = true;
				}
			}
		}
		return appended ? ToolResult.content(content, result.getAttachments()) : result;
	}

	/** 调用 SD WebUI 的 WD1.4 反推接口，失败时返回空串，不影响原图结果。 */
	private static String interrogateImage(String imageId, ObjectStorageProvider tos, ResourceLock lock,
			AIOutput output) {
		if (imageId == null || imageId.isEmpty() || tos == null
				|| System.getProperty("sdwebuiUrl") == null
				|| System.getProperty("sdwebuiUrl").trim().isEmpty()) {
			return "";
		}
		ResourcePermit permit = null;
		try {
			if (lock != null) {
				permit = lock.acquire(6);
			}
			byte[] data = tos.download(imageId, output == null ? null : output::addUsage);
			if (data == null || data.length == 0) {
				return "";
			}
			JsonObject tags = SDXLMcp.interrogate(Base64.getEncoder().encodeToString(data), sdConnector());
			StringBuilder text = new StringBuilder();
			for (String tag : tags.keySet()) {
				if (text.length() > 0) {
					text.append(',');
				}
				text.append(tag);
				if (text.length() > 2000) {
					break;
				}
			}
			if (output != null) {
				output.addUsage(new LocalSDXLUsage(0, 1));
			}
			return text.toString();
		} catch (Throwable ex) {
			// 反推只是附加信息，失败不影响 Qwen 已经生成的图片
			ex.printStackTrace();
			return "";
		} finally {
			if (permit != null) {
				permit.release();
			}
		}
	}

	/** SD WebUI 反推请求连接器，与 SDXL 本地客户端使用同一套接口。 */
	private static BiFunction<String, String, CompletableFuture<JsonObject>> sdConnector() {
		return (url, payload) -> CompletableFuture.supplyAsync(() -> {
			try {
				return HttpRequestBuilder.create("http", System.getProperty("sdwebuiUrl"))
						.header("Accept", "application/json")
						.header("Content-Type", "application/json; utf-8")
						.url(url)
						.post()
						.send(payload)
						.readJson();
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});
	}

	/** 快速探测 SD WebUI 是否在线；超时约 1.5 秒，避免子 agent 在离线时长时间等待。 */
	private static boolean isSdWebuiOnline() {
		String base = System.getProperty("sdwebuiUrl", "").trim();
		if (base.isEmpty()) {
			return false;
		}
		if (!base.startsWith("http://") && !base.startsWith("https://")) {
			base = "http://" + base;
		}
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		try {
			new HttpRequestBuilder(base + "/internal/ping").readTimeout(1500).get().readString();
			return true;
		} catch (Throwable ex) {
			return false;
		}
	}

	/**
	 * 以通用多媒体 API 的形式提供 SDXLAgent。
	 * <p>
	 * 复用 {@link #createImage} 的现有工具实现，不改变原方法；返回的 API 接受“可选图片附件 +
	 * 提示词”，输出图片附件 + 子 agent 文本。
	 * </p>
	 *
	 * @param path   子 agent 配置目录
	 * @param tos    对象存储
	 * @param lora   LoRA 配置
	 * @param charas 角色提示词
	 * @param isNsfw 是否允许不宜内容
	 * @param lock   资源锁
	 * @return 通用多媒体 API
	 * @throws IOException 读取子 agent 配置失败
	 */
	public static MultimediaApi createMultimediaApi(File path, ObjectStorageProvider tos,
			Map<String, LoraConfigurations> lora, List<String> charas, boolean isNsfw, ResourceLock lock)
			throws IOException {
		return new SDXLAgentMultimediaApi(path, tos, lora, charas, isNsfw, lock);
	}
}
