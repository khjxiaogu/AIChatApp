package com.khjxiaogu.aiwuxia.llm.message;

import java.util.Map;

public class ImageContent implements Message{

	String image_url;
	String description;
	Map<String,String> fileId;
	public ImageContent() {
	}

	@Override
	public boolean isPlainText() {
		return false;
	}

	@Override
	public boolean isTextRepresentable() {
		return description!=null;
	}

	@Override
	public String toText() {
		return description==null?"":description;
	}

}
