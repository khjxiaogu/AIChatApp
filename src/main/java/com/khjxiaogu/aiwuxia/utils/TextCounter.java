package com.khjxiaogu.aiwuxia.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;

/**
 * 文本计数工具：统一项目中“数字数 / 数词数 / 数 token”的实现。
 * <p>
 * 词数口径：<b>一个英文单词算 1，一个中文字符算 1</b>；空白与标点不计。
 * 英文单词按连续字母/数字分组，撇号、连字符夹在单词中间时不拆词；
 * 中文按单个汉字计数（CJK 统一表意文字）。
 * </p>
 * <p>
 * token 数仍沿用 {@link TokenSimulatedCounter} 的模拟算法，本类只做转发，
 * 不改变已有上下文窗口的计算结果。
 * </p>
 */
public final class TextCounter {

	/** 只用静态方法，不允许实例化。 */
	private TextCounter() {
	}

	/**
	 * 统计字符串的字符数（UTF-16 code unit，与 {@link String#length()} 一致）。
	 * <p>
	 * 截断、消息长度上限等必须按字符下标处理的场景使用本方法；需要“字数”统计时请用
	 * {@link #countWords(CharSequence)}。
	 * </p>
	 *
	 * @param text 文本，可为 null
	 * @return 字符数；null 返回 0
	 */
	public static int countChars(CharSequence text) {
		return text == null ? 0 : text.length();
	}

	/**
	 * 统计词数：一个英文单词算 1，一个中文字符算 1。
	 *
	 * @param text 文本，可为 null
	 * @return 词数；null 或空串返回 0
	 */
	public static int countWords(CharSequence text) {
		if (text == null || text.length() == 0) {
			return 0;
		}
		int count = 0;
		boolean inWord = false;
		for (int i = 0; i < text.length();) {
			int codePoint = Character.codePointAt(text, i);
			i += Character.charCount(codePoint);
			if (isCjk(codePoint)) {
				count++;
				inWord = false;
			} else if (Character.isLetterOrDigit(codePoint)) {
				if (!inWord) {
					count++;
					inWord = true;
				}
			} else if (inWord && isWordConnector(codePoint) && hasWordPartAfter(text, i)) {
				// 撇号/连字符夹在单词中间：don't、hello-world 仍算一个词
			} else {
				inWord = false;
			}
		}
		return count;
	}

	/**
	 * 统计一组文本段的词数，逐段读取 {@code text} 字段后累加。
	 *
	 * @param content 文本段数组，可为 null
	 * @return 所有 {@code text} 字段的词数之和
	 */
	public static int countWordsInTexts(JsonArray content) {
		if (content == null) {
			return 0;
		}
		int count = 0;
		for (JsonElement element : content) {
			if (element == null || !element.isJsonObject()) {
				continue;
			}
			JsonObject object = element.getAsJsonObject();
			JsonElement text = object.get("text");
			if (text != null && text.isJsonPrimitive()) {
				count += countWords(text.getAsString());
			}
		}
		return count;
	}

	/**
	 * 统计模拟 token 数，转发到 {@link TokenSimulatedCounter}。
	 *
	 * @param text 文本，可为 null
	 * @return 模拟 token 数
	 */
	public static long countTokens(CharSequence text) {
		return TokenSimulatedCounter.fastCountLength(text);
	}

	/**
	 * 统计内容段的模拟 token 数，转发到 {@link TokenSimulatedCounter}。
	 *
	 * @param content 内容段，可为 null
	 * @return 模拟 token 数
	 */
	public static long countTokens(MessageContents content) {
		return content == null ? 0L : TokenSimulatedCounter.fastCountLength(content);
	}

	/** 是否为 CJK 统一表意文字（中文汉字，含扩展区）。 */
	private static boolean isCjk(int codePoint) {
		return (codePoint >= 0x3400 && codePoint <= 0x4DBF)
				|| (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
				|| (codePoint >= 0xF900 && codePoint <= 0xFAFF)
				|| (codePoint >= 0x20000 && codePoint <= 0x2FA1F)
				|| (codePoint >= 0x30000 && codePoint <= 0x3134F);
	}

	/** 是否可以作为英文单词中间的连接符。 */
	private static boolean isWordConnector(int codePoint) {
		return codePoint == '\'' || codePoint == '\u2019' || codePoint == '-';
	}

	/** 连接符后面是否还有单词字符，避免把句末的撇号/连字符算进单词。 */
	private static boolean hasWordPartAfter(CharSequence text, int index) {
		if (index >= text.length()) {
			return false;
		}
		int next = Character.codePointAt(text, index);
		return !isCjk(next) && Character.isLetterOrDigit(next);
	}
}
