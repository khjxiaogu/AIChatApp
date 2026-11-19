package com.khjxiaogu.aiwuxia.chat;

/**
 * 消息内嵌键盘上的一个按钮。
 * <p>
 * 目前只有 QQ 官方适配器实现；其他协议不支持时审批会退回文本。
 * </p>
 */
public final class ChatButton {
	/** 按钮 id，同一张卡片内唯一。 */
	public final String id;
	/** 按钮文字，最多 10 个字符。 */
	public final String label;
	/** 点击后显示的文字；为空时保持 label。 */
	public final String visitedLabel;
	/** 回调数据，点击后由协议回传给机器人。 */
	public final String data;
	/** 样式：0 灰色线框，1 蓝色线框，3 白底红字，4 蓝底白字。 */
	public final int style;

	public ChatButton(String id, String label, String visitedLabel, String data, int style) {
		this.id = id;
		this.label = label;
		this.visitedLabel = visitedLabel;
		this.data = data;
		this.style = style;
	}
}
