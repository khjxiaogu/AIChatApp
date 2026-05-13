package com.khjxiaogu.aiwuxia.llm;

import com.khjxiaogu.aiwuxia.llm.message.MessageContent;
import com.khjxiaogu.aiwuxia.llm.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
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
	public DirectHistoryItem(Role role, String context) {
		this(role,new MessageContents(context));
	}
	public DirectHistoryItem(Role role, String context,MessageContents reasoner) {
		super();
		this.role = role;
		this.context = new MessageContents(context);
		this.reasoner=reasoner;
		tokenSimulated=TokenSimulatedCounter.fastCountLength(context)+TokenSimulatedCounter.fastCountLength(reasoner);
	}

	@Override
	public MessageContents getContextContent() {
		return context;
	}

	@Override
	public CharSequence getDisplayContent() {
		return context.toText();
	}

	@Override
	public void appendLine(String content, boolean addToContext) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void append(String content, boolean addToContext) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void appendContext(String content) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setContextContent(String fullContent) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void appendReasoner(MessageContent fullContent) {
		throw new UnsupportedOperationException();
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
	public MessageContents getReasoningContent() {
		return MessageContents.EMPTY;
	}

	@Override
	public boolean isValidContext() {
		return true;
	}

	@Override
	public void setValidContext(boolean sendable) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isDeleted() {
		return false;
	}

	@Override
	public void setDeleted(boolean sendable) {
		throw new UnsupportedOperationException();
	}

	@Override
	public String getAudioId() {
		return null;
	}

	@Override
	public void setAudioId(String audioId) {
		throw new UnsupportedOperationException();
	}

	@Override
	public ApplicationState getLastState() {
		return null;
	}

	@Override
	public void setLastState(ApplicationState lastState) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getPrevIdentifier() {
		return -2;
	}

	@Override
	public void setPrevIdentifier(int prevIdentifier) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void setReasonContent(String reasonContent) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getTokenLength() {
		return tokenSimulated;
	}

	@Override
	public void setTokenLength(long tokenLength) {
		throw new UnsupportedOperationException();
	}

}
