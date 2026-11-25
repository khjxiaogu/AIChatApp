package com.khjxiaogu.aiwuxia.llm;

import java.lang.reflect.Type;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ModelCategory;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.AudioContent;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.ServerToolUseContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolContent;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;

public class HistoryRequestBuilder {
	public static class ToolCallSerilizer implements JsonSerializer<ToolCall>{

		Gson rawGs=new Gson();
		@Override
		public JsonElement serialize(ToolCall src, Type typeOfSrc, JsonSerializationContext context) {
			
			JsonElement serailized =rawGs.toJsonTree(src);
			if(serailized.isJsonObject())
				serailized.getAsJsonObject().addProperty("type", "function");
			return serailized;
		}

	}
	public static Gson gs=new GsonBuilder().registerTypeHierarchyAdapter(ToolCall.class, new ToolCallSerilizer()).create();

	/**
	 * 多模态内容项的序列化策略。
	 * <p>
	 * 各提供者的请求格式并不完全一致（视频的 fps 放哪、音频字段名、以及是否支持该模态都可能不同），
	 * 因此具体内容块由提供者自己实现；本类只负责公共的消息骨架（角色、思考内容、工具调用/结果、
	 * 服务端联网记录等）。返回 {@code null} 表示该内容项按带 ID 的文本标签降级。
	 * </p>
	 */
	@FunctionalInterface
	public interface MediaPartSerializer {
		/**
		 * 把一段多媒体内容序列化成提供者要求的 content 内容项。
		 *
		 * @param content  内容（图片/视频/音频）
		 * @param resolver 附件解析器：对象存储 ID → 提供者可访问的地址
		 * @return 内容项；{@code null} 表示不支持该内容，调用方降级为文本
		 */
		JsonObject serialize(MessageContent content, AttachmentResolver resolver);
	}

	/**
	 * 默认策略：只序列化图片（OpenAI 兼容的 {@code image_url}），视频/音频降级为文本标签。
	 * <p>
	 * 仅用于没有自定义多模态格式的提供者；需要视频/音频的提供者应传入自己的策略。
	 * </p>
	 */
	public static final MediaPartSerializer OPENAI_MEDIA_PARTS = (content, resolver) -> {
		if (content instanceof ImageContent) {
			ImageContent image = (ImageContent) content;
			return imagePart(resolver.resolveUrl(image.getImageUrl()));
		}
		return null;
	};
	/**
	 * 构造 OpenAI 兼容的 messages 数组，不回传服务端联网工具的调用记录。
	 * <p>
	 * 供不认识 {@code server_tool_use} 的提供者（火山方舟、SiliconFlow、MiMo 等）使用：
	 * 这类记录会降级成 {@link ServerToolUseContent#toText()} 给出的占位文本，
	 * 与普通思考内容一样被截断，不会把官方原始块塞进请求。
	 * </p>
	 *
	 * @param request 请求
	 * @return messages 数组
	 */
	public static JsonArray createRequest(AIRequest request) {
		return createRequest(request, false, OPENAI_MEDIA_PARTS);
	}

	/**
	 * 构造 OpenAI 兼容的 messages 数组。
	 * <p>
	 * {@code replayServerToolCalls} 为 {@code true} 时，把历史里 DeepSeek 服务端联网工具的调用记录
	 * （{@link ServerToolUseContent}）原样挂到该轮助手消息的 {@code server_tool_calls} 字段上：
	 * 官方要求把上一轮的调用原样回传，{@code read_link} 的网页正文（{@code signature}）才能拼回上下文。
	 * 为 {@code false} 时这些记录改为占位文本，其余模型据此仍能看到“这一轮查过什么”。
	 * </p>
	 *
	 * @param request                请求
	 * @param replayServerToolCalls  是否原样回传服务端联网工具的调用记录
	 * @return messages 数组
	 */
	public static JsonArray createRequest(AIRequest request, boolean replayServerToolCalls) {
		return createRequest(request, replayServerToolCalls, OPENAI_MEDIA_PARTS);
	}

