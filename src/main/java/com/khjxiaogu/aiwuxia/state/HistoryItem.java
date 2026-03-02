package com.khjxiaogu.aiwuxia.state;

import java.io.Serializable;

import com.khjxiaogu.aiwuxia.Role;

public class HistoryItem implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -2560643964648148908L;
	int identifier = -1;
	Role role;
	private StringBuilder content;
	private StringBuilder sendContent;
	private StringBuilder reasonContent;
	public boolean shouldSend;
	public StateIntf lastState;
	public String audioId;

	public HistoryItem(Role role, String content, boolean shouldSend) {
		super();
		this.setFullContent(null);
		this.content = new StringBuilder(content);
		this.role = role;
		this.shouldSend = shouldSend;
	}
	public HistoryItem(Role role, String content, String fullContent) {
		super();
		this.role = role;
		this.content = new StringBuilder(content);
		this.sendContent = new StringBuilder(fullContent);
		this.shouldSend = true;
	}

	public HistoryItem(int identifier, Role role, String content, String fullContent) {
		super();
		this.identifier = identifier;
		this.role = role;
		this.content = new StringBuilder(content);
		this.sendContent = new StringBuilder(fullContent);
		this.shouldSend = true;
	}

	public HistoryItem(int identifier, Role role, String content, boolean shouldSend) {
		super();
		this.identifier = identifier;
		this.role = role;
		this.content = new StringBuilder(content);
		this.shouldSend = shouldSend;
	}

	public CharSequence getFullContent() {
		if (sendContent != null)
			return sendContent;
		return content;
	}

	public CharSequence getContent() {
		return content;
	}

	public boolean hasFullContent() {
		return this.sendContent != null;
	}

	public void createFullContent() {
		if (sendContent == null)
			this.sendContent = new StringBuilder(content);
	}
	public StringBuilder createReasonContent() {
		if (reasonContent == null)
			this.reasonContent = new StringBuilder();
		return reasonContent;
	}
	public void appendLine(String content, boolean shouldSend) {
		if (this.shouldSend) {
			if (!shouldSend) {
				createFullContent();
			} else {
				if (sendContent != null) {
					sendContent.append(content).append("\n");
				}
			}
		}
		this.content.append(content).append("\n");
	}
	public void append(String content, boolean shouldSend) {
		if (this.shouldSend) {
			if (!shouldSend) {
				createFullContent();
			} else {
				if (sendContent != null) {
					sendContent.append(content);
				}
			}
		}
		this.content.append(content);
	}
	public void appendSending(String content) {
		createFullContent();
		this.sendContent.append(content);
	}

	public void setFullContent(String fullContent) {
		if (fullContent == null)
			this.sendContent = null;
		else
			this.sendContent = new StringBuilder(fullContent);
	}
	public void appendReasoner(String fullContent) {
		createReasonContent().append(fullContent);
	}
	public void setAudio(String id) {
		audioId=id;
	}
	@Override
	public String toString() {
		return "HistoryItem [role=" + getRole() + ", fullContent=" + getFullContent() + "]";
	}
	public Role getRole() {
		return role;
	}
	public int getIdentifier() {
		return identifier;
	}
	public String getReasoningContent() {
		if(reasonContent==null)
			return "";
		return reasonContent.toString();
	}

}