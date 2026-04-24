package com.khjxiaogu.aiwuxia.llm.message;

public interface Message {

	boolean isPlainText();
	boolean isTextRepresentable();
	String toText();
}