	/**
	 * 构造 OpenAI 兼容的 messages 数组，并指定多模态内容项的序列化策略。
	 * <p>
	 * 消息骨架由本类统一构造；图片/视频/音频的内容项怎么写由提供者通过 {@code media} 决定，
	 * 因为各家的字段结构不一致（例如视频 fps 的位置、音频字段名）。请求声明的模态由
	 * {@link AIRequest#multimodal} 把关：未声明的模态不会交给序列化策略，而是降级成文本标签。
	 * </p>
	 *
	 * @param request                请求
	 * @param replayServerToolCalls  是否原样回传服务端联网工具的调用记录
	 * @param media                  多媒体内容项序列化策略，为 {@code null} 时只按文本降级
	 * @return messages 数组
	 */
	public static JsonArray createRequest(AIRequest request, boolean replayServerToolCalls,
			MediaPartSerializer media) {
		JsonArray messages=new JsonArray();
		AttachmentResolver resolver=AttachmentResolver.of(request);
		for(HistoryItem hi:request.history) {
			// 本条记录写入的第一条消息下标：服务端联网调用只能挂到本条记录自己的助手消息上
			int firstMessage=messages.size();
			boolean shouldContainReasoner=false;
			boolean shouldSkipContent=false;
			JsonArray serverToolCalls=new JsonArray();
			if(hi.getReasoningContent()!=null&&!hi.getReasoningContent().isEmpty()) {
				for(MessageContent msgc:hi.getReasoningContent()) {
					// 服务端联网调用的记录也要走“按段重建消息”的路径：
					// 回传模式下要原样挂回 server_tool_calls，其它模型则要换成占位文本，
					// 两种模式都不能让它跟着普通记录被折叠掉
					if(msgc instanceof ToolContent||msgc instanceof ServerToolUseContent) {
						shouldContainReasoner=true;
						break;
					}
				}
				boolean hasPrevious=false;
				if(shouldContainReasoner) {
					for(MessageContent msgc:hi.getReasoningContent()) {
						if(msgc instanceof ToolContent) {
							messages.add(createToolMessage((ToolContent) msgc, request, resolver));
							hasPrevious=false;
						}else if(msgc instanceof ToolCallContent){
							if(hasPrevious) {
								messages.get(messages.size()-1).getAsJsonObject().add("tool_calls", gs.toJsonTree(((ToolCallContent) msgc).getToolCalls()));
							}else {
	
								messages.add(createReasonerMessage("",((ToolCallContent) msgc).getToolCalls()));
							}
						}else if(msgc instanceof ServerToolUseContent){
							ServerToolUseContent serverToolUse=(ServerToolUseContent) msgc;
							if(replayServerToolCalls) {
								// 原样累积，最后统一挂到本条记录的助手消息上
								for(JsonElement step:serverToolUse.getServerToolCalls()) {
									serverToolCalls.add(step);
								}
							}else {
								// 其它模型不认识 server_tool_use：换成带截断的占位文本，
								// 有上一条助手消息就并入它的思考，保持一条消息对应一轮回答
								String placeholder=serverToolUse.toText();
								if(hasPrevious) {
									JsonObject previous=messages.get(messages.size()-1).getAsJsonObject();
									String reasoning=JsonConfig.getString(previous, "reasoning_content");
									previous.addProperty("reasoning_content",
											reasoning==null||reasoning.isEmpty()?placeholder:reasoning+"\n"+placeholder);
								}else {
									hasPrevious=true;
									messages.add(createReasonerMessage(placeholder,null));
								}
							}
						}else {
							hasPrevious=true;
							messages.add(createReasonerMessage(msgc.toText(),null));
						}
					}
					if(hasPrevious) {
						messages.get(messages.size()-1).getAsJsonObject().add("content", createMessageContent(hi.getContextContent(), request, resolver, media));
						shouldSkipContent=true;
					}
				}
			}
			
			if(!shouldSkipContent) {
				JsonObject msg=new JsonObject();
				msg.addProperty("role", hi.getRole().getRoleName());
				msg.add("content", createMessageContent(hi.getContextContent(), request, resolver, media));
				messages.add(msg);
			}

			if(serverToolCalls.size()>0) {
				attachServerToolCalls(messages, firstMessage, serverToolCalls);
			}
		}
		
		if(request.prefix!=null&&request.category!=ModelCategory.REASONING) {
			JsonObject msg=new JsonObject();
			msg.addProperty("role", "assistant");
			msg.addProperty("content", request.prefix);
			msg.addProperty("prefix", true);
			messages.add(msg);
		}
		return messages;
	}

