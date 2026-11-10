package com.khjxiaogu.aiwuxia.llm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;

/**
 * 工具执行结果。
 * <p>
 * 工具统一返回 {@link java.util.concurrent.CompletableFuture} 包装的本对象，职责分为两部分：
 * </p>
 * <ul>
 *   <li><b>结果内容（{@link #getContent()}）</b>：只提供给模型进一步处理，可以是多段
 *       {@link MessageContent}（文本、图片等），例如图片生成工具把生成的图片直接放进结果里；
 *       默认<b>不</b>展示给用户，需要展示时由工具自己的
 *       {@code ToolCallPresenter.resultDisplay(...)} 生成用户友好内容。</li>
 *   <li><b>附件（{@link #getAttachments()}）</b>：需要附加在结果中返回给用户的图片、音频、视频等，
 *       由会话统一写入当前上下文并按传输方式发送（例如群聊发图）。附件不参与模型请求。</li>
 *   <li><b>是否成功（{@link #isSuccess()}）</b>：默认 true；只有明确的简单失败（网络不可达、服务不存在等）
 *       才标记为 false。目前仅 MCP 服务端用它判断是否免除基础调用费，其它调用方可忽略。</li>
 * </ul>
 */
public final class ToolResult {

	private static final ToolResult EMPTY = new ToolResult(MessageContents.EMPTY, Collections.<MessageContent>emptyList(),
			true);

	/** 只提供给模型处理的结果内容。 */
	private final MessageContents content;
	/** 需要返回给用户的附件。 */
	private final List<MessageContent> attachments;
	/**
	 * 工具是否成功完成。
	 * <p>
	 * 默认 true。只有明确知道是“简单失败”（网络无法连接、服务不存在/未启动等）时才设为 false；
	 * 其余情况保持默认，避免把业务性失败误判为不收费。该字段只供 MCP 服务端计算基础调用费使用，
	 * 其它调用方可以忽略。
	 * </p>
	 */
	private final boolean success;

	private ToolResult(MessageContents content, List<MessageContent> attachments) {
		this(content, attachments, true);
	}

	private ToolResult(MessageContents content, List<MessageContent> attachments, boolean success) {
		this.content = content == null ? MessageContents.EMPTY : content;
		this.attachments = attachments == null ? Collections.<MessageContent>emptyList()
				: Collections.unmodifiableList(new ArrayList<>(attachments));
		this.success = success;
	}

	/**
	 * 只包含文本的工具结果（文本只提供给模型）。
	 *
	 * @param text 结果文本，null 视为空串
	 * @return 工具结果
	 */
	public static ToolResult text(String text) {
		if (text == null || text.isEmpty()) {
			return EMPTY;
		}
		return new ToolResult(new MutableMessageContents(text), null);
	}

	/**
	 * 只包含结果内容的工具结果（没有给用户的附件）。
	 *
	 * @param content 模型可见的结果内容，null 或空视为无内容
	 * @return 工具结果
	 */
	public static ToolResult content(MessageContents content) {
		return content(content, (Collection<? extends MessageContent>) null);
	}

	/**
	 * 只包含结果内容的工具结果（没有给用户的附件）。
	 *
	 * @param content 模型可见的结果内容，null 或空视为无内容
	 * @return 工具结果
	 */
	public static ToolResult of(MessageContents content) {
		return content(content);
	}

	/**
	 * 结果内容 + 给用户的附件。
	 *
	 * @param content     模型可见的结果内容，null 视为空
	 * @param attachments 给用户的附件，null 忽略
	 * @return 工具结果
	 */
	public static ToolResult of(MessageContents content, MessageContent... attachments) {
		return content(content, attachments);
	}

	/**
	 * 结果内容 + 给用户的附件集合。
	 *
	 * @param content     模型可见的结果内容，null 视为空
	 * @param attachments 给用户的附件集合，null 忽略
	 * @return 工具结果
	 */
	public static ToolResult of(MessageContents content, Collection<? extends MessageContent> attachments) {
		return content(content, attachments);
	}

	/**
	 * 结果内容 + 给用户的附件。
	 *
	 * @param content     模型可见的结果内容，null 视为空
	 * @param attachments 给用户的附件，null 忽略
	 * @return 工具结果
	 */
	public static ToolResult content(MessageContents content, Collection<? extends MessageContent> attachments) {
		List<MessageContent> list = copyAttachments(attachments);
		if ((content == null || content.isEmpty()) && list.isEmpty()) {
			return EMPTY;
		}
		return new ToolResult(content, list);
	}

