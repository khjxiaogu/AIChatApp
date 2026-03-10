package com.khjxiaogu.aiwuxia;

import java.util.ArrayDeque;
import java.util.Date;

import com.khjxiaogu.aiwuxia.state.HistoryHolder;

public class AIGroupSession extends AISession {
	ArrayDeque<String> messageQueue=new ArrayDeque<>();
	public AIGroupSession(String user, HistoryHolder historym, AIData data, AIApplication aiapp) {
		super(user, historym, data, aiapp);
	}
	public void addMessage(String msg) {
		messageQueue.add(msg);
		if(messageQueue.size()>10)
			messageQueue.pollFirst();
	}
	public String getPrompt() {
		StringBuilder sb=new StringBuilder();
		sb.append("当前时间：").append(new Date().toLocaleString()).append("\n");
		while(true) {
			String msg=messageQueue.pollFirst();
			if(msg==null)break;
			sb.append(msg).append("\n");
		}
		return sb.toString();
	}
}
