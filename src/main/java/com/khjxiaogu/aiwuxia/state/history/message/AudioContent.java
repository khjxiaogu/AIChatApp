package com.khjxiaogu.aiwuxia.state.history.message;

/**
 * 音频消息内容。
 * <p>
 * 实现 {@link MessageContent} 接口，包含音频地址（URL、data URL 或本地文件路径）和可选描述。
 * 工具只产生本内容，具体如何发送由会话处理。
 * </p>
 */
public class AudioContent implements MessageContent {

	final String audio_url;
	final String description;

	/**
	 * 使用音频地址和描述构造音频内容。
	 *
	 * @param audio_url   音频地址，不可为 null
	 * @param description 描述，可为 null
	 */
	public AudioContent(String audio_url, String description) {
		this.audio_url = audio_url;
		this.description = description;
	}

	/**
	 * 使用音频地址构造音频内容（无描述）。
	 *
	 * @param audio_url 音频地址
	 */
	public AudioContent(String audio_url) {
		this(audio_url, null);
	}

	/** 音频地址。 */
	public String getAudioUrl() {
		return audio_url;
	}

	/** 音频描述，可能为 null。 */
	public String getDescription() {
		return description;
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
		return description == null ? "<audio id=" + audio_url + ">" : "<audio id=" + audio_url + " alt=\"" + description + "\">";
	}

	@Override
	public String getType() {
		return "audio";
	}

	@Override
	public MessageContent copy() {
		return this;
	}
}
