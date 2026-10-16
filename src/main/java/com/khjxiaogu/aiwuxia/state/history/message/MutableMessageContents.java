package com.khjxiaogu.aiwuxia.state.history.message;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * {@link MessageContents} 的可变实现。
 * <p>
 * 内部内容分为两部分：已经固化的内容段（{@code messages} 列表）与尾部尚未固化的文本缓冲
 * （{@code pendingText}）。{@link #append(String)} 以及纯文本的 {@link #add(MessageContent)}
 * 只把文本写进缓冲区，不会为每一小段文本都创建一个对象，因此流式逐段追加文本
 * （例如思维链逐字写入）既不会产生大量碎片对象，也不需要反复做"取出来再拼回去"的合并。
 * </p>
 * <p>
 * 缓冲区在以下两种时机固化成不可变的 {@link PlainText} 段落并放进内容列表：
 * </p>
 * <ul>
 *   <li>插入非文本内容（图片、工具调用等）时：必须先固化，才能保证各段内容的先后顺序；</li>
 *   <li>序列化或遍历（{@link #iterator()}）时：此时内容要被外部读取，缓冲区顺手定型为 {@link PlainText}，
 *       避免读取方看到的内容与实际内容不一致。</li>
 * </ul>
 * <p>
 * 因此遍历前无需任何准备，{@link #toText()} 之类的读取也不会丢失尚未固化的文本。
 * 固化时如果集合尾部已经是纯文本段（例如流式生成过程中被前端补发读取过一次），
 * 缓冲区会并入该段而不是新起一段，集合中因此始终不会出现连续的纯文本段。
 * 如需直接添加而不合并，可使用 {@link #addRaw(MessageContent)}。
 * </p>
 * <p>
 * 读写方法均在实例锁上同步：思维链由后台线程异步逐段写入时，前端补发、日志打印等
 * 其它线程可能同时在读取同一个容器，同步保证它们不会读到写了一半的内容。
 * </p>
 *
 * @see #add(MessageContent)
 * @see #append(String)
 * @see #solidify()
 */
public class MutableMessageContents implements MessageContents {
	/** 已经固化的内容段 */
	private List<MessageContent> messages;
	/** 尚未固化的尾部文本，追加文本时只写进这里 */
	private final StringBuilder pendingText;

	/**
	 * 构造一个空的可变消息内容集合。
	 */
	public MutableMessageContents() {
		messages = new ArrayList<>();
		pendingText = new StringBuilder();
	}

	/**
	 * 使用可变参数构造消息内容集合，每个参数通过 {@link #add(MessageContent)} 添加
	 * （纯文本会并入文本缓冲区）。
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
		this();
		this.messages = messages;
	}

	/**
	 * 从另一个 {@link MessageContents} 拷贝构造，每个元素通过 {@link #add(MessageContent)} 添加
	 * （纯文本会并入文本缓冲区，相邻文本因此自动合并）。
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
	 * 如果 {@code text} 不为 null 则写入文本缓冲区。
	 *
	 * @param text 初始文本内容，可为 null
	 */
	public MutableMessageContents(String text) {
		this();
		if (text != null)
			append(text);
	}


	@Override
	public String toString() {
		return toText();
	}

	/**
	 * 将所有内容的文本表示拼接成一个字符串。
	 * <p>
	 * 只读，不会改动内容：已固化的段落依次拼接后，再补上仍在缓冲区里的尾部文本。
	 * 与接口默认实现相比在实例锁上同步，读取期间后台线程不会改动内容。
	 * </p>
	 */
	@Override
	public synchronized String toText() {
		StringBuilder builder = new StringBuilder();
		for (MessageContent msg : messages)
			builder.append(msg.toText());
		builder.append(pendingText);
		return builder.toString();
	}

	@Override
	public synchronized boolean isEmpty() {
		return messages.isEmpty() && pendingText.length() == 0;
	}

	/**
	 * 把文本缓冲区固化成 {@link PlainText} 段落。
	 * <p>
	 * 缓冲区为空时不做任何事；固化后缓冲区被清空，后续追加的文本会重新进入缓冲区。
	 * 如果集合尾部已经是纯文本段，缓冲区会并入该段（替换成一个内容更长的 {@link PlainText}），
	 * 避免出现连续的纯文本段。遍历（{@link #iterator()}）与插入非文本内容时都会自动调用本方法，
	 * 通常无需手动调用。
	 * </p>
	 */
	public synchronized void solidify() {
		solidifyLocked();
	}

	/**
	 * 内部固化实现，调用方必须已持有实例锁。
	 */
	private void solidifyLocked() {
		if (pendingText.length() == 0)
			return;
		String text = pendingText.toString();
		pendingText.setLength(0);
		if (!messages.isEmpty() && messages.get(messages.size() - 1).isPlainText()) {
			MessageContent last = messages.get(messages.size() - 1);
			messages.set(messages.size() - 1, new PlainText(last.toText() + text));
			return;
		}
		messages.add(new PlainText(text));
	}

	/**
	 * 直接添加一个消息内容，不做任何合并处理。
	 * <p>
	 * 如果缓冲区里还有未固化的文本，会先把它固化，以保证先后顺序。
	 * </p>
	 *
	 * @param msg 要添加的消息内容，不可为 null
	 */
	public synchronized void addRaw(MessageContent msg) {
		solidifyLocked();
		messages.add(msg);
	}

	/**
	 * 添加一个消息内容。
	 * <p>
	 * 纯文本会被"取出来"直接追加进文本缓冲区（不保留传入的对象），与缓冲区中已有的文本自然合并，
	 * 因此集合中不会出现连续的纯文本段落；非文本内容则先固化缓冲区，再作为独立段落添加。
	 * </p>
	 *
	 * @param msg 要添加的消息内容，不可为 null
	 */
	public synchronized void add(MessageContent msg) {
		if (msg.isPlainText()) {
			pendingText.append(msg.toText());
			return;
		}
		solidifyLocked();
		messages.add(msg);
	}

	/**
	 * 向集合末尾追加纯文本。
	 * <p>
	 * 文本先进入缓冲区并在需要时（插入非文本、遍历/序列化、调用 {@link #solidify()}）固化成
	 * {@link PlainText}，因此连续追加不会产生多个纯文本段落。
	 * </p>
	 *
	 * @param msg 要追加的文本，不可为 null
	 * @return 当前实例（支持链式调用）
	 */
	public synchronized MutableMessageContents append(String msg) {
		if (msg != null)
			pendingText.append(msg);
		return this;
	}

	/**
	 * 遍历已固化的内容段。
	 * <p>
	 * 遍历前会先把缓冲区固化成 {@link PlainText}，所以遍历结果总是包含全部内容
	 * （这也是序列化能拿到完整内容的原因）。返回的是当前内容的快照，遍历期间后台线程的追加
	 * 不会影响本次遍历，也不会抛出并发修改异常。
	 * </p>
	 */
	@Override
	public synchronized Iterator<MessageContent> iterator() {
		solidifyLocked();
		return new ArrayList<>(messages).iterator();
	}

	/**
	 * 清空集合中的所有消息内容（含尚未固化的文本缓冲区）。
	 */
	public synchronized void clear() {
		messages.clear();
		pendingText.setLength(0);
	}
}
