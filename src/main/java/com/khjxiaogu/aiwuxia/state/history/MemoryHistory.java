package com.khjxiaogu.aiwuxia.state.history;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.utils.ReverseIterator;

public class MemoryHistory implements Serializable, HistoryHolder {
	/**
	 * 
	 */
	private static final long serialVersionUID = 8307578868037728518L;
	List<HistoryMemoryItem> history = new ArrayList<>();
	AtomicInteger idgenerator=new AtomicInteger();

	@Override
	public boolean isEmpty() {
		return history.isEmpty();
	}

	@SuppressWarnings("unchecked")
	@Override
	public Iterator<HistoryItem> iterator() {
		return (Iterator)history.iterator();
	}

	@Override
	public synchronized HistoryItem add( Role role, String content, String fullContent,boolean isSendable) {
		HistoryMemoryItem mhi;
		history.add(mhi=new HistoryMemoryItem(newUniqueId(),role,content,fullContent,isSendable));
		return mhi;
	}

	@Override
	public synchronized void clear() {
		history.clear();
	}


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
		return new ReverseIterator(history);
	}

	public int newUniqueId() {
		int num=idgenerator.incrementAndGet();
		if(num<history.size()) {
			num=history.size();
			idgenerator.set(num);
		}
		
		return num;
	}

	public MemoryHistory() {
		super();
	}

	/**
	 * 迭代器，仅遍历 List<Item> 中 isSendable() 为 true 的元素。
	 */
	public static class SendableIterator implements Iterator<HistoryItem> {
	    private final Iterator<HistoryMemoryItem> iterator;
	    private HistoryMemoryItem nextItem; // 缓存下一个可发送的元素

	    /**
	     * 通过一个迭代器构造
	     */
	    public SendableIterator(Iterator<HistoryMemoryItem> iterator) {
	        this.iterator = iterator;
	        advance();
	    }

	    /**
	     * 通过一个 List 构造
	     */
	    public SendableIterator(List<HistoryMemoryItem> list) {
	        this(list.iterator());
	    }

	    /**
	     * 查找下一个符合条件的元素，保存到 nextItem
	     */
	    private void advance() {
	        while (iterator.hasNext()) {
	        	HistoryMemoryItem item = iterator.next();
	            if (item.isValidContext()) {
	                nextItem = item;
	                return;
	            }
	        }
	        nextItem = null; // 没有更多元素
	    }

	    @Override
	    public boolean hasNext() {
	        return nextItem != null;
	    }

	    @Override
	    public HistoryMemoryItem next() {
	        if (!hasNext()) {
	            throw new NoSuchElementException();
	        }
	        HistoryMemoryItem result = nextItem;
	        advance(); // 提前加载下一个
	        return result;
	    }

	    @Override
	    public void remove() {
	        throw new UnsupportedOperationException("remove not supported");
	    }
	}
	@Override
	public HistoryMemoryItem peekLast() {
		return history.get(history.size()-1);
	}

	@Override
	public Iterator<HistoryItem> validContextIterator() {
		return new SendableIterator(history.iterator());
	}

	@Override
	public HistoryItem removeLast() {
		if(history.isEmpty())return null;
		return remove(history.size()-1);
	}

}