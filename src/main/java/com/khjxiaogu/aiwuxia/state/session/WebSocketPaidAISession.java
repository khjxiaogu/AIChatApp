package com.khjxiaogu.aiwuxia.state.session;

import java.io.File;

import com.khjxiaogu.aiwuxia.AIChatService;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.ApplicationAttributes;
import com.khjxiaogu.aiwuxia.respscheme.UsageIntf;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;

public class WebSocketPaidAISession extends WebSocketAISession {

	public WebSocketPaidAISession(AIChatService par, String uid, String chatid, AIApplication aiapp,ApplicationAttributes attr, File fn, HistoryHolder history, ExtraData data) {
		super(par, uid, chatid, aiapp, attr, fn, history, data);
	}
	@Override
	public void addUsage(UsageIntf usage) {

		parent.consumePaidTokens(user, (int) Math.ceil(usage.getEquivantTokens()));
		
		super.addUsage(usage);
	}
	@Override
	public boolean canGenerate() {
		return parent.hasAnyPaidTokenRemaining(user);
	}

}
