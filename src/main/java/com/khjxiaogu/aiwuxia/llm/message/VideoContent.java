package com.khjxiaogu.aiwuxia.llm.message;

public class VideoContent implements MessageContent{

	String video_url;
	String description;


	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getVideoUrl() {
		return video_url;
	}

	public VideoContent(String video_url, String description) {
		super();
		this.video_url = video_url;
		this.description = description;
	}

	public VideoContent(String video_url) {
		super();
		this.video_url = video_url;
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

	@Override
	public String getType() {
		return "video";
	}

}
