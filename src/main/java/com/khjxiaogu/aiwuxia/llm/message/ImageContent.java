package com.khjxiaogu.aiwuxia.llm.message;

public class ImageContent implements MessageContent{

	String image_url;
	String description;

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

	public ImageContent(String image_url, String description) {
		super();
		this.image_url = image_url;
		this.description = description;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getImageUrl() {
		return image_url;
	}

	public ImageContent(String image_url) {
		super();
		this.image_url = image_url;
	}

	@Override
	public String getType() {
		return "image";
	}

	@Override
	public MessageContent copy() {
		return new ImageContent(image_url,description);
	}

}
