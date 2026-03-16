package com.khjxiaogu.aiwuxia.state.history;

import java.io.Serializable;

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

class HistoryMemoryItem implements Serializable, HistoryItem {
	/**
	 * 
	 */
	private static final long serialVersionUID = -2560643964648148908L;
	private int identifier = -1;
	private Role role;
	private StringBuilder content;
	private StringBuilder sendContent;
	private StringBuilder reasonContent;
	private boolean shouldSend;
	private ApplicationState lastState;
	private String audioId;

	HistoryMemoryItem(Role role, String content, boolean shouldSend) {
		super();
		this.setContextContent(null);
		this.content = new StringBuilder(content);
		this.setRole(role);
		this.shouldSend = shouldSend;
	}
	HistoryMemoryItem(Role role, String content, String fullContent) {
		super();
		this.setRole(role);
		this.content = new StringBuilder(content);
		this.sendContent = new StringBuilder(fullContent);
		this.shouldSend = true;
	}

	HistoryMemoryItem(int identifier, Role role, String content, String fullContent) {
		super();
		this.setIdentifier(identifier);
		this.setRole(role);
		this.content = new StringBuilder(content);
		this.sendContent = new StringBuilder(fullContent);
		this.shouldSend = true;
	}
	HistoryMemoryItem(int identifier, Role role, String content, String fullContent, boolean shouldSend) {
		super();
		this.setIdentifier(identifier);
		this.setRole(role);
		if(content!=null)
			this.content = new StringBuilder(content);
		if(fullContent!=null)
			this.sendContent = new StringBuilder(fullContent);
		this.shouldSend = shouldSend;
	}
	HistoryMemoryItem(int identifier, Role role, String content, boolean shouldSend) {
		super();
		this.setIdentifier(identifier);
		this.setRole(role);
		this.content = new StringBuilder(content);
		this.shouldSend = shouldSend;
	}

	@Override
	public CharSequence getContextContent() {
		if (sendContent != null)
			return sendContent;
		return content;
	}

	@Override
	public CharSequence getDisplayContent() {
		return content;
	}

	public boolean hasContextContent() {
		return this.sendContent != null;
	}

	public void createContextContent() {
		if (sendContent == null)
			this.sendContent = new StringBuilder(content);
	}
	public StringBuilder createReasonContent() {
		if (reasonContent == null)
			this.reasonContent = new StringBuilder();
		return reasonContent;
	}
	@Override
	public void appendLine(String content, boolean shouldSend) {
		if (this.shouldSend) {
			if (!shouldSend) {
				createContextContent();
			} else {
				if (sendContent != null) {
					sendContent.append(content).append("\n");
				}
			}
		}
		this.content.append(content).append("\n");
	}
	@Override
	public void append(String content, boolean shouldSend) {
		if (this.shouldSend) {
			if (!shouldSend) {
				createContextContent();
			} else {
				if (sendContent != null) {
					sendContent.append(content);
				}
			}
		}
		this.content.append(content);
	}
	@Override
	public void appendContext(String content) {
		createContextContent();
		this.sendContent.append(content);
	}

	@Override
	public void setContextContent(String fullContent) {
		if (fullContent == null)
			this.sendContent = null;
		else
			this.sendContent = new StringBuilder(fullContent);
	}
	@Override
	public void appendReasoner(String fullContent) {
		createReasonContent().append(fullContent);
	}

	@Override
	public String toString() {
		return "HistoryItem [role=" + getRole() + ", fullContent=" + getContextContent() + "]";
	}
	@Override
	public Role getRole() {
		return role;
	}
	@Override
	public int getIdentifier() {
		return identifier;
	}
	@Override
	public String getReasoningContent() {
		if(reasonContent==null)
			return "";
		return reasonContent.toString();
	}
	@Override
	public boolean isValidContext() {
		return shouldSend;
	}
	@Override
	public void setValidContext(boolean sendable) {
		this.shouldSend=sendable;
	}
	@Override
	public String getAudioId() {
		return audioId;
	}
	@Override
	public void setAudioId(String audioId) {
		this.audioId = audioId;
	}
	@Override
	public ApplicationState getLastState() {
		return lastState;
	}
	@Override
	public void setLastState(ApplicationState lastState) {
		this.lastState = lastState;
	}
	void setRole(Role role) {
		this.role = role;
	}
	void setIdentifier(int identifier) {
		this.identifier = identifier;
	}

}