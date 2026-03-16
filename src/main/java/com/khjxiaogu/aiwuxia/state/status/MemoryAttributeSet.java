package com.khjxiaogu.aiwuxia.state.status;

import java.io.Serializable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

public class MemoryAttributeSet implements Serializable, AttributeSet {
	/**
	 * 
	 */
	private static final long serialVersionUID = -7540278636736725189L;
	public Map<String, String> values = new LinkedHashMap<>();
	public void clear() {
		values.clear();
	}
	public String putIfAbsent(String key, String value) {
		return values.putIfAbsent(key, value);
	}
	public String name;

	public MemoryAttributeSet(String name) {
		super();
		this.name = name;
	}
	public MemoryAttributeSet(MemoryAttributeSet other) {
		super();
		this.name = other.name;
		this.values.putAll(other.values);
	}
	public String toString() {
		StringBuilder sb = new StringBuilder("【");
		sb.append(name).append("】\n");
		for (Entry<String, String> n : values.entrySet()) {
			sb.append("【").append(n.getKey()).append("】").append(n.getValue()).append("\n");
		}
		
		return sb.toString();
	}
	public boolean contains(String key) {
		return values.containsKey(key);
	}
	public String get(String key) {
		return values.get(key);
	}
	public String put(String key,String val) {
		return values.put(key,val);
	}
	public boolean isEmpty() {
		return values.isEmpty();
	}
	@Override
	public Iterator<Entry<String, String>> iterator() {
		return values.entrySet().iterator();
	}
	public Map<String,String> getAsMap(){
		return values;
	}
}