package com.khjxiaogu.aiwuxia.llm.message;

public class PlainText implements Message {
	String text;
	public PlainText() {
	}
	@Override
	public boolean isTextRepresentable() {
		return true;
	}
	@Override
	public String toText() {
		return text;
	}
	@Override
	public boolean isPlainText() {
		return true;
	}

}