	/**
	 * 把一轮服务端联网工具的调用记录挂到本轮最后一条助手消息上。
	 * <p>
	 * 官方的多轮要求是“把上一条 assistant 消息连同 {@code server_tool_calls} 原样回传”，
	 * 所以这里优先复用本轮已经构造出来的助手消息（通常就是带最终回答的那条），
	 * 只在极端情况下（本轮没有任何助手消息）才补一条空内容的助手消息。
	 * </p>
	 *
	 * @param messages        已构造的 messages 数组
	 * @param from            本条记录写出的第一条消息下标
	 * @param serverToolCalls 本轮累积的服务端调用（为空时不做任何事）
	 */
	private static void attachServerToolCalls(JsonArray messages, int from, JsonArray serverToolCalls) {
		if(serverToolCalls==null||serverToolCalls.size()==0) {
			return;
		}
		for(int i=messages.size()-1;i>=from;i--) {
			if(!messages.get(i).isJsonObject()) {
				continue;
			}
			JsonObject message=messages.get(i).getAsJsonObject();
			if(!Role.ASSISTANT.getRoleName().equals(JsonConfig.getString(message, "role"))) {
				continue;
			}
			message.add("server_tool_calls", serverToolCalls);
			return;
		}
		JsonObject message=new JsonObject();
		message.addProperty("role", Role.ASSISTANT.getRoleName());
		message.addProperty("content", "");
		message.add("server_tool_calls", serverToolCalls);
		messages.add(message);
	}

	/**
	 * 构造一条普通消息的 content。
	 * <p>
	 * 纯文本保持字符串形式；只有请求声明了对应模态、消息里确实有该模态的附件、并且提供者的
	 * {@link MediaPartSerializer} 能序列化它时，才按 content 数组提交，附件地址由
	 * {@link AttachmentResolver} 从对象存储 ID 解析。其余情况（未声明模态、提供者不支持）
	 * 一律降级为带 ID 的文本标签，消息里仍然只有对象存储 ID。
	 * </p>
	 */
	private static JsonElement createMessageContent(MessageContents contents, AIRequest request,
			AttachmentResolver resolver, MediaPartSerializer media) {
		if (contents == null) {
			return new JsonPrimitive("");
		}
		JsonArray array = new JsonArray();
		boolean hasMediaPart = false;
		for (MessageContent content : contents) {
			if (content == null) {
				continue;
			}
			JsonObject part = null;
			if (media != null && isDeclaredMedia(content, request)) {
				part = media.serialize(content, resolver);
			}
			if (part != null) {
				array.add(part);
				hasMediaPart = true;
			} else {
				JsonObject text = new JsonObject();
				text.addProperty("type", "text");
				text.addProperty("text", resolvedText(content, resolver));
				array.add(text);
			}
		}
		if (!hasMediaPart) {
			return new JsonPrimitive(normalizeIds(contents, resolver));
		}
		return array;
	}

	/** 内容是否是请求已声明、可以交给提供者序列化策略的多媒体类型。 */
	private static boolean isDeclaredMedia(MessageContent content, AIRequest request) {
		if (content instanceof ImageContent) {
			return request.multimodal.image;
		}
		if (content instanceof VideoContent) {
			return request.multimodal.video;
		}
		if (content instanceof AudioContent) {
			return request.multimodal.audio;
		}
		return false;
	}

	/**
	 * 把消息里的附件引用统一成对象存储 ID 的文本表示，供不支持多模态的提供者使用。
	 */
	private static String normalizeIds(MessageContents contents, AttachmentResolver resolver) {
		if (!resolver.hasStorage()) {
			return contents.toText();
		}
		StringBuilder text = new StringBuilder();
		for (MessageContent content : contents) {
			text.append(resolvedText(content, resolver));
		}
		return text.toString();
	}

