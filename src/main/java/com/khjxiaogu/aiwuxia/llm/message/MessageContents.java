package com.khjxiaogu.aiwuxia.llm.message;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;

public class MessageContents implements Iterable<MessageContent>{
	public static final MessageContents EMPTY=new MessageContents(Collections.unmodifiableList(new ArrayList<>()));
	static Map<String,Class<? extends MessageContent>> types=new HashMap<>();
	static {
		types.put("image", ImageContent.class);
		types.put("text", PlainText.class);
		types.put("video", VideoContent.class);
		types.put("tool", ToolContent.class);
		types.put("tool_call", ToolCallContent.class);
		
	}
	public static class Serilizer implements JsonSerializer<MessageContents>, JsonDeserializer<MessageContents> {

		@Override
		public MessageContents deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
			MessageContents content= new MessageContents();
			if (json.isJsonArray()) {
				JsonArray data = json.getAsJsonArray();
				for (JsonElement je : data) {
					if (je.isJsonObject()) {
						JsonObject jo=je.getAsJsonObject();
						if(!jo.has("type"))
							throw new JsonSyntaxException("Type missing");
						String type=jo.get("type").getAsString();
						Class<? extends MessageContent> clazz=types.get(type);
						if(clazz==null)
							throw new JsonSyntaxException("Invalid type "+type);
						content.add(context.deserialize(je, clazz));
					} else if (je.isJsonPrimitive()) {
						content.append(je.getAsString());
					}
				}
			} else if (json.isJsonPrimitive()) {
				content.append(json.getAsString());
			}
			return content;
		}

		@Override
		public JsonElement serialize(MessageContents src, Type typeOfSrc, JsonSerializationContext context) {
			JsonArray data = new JsonArray();
			for (MessageContent message : src.messages) {
				if(message instanceof PlainText) {
					data.add(((PlainText)message).text.toString());
				}else {
					JsonObject origData = context.serialize(message).getAsJsonObject();
					origData.addProperty("type", message.getType());
					data.add(origData);
				}
				
			}
			return data;
		}

	}

	List<MessageContent> messages;
	public MessageContents() {
		messages=new ArrayList<>();
	}
	
	public MessageContents(MessageContent... messages) {
		this();
		for(MessageContent mc:messages)
			add(mc);
	}
	public MessageContents(List<MessageContent> messages) {
		super();
		this.messages = messages;
	}
	public MessageContents(MessageContents messages) {
		this();
		for(MessageContent mc:messages)
			add(mc);
	}

	public MessageContents(String text) {
		this();
		if(text!=null)
			append(text);
	}
	public boolean isPlainText() {
		for(MessageContent msg:messages)
			if(!msg.isPlainText())
				return false;
		return true;
	}
	public boolean isTextRepresentable() {
		for(MessageContent msg:messages)
			if(!msg.isTextRepresentable())
				return false;
		return true;
	}
	public String toText() {
		StringBuilder builder=new StringBuilder();
		for(MessageContent msg:messages)
			builder.append(msg.toText());
		return builder.toString();
	}
	public void addRaw(MessageContent msg) {
		messages.add(msg);
	}
	public void add(MessageContent msg) {
		if(msg instanceof PlainText&&!messages.isEmpty()) {
			MessageContent last=messages.get(messages.size()-1);
			if(last instanceof PlainText) {
				((PlainText)last).append(((PlainText)msg).text);
				return;
			}
		}
		messages.add(msg);
	}
	public MessageContents append(String msg) {
		if(!messages.isEmpty()) {
			MessageContent last=messages.get(messages.size()-1);
			if(last instanceof PlainText) {
				((PlainText)last).append(msg);
				return this;
			}
		}
		messages.add(new PlainText(msg));
		return this;
	}
	@Override
	public Iterator<MessageContent> iterator() {
		return messages.iterator();
	}

	public boolean isEmpty() {
		return messages.isEmpty();
	}
	public void clear() {
		messages.clear();
	}
}
