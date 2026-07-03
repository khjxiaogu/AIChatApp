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
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
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
		toolmsg.addProperty("content", tool.getResult());
		return toolmsg;
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
