package com.khjxiaogu.aiwuxia.llm.message;

public class PlainText implements MessageContent {
	StringBuilder text;
	public PlainText() {
		text=new StringBuilder();
	}
	public PlainText(String str) {
		text=new StringBuilder(str);
	}
	@Override
	public boolean isTextRepresentable() {
		return true;
	}
	public void append(CharSequence str) {
		text.append(str);
	}
	@Override
	public String toText() {
		return text.toString();
	}
	@Override
	public boolean isPlainText() {
		return true;
	}


	@Override
	public String getType() {
		return "text";
	}
}
