package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.DirectHistoryItem;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallDisplay;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModel;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelAttachments;
import com.khjxiaogu.aiwuxia.modelproxy.ModelGenerationResult;
import com.khjxiaogu.aiwuxia.modelproxy.ModelType;
import com.khjxiaogu.aiwuxia.modelproxy.api.AttachmentRef;
import com.khjxiaogu.aiwuxia.modelproxy.api.LocalModelCall;
import com.khjxiaogu.aiwuxia.modelproxy.api.SdxlAction;
import com.khjxiaogu.aiwuxia.modelproxy.api.SdxlParams;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.vision.LocalSDXLUsage;
import com.khjxiaogu.aiwuxia.vision.SDXLApi;

public class SDXLMcp implements ToolProvider {
	/** 本次提供的工具集合。 */
	private final List<ToolData> tools = new ArrayList<>();
	/** 画面比例 → 宽高。 */
	private final Map<String, int[]> resolutions = new HashMap<>();
	private final List<PromptClass> commonNegatives = new ArrayList<>();
	private final List<PromptClass> commonPositive = new ArrayList<>();
	private final ObjectStorageProvider tos;
	private final Map<String, LoraConfigurations> lora;
	private final boolean isNsfw;
	private final BiFunction<String, AIOutput, String> urlGetter;
	/** SDXL 执行通道：语义请求 → 结果字节。 */
	private final Channel channel;
	/**
	 * 结果内容里放对象存储 id 而不是直接链接。
	 * <p>
	 * 供 MCP 使用：生成结果统一写入对象存储后由 MCP 服务端转换成统一媒体入口；
	 * 普通绘画会话保持 false，继续在结果里放可直接访问的图片链接。
	 * </p>
	 */
	private final boolean contentIdMode;
	/**
	 * 请求经本地模型连接器执行时，连接器所属的用户标识（null 表示共享连接池）。
	 * <p>
	 * 只用于判断“这次请求会落到哪个连接器上、它是否声明了分块/大报文能力”，
	 * 见 {@link #referencePayload(String, AIOutput)}。
	 * </p>
	 */
	private final String connectorUid;
	/** 请求是否经本地模型连接器执行；为 false 时参考图始终按 {@link #urlGetter} 的形式发送。 */
	private final boolean viaLocalModelConnector;

	/**
	 * SDXL 执行通道：把一次 {@link LocalModelCall}（参数对象 {@link SdxlParams} + 附件引用）
	 * 交给执行方，返回结果字节。
	 * <p>
	 * 与 H3 / Qwen 一样，服务器只描述“要什么图”，真正的 SD WebUI 调用由本地模型客户端完成；
	 * 两种实现见 {@link #remoteChannel(String)}（本地模型客户端）与
	 * {@link #localChannel(ResourceLock)}（服务器直连 {@code -DsdwebuiUrl}）。
	 * </p>
	 */
	@FunctionalInterface
	public interface Channel {
		/**
		 * 执行一次 SDXL 调用。
		 *
		 * @param call 统一调用对象
		 * @return 结果字节（图片字节，或反推结果 JSON）
		 * @throws IOException 执行失败
		 */
		byte[] send(LocalModelCall<SdxlParams> call) throws IOException;
	}

	/**
	 * 经本地模型客户端（{@code model_type=sdxl}）执行的通道。
	 *
	 * @param uid 用户标识，可为 null 或空（共享连接池）
	 * @return 执行通道
	 */
	public static Channel remoteChannel(String uid) {
		return call -> {
			try {
				ModelGenerationResult result = LocalModel.send(uid, call).get();
				if (result == null || result.bodyData == null) {
					throw new IOException("本地模型没有返回数据");
				}
				return result.bodyData;
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IOException("请求被中断", ex);
			} catch (ExecutionException ex) {
				Throwable cause = ex.getCause() == null ? ex : ex.getCause();
				String message = cause.getMessage();
				throw new IOException(message == null || message.isEmpty() ? "本地模型调用失败" : message, cause);
			}
		};
	}

	/**
	 * 直接在服务器进程里调用 {@code -DsdwebuiUrl} 的通道（不需要本地模型客户端）。
	 * <p>
	 * 资源锁照旧：一次生成占满整把锁。附件从本进程的注册表读取，效果与客户端拉取一致。
	 * </p>
	 *
	 * @param lock 与其它本地模型共享的资源锁
	 * @return 执行通道
	 */
	public static Channel localChannel(ResourceLock lock) {
		return call -> {
			SdxlParams params = call.requireParams(SdxlParams.class);
			byte[] imageBytes = null;
			AttachmentRef ref = params.image == null ? null : call.attachment(params.image);
			if (ref != null) {
				imageBytes = LocalModelAttachments.shared().read(call.reqid, ref.id);
			}
			try (ResourcePermit permit = lock.acquire(12)) {
				return SDXLApi.dispatch(params, imageBytes, SDXLApi.direct(System.getProperty("sdwebuiUrl")));
			}
		};
	}

	/**
	 * 通过本地模型通道反推图片提示词。
	 *
	 * @param imageId 图片的对象存储 id 或链接
	 * @param tos     对象存储，可为 null
	 * @param output  用量接收方，可为 null
	 * @return 提示词 → 可能性
	 * @throws IOException 调用失败或结果无法解析
	 */
	public static JsonObject interrogateRemote(String imageId, ObjectStorageProvider tos, AIOutput output)
			throws IOException {
		String reqid = UUID.randomUUID().toString();
		List<AttachmentRef> attachments = new ArrayList<>();
		String image = imageId;
		String storageId = storageIdOf(imageId);
		if (storageId != null && tos != null) {
			AttachmentRef ref = registerImageAttachment(reqid, null, tos, storageId, output);
			attachments.add(ref);
			image = ref.id;
		}
		SdxlParams params = new SdxlParams(SdxlAction.INTERROGATE, "", "", 1, 64, 64, image, 0, false, "");
		return parseJson(remoteChannel(null).send(LocalModelCall.sdxl(reqid, params, attachments)));
	}

