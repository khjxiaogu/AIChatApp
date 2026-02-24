package com.khjxiaogu.aiwuxia;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

class Interface implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -7540278636736725189L;
	Map<String, String> values = new LinkedHashMap<>();
	String name;

	public Interface(String name) {
		super();
		this.name = name;
	}

	public String toString() {
		StringBuilder sb = new StringBuilder("【");
		sb.append(name).append("面板】\n");
		for (Entry<String, String> n : values.entrySet()) {
			sb.append("【").append(n.getKey()).append("】").append(n.getValue()).append("\n");
		}
		return sb.toString();
	}
}