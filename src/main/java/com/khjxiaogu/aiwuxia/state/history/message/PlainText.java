package com.khjxiaogu.aiwuxia.state.history.message;

/**
 * 纯文本消息内容。
 * <p>
 * 实现 {@link MessageContent} 接口，内部使用 {@link StringBuilder} 存储文本数据，
 * 支持通过 {@link #append(CharSequence)} 方法追加文本内容。
 * </p>
 */
public class PlainText implements MessageContent {
	StringBuilder text;

	/**
	 * 构造一个空的纯文本消息。
	 */
	public PlainText() {
		text = new StringBuilder();
	}

	/**
	 * 使用指定的字符串构造纯文本消息。
	 *
	 * @param str 初始文本内容，不可为 null
	 */
	public PlainText(String str) {
		text = new StringBuilder(str);
	}

	@Override
	public boolean isTextRepresentable() {
		return true;
	}

	/**
	 * 向当前文本末尾追加字符串。
	 *
	 * @param str 要追加的字符序列
	 */
	public void append(CharSequence str) {
		text.append(str);
	}

	@Override
	public String toText() {
		return text.toString();
	}

	@Override
	public boolean isPlainText() {
		return true;
	}

	@Override
	public String getType() {
		return "text";
	}

	@Override
	public MessageContent copy() {
		return new PlainText(text.toString());
	}
}
