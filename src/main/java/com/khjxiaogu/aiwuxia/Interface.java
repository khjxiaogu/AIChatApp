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
		if("主角".equals(name))
			fillMainChara();
		else
			fillSubChara();
	}
	public void fillMainChara() {
		values.putIfAbsent("姓名","");
		values.putIfAbsent("性别","");
		values.putIfAbsent("年龄","");
		values.putIfAbsent("容貌","");
		values.putIfAbsent("天赋","");
		values.putIfAbsent("灵根","");
		values.putIfAbsent("境界","");
		values.putIfAbsent("功法","");
		values.putIfAbsent("出身","");
		values.putIfAbsent("状态","");
		values.putIfAbsent("灵石","");
		values.putIfAbsent("物品清单","");
	}
	public void fillSubChara() {
		values.putIfAbsent("姓名","");
		values.putIfAbsent("性别","");
		values.putIfAbsent("性格","");
		values.putIfAbsent("年龄","");
		values.putIfAbsent("容貌","");
		values.putIfAbsent("天赋","");
		values.putIfAbsent("灵根","");
		values.putIfAbsent("境界","");
		values.putIfAbsent("功法","");
		values.putIfAbsent("出身","");
		values.putIfAbsent("状态","");
		values.putIfAbsent("物品清单","");
		values.putIfAbsent("关系网络","");
		values.putIfAbsent("好感度","");
		values.putIfAbsent("记忆烙印",""); 
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