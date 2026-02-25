package com.khjxiaogu.aiwuxia;

import java.io.Serializable;

class HistoryItem implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -2560643964648148908L;
	int identifier = -1;
	Role role;
	private StringBuilder content;
	private StringBuilder sendContent;
	private StringBuilder reasonContent;
	boolean shouldSend;
	StateIntf lastState;

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

	CharSequence getFullContent() {
		if (sendContent != null)
			return sendContent;
		return content;
	}

	CharSequence getContent() {
		return content;
	}

	boolean hasFullContent() {
		return this.sendContent != null;
	}

	void createFullContent() {
		if (sendContent == null)
			this.sendContent = new StringBuilder(content);
	}
	StringBuilder createReasonContent() {
		if (reasonContent == null)
			this.reasonContent = new StringBuilder();
		return reasonContent;
	}
	void appendLine(String content, boolean shouldSend) {
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
	void append(String content, boolean shouldSend) {
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
	void appendSending(String content) {
		createFullContent();
		this.sendContent.append(content);
	}

	void setFullContent(String fullContent) {
		if (fullContent == null)
			this.sendContent = null;
		else
			this.sendContent = new StringBuilder(fullContent);
	}

	@Override
	public String toString() {
		return "HistoryItem [role=" + role + ", fullContent=" + getFullContent() + "]";
	}

}