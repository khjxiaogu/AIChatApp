package com.khjxiaogu.aiwuxia;

public interface AIResponseHandler {
	public void init(String secName,AIState state);
	public void handle(AIState state,int ch);
}
