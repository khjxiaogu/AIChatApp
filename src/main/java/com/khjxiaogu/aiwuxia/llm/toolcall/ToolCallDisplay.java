package com.khjxiaogu.aiwuxia.llm.toolcall;

import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;

/**
 * 一次工具调用（或工具结果）的用户友好显示。
 * <p>
 * 由 {@link ToolCallPresenter} 从原始调用信息（工具名 + 参数，或结果文本）转换而来：
 * </p>
 * <ul>
 *   <li>{@link #getBrief()}：用户友好简述，用于<b>替代原始工具名称</b>；</li>
 *   <li>{@link #getContent()}：工具具体内容（{@link MessageContents}，可含图片/视频等结构化内容），
 *       默认为空；</li>
 * </ul>
 * <p>本类不可变，仅用于展示，不参与持久化与模型请求。</p>
 */
public final class ToolCallDisplay {

	/** 空显示：简述为空、内容为空。 */
	public static final ToolCallDisplay EMPTY = new ToolCallDisplay("", MessageContents.EMPTY);

	private final String brief;
	private final MessageContents content;

	public ToolCallDisplay(String brief, MessageContents content) {
		this.brief = brief == null ? "" : brief;
		this.content = content == null ? MessageContents.EMPTY : content;
	}

	/** 只有简述、没有具体内容的显示。 */
	public static ToolCallDisplay of(String brief) {
		return new ToolCallDisplay(brief, MessageContents.EMPTY);
	}

	/** 简述 + 具体内容的显示。 */
	public static ToolCallDisplay of(String brief, MessageContents content) {
		return new ToolCallDisplay(brief, content);
	}

	/** 用户友好简述（替代工具名称）。 */
	public String getBrief() {
		return brief;
	}

	/** 工具具体内容，默认为 {@link MessageContents#EMPTY}。 */
	public MessageContents getContent() {
		return content;
	}

	/** 是否提供了具体内容。 */
	public boolean hasContent() {
		return !content.isEmpty();
	}

	@Override
	public String toString() {
		return "ToolCallDisplay [brief=" + brief + ", content=" + content.toText() + "]";
	}
}
