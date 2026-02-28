package com.khjxiaogu.aiwuxia.state;

import java.util.Iterator;

public interface HistoryHolder extends Iterable<HistoryItem> {

	HistoryItem get(int num);

	boolean isEmpty();

	Iterator<HistoryItem> iterator();

	boolean add(HistoryItem e);

	void clear();

	void add(int index, HistoryItem element);

	HistoryItem remove(int index);

	Iterator<HistoryItem> reverseIterator();


	int size();
	
	int newUniqueId();
	void removeOf(int identifier);

	String toString();

}