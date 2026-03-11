package com.khjxiaogu.aiwuxia.state.history;

import java.util.Iterator;

import com.khjxiaogu.aiwuxia.state.Role;

public interface HistoryHolder extends Iterable<HistoryItem> {

	boolean isEmpty();

	Iterator<HistoryItem> iterator();

	HistoryItem add(Role role, String content, String fullContent,boolean isSendable);

	void clear();


	HistoryItem remove(int index);

	Iterator<HistoryItem> reverseIterator();


	int size();
	
	void removeOf(int identifier);

	String toString();
	HistoryMemoryItem peekLast();
	Iterator<HistoryItem> sendableIterator();

	default HistoryItem add(Role role, String content, boolean isSendable) {
		return add(role,content,null,isSendable);
	};
	default HistoryItem add(Role role, String content, String fullContent) {
		return add(role,content,fullContent,true);
	};
}