package com.khjxiaogu.aiwuxia.state.history.message;

/**
 * 图片消息内容。
 * <p>
 * 实现 {@link MessageContent} 接口，包含图片的 URL 地址和可选的文字描述。
 * 当描述不为 null 时，该内容可表示为文本；否则不可表示为文本。
 * </p>
 */
public class ImageContent implements MessageContent {

	final String image_url;
	final String description;
	/** 是否为不宜内容（QQ 发送时做遮挡/阅读器链接处理）。 */
	final boolean censored;

	/**
	 * 使用图片 URL 和描述文本构造图片消息内容。
	 *
	 * @param image_url   图片的 URL 地址，不可为 null
	 * @param description 图片的文字描述，可为 null
	 */
	public ImageContent(String image_url, String description) {
		this(image_url, description, false);
	}

	/**
	 * 使用图片 URL、描述与不宜内容标记构造图片消息内容。
	 *
	 * @param image_url   图片的 URL 地址，不可为 null
	 * @param description 图片的文字描述，可为 null
	 * @param censored    是否为不宜内容
	 */
	public ImageContent(String image_url, String description, boolean censored) {
		super();
		this.image_url = image_url;
		this.description = description;
		this.censored = censored;
	}

	/**
	 * 使用图片 URL 构造图片消息内容（无描述）。
	 *
	 * @param image_url 图片的 URL 地址，不可为 null
	 */
	public ImageContent(String image_url) {
		this(image_url,null);
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
		return description == null ? "<image id="+image_url+">" : "<image id="+image_url+" alt=\""+description+"\">";
	}

	/**
	 * 获取图片的文字描述。
	 *
	 * @return 描述文本，可能为 null
	 */
	public String getDescription() {
		return description;
	}
	/**
	 * 获取图片的 URL 地址。
	 *
	 * @return 图片 URL 字符串
	 */
	public String getImageUrl() {
		return image_url;
	}

	/**
	 * 是否为不宜内容。
	 *
	 * @return 不宜内容返回 true
	 */
	public boolean isCensored() {
		return censored;
	}

	@Override
	public String getType() {
		return "image";
	}

	@Override
	public MessageContent copy() {
		return this;
	}

}
