package com.khjxiaogu.aiwuxia.modelproxy.api;

/** SDXL 的动作。 */
public enum SdxlAction {
	/** 文生图。 */
	TXT2IMG("txt2img"),
	/** 图生图。 */
	IMG2IMG("img2img"),
	/** 分区绘图（带附件时是分区图生图）。 */
	REGIONAL("regional"),
	/** 反推提示词。 */
	INTERROGATE("interrogate");

	/** 报文里的动作名。 */
	public final String wireName;

	SdxlAction(String wireName) {
		this.wireName = wireName;
	}

	/** 按报文动作名解析，未知返回 null。 */
	public static SdxlAction of(String wireName) {
		if (wireName == null) {
			return null;
		}
		for (SdxlAction action : values()) {
			if (action.wireName.equalsIgnoreCase(wireName.trim())) {
				return action;
			}
		}
		return null;
	}
}
