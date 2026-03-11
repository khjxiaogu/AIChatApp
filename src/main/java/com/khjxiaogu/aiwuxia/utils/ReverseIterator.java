package com.khjxiaogu.aiwuxia.utils;

import java.util.Iterator;
import java.util.List;

public class ReverseIterator<T> implements Iterator<T>, Iterable<T> {

    private final List<T> list;
    private int position;

    public ReverseIterator(List<T> list) {
        this.list = list;
        this.position = list.size() - 1;
    }

    @Override
    public Iterator<T> iterator() {
        return this;
    }

    @Override
    public boolean hasNext() {
        return position >= 0;
    }

    @Override
    public T next() {
        return list.get(position--);
    }

    @Override
    public void remove() {
        list.remove(position+1);
    }

}