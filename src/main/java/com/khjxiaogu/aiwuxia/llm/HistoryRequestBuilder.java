package com.khjxiaogu.aiwuxia.llm;

import java.lang.reflect.Type;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ModelCategory;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolContent;

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
							messages.add(createToolMessage((ToolContent) msgc));
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
						messages.get(messages.size()-1).getAsJsonObject().addProperty("content", hi.getContextContent().toText());
						shouldSkipContent=true;
					}
				}
			}
			
			if(!shouldSkipContent) {
				JsonObject msg=new JsonObject();
				msg.addProperty("role", hi.getRole().getRoleName());
				msg.addProperty("content", hi.getContextContent().toText());
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
	public static JsonObject createToolMessage(ToolContent tool) {
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
			toolmsg.add("content", createToolContent(result));
		}
		return toolmsg;
	}

	/**
	 * 把工具结果内容转换为 OpenAI 兼容的 content 数组。
	 * <p>
	 * 图片以 {@code image_url} 内容块提交，其余内容（文本、音频、视频等）按文本表示提交，
	 * 保证不同提供者至少能拿到可读的结果。
	 * </p>
	 */
	private static JsonArray createToolContent(MessageContents contents) {
		JsonArray array = new JsonArray();
		for (MessageContent content : contents) {
			if (content == null) {
				continue;
			}
			if (content instanceof ImageContent) {
				JsonObject part = new JsonObject();
				part.addProperty("type", "image_url");
				JsonObject image = new JsonObject();
				image.addProperty("url", ((ImageContent) content).getImageUrl());
				part.add("image_url", image);
				array.add(part);
			} else {
				JsonObject part = new JsonObject();
				part.addProperty("type", "text");
				part.addProperty("text", content.toText());
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
