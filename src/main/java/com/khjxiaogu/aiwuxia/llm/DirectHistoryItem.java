package com.khjxiaogu.aiwuxia.llm;

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.utils.TokenSimulatedCounter;

public class DirectHistoryItem implements HistoryItem {
	final Role role;
	final MessageContents context;
	final MessageContents reasoner;
	final long tokenSimulated;
	public DirectHistoryItem(Role role, MessageContents context) {
		super();
		this.role = role;
		this.context = context;
		tokenSimulated=TokenSimulatedCounter.fastCountLength(context);
		reasoner=MessageContents.EMPTY;
	}
	public DirectHistoryItem(Role role, MessageContent... context) {
		super();
		this.role = role;
		this.context = new MutableMessageContents(context);
		tokenSimulated=TokenSimulatedCounter.fastCountLength(this.context);
		reasoner=MessageContents.EMPTY;
	}
	public DirectHistoryItem(Role role, String context) {
		this(role,new MutableMessageContents(context));
	}
	public DirectHistoryItem(Role role, String context,MessageContents reasoner) {
		super();
		this.role = role;
		this.context = new MutableMessageContents(context);
		this.reasoner=reasoner;
		tokenSimulated=TokenSimulatedCounter.fastCountLength(context)+TokenSimulatedCounter.fastCountLength(reasoner);
	}

	@Override
	public MessageContents getContextContent() {
		return context;
	}

	@Override
	public MessageContents getDisplayContent() {
		return context;
	}
	@Override
	public MessageContents getReasoningContent() {
		return MessageContents.EMPTY;
	}

	@Override
	public boolean isValidContext() {
		return true;
	}
	@Override
	public boolean isDeleted() {
		return false;
	}


	@Override
	public String getAudioId() {
		return null;
	}

	@Override
	public ApplicationState getLastState() {
		return null;
	}


	@Override
	public int getPrevIdentifier() {
		return -2;
	}


	@Override
	public long getTokenLength() {
		return tokenSimulated;
	}
	@Override
	public Role getRole() {
		return role;
	}
	@Override
	public int getIdentifier() {
		return -1;
	}
	@Override
	public boolean maySendReasoner() {
		return true;
	}

}
