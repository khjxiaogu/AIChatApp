package com.khjxiaogu.aiwuxia.state.history;

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.status.StateIntf;

public interface HistoryItem {

	CharSequence getFullContent();

	CharSequence getContent();

	boolean hasFullContent();

	void createFullContent();

	StringBuilder createReasonContent();

	void appendLine(String content, boolean shouldSend);

	void append(String content, boolean shouldSend);

	void appendSending(String content);

	void setFullContent(String fullContent);

	void appendReasoner(String fullContent);

	void setAudio(String id);

	Role getRole();

	int getIdentifier();

	String getReasoningContent();

	boolean isSendable();

	void setSendable(boolean sendable);

	String getAudioId();

	void setAudioId(String audioId);

	StateIntf getLastState();

	void setLastState(StateIntf lastState);

}