	/**
	 * 结果内容 + 给用户的附件。
	 *
	 * @param content     模型可见的结果内容，null 视为空
	 * @param attachments 给用户的附件，null 忽略
	 * @return 工具结果
	 */
	public static ToolResult content(MessageContents content, MessageContent... attachments) {
		return content(content, toCollection(attachments));
	}

	/**
	 * 文本结果 + 给用户的附件。
	 *
	 * @param text        结果文本（只提供给模型），null 视为空串
	 * @param attachments 给用户的附件，null 忽略
	 * @return 工具结果
	 */
	public static ToolResult of(String text, MessageContent... attachments) {
		return of(text, toCollection(attachments));
	}

	/**
	 * 文本结果 + 给用户的附件集合。
	 *
	 * @param text        结果文本（只提供给模型），null 视为空串
	 * @param attachments 给用户的附件集合，null 忽略
	 * @return 工具结果
	 */
	public static ToolResult of(String text, Collection<? extends MessageContent> attachments) {
		List<MessageContent> list = copyAttachments(attachments);
		if ((text == null || text.isEmpty()) && list.isEmpty()) {
			return EMPTY;
		}
		return new ToolResult(new MutableMessageContents(text), list);
	}

	/**
	 * 只包含文本的失败结果：内容照常回传给模型，但标记为简单失败。
	 * <p>
	 * 适用于明确的服务不可用（连接失败、服务未启动、无可用模型等），
	 * 不应把普通业务错误或参数错误标成失败。
	 * </p>
	 *
	 * @param text 失败说明，null 视为空串
	 * @return 标记失败的工具结果
	 */
	public static ToolResult failure(String text) {
		return new ToolResult(text == null || text.isEmpty() ? MessageContents.EMPTY : new MutableMessageContents(text),
				null, false);
	}

	/**
	 * 只包含结果内容的失败结果。
	 *
	 * @param content 模型可见的结果内容，null 视为空
	 * @return 标记失败的工具结果
	 */
	public static ToolResult failure(MessageContents content) {
		return new ToolResult(content, null, false);
	}

	/**
	 * 把当前结果标记为成功或失败，内容与附件不变。
	 *
	 * @param success 是否成功
	 * @return 新的结果对象
	 */
	public ToolResult withSuccess(boolean success) {
		if (this.success == success) {
			return this;
		}
		return new ToolResult(content, attachments, success);
	}

	/** 把当前结果标记为简单失败。 */
	public ToolResult asFailure() {
		return withSuccess(false);
	}

	/** 工具是否成功完成；未显式标记时默认 true。 */
	public boolean isSuccess() {
		return success;
	}

	/**
	 * 模型可见的结果内容，永不为 null。
	 *
	 * @return 结果内容
	 */
	public MessageContents getContent() {
		return content;
	}

	/**
	 * 模型可见的结果内容（与 {@link #getContent()} 等价）。
	 *
	 * @return 结果内容，永不为 null
	 */
	public MessageContents getResult() {
		return content;
	}

	/**
	 * 结果内容的文本表示（只用于兼容旧的文本调用方与日志，不用于向用户展示）。
	 *
	 * @return 结果文本，永不为 null
	 */
	public String getText() {
		return content == null ? "" : content.toText();
	}

	/** 给用户的附件列表（只读），永不为 null。 */
	public List<MessageContent> getAttachments() {
		return attachments;
	}

	/** 是否带有给用户的附件。 */
	public boolean hasAttachments() {
		return !attachments.isEmpty();
	}

	/** 是否带有模型可见的结果内容。 */
	public boolean hasContent() {
		return content != null && !content.isEmpty();
	}

	/** 复制附件列表并过滤 null。 */
	private static List<MessageContent> copyAttachments(Collection<? extends MessageContent> attachments) {
		if (attachments == null || attachments.isEmpty()) {
			return Collections.<MessageContent>emptyList();
		}
		List<MessageContent> list = new ArrayList<>(attachments.size());
		for (MessageContent attachment : attachments) {
			if (attachment != null) {
				list.add(attachment);
			}
		}
		return list;
	}

	/** 可变参数转集合；null/空返回 null，让调用方走无附件分支。 */
	private static Collection<MessageContent> toCollection(MessageContent[] attachments) {
		if (attachments == null || attachments.length == 0) {
			return null;
		}
		List<MessageContent> list = new ArrayList<>(attachments.length);
		for (MessageContent attachment : attachments) {
			if (attachment != null) {
				list.add(attachment);
			}
		}
		return list;
	}
}
