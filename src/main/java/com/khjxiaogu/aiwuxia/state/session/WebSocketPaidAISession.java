package com.khjxiaogu.aiwuxia.state.session;

import java.io.File;

import com.khjxiaogu.aiwuxia.AIChatService;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.respscheme.Usage;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;

public class WebSocketPaidAISession extends WebSocketAISession {

	public WebSocketPaidAISession(AIChatService par, String uid, String chatid, AIApplication aiapp, File fn, HistoryHolder history, ExtraData data) {
		super(par, uid, chatid, aiapp, fn, history, data);
	}
	@Override
	public void addUsage(Usage usage) {
		int uncached_cost=0;
		if(usage.prompt_cache_hit_tokens==0&&usage.prompt_cache_miss_tokens==0) {
			uncached_cost+=usage.prompt_tokens;
		}else {
			uncached_cost+=usage.prompt_cache_miss_tokens;
			uncached_cost+=Math.ceil(usage.prompt_cache_hit_tokens/10f);
		}
		uncached_cost+=Math.ceil(usage.completion_tokens*1.5f);
		parent.consumePaidTokens(user, uncached_cost);
		
		super.addUsage(usage);
	}
	@Override
	public boolean canGenerate() {
		return parent.hasAnyPaidTokenRemaining(user);
	}

}
