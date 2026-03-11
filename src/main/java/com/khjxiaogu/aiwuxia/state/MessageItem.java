package com.khjxiaogu.aiwuxia.state;

public class MessageItem {
	public int id;
	public Role role;
	public String message;
	public MessageItem(int id, Role role, String message) {
		super();
		this.id = id;
		this.role = role;
		this.message = message;
	}
}
