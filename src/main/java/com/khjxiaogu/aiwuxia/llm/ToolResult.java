package com.khjxiaogu.aiwuxia.llm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;

/**
 * 工具执行结果。
 * <p>
 * 工具统一返回 {@link java.util.concurrent.CompletableFuture} 包装的本对象：
 * 文本结果交给模型，附件（图片、音频、视频等）由会话统一处理，工具本身不直接发送消息。
 * </p>
 */
public final class ToolResult {

	private static final ToolResult EMPTY = new ToolResult("", Collections.<MessageContent>emptyList());

	private final String text;
	private final List<MessageContent> attachments;

	private ToolResult(String text, List<MessageContent> attachments) {
		this.text = text == null ? "" : text;
		this.attachments = attachments == null ? Collections.<MessageContent>emptyList()
				: Collections.unmodifiableList(new ArrayList<>(attachments));
	}

	/**
	 * 只包含文本的工具结果。
	 *
	 * @param text 结果文本，null 视为空串
	 * @return 工具结果
	 */
	public static ToolResult text(String text) {
		if (text == null || text.isEmpty()) {
			return EMPTY;
		}
		return new ToolResult(text, null);
	}

	/**
	 * 文本 + 附件。
	 *
	 * @param text        结果文本，null 视为空串
	 * @param attachments 附件，null 忽略
	 * @return 工具结果
	 */
	public static ToolResult of(String text, MessageContent... attachments) {
		if (attachments == null || attachments.length == 0) {
			return text(text);
		}
		List<MessageContent> list = new ArrayList<>(attachments.length);
		for (MessageContent attachment : attachments) {
			if (attachment != null) {
				list.add(attachment);
			}
		}
		return new ToolResult(text, list);
	}

	/**
	 * 文本 + 附件集合。
	 *
	 * @param text        结果文本，null 视为空串
	 * @param attachments 附件集合，null 忽略
	 * @return 工具结果
	 */
	public static ToolResult of(String text, Collection<? extends MessageContent> attachments) {
		if (attachments == null || attachments.isEmpty()) {
			return text(text);
		}
		List<MessageContent> list = new ArrayList<>(attachments.size());
		for (MessageContent attachment : attachments) {
			if (attachment != null) {
				list.add(attachment);
			}
		}
		return new ToolResult(text, list);
	}

	/** 结果文本，永不为 null。 */
	public String getText() {
		return text;
	}

	/** 附件列表（只读），永不为 null。 */
	public List<MessageContent> getAttachments() {
		return attachments;
	}

	/** 是否带有附件。 */
	public boolean hasAttachments() {
		return !attachments.isEmpty();
	}
}