	/**
	 * 取单段内容的文本表示；图片/视频/音频先把引用规范化为对象存储 ID，保证文本里只有 ID。
	 */
	private static String resolvedText(MessageContent content, AttachmentResolver resolver) {
		if (content instanceof ImageContent) {
			ImageContent image = (ImageContent) content;
			return new ImageContent(resolver.resolveId(image.getImageUrl()), image.getDescription(),
					image.isCensored()).toText();
		}
		if (content instanceof VideoContent) {
			VideoContent video = (VideoContent) content;
			return new VideoContent(resolver.resolveId(video.getVideoUrl()), video.getDescription()).toText();
		}
		if (content instanceof AudioContent) {
			AudioContent audio = (AudioContent) content;
			return new AudioContent(resolver.resolveId(audio.getAudioUrl()), audio.getDescription()).toText();
		}
		return content.toText();
	}

	/** 构造 OpenAI 兼容的 image_url 内容块。 */
	private static JsonObject imagePart(String url) {
		JsonObject part = new JsonObject();
		part.addProperty("type", "image_url");
		JsonObject image = new JsonObject();
		image.addProperty("url", url);
		part.add("image_url", image);
		return part;
	}

	public static JsonObject createToolMessage(ToolContent tool, AIRequest request, AttachmentResolver resolver) {
		JsonObject toolmsg=new JsonObject();
		toolmsg.addProperty("role", Role.TOOL.getRoleName());
		toolmsg.addProperty("tool_call_id", tool.getToolId());
		// 工具结果只提供给模型：纯文本沿用字符串，含图片等多段内容时使用 OpenAI 兼容的 content 数组
		MessageContents result = tool.getResult();
		if (result == null || result.isEmpty()) {
			toolmsg.addProperty("content", "");
		} else if (result.isPlainText()) {
			toolmsg.addProperty("content", result.toText());
		} else {
			toolmsg.add("content", createToolContent(result, request, resolver));
		}
		return toolmsg;
	}

	/**
	 * 兼容旧调用：不提供请求上下文时按纯文本处理，图片使用对象存储 ID 的文本标签。
	 */
	public static JsonObject createToolMessage(ToolContent tool) {
		return createToolMessage(tool, null, new AttachmentResolver(null, null));
	}

	/**
	 * 把工具结果内容转换为 OpenAI 兼容的 content 数组。
	 * <p>
	 * 图片以 {@code image_url} 内容块提交；提供者不支持图片时，图片降级为带对象存储 ID 的
	 * 文本标签。其余内容（文本、音频、视频等）按文本表示提交，保证不同提供者至少能拿到可读的结果。
	 * </p>
	 */
	private static JsonArray createToolContent(MessageContents contents, AIRequest request,
			AttachmentResolver resolver) {
		JsonArray array = new JsonArray();
		for (MessageContent content : contents) {
			if (content == null) {
				continue;
			}
			if (content instanceof ImageContent) {
				ImageContent image = (ImageContent) content;
				if (request != null && request.multimodal.image) {
					array.add(imagePart(resolver.resolveUrl(image.getImageUrl())));
					continue;
				}
				JsonObject part = new JsonObject();
				part.addProperty("type", "text");
				part.addProperty("text", new ImageContent(resolver.resolveId(image.getImageUrl()),
						image.getDescription(), image.isCensored()).toText());
				array.add(part);
			} else {
				JsonObject part = new JsonObject();
				part.addProperty("type", "text");
				part.addProperty("text", resolvedText(content, resolver));
				array.add(part);
			}
		}
		return array;
	}
	public static JsonObject createReasonerMessage(String message,List<ToolCall> toolcalls) {
		JsonObject messageContent=new JsonObject();
		messageContent.addProperty("role", Role.ASSISTANT.getRoleName());
	
		messageContent.addProperty("reasoning_content", message);
		if(toolcalls!=null)
			messageContent.add("tool_calls", gs.toJsonTree(toolcalls));
		return messageContent;
	}
}
