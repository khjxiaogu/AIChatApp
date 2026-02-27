package com.khjxiaogu.aiwuxia.commands;

import com.khjxiaogu.aiwuxia.AISession;

public class PassthroughHandler implements AIResponseHandler{
	public static PassthroughHandler INSTANCE;
	public PassthroughHandler() {

	}
	@Override
	public void init(String secName, AISession state) {
		
	}
	@Override
	public void handle(AISession state, int ch) {
		state.appendLine(null, null, false);
	}



}
