package com.khjxiaogu.aiwuxia.state.history.message;

/**
 * 单条消息内容接口。
 * <p>
 * 定义消息内容的基本操作，包括类型判断和文本转换。
 * 消息内容可以是纯文本、图片、视频、工具调用或工具调用结果等多种形式。
 * 实现类应通过 {@link #getType()} 返回唯一的内容类型标识。
 * </p>
 *
 * @see PlainText
 * @see ImageContent
 * @see VideoContent
 * @see ToolCallContent
 * @see ToolContent
 */
public interface MessageContent {

	/**
	 * 判断该消息内容是否为纯文本。
	 *
	 * @return {@code true} 如果是纯文本；{@code false} 否则
	 */
	boolean isPlainText();

	/**
	 * 判断该消息内容是否可以表示为纯文本字符串。
	 * <p>
	 * 某些非纯文本内容（如图片、视频）如果有描述文本，
	 * 也可通过 {@link #toText()} 获取其文本表示。
	 * </p>
	 *
	 * @return {@code true} 如果可以表示为文本；{@code false} 否则
	 */
	boolean isTextRepresentable();

	/**
	 * 获取该消息内容的文本表示。
	 * <ul>
	 *   <li>纯文本：返回文本内容本身。</li>
	 *   <li>图片/视频：返回其描述文本（无描述则返回空字符串）。</li>
	 *   <li>工具调用：返回空字符串。</li>
	 *   <li>工具结果：返回执行结果字符串。</li>
	 * </ul>
	 *
	 * @return 文本表示，不可为 null
	 */
	String toText();

	/**
	 * 获取内容类型标识字符串。
	 *
	 * @return 类型标识，如 {@code "text"}、{@code "image"}、{@code "video"}、{@code "tool_call"}、{@code "tool"}
	 */
	String getType();

	/**
	 * 创建当前消息内容的深拷贝。
	 * <p>
	 * 对于不可变对象（如 {@link ToolCallContent}），可返回自身引用。
	 * </p>
	 *
	 * @return 拷贝后的 {@link MessageContent} 对象
	 */
	MessageContent copy();
}
