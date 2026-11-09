package com.khjxiaogu.aiwuxia.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonObject;

/**
 * 适配器归一化后的一条入站消息。
 * <p>
 * 所有 id 都是字符串：不同协议的消息 id、用户 id、群 id 形态不同（QQ 是数字，别的平台可能
 * 是字符串），控制器只在需要和会话配置比较时再转成数字。
 * </p>
 */
public final class IncomingMessage {
	/** 会话标识：群消息为群 id，私聊为对方用户 id。 */
	private final String chatId;
	/** 收到这条消息的机器人自身 id。 */
	private final String selfId;
	/** 发送者 id。 */
	private final String senderId;
	/** 发送者显示名（群名片/昵称），可能为空。 */
	private final String senderName;
	/** 消息 id。 */
	private final String messageId;
	/** 被 @ 的用户 id 列表（不含 @全体）。 */
	private final List<String> mentionedIds;
	/** 这条消息回复的消息 id，没有回复时为 null。 */
	private final String replyToMessageId;
	/** 归一化后的内容块，按消息里的顺序排列。 */
	private final List<IncomingContent> contents;
	/** 原始协议报文，供需要调试或协议特化的场景使用，可能为 null。 */
	private final JsonObject raw;

	private IncomingMessage(Builder builder) {
		this.chatId = builder.chatId;
		this.selfId = builder.selfId;
		this.senderId = builder.senderId;
		this.senderName = builder.senderName;
		this.messageId = builder.messageId;
		this.mentionedIds = Collections.unmodifiableList(new ArrayList<>(builder.mentionedIds));
		this.replyToMessageId = builder.replyToMessageId;
		this.contents = Collections.unmodifiableList(new ArrayList<>(builder.contents));
		this.raw = builder.raw;
	}

	public String getChatId() {
		return chatId;
	}

	public String getSelfId() {
		return selfId;
	}

	public String getSenderId() {
		return senderId;
	}

	public String getSenderName() {
		return senderName;
	}

	public String getMessageId() {
		return messageId;
	}

	public List<String> getMentionedIds() {
		return mentionedIds;
	}

	/** 是否 @ 了指定用户。 */
	public boolean isMentioned(String userId) {
		return userId != null && mentionedIds.contains(userId);
	}

	public String getReplyToMessageId() {
		return replyToMessageId;
	}

	public List<IncomingContent> getContents() {
		return contents;
	}

	public JsonObject getRaw() {
		return raw;
	}

	/** 消息里的全部纯文本（不含引用内容）。 */
	public String getPlainText() {
		StringBuilder builder = new StringBuilder();
		for (IncomingContent content : contents) {
			if (content instanceof IncomingContent.TextBlock) {
				builder.append(((IncomingContent.TextBlock) content).getText());
			}
		}
		return builder.toString();
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * {@link IncomingMessage} 的构造器：字段较多，适配器按协议逐项填充。
	 */
	public static final class Builder {
		private String chatId = "";
		private String selfId = "";
		private String senderId = "";
		private String senderName = "";
		private String messageId = "";
		private String replyToMessageId;
		private JsonObject raw;
		private final List<String> mentionedIds = new ArrayList<>();
		private final List<IncomingContent> contents = new ArrayList<>();

		public Builder chatId(String chatId) {
			this.chatId = chatId == null ? "" : chatId;
			return this;
		}

		public Builder selfId(String selfId) {
			this.selfId = selfId == null ? "" : selfId;
			return this;
		}

		public Builder senderId(String senderId) {
			this.senderId = senderId == null ? "" : senderId;
			return this;
		}

		public Builder senderName(String senderName) {
			this.senderName = senderName == null ? "" : senderName;
			return this;
		}

		public Builder messageId(String messageId) {
			this.messageId = messageId == null ? "" : messageId;
			return this;
		}

		public Builder replyToMessageId(String replyToMessageId) {
			this.replyToMessageId = replyToMessageId;
			return this;
		}

		public Builder raw(JsonObject raw) {
			this.raw = raw;
			return this;
		}

		public Builder mentionedId(String userId) {
			if (userId != null && !userId.isEmpty()) {
				mentionedIds.add(userId);
			}
			return this;
		}

		public Builder content(IncomingContent content) {
			if (content != null) {
				contents.add(content);
			}
			return this;
		}

		public IncomingMessage build() {
			return new IncomingMessage(this);
		}
	}
}
