package com.khjxiaogu.aiwuxia.state.history.message;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * {@link MessageContents} 的可变实现。
 * <p>
 * 内部使用 {@link ArrayList} 存储 {@link MessageContent} 对象。
 * {@link #add(MessageContent)} 和 {@link #append(String)} 方法会自动合并相邻的
 * {@link PlainText} 对象，避免产生连续的纯文本片段。
 * 如需直接添加而不合并，可使用 {@link #addRaw(MessageContent)}。
 * </p>
 */
public class MutableMessageContents implements MessageContents {
	private List<MessageContent> messages;

	/**
	 * 构造一个空的可变消息内容集合。
	 */
	public MutableMessageContents() {
		messages = new ArrayList<>();
	}

	/**
	 * 使用可变参数构造消息内容集合，每个参数通过 {@link #add(MessageContent)} 添加
	 * （会自动合并相邻的纯文本）。
	 *
	 * @param messages 要添加的消息内容
	 */
	public MutableMessageContents(MessageContent... messages) {
		this();
		for (MessageContent mc : messages)
			add(mc);
	}

	/**
	 * 使用已有的列表构造消息内容集合（直接引用，不拷贝）。
	 *
	 * @param messages 消息内容列表，不可为 null
	 */
	public MutableMessageContents(List<MessageContent> messages) {
		super();
		this.messages = messages;
	}

	/**
	 * 从另一个 {@link MessageContents} 拷贝构造，每个元素通过 {@link #add(MessageContent)} 添加。
	 *
	 * @param messages 源消息内容集合，不可为 null
	 */
	public MutableMessageContents(MessageContents messages) {
		this();
		for (MessageContent mc : messages)
			add(mc);
	}

	/**
	 * 使用纯文本字符串构造消息内容集合。
	 * 如果 {@code text} 不为 null 则自动追加为一个 {@link PlainText} 条目。
	 *
	 * @param text 初始文本内容，可为 null
	 */
	public MutableMessageContents(String text) {
		this();
		if (text != null)
			append(text);
	}

	@Override
	public boolean isPlainText() {
		for (MessageContent msg : messages)
			if (!msg.isPlainText())
				return false;
		return true;
	}

	@Override
	public boolean isTextRepresentable() {
		for (MessageContent msg : messages)
			if (!msg.isTextRepresentable())
				return false;
		return true;
	}

	@Override
	public String toText() {
		StringBuilder builder = new StringBuilder();
		for (MessageContent msg : messages)
			builder.append(msg.toText());
		return builder.toString();
	}

	/**
	 * 直接添加一个消息内容，不做任何合并处理。
	 *
	 * @param msg 要添加的消息内容，不可为 null
	 */
	public void addRaw(MessageContent msg) {
		messages.add(msg);
	}

	/**
	 * 添加一个消息内容，如果该内容与集合中最后一个元素均为 {@link PlainText}，
	 * 则自动将文本追加到最后一个元素上，避免产生连续的纯文本片段。
	 *
	 * @param msg 要添加的消息内容，不可为 null
	 */
	public void add(MessageContent msg) {
		if (msg instanceof PlainText && !messages.isEmpty()) {
			MessageContent last = messages.get(messages.size() - 1);
			if (last instanceof PlainText) {
				((PlainText) last).append(((PlainText) msg).text);
				return;
			}
		}
		messages.add(msg);
	}

	/**
	 * 向集合末尾追加纯文本。
	 * <p>
	 * 如果最后一个元素已是 {@link PlainText}，则直接追加到其末尾；
	 * 否则创建一个新的 {@link PlainText} 条目。
	 * </p>
	 *
	 * @param msg 要追加的文本，不可为 null
	 * @return 当前实例（支持链式调用）
	 */
	public MutableMessageContents append(String msg) {
		if (!messages.isEmpty()) {
			MessageContent last = messages.get(messages.size() - 1);
			if (last instanceof PlainText) {
				((PlainText) last).append(msg);
				return this;
			}
		}
		messages.add(new PlainText(msg));
		return this;
	}

	@Override
	public Iterator<MessageContent> iterator() {
		return messages.iterator();
	}

	@Override
	public boolean isEmpty() {
		return messages.isEmpty();
	}

	/**
	 * 清空集合中的所有消息内容。
	 */
	public void clear() {
		messages.clear();
	}
}
