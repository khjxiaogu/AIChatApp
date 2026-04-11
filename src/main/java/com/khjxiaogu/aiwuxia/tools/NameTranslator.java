package com.khjxiaogu.aiwuxia.tools;

import java.util.HashMap;
import java.util.Map;

public class NameTranslator {
	Map<String,String> names=new HashMap<>();
	public String translate(String hintName) {
		StringBuilder out=new StringBuilder();
		for(String s:hintName.split("/")) {
			out.append(names.getOrDefault(s, s));
		}
		return out.toString();
	}
	public NameTranslator add(String key,String name) {
		names.put(key, name);
		return this;
	}
}
