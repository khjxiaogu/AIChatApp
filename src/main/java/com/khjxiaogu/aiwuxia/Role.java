package com.khjxiaogu.aiwuxia;

public enum Role {
	ASSISTANT("系统"),
	USER("你"),
	SYSTEM("系统"),
	APPLICATION("");
	final String name;
	final String gotName;
	Role(String viewName) {
		gotName=viewName;
		name=name().toLowerCase();
	}
	public String getRoleName() {
		return name;
	}
	public String getName() {
		return gotName;
	}
}
