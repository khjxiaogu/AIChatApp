package com.khjxiaogu.aiwuxia.state;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

import com.khjxiaogu.aiwuxia.ReverseIterator;

public class History implements Serializable, HistoryHolder {
	/**
	 * 
	 */
	private static final long serialVersionUID = 8307578868037728518L;
	List<HistoryItem> history = new ArrayList<>();

	@Override
	public HistoryItem get(int num) {
		return history.get(num);
	}

	@Override
	public boolean isEmpty() {
		return history.isEmpty();
	}

	@Override
	public Iterator<HistoryItem> iterator() {
		return history.iterator();
	}

	@Override
	public synchronized boolean add(HistoryItem e) {
		return history.add(e);
	}

	@Override
	public void clear() {
		history.clear();
	}

	@Override
	public synchronized void add(int index, HistoryItem element) {
		history.add(index, element);
	}

	@Override
	public synchronized HistoryItem remove(int index) {
		return history.remove(index);
	}


	@Override
	public int size() {
		return history.size();
	}

	@Override
	public synchronized void removeOf(int identifier) {
		for(Iterator<HistoryItem> it=reverseIterator();it.hasNext();) {
			HistoryItem hi=it.next();
			if(hi.getIdentifier()==identifier)
				it.remove();
		}
	}
/*
	@Override
	public synchronized HistoryItem removeUntil(String role) {
		ListIterator<HistoryItem> it=this.listIterator(this.size());
		HistoryItem ret=null;
		while(it.hasPrevious()) {
			ret=it.previous();
			it.remove();
			if(role.equals(ret.role))
				break;
		}
		return ret;
	}*/
	@Override
	public String toString() {
		return "["+ history + "]";
	}

	@Override
	public Iterator<HistoryItem> reverseIterator() {
		return new ReverseIterator<>(history);
	}

}