	/** 把对象存储里的图片登记成附件，真正被拉取时才读存储。 */
	private static AttachmentRef registerImageAttachment(String reqid, String uid, ObjectStorageProvider tos,
			String storageId, AIOutput output) {
		return LocalModelAttachments.shared().register(reqid, uid, AttachmentRef.KIND_IMAGE, "png", "reference.png",
				() -> {
					try {
						byte[] data = tos.download(storageId, output == null ? null : output::addUsage);
						if (data == null) {
							throw new IOException("参考图内容为空：" + storageId);
						}
						return data;
					} catch (IOException ex) {
						throw new RuntimeException(ex);
					}
				});
	}

	/**
	 * 组织一次 SDXL 调用：参考图是对象存储 id 时登记为附件（客户端按需拉取），
	 * 其它引用继续走原来的链接/Base64 兜底。
	 */
	private byte[] generate(SdxlAction action, String prompt, String negative, int steps, int width, int height,
			String imageRef, double denoising, boolean isRow, String ratio, AIOutput output) throws IOException {
		String reqid = UUID.randomUUID().toString();
		List<AttachmentRef> attachments = new ArrayList<>();
		String image = null;
		if (imageRef != null && !imageRef.isEmpty()) {
			String storageId = storageIdOf(imageRef);
			if (storageId != null && tos != null) {
				AttachmentRef ref = registerImageAttachment(reqid, connectorUid, tos, storageId, output);
				attachments.add(ref);
				image = ref.id;
			} else {
				image = referencePayload(imageRef, output);
			}
		}
		SdxlParams params = new SdxlParams(action, prompt, negative, steps, width, height, image, denoising, isRow,
				ratio);
		return channel.send(LocalModelCall.sdxl(reqid, params, attachments));
	}

	/** 解析结果字节为 JSON。 */
	private static JsonObject parseJson(byte[] data) throws IOException {
		if (data == null || data.length == 0) {
			throw new IOException("SD WebUI 没有返回反推结果");
		}
		try {
			return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (RuntimeException ex) {
			throw new IOException("反推结果解析失败", ex);
		}
	}

	public static class LoraConfigurations{
		public final List<LoraConfiguration> li=new ArrayList<>(5);
		public LoraConfigurations(String loraName, float loraValue) {
			super();
			li.add(new LoraConfiguration(loraName,loraValue));
		}
		public LoraConfigurations(String loraName, float loraValue,String loraName2, float loraValue2) {
			super();
			li.add(new LoraConfiguration(loraName,loraValue));
			li.add(new LoraConfiguration(loraName2,loraValue2));
		}

		public LoraConfigurations(LoraConfiguration...loras) {
			super();
			for(LoraConfiguration lora:loras) {
				li.add(lora);
			}
		}
		public LoraConfigurations(JsonObject lora) {
			super();
			li.add(new LoraConfiguration(lora));
			
		}
		public LoraConfigurations(JsonArray loras) {
			super();
			for(JsonElement lora:loras) {
				li.add(new LoraConfiguration(lora.getAsJsonObject()));
			}
		}
	}
	public static class PromptClass{
		private final String prompt;
		private final String promptText;
		public PromptClass(String prompt) {
			super();
			this.prompt = cleanToken(prompt);
			this.promptText = prompt;
		}
	    private static String cleanToken(String token) {
	    	token = token.replaceAll(":[\\d.]+$", "").trim();
	        while (token.startsWith("(") && token.endsWith(")")) {
	        	token = token.substring(1, token.length() - 1).trim();
	        }
	        token = token.replaceAll(":[\\d.]+$", "").trim();
	        while (token.startsWith("[") && token.endsWith("]")) {
	        	token = token.substring(1, token.length() - 1).trim();
	        }
	        token = token.replaceAll(":[\\d.]+$", "").trim();
	        return token;
	    }
		public PromptClass(String prompt, String promptText) {
			super();
			this.prompt = prompt;
			this.promptText = promptText;
		}
		@Override
		public String toString() {
			return promptText;
		}
		@Override
		public int hashCode() {
			return Objects.hash(prompt);
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null) return false;
			if (getClass() != obj.getClass()) return false;
			PromptClass other = (PromptClass) obj;
			return Objects.equals(prompt, other.prompt);
		}
		
	}
	public static class LoraConfiguration{
		public String loraName;
		public float loraValue;
		public final boolean fixed;
		public LoraConfiguration(JsonObject loraConfig) {
			super();
			this.loraName = loraConfig.get("key").getAsString();
			this.loraValue = loraConfig.get("weight").getAsFloat();
			if(loraConfig.has("fixed")) {
				fixed=loraConfig.get("fixed").getAsBoolean();
			}else
				fixed=false;
		}
		LoraConfiguration(String loraName, float loraValue, boolean fixed) {
			super();
			this.loraName = loraName;
			this.loraValue = loraValue;
			this.fixed = fixed;
		}
		LoraConfiguration(String loraName, float loraValue) {
			super();
			this.loraName = loraName;
			this.loraValue = loraValue;
			this.fixed = false;
		}
		
		
	
	}
    /**
     * 处理 LoraConfiguration 列表
     * @param configList 输入的配置列表
     * @return 格式化后的字符串
     */
    public static String processLoraConfigurations(Collection<LoraConfigurations> configList) {
        if (configList == null || configList.isEmpty()) {
            return "";
        }

        // 第一步：根据 loraName 去重，保留 loraValue 最小的项
        Map<String, Float> minValueMap = new HashMap<>();
        Map<String, Float> fixedValueMap = new HashMap<>();
        for (LoraConfigurations configs : configList) {
        	for(LoraConfiguration config:configs.li) {
	            String name = config.loraName;
	            float value = config.loraValue;
	            if(!fixedValueMap.containsKey(name)&&config.fixed) {
	            	fixedValueMap.put(name, value);
	            	continue;
	            }
	            
	            if (!minValueMap.containsKey(name) || value < minValueMap.get(name)) {
	                minValueMap.put(name, value);
	            }
        	}
        }
        // 将去重后的结果转为列表，便于后续处理
        

        // 第二步：如果有两个或以上不同的 loraName，每个 loraValue 乘以 0.8
        if (minValueMap.size() >= 2) {
            for (Map.Entry<String, Float> entry : minValueMap.entrySet()) {
                entry.setValue(entry.getValue() * 0.8f);
            }
        }
        minValueMap.putAll(fixedValueMap);
        List<Map.Entry<String, Float>> uniqueEntries = new ArrayList<>(minValueMap.entrySet());
        // 第三步：按 loraName 排序（保证输出顺序一致），组装字符串
        uniqueEntries.sort(Comparator.comparing(Map.Entry::getKey));

        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Float> entry : uniqueEntries) {
            String formattedValue = String.format("%.2f", entry.getValue());
            result.append(String.format("<lora:%s:%s>", entry.getKey(), formattedValue));
        }

