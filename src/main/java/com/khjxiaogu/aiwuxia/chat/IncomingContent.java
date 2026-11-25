package com.khjxiaogu.aiwuxia.chat;

/**
 * 一条入站消息里的内容块。
 * <p>
 * 适配器负责把各聊天协议的消息段（文本、图片、引用等）归一化成这里的几种块，控制器只处理
 * 归一化后的内容，不接触任何协议字段。
 * </p>
 */
public interface IncomingContent {

	/**
	 * 纯文本块。
	 */
	public static final class TextBlock implements IncomingContent {
		private final String text;

		public TextBlock(String text) {
			this.text = text == null ? "" : text;
		}

		public String getText() {
			return text;
		}

		@Override
		public String toString() {
			return text;
		}
	}

	/**
	 * 图片块。
	 * <p>
	 * {@code url} 是控制器读取图片内容用的引用：可以是 http(s) 链接、data URL，也可以是协议内部的
	 * 图片句柄；无法直接读取时为 null，此时控制器退回使用 {@link #getSummary()} 的占位文字。
	 * </p>
	 */
	public static final class ImageBlock implements IncomingContent {
		private final String url;
		private final String summary;

		public ImageBlock(String url, String summary) {
			this.url = url;
			this.summary = summary == null ? "" : summary;
		}

		public String getUrl() {
			return url;
		}

		public String getSummary() {
			return summary.isEmpty() ? "图片" : summary;
		}

		@Override
		public String toString() {
			return "<图片:" + getSummary() + ">";
		}
	}

	/**
	 * 文件块（群文件/普通附件）。
	 * <p>
	 * {@code url} 是协议提供的下载地址，可能带有时效；{@code fileName} 是展示/定位用的文件名；
	 * {@code size} 是协议给出的大小（字节），未知时为 -1。
	 * </p>
	 */
	public static final class FileBlock implements IncomingContent {
		private final String url;
		private final String fileName;
		private final long size;

		public FileBlock(String url, String fileName, long size) {
			this.url = url == null ? "" : url;
			this.fileName = fileName == null ? "" : fileName;
			this.size = size;
		}

		public String getUrl() {
			return url;
		}

		public String getFileName() {
			return fileName;
		}

		public long getSize() {
			return size;
		}

		/** 展示用摘要：文件名，未知时为“文件”。 */
		public String getSummary() {
			return fileName.isEmpty() ? "文件" : fileName;
		}

		@Override
		public String toString() {
			return "<文件:" + getSummary() + ">";
		}
	}

	/**
	 * 引用（回复）块，对应用户在聊天里“回复某条消息”。
	 */
	public static final class QuoteBlock implements IncomingContent {
		private final String messageId;
		private final String senderId;
		private final String text;

		public QuoteBlock(String messageId, String senderId, String text) {
			this.messageId = messageId == null ? "" : messageId;
			this.senderId = senderId == null ? "" : senderId;
			this.text = text == null ? "" : text;
		}

		/** 被引用消息的 id，未知时为空串。 */
		public String getMessageId() {
			return messageId;
		}

		/** 被引用消息的发送者 id，未知时为空串。 */
		public String getSenderId() {
			return senderId;
		}

		/** 被引用消息里的文本，未知时为空串。 */
		public String getText() {
			return text;
		}

		@Override
		public String toString() {
			return "<引用:" + messageId + ">";
		}
	}
}
