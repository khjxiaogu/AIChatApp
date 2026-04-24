package com.khjxiaogu.aiwuxia.llm.message;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class MessageContent implements Iterable<Message>{
	List<Message> messages=new ArrayList<>();
	public MessageContent() {
	}
	public boolean isPlainText() {
		for(Message msg:messages)
			if(!msg.isPlainText())
				return false;
		return true;
	}
	public boolean isTextRepresentable() {
		for(Message msg:messages)
			if(!msg.isTextRepresentable())
				return false;
		return true;
	}
	public String toText() {
		StringBuilder builder=new StringBuilder();
		for(Message msg:messages)
			builder.append(msg.toText());
		return builder.toString();
	}
	@Override
	public Iterator<Message> iterator() {
		return messages.iterator();
	}

}
