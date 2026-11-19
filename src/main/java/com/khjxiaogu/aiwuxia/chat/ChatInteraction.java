package com.khjxiaogu.aiwuxia.chat;

/**
 * 用户与消息按钮/快捷菜单的互动事件。
 * <p>
 * QQ 官方对应 {@code INTERACTION_CREATE}；适配器会先回应平台，再回调控制器。
 * </p>
 */
public final class ChatInteraction {
	/** 消息按钮回调。 */
	public static final int TYPE_BUTTON = 11;
	/** 单聊快捷菜单回调。 */
	public static final int TYPE_MENU = 12;

	private final String id;
	private final String selfId;
	private final int type;
	private final String scene;
	private final String userId;
	private final String groupId;
	private final String memberId;
	private final String messageId;
	private final String buttonId;
	private final String buttonData;

	public ChatInteraction(String id, String selfId, int type, String scene, String userId, String groupId,
			String memberId, String messageId, String buttonId, String buttonData) {
		this.id = id;
		this.selfId = selfId;
		this.type = type;
		this.scene = scene;
		this.userId = userId;
		this.groupId = groupId;
		this.memberId = memberId;
		this.messageId = messageId;
		this.buttonId = buttonId;
		this.buttonData = buttonData;
	}

	/** 互动事件 id（用于回应平台）。 */
	public String getId() {
		return id;
	}

	/** 收到事件的机器人账号（官方为 AppID）。 */
	public String getSelfId() {
		return selfId;
	}

	public int getType() {
		return type;
	}

	/** c2c / group / guild。 */
	public String getScene() {
		return scene;
	}

	/** 单聊用户 openid。 */
	public String getUserId() {
		return userId;
	}

	/** 群 openid。 */
	public String getGroupId() {
		return groupId;
	}

	/** 群成员 openid。 */
	public String getMemberId() {
		return memberId;
	}

	public String getMessageId() {
		return messageId;
	}

	public String getButtonId() {
		return buttonId;
	}

	public String getButtonData() {
		return buttonData;
	}

	/** 操作者 id：单聊用 user_openid，群聊用 group_member_openid。 */
	public String getOperatorId() {
		return userId != null && !userId.isEmpty() ? userId : memberId;
	}
}