        return result.toString();
    }
    public static boolean matchWord(String text, String word) {
        if (text == null || text.isEmpty() || word == null || word.isEmpty()) {
            return false;
        }
        String regex = "\\b" + Pattern.quote(word) + "\\b";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        return matcher.find();
    }
    public static boolean validatePrompt(String prompt) {
    	for(int i=0;i<prompt.length();i++){
			if(prompt.codePointAt(i)>0x100) {
				return false;
			}
		}
    	return true;
    }
    public static String appendCommonPrompt(String prompt,Collection<PromptClass> commons) {
    	Set<PromptClass> prompts=new LinkedHashSet<>(commons);
		if(!prompt.isEmpty()) {
			for(String s:prompt.split(",")) {
				prompts.add(new PromptClass(s));
			}
		}
		StringBuilder sb=new StringBuilder();
		for(PromptClass s:prompts) {
			sb.append(s).append(",");
		}
		sb.deleteCharAt(sb.length()-1);
		return sb.toString();
    }
    /**
     * 创建使用共享本地模型池的 SDXL 图片工具集。
     * <p>
     * 这是不带用户的旧入口，只使用服务器共享的本地模型；
     * 需要让某个用户的绘画会话优先使用该用户自己接入的本地模型时，
     * 使用 {@link #createRemoteLocalModel(String, ObjectStorageProvider, Map, BiFunction, boolean)}。
     * </p>
     *
     * @param tos       对象存储
     * @param lora      Lora 配置
     * @param urlGetter 图片 id → 图片内容
     * @param isNsfw    是否允许不宜内容
     * @return 工具集
     */
    public static SDXLMcp createRemoteLocalModel(ObjectStorageProvider tos,Map<String,LoraConfigurations> lora,BiFunction<String,AIOutput,String> urlGetter,boolean isNsfw) {
        return createRemoteLocalModel(null, tos, lora, urlGetter, isNsfw, false);
    }

    /**
     * 创建绑定到指定用户的 SDXL 图片工具集。
     * <p>
     * 工具执行时会把用户标识一起交给本地模型握手器：该用户接入了自己的本地绘画模型就用用户的，
     * 用户没有在线连接时自动回退到服务器共享的本地模型。用户接入或断开本地模型都会在下次调用时
     * 立刻生效，因此同一个用户的所有绘画会话（包括已经打开的会话）都会使用同一份模型选择。
     * </p>
     *
     * @param uid       用户标识，可为 null 或空（等价于共享池）
     * @param tos       对象存储
     * @param lora      Lora 配置
     * @param urlGetter 图片 id → 图片内容
     * @param isNsfw    是否允许不宜内容
     * @return 绑定该用户的工具集
     */
    public static SDXLMcp createRemoteLocalModel(String uid, ObjectStorageProvider tos,
            Map<String, LoraConfigurations> lora, BiFunction<String, AIOutput, String> urlGetter, boolean isNsfw) {
        return createRemoteLocalModel(uid, tos, lora, urlGetter, isNsfw, false);
    }

    /**
     * 创建绑定到指定用户的 SDXL 图片工具集，并指定结果内容里是否放对象存储 id。
     * <p>
     * 参考图（图生图 / 分区图生图 / 反推提示词）的发送方式取决于实际接管的本地模型连接器：
     * 连接器声明了分块/大报文能力（{@code flags} 含 {@code chunked}）时直接把图片文件以 Base64 放进请求报文，
     * 否则仍然只传 {@code urlGetter} 给出的链接。判断逻辑见
     * {@link #referencePayload(String, AIOutput)}。
     * </p>
     *
     * @param uid           用户标识，可为 null 或空（等价于共享池）
     * @param tos           对象存储
     * @param lora          Lora 配置
     * @param urlGetter     图片 id → 图片引用（通常是链接；支持分块的连接器下会被替换成文件内容）
     * @param isNsfw        是否允许不宜内容
     * @param contentIdMode true 表示结果内容里放对象存储 id，由 MCP 服务端转换成统一媒体入口
     * @return 绑定该用户的工具集
     */
    public static SDXLMcp createRemoteLocalModel(String uid, ObjectStorageProvider tos,
            Map<String, LoraConfigurations> lora, BiFunction<String, AIOutput, String> urlGetter, boolean isNsfw,
            boolean contentIdMode) {
        return create(tos, lora, isNsfw, urlGetter, remoteChannel(uid), contentIdMode, uid, true);
    }
	public static SDXLMcp createLocal(ObjectStorageProvider tos,Map<String,LoraConfigurations> lora,boolean isNsfw,ResourceLock lock) {
		return create(tos,lora,isNsfw,base64UrlGetter(tos),localChannel(lock));
	}

	/**
	 * 使用共享本地模型池的 SDXL 工具集（参考图读取方式与 {@link #createLocal} 相同）。
	 * <p>
	 * 与 {@link #createLocal} 的区别是：请求不直接发往 {@code -DsdwebuiUrl}，而是通过
	 * {@link LocalModel} 交给 {@code model_type=sdxl} 的本地模型客户端（例如
	 * {@code LocalModelConnectionService} 里的 {@link com.khjxiaogu.aiwuxia.vision.SDXLLocalModelHandler}）。
	 * 因此聊天进程不需要能访问 SD WebUI，服务端也不需要跑在同一个进程里。
	 * </p>
	 *
	 * @param tos    对象存储
	 * @param lora   Lora 配置
	 * @param isNsfw 是否允许不宜内容
	 * @return 工具集
	 */
	public static SDXLMcp createRemoteLocalModel(ObjectStorageProvider tos, Map<String, LoraConfigurations> lora,
			boolean isNsfw) {
		return createRemoteLocalModel(null, tos, lora, base64UrlGetter(tos), isNsfw);
	}

	/**
	 * 把对象存储里的图片 id 读成 Base64 的参考图读取器。
	 * <p>
	 * SDXL 的图生图/分区请求要求把参考图随 JSON 一起发送，本地与远程模型都使用同样的读取方式。
	 * </p>
	 *
	 * @param tos 对象存储
	 * @return 图片 id → Base64 的函数
	 */
	public static BiFunction<String, AIOutput, String> base64UrlGetter(ObjectStorageProvider tos) {
		return (id, output) -> {
			try {
				return Base64.getEncoder().encodeToString(tos.download(id, output == null ? null : output::addUsage));
			} catch (IOException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		};
	}

	/**
	 * 参考图在 SDXL 请求报文里的取值。
	 * <p>
	 * 经本地模型连接器执行、且该连接器声明了分块/大报文能力（握手 URL 或能力集合报文的 {@code flags} 含 {@code chunked}）时，
	 * 直接把图片文件以 Base64 放进报文，而不是只给一个链接：声明了该标记的连接接收大请求时会按分块
	 * 下发，不受单个 WebSocket 帧大小限制，连接器以及它后面的 SD WebUI 也不需要能访问本服务器
	 * 的图片链接。其它情况（例如浏览器接入页这类没有声明分块能力的连接）保持原来的链接形式；
	 * 读不到图片时也回退到链接，避免整次生成失败。
	 * </p>
	 *
	 * @param reference 图片引用：对象存储 id，或 {@code /painter/image?image_id=...}、
	 *                  {@code /aichat/mcp/media?file_id=...} 这类链接
	 * @param output    用量接收方，可为 null
	 * @return 放进请求报文的取值
	 */
	private String referencePayload(String reference, AIOutput output) {
		if (viaLocalModelConnector && LocalModel.supportsChunked(connectorUid, ModelType.SDXL)) {
			String inline = inlineReference(reference, output);
			if (inline != null) {
				return inline;
			}
		}
		return urlGetter.apply(reference, output);
	}

	/**
	 * 按对象存储 id 读取图片并编码成 Base64，供声明了分块能力的连接器直接在报文里接收文件。
	 *
	 * @param reference 图片引用
	 * @param output    用量接收方，可为 null
	 * @return Base64 内容；不是可读取的对象存储 id 或读取失败时返回 null
	 */
	private String inlineReference(String reference, AIOutput output) {
		String id = storageIdOf(reference);
		if (id == null || tos == null) {
			return null;
		}
		try {
			byte[] data = tos.download(id, output == null ? null : output::addUsage);
			if (data == null || data.length == 0) {
				System.err.println("[SDXL] 参考图内容为空，回退为链接：" + id);
				return null;
			}
			System.out.println("[SDXL] 连接器支持分块，参考图随报文下发：id=" + id + " 大小=" + data.length + "B");
			return Base64.getEncoder().encodeToString(data);
		} catch (Throwable ex) {
			System.err.println("[SDXL] 参考图读取失败，回退为链接：" + id + " 错误=" + ex);
			return null;
		}
	}

	/** 对象存储 id：72 位十六进制内容哈希，可带扩展名。 */
	private static final Pattern STORAGE_ID_PATTERN = Pattern.compile("[0-9a-fA-F]{72}(?:\\.[0-9A-Za-z]{1,16})?");
	/** 参考图链接里的对象存储 id 参数名。 */
	private static final String[] STORAGE_ID_PARAMS = { "file_id=", "image_id=" };

	/**
	 * 从图片引用里取出对象存储 id。
	 * <p>
	 * 既接受裸 id（72 位十六进制，可带扩展名），也接受本程序的图片链接：
	 * {@code /painter/image?image_id=...}、{@code /aichat/mcp/media?file_id=...}。
	 * </p>
	 *
	 * @param reference 图片引用，可为 null
	 * @return 对象存储 id；无法识别时返回 null
	 */
	public static String storageIdOf(String reference) {
		if (reference == null) {
			return null;
		}
		String value = reference.trim();
		if (value.isEmpty()) {
			return null;
		}
		if (STORAGE_ID_PATTERN.matcher(value).matches()) {
			return value;
		}
		for (String param : STORAGE_ID_PARAMS) {
			int index = value.indexOf(param);
			if (index < 0) {
				continue;
			}
			String id = value.substring(index + param.length());
			int amp = id.indexOf('&');
			if (amp >= 0) {
				id = id.substring(0, amp);
			}
			try {
				id = URLDecoder.decode(id, "UTF-8");
			} catch (java.io.UnsupportedEncodingException ex) {
				// UTF-8 一定可用；保留原值
			}
			id = id.trim();
			if (STORAGE_ID_PATTERN.matcher(id).matches()) {
				return id;
			}
		}
		return null;
	}
	/**
	 * 从字符串列表中搜索同时包含所有搜索关键词的字符串。
	 * 搜索关键词由 searchStr 按空格切分得到，多个关键词之间为“与”关系。
	 *
	 * @param list      待搜索的字符串列表
	 * @param searchStr 包含关键词的字符串，以空格分隔
	 * @return 包含所有关键词的字符串集合（HashSet，不保证顺序）
	 */
	public static Set<String> searchContainingAllKeywords(List<String> list, String searchStr) {
	    Set<String> result = new HashSet<>();
	    if (list == null || searchStr == null) {
	        return result;
	    }

	    // 按空格切分，并过滤掉开头/结尾空格导致的空串
	    String[] keywords = searchStr.trim().split("\\s+");
	    if (keywords.length == 0 || (keywords.length == 1 && keywords[0].isEmpty())) {
	        return result;
	    }

	    for (String item : list) {
	        if (item == null) {
	            continue;
	        }
	        boolean containsAll = true;
	        for (String keyword : keywords) {
	            if (!item.contains(keyword)) {
	                containsAll = false;
	                break;
	            }
	        }
	        if (containsAll) {
	            result.add(item);
	        }
	    }
	    if(result.isEmpty()) {
	        for (String item : list) {
		        if (item == null) {
		            continue;
		        }
		        for (String keyword : keywords) {
		            if (item.contains(keyword)) {
		 		        result.add(item);
		 		        break;
		            }
		        }
		       
		    }
	    }
	    return result;
	}
	public static List<String> readLinesFromFile(File filePath) {
	    // 使用 Files.readAllLines，默认 UTF-8 编码
		if(!filePath.exists())
			return null;
	    try {
			return Files.readAllLines(filePath.toPath(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			e.printStackTrace();
		}
	    return null;
	}
	/**
	 * 创建 SDXL 图片工具集。
	 *
	 * @param tos       对象存储
	 * @param lora      Lora 配置
	 * @param isNsfw    是否允许不宜内容
	 * @param urlGetter 图片 id → 图片内容
	 * @param channel   SDXL 执行通道
	 * @return 工具集
	 */
	public static SDXLMcp create(ObjectStorageProvider tos, Map<String, LoraConfigurations> lora, boolean isNsfw,
			BiFunction<String, AIOutput, String> urlGetter, Channel channel) {
		return create(tos, lora, isNsfw, urlGetter, channel, false);
	}

	/**
	 * 创建图片工具集，并指定结果内容里是否放对象存储 id。
	 *
	 * @param tos           对象存储
	 * @param lora          Lora 配置
	 * @param isNsfw        是否允许不宜内容
	 * @param urlGetter     图片 id → 图片内容
	 * @param channel       SDXL 执行通道
	 * @param contentIdMode true 表示结果内容里放对象存储 id，由 MCP 服务端统一转换链接
	 * @return 工具集
	 */
	public static SDXLMcp create(ObjectStorageProvider tos, Map<String, LoraConfigurations> lora, boolean isNsfw,
			BiFunction<String, AIOutput, String> urlGetter, Channel channel, boolean contentIdMode) {
		return create(tos, lora, isNsfw, urlGetter, channel, contentIdMode, null, false);
	}

	/**
	 * 创建图片工具集，并指定参考图在本地模型连接器上的发送方式所需的信息。
	 *
	 * @param tos                  对象存储
	 * @param lora                 Lora 配置
	 * @param isNsfw               是否允许不宜内容
	 * @param urlGetter            图片 id → 图片引用（链接或 Base64）
	 * @param channel              SDXL 执行通道
	 * @param contentIdMode        true 表示结果内容里放对象存储 id，由 MCP 服务端统一转换链接
	 * @param connectorUid         本地模型连接器所属用户，可为 null（共享连接池）
	 * @param viaLocalModelConnector 是否经本地模型连接器执行；true 时连接器声明了 {@code flags=chunked}
	 *                             就把参考图文件直接放进报文
	 * @return 工具集
	 */
	public static SDXLMcp create(ObjectStorageProvider tos, Map<String, LoraConfigurations> lora, boolean isNsfw,
			BiFunction<String, AIOutput, String> urlGetter, Channel channel, boolean contentIdMode,
			String connectorUid, boolean viaLocalModelConnector) {
		return new SDXLMcp(tos, lora, isNsfw, urlGetter, channel, contentIdMode, connectorUid,
				viaLocalModelConnector);
	}

	/**
	 * 创建角色提示词搜索工具。该工具与 SDXL 图片工具没有共享状态，因此独立注册。
	 *
	 * @param charas 角色提示词列表
	 * @return 独立的工具定义
	 */
	public static ToolData createSearchCharacter(List<String> charas) {
		return new ToolData.Builder("search_character", "通过关键词搜索特定角色的提示词，返回相符的提示词，以,隔开")
				.putParam("keyword", "角色英文名", "角色关键词")
				.presenter(ToolCallPresenter.ofArgument("搜索角色", "keyword"))
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();

						String keyword = jo.get("keyword").getAsString();
						System.out.println(keyword);
						StringBuilder sb = new StringBuilder("符合的角色提示词列表：");
						for (String key : searchContainingAllKeywords(charas, keyword)) {
							sb.append(key).append(",");
						}
						return CompletableFuture.completedFuture(ToolResult.text(sb.toString()));
					} catch (Exception e) {
						e.printStackTrace();
					}
					return CompletableFuture.completedFuture(ToolResult.text("参数格式错误"));
				}).build();
	}

	public SDXLMcp(ObjectStorageProvider tos, Map<String, LoraConfigurations> lora, boolean isNsfw,
			BiFunction<String, AIOutput, String> urlGetter, Channel channel) {
		this(tos, lora, isNsfw, urlGetter, channel, false);
	}

	public SDXLMcp(ObjectStorageProvider tos, Map<String, LoraConfigurations> lora, boolean isNsfw,
			BiFunction<String, AIOutput, String> urlGetter, Channel channel, boolean contentIdMode) {
		this(tos, lora, isNsfw, urlGetter, channel, contentIdMode, null, false);
	}

	/**
	 * 创建 SDXL 图片工具集。
	 *
	 * @param tos                    对象存储
	 * @param lora                    Lora 配置
	 * @param isNsfw                  是否允许不宜内容
	 * @param urlGetter               图片 id → 图片引用（链接或 Base64）
	 * @param channel                 SDXL 执行通道
	 * @param contentIdMode           true 表示结果内容里放对象存储 id
	 * @param connectorUid            本地模型连接器所属用户，可为 null（共享连接池）
	 * @param viaLocalModelConnector  是否经本地模型连接器执行；为 true 时按连接器的分块能力
	 *                                决定参考图是内嵌文件还是只给链接
	 */
	public SDXLMcp(ObjectStorageProvider tos, Map<String, LoraConfigurations> lora, boolean isNsfw,
			BiFunction<String, AIOutput, String> urlGetter, Channel channel, boolean contentIdMode,
			String connectorUid, boolean viaLocalModelConnector) {
		this.tos = tos;
		this.lora = lora;
		this.isNsfw = isNsfw;
		this.urlGetter = urlGetter;
		this.channel = channel;
		this.contentIdMode = contentIdMode;
		this.connectorUid = connectorUid;
		this.viaLocalModelConnector = viaLocalModelConnector;

		resolutions.put("16:9", new int[] { 1920, 1080 });
		resolutions.put("9:16", new int[] { 1080, 1920 });
		resolutions.put("3:4", new int[] { 1200, 1600 });
		resolutions.put("4:3", new int[] { 1600, 1200 });
		resolutions.put("1:1", new int[] { 1200, 1200 });
		// 手部相关的负向词默认覆盖多指/缺指/粘连等；正向补强由绘画子 agent 的提示词负责
		for (String s : "low quality,bad anatomy,unnatural hair color,bad feet,malformed hands,bad hands,deformed hands,missing fingers,extra fingers,extra digits,fused fingers,too many fingers,poorly drawn hands,malformed limbs,missing limb,mutated hands,extra arms,extra limb,mutated hands and fingers,extra legs,floating limbs,disconnected limbs,trademark,artist's name,username,watermark,signature,watermark,text,words,extra limbs,missing limbs,distorted,blurry,fused toes,too many toes,extra toes"
				.split(",")) {
			commonNegatives.add(new PromptClass(s));
		}
		for (String s : "(masterpiece),(best quality),(absurdres)".split(",")) {
			commonPositive.add(new PromptClass(s));
		}
		if (!isNsfw) {
			commonNegatives.add(new PromptClass("nsfw", "((nsfw))"));
			commonNegatives.add(new PromptClass("nipples", "((nipples))"));
			commonNegatives.add(new PromptClass("pussy", "((pussy))"));
			commonNegatives.add(new PromptClass("penis", "((penis))"));
		}

		tools.add(new ToolData.Builder("image_interrogate", "通过WD1.4模型反推图片的所有可能提示词按可能性从大到小排列，需要结合图片描述功能把错误的提示词排除。")
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身，不得包含任何其他内容", "图片ID")
				.presenter(ToolCallPresenter.ofName("反推提示词"))
				.tool((data, output, manager) -> {
					Pattern patt = Pattern.compile("\"picture_id\"\\s*:\\s*\"([^\"]+)\"");
					Matcher mtch = patt.matcher(data);
					if (mtch.find()) {
						String id = mtch.group(1);
						try {
							JsonObject datax = parseJson(generate(SdxlAction.INTERROGATE, "", "", 1, 64, 64, id, 0, false, "", output));
							StringBuilder sb=new StringBuilder("按可能性从大到小排列的提示词：");
							for(String key:datax.keySet()) {
								sb.append(key).append(",");
							}
							if(output!=null)
								output.addUsage(new LocalSDXLUsage(0,1));
							return CompletableFuture.completedFuture(ToolResult.text(sb.toString()));
						} catch (IOException e) {
							e.printStackTrace();
						}
						return CompletableFuture.completedFuture(ToolResult.failure("服务暂不可用"));
						
					}
					return CompletableFuture.completedFuture(ToolResult.text("参数格式错误"));
				}).build());
		
		tools.add(new ToolData.Builder("sdxl_gen_image",
				"使用Stable Diffusion XL生成图片，生成后需要使用其他工具发送。")
				.putParam("positive", "正面提示词，必须是纯英文，不需要包含画质提示词。", "正面提示词")
				.putParam("negative", "负面提示词，必须是纯英文，不需要包含画质提示词。", "负面提示词")
				.putParam("resolution", "画面比例，必须是16:9/9:16/4:3/3:4之一", "画面比例")
				.presenter(call->{
					JsonObject jo=JsonParser.parseString(call.function.arguments).getAsJsonObject();
					MutableMessageContents content=new MutableMessageContents();
					content.append("正面:"+jo.get("positive").getAsString());
					content.append("\n负面:"+jo.get("negative").getAsString());
					content.append("\n画面比例:"+jo.get("resolution").getAsString());
					return ToolCallDisplay.of("文生图", content);
				})
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						System.out.println(data);
						String resolution = jo.get("resolution").getAsString();
						int[] its = resolutions.get(resolution);
						if (its == null)
							return CompletableFuture.completedFuture(ToolResult.text("不支持的分辨率设置"));
						String prompt=jo.get("positive").getAsString();
						String negative=jo.get("negative").getAsString();
						String extras="";
						if(!validatePrompt(prompt)||!validatePrompt(negative)) {
							return CompletableFuture.completedFuture(ToolResult.text("失败，提示词中有非英文/符号的内容。"));
						}
						prompt=appendCommonPrompt(prompt,commonPositive);
						negative=appendCommonPrompt(negative,commonNegatives);
						Set<LoraConfigurations> loras=addLoras(prompt,lora);
						prompt=prompt+processLoraConfigurations(loras);
						if(loras.size()>1)
							extras="生成多人图片需要使用regional_image，使用该工具生成图片可能混乱，需要检查。";

						
						System.out.println(prompt);
						System.out.println(negative);
						try {
							byte[] image=generate(SdxlAction.TXT2IMG,prompt,negative,20,its[0],its[1],null,0,false,"",output);
							
							String fn = tos.uploadIfNotExists(image,output == null ? null : output::addUsage);
							Builder builder = AIRequest.builder("imageRecognize").taskType(TaskType.STORY)
									.multimodal(MultimodalType.IMAGE_ONLY).strength(ReasoningStrength.WEAK).storage(tos);
							builder.addHistoryItem(Role.SYSTEM,
									"请观察图片，简要描述图片内容，并判断其是否为18+成人向图片并给出原因。"
									+ "判定标准：直接裸露性器官，出现女性角色直接完整的乳头（仅露出胸部不算），血腥恐怖内容。仅擦边不属于。");

							// 消息里只放对象存储 ID，模型调用层再解析成可访问地址
							builder.addHistoryItem(
									new DirectHistoryItem(Role.USER, new ImageContent(fn)));
							builder.temperature(1.3f).maxTokens(3000).streamed();
							String caption="";
							for(int i=0;i<3;i++)
								try {
									AIOutput inner=LLMConnector.call(builder.build());
									if(output!=null)
										inner.addUsageListener(output::addUsage);
									caption= FileUtil.printAndCollectContent(inner.getContent()).toText();
									break;
								}catch(Exception err) {
									err.printStackTrace();
								}
							if(output!=null)
								output.addUsage(new LocalSDXLUsage(1,0));
							return CompletableFuture.completedFuture(
									imageResult("生成成功，", fn, imageText(fn, extras, caption)));
						} catch (IOException e) {
							e.printStackTrace();
							return CompletableFuture.completedFuture(ToolResult.failure("图片生成失败，服务暂不可用。"));
						}
					}catch(Throwable t) {
						t.printStackTrace();
						return CompletableFuture.completedFuture(ToolResult.text("参数格式错误"));
					}
				}).build());
		tools.add(new ToolData.Builder("regional_image",
				"使用Stable Diffusion XL搭配分区提示词生成图片，不需要包含画质提示词，提示词必须是纯英文，生成后需要使用其他工具发送。")
				.putParam("picture_id", "如果要使用图生图，务必在这里填上图片ID，否则不填或留空", "图片ID")
				.putParam("strength", "如果不使用图生图，该参数无效。重绘幅度，介于0与1.5之间，该重绘幅度并非sdxl的参数。0为微调图片细节，1为重绘图片，需要输入字符串。", "重绘幅度")
				.putParam("common_positive", "共用正面提示词", "共用正面提示词")
				.putParam("first_positive", "第一区域提示词，必须是纯英文，不需要包含画质提示词。", "第一区域正面提示词")
				.putParam("second_positive", "第二区域提示词，必须是纯英文，不需要包含画质提示词。", "第二区域正面提示词")
				.putParam("common_negative", "共用负面提示词，必须是纯英文，不需要包含画质提示词。", "共用负面提示词")
				.putParam("placement", "区域排布，必须是row/col之一，col表示两个区域呈现左右结构，row表示两个区域呈现上下结构", "区域排布")
				.putParam("area_ratio", "区域比例，必须是以x:y的格式，其中x是第一区域面积占比，y是第二区域面积占比，x和y必须为整数。例如1:1", "区域比例")
				.putParam("resolution", "画面比例，必须是16:9/9:16/4:3/3:4之一", "画面比例")
				.presenter(call->{
					JsonObject jo=JsonParser.parseString(call.function.arguments).getAsJsonObject();
					MutableMessageContents content=new MutableMessageContents();
					content.append("1区正面:"+jo.get("first_positive").getAsString());
					content.append("\n2区正面:"+jo.get("second_positive").getAsString());
					content.append("\n共同正面:"+jo.get("common_positive").getAsString());
					content.append("\n排版:"+("row".equals(jo.get("placement").getAsString())?"上下":"左右"));
					content.append("\n负面:"+jo.get("common_negative").getAsString());
					content.append("\n区域比例:"+jo.get("area_ratio").getAsString());
					content.append("\n画面比例:"+jo.get("resolution").getAsString());
					return ToolCallDisplay.of("分区图片", content);
				})
				.tool((data, output, manager) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					System.out.println(data);
					try {
						String resolution = jo.get("resolution").getAsString();
						int[] its = resolutions.get(resolution);
						if (its == null)
							return CompletableFuture.completedFuture(ToolResult.text("不支持的分辨率设置"));
						String placement=jo.get("placement").getAsString();
						boolean isRow=false;
						if(placement.startsWith("row")) {
							isRow=true;
						}else if(placement.startsWith("col")) {
							isRow=false;
						}else {
							return CompletableFuture.completedFuture(ToolResult.text("区域排布错误，区域排布必须是row/col之一"));
						}
						String area_ratio=jo.get("area_ratio").getAsString();
						if(!area_ratio.contains(":")) {
							return CompletableFuture.completedFuture(ToolResult.text("区域比例错误，必须是x:y的格式"));
						}
						String[] ratios=area_ratio.split(":");
						if(ratios.length!=2) {
							return CompletableFuture.completedFuture(ToolResult.text("区域比例错误，必须是x:y的格式"));
						}


						String prompt=appendCommonPrompt(jo.get("common_positive").getAsString(),commonPositive);
						String negative=appendCommonPrompt(jo.get("common_negative").getAsString(),commonNegatives);
						String ratio=ratios[0]+","+ratios[1];
						prompt=prompt+" BREAK\n";
						prompt=prompt+jo.get("first_positive").getAsString()+" BREAK\n";
						prompt=prompt+jo.get("second_positive").getAsString();
						Set<LoraConfigurations> loras=addLoras(prompt,lora);
						prompt=prompt+processLoraConfigurations(loras);
						System.out.println(prompt);
						System.out.println(negative);
						if(!validatePrompt(prompt)||!validatePrompt(negative)) {
							return CompletableFuture.completedFuture(ToolResult.text("失败，提示词中有非英文/符号的内容。"));
						}
						try {
							byte[] image;
							if(jo.has("picture_id")&&jo.get("picture_id").getAsString().length()>20) {
								float strength=1;
								if(jo.has("strength")) {
									JsonPrimitive jes=jo.get("strength").getAsJsonPrimitive();
									if(jes.isString())
										strength=Float.parseFloat(jes.getAsString());
									else if(jes.isNumber())
										strength=jes.getAsFloat();
								}
								int regionalSteps=(int) (20+(Math.min(Math.max(strength-0.5, 0),1)*10));
								image=generate(SdxlAction.REGIONAL,prompt,negative,regionalSteps,its[0],its[1],
										jo.get("picture_id").getAsString(),strength*0.2f+0.6f,isRow,ratio,output);
							}else{
								image=generate(SdxlAction.REGIONAL,prompt,negative,30,its[0],its[1],
										null,0,isRow,ratio,output);
							}
							
							String fn = tos.uploadIfNotExists(image,output == null ? null : output::addUsage);
							Builder builder = AIRequest.builder("imageRecognize").taskType(TaskType.STORY)
									.multimodal(MultimodalType.IMAGE_ONLY).strength(ReasoningStrength.WEAK).storage(tos);
							builder.addHistoryItem(Role.SYSTEM,
									"请观察图片，简要描述图片内容，并判断其是否为18+成人向图片并给出原因。"
									+ "判定标准：直接裸露性器官，出现女性角色直接完整的乳头（仅露出胸部不算），血腥恐怖内容。仅擦边不属于。");

							// 消息里只放对象存储 ID，模型调用层再解析成可访问地址
							builder.addHistoryItem(
									new DirectHistoryItem(Role.USER, new ImageContent(fn)));
							builder.temperature(1.3f).maxTokens(3000).streamed();
							String caption="";
							for(int i=0;i<3;i++)
								try {
									AIOutput inner=LLMConnector.call(builder.build());
									if(output!=null)
										inner.addUsageListener(output::addUsage);
									caption= FileUtil.printAndCollectContent(inner.getContent()).toText();
									break;
								}catch(Exception err) {
									err.printStackTrace();
								}
							if(output!=null)
								output.addUsage(new LocalSDXLUsage(2,0));
							return CompletableFuture.completedFuture(
									imageResult("生成成功，", fn, imageText(fn, "", caption)));
						} catch (IOException e) {
							e.printStackTrace();
							return CompletableFuture.completedFuture(ToolResult.failure("图片生成失败，服务暂不可用。"));
						}
					}catch(Throwable t) {
						t.printStackTrace();
						return CompletableFuture.completedFuture(ToolResult.text("参数格式错误"));
					}
				}).build());
		tools.add(new ToolData.Builder("image_to_image",
				"使用Stable Diffusion XL的图生图功能，生成后需要使用其他工具发送。")
				.putParam("positive", "正面提示词，必须是纯英文。", "正面提示词")
				.putParam("negative", "负面提示词，必须是纯英文。", "负面提示词")
				.putParam("resolution", "画面比例，必须是16:9/9:16/4:3/3:4之一", "画面比例")
				.putParam("strength", "重绘幅度，介于0与1.5之间，该重绘幅度并非sdxl的参数。0为微调图片细节，1为重绘图片，需要输入字符串。", "重绘幅度")
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身，不得包含任何其他内容", "图片ID")

				.presenter(call->{
					JsonObject jo=JsonParser.parseString(call.function.arguments).getAsJsonObject();
					MutableMessageContents content=new MutableMessageContents();
					content.addRaw(new ImageContent(jo.get("picture_id").getAsString()));
					content.append("正面:"+jo.get("positive").getAsString());
					content.append("\n负面:"+jo.get("negative").getAsString());
					content.append("\n画面比例:"+jo.get("resolution").getAsString());
					content.append("\n强度:"+jo.get("strength").getAsString());
					return ToolCallDisplay.of("图生图", content);
				})
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						System.out.println(data);
						String resolution = jo.get("resolution").getAsString();
						int[] its = resolutions.get(resolution);
						if (its == null)
							return CompletableFuture.completedFuture(ToolResult.text("不支持的分辨率设置"));
						String prompt=jo.get("positive").getAsString();
						String negative=jo.get("negative").getAsString();
						String pid=jo.get("picture_id").getAsString();
						float strength=1;
						if(jo.has("strength")) {
							JsonPrimitive jes=jo.get("strength").getAsJsonPrimitive();
							if(jes.isString())
								strength=Float.parseFloat(jes.getAsString());
							else if(jes.isNumber())
								strength=jes.getAsFloat();
						}
						prompt=appendCommonPrompt(prompt,commonPositive);
						negative=appendCommonPrompt(negative,commonNegatives);
						Set<LoraConfigurations> loras=addLoras(prompt,lora);
						prompt=prompt+processLoraConfigurations(loras);
						System.out.println(prompt);
						System.out.println(negative);
						try {

							byte[] image=generate(SdxlAction.IMG2IMG,prompt,negative,
									(int) (20+(Math.min(Math.max(strength-0.5, 0),1)*10)),
									its[0],its[1],pid,strength*0.2f+0.6f,false,"",output);

							
							String fn = tos.uploadIfNotExists(image,output == null ? null : output::addUsage);
							Builder builder = AIRequest.builder("imageRecognize").taskType(TaskType.STORY)
									.multimodal(MultimodalType.IMAGE_ONLY).strength(ReasoningStrength.WEAK).storage(tos);
							builder.addHistoryItem(Role.SYSTEM,
									"请观察图片，简要描述图片内容，并判断其是否为18+成人向图片并给出原因。"
									+ "判定标准：直接裸露性器官，出现女性角色直接完整的乳头（仅露出胸部不算），血腥恐怖内容。仅擦边不属于。");

							// 消息里只放对象存储 ID，模型调用层再解析成可访问地址
							builder.addHistoryItem(
									new DirectHistoryItem(Role.USER, new ImageContent(fn)));
							AIOutput inner=LLMConnector.call(builder.temperature(1.3f).maxTokens(3000).build());
							if(output!=null)
								inner.addUsageListener(output::addUsage);
							String caption= FileUtil.printAndCollectContent(
								inner.getContent()).toText();
							if(output!=null)
								output.addUsage(new LocalSDXLUsage(1,0));
							return CompletableFuture.completedFuture(
									imageResult("生成成功，", fn, imageText(fn, "", caption)));
						} catch (IOException e) {
							e.printStackTrace();
							return CompletableFuture.completedFuture(ToolResult.failure("图片生成失败，服务暂不可用。"));
						}
					}catch(Throwable t) {
						t.printStackTrace();
						return CompletableFuture.completedFuture(ToolResult.text("参数格式错误"));
					}
				}).build());
	}

	@Override
	public Collection<ToolData> getTools() {
		return java.util.Collections.unmodifiableList(tools);
	}

	/**
	 * 图片结果的文本：MCP 模式已经在结果内容里带上图片，不再重复赘述图片 id；
	 * 普通绘画会话仍保留 id，方便后续图生图使用。
	 */
	private String imageText(String fn, String extras, String caption) {
		return (contentIdMode ? "" : "图片id为：" + fn + "。") + extras + caption;
	}

	/**
	 * 构造图片生成工具的结果内容：图片 Content 插在前后文本之间。
	 * <p>
	 * 图片只放在结果内容里提供给模型进一步处理，不放进给用户的附件。
	 * </p>
	 *
	 * @param before   图片前的文本
	 * @param imageId 生成图片的对象存储 ID
	 * @param after    图片后的文本
	 * @return 工具结果
	 */
	private static ToolResult imageResult(String before, String imageId, String after) {
		MutableMessageContents content = new MutableMessageContents();
		content.append(before);
		content.addRaw(new ImageContent(imageId));
		content.append(after);
		return ToolResult.content(content);
	}

	public static Set<LoraConfigurations> addLoras(String prompt,Map<String, LoraConfigurations> lora) {
		Set<LoraConfigurations> usingLoras=new HashSet<>();
		for(Entry<String, LoraConfigurations> s:lora.entrySet()) {
			if(matchWord(prompt,s.getKey()))
				usingLoras.add(s.getValue());
		}
		return usingLoras;
	}
}
