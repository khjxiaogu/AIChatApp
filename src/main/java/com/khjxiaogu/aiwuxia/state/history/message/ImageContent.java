package com.khjxiaogu.aiwuxia.state.history.message;

/**
 * 图片消息内容。
 * <p>
 * 实现 {@link MessageContent} 接口，包含图片的 URL 地址和可选的文字描述。
 * 当描述不为 null 时，该内容可表示为文本；否则不可表示为文本。
 * </p>
 */
public class ImageContent implements MessageContent {

	String image_url;
	String description;

	/**
	 * 使用图片 URL 和描述文本构造图片消息内容。
	 *
	 * @param image_url   图片的 URL 地址，不可为 null
	 * @param description 图片的文字描述，可为 null
	 */
	public ImageContent(String image_url, String description) {
		super();
		this.image_url = image_url;
		this.description = description;
	}

	/**
	 * 使用图片 URL 构造图片消息内容（无描述）。
	 *
	 * @param image_url 图片的 URL 地址，不可为 null
	 */
	public ImageContent(String image_url) {
		super();
		this.image_url = image_url;
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
		return description == null ? "<image id="+image_url+">" : description;
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
	 * 设置图片的文字描述。
	 *
	 * @param description 新的描述文本，可为 null
	 */
	public void setDescription(String description) {
		this.description = description;
	}

	/**
	 * 获取图片的 URL 地址。
	 *
	 * @return 图片 URL 字符串
	 */
	public String getImageUrl() {
		return image_url;
	}

	@Override
	public String getType() {
		return "image";
	}

	@Override
	public MessageContent copy() {
		return new ImageContent(image_url, description);
	}

}
