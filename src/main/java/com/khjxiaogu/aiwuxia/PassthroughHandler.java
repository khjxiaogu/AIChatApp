package com.khjxiaogu.aiwuxia;

import java.util.Scanner;

public class PassthroughHandler implements AIResponseHandler{
	public static PassthroughHandler INSTANCE;
	public PassthroughHandler() {

	}
	@Override
	public void init(String secName, AIState state) {
		
	}
	@Override
	public void handle(AIState state, int ch) {
		state.appendLine(null, null, false);
	}



}
