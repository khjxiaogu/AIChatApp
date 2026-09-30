package com.khjxiaogu.aiwuxia.llm;

import java.util.concurrent.CompletableFuture;

public interface AgentMessager {
	public void sendAsyncTool(String tool,CompletableFuture<String> result);
	public void addToolStatus(String tool,CompletableFuture<String> result);
	public CompletableFuture<String> sendMusic(String tool,CompletableFuture<String> url);
	public CompletableFuture<String> sendVideo(String tool,CompletableFuture<String> url);
	public CompletableFuture<String> sendImage(String tool, CompletableFuture<String> url, boolean censored);
}
