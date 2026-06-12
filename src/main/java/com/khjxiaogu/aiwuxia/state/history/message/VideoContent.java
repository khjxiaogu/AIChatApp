package com.khjxiaogu.aiwuxia.state.history.message;

/**
 * 视频消息内容。
 * <p>
 * 实现 {@link MessageContent} 接口，包含视频的 URL 地址和可选的文字描述。
 * 当描述不为 null 时，该内容可表示为文本；否则不可表示为文本。
 * </p>
 */
public class VideoContent implements MessageContent {

	String video_url;
	String description;

	/**
	 * 使用视频 URL 和描述文本构造视频消息内容。
	 *
	 * @param video_url   视频的 URL 地址，不可为 null
	 * @param description 视频的文字描述，可为 null
	 */
	public VideoContent(String video_url, String description) {
		super();
		this.video_url = video_url;
		this.description = description;
	}

	/**
	 * 使用视频 URL 构造视频消息内容（无描述）。
	 *
	 * @param video_url 视频的 URL 地址，不可为 null
	 */
	public VideoContent(String video_url) {
		super();
		this.video_url = video_url;
	}

	/**
	 * 获取视频的文字描述。
	 *
	 * @return 描述文本，可能为 null
	 */
	public String getDescription() {
		return description;
	}

	/**
	 * 设置视频的文字描述。
	 *
	 * @param description 新的描述文本，可为 null
	 */
	public void setDescription(String description) {
		this.description = description;
	}

	/**
	 * 获取视频的 URL 地址。
	 *
	 * @return 视频 URL 字符串
	 */
	public String getVideoUrl() {
		return video_url;
	}

	@Override
	public boolean isPlainText() {
		return false;
	}

	@Override
	public boolean isTextRepresentable() {
		return description != null;
	}

	@Override
	public String toText() {
		return description == null ? "" : description;
	}

	@Override
	public String getType() {
		return "video";
	}

	@Override
	public MessageContent copy() {
		return new VideoContent(video_url, description);
	}

}
