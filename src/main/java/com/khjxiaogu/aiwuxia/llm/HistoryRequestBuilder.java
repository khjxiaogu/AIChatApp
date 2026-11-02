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
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolContent;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;

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
	public static JsonArray createRequest(AIRequest request) {
		JsonArray messages=new JsonArray();
		AttachmentResolver resolver=AttachmentResolver.of(request);
		for(HistoryItem hi:request.history) {
			boolean shouldContainReasoner=false;
			boolean shouldSkipContent=false;
			if(hi.getReasoningContent()!=null&&!hi.getReasoningContent().isEmpty()) {
				for(MessageContent msgc:hi.getReasoningContent()) {
					if(msgc instanceof ToolContent) {
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
						}else {
							hasPrevious=true;
							messages.add(createReasonerMessage(msgc.toText(),null));
						}
					}
					if(hasPrevious) {
						messages.get(messages.size()-1).getAsJsonObject().add("content", createMessageContent(hi.getContextContent(), request, resolver));
						shouldSkipContent=true;
					}
				}
			}
			
			if(!shouldSkipContent) {
				JsonObject msg=new JsonObject();
				msg.addProperty("role", hi.getRole().getRoleName());
				msg.add("content", createMessageContent(hi.getContextContent(), request, resolver));
				messages.add(msg);
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
	 * 构造一条普通消息的 content。
	 * <p>
	 * 纯文本保持字符串形式；当请求声明支持图片且消息里确实有图片时，按 OpenAI 兼容的
	 * content 数组提交，图片地址由 {@link AttachmentResolver} 从对象存储 ID 解析。
	 * 不支持图片的提供者则把图片降级为带 ID 的文本标签，消息里仍然只有对象存储 ID。
	 * </p>
	 */
	private static JsonElement createMessageContent(MessageContents contents, AIRequest request,
			AttachmentResolver resolver) {
		if (contents == null) {
			return new JsonPrimitive("");
		}
		if (!request.multimodal.image || !containsImage(contents)) {
			return new JsonPrimitive(normalizeIds(contents, resolver));
		}
		JsonArray array = new JsonArray();
		for (MessageContent content : contents) {
			if (content == null) {
				continue;
			}
			if (content instanceof ImageContent) {
				ImageContent image = (ImageContent) content;
				JsonObject part = imagePart(resolver.resolveUrl(image.getImageUrl()));
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

	/** 消息里是否包含图片。 */
	private static boolean containsImage(MessageContents contents) {
		for (MessageContent content : contents) {
			if (content instanceof ImageContent) {
				return true;
			}
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
