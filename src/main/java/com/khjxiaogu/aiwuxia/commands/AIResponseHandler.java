package com.khjxiaogu.aiwuxia.commands;

import com.khjxiaogu.aiwuxia.state.session.AISession;

public interface AIResponseHandler {
	public void init(String secName,AISession state);
	public void handle(AISession state,int ch);
}
