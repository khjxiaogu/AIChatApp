package com.khjxiaogu.aiwuxia.llm.message;

public interface MessageContent {

	boolean isPlainText();
	boolean isTextRepresentable();
	String toText();
	String getType();
}
