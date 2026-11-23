package com.khjxiaogu.aiwuxia.modelproxy.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 朗读（TTS）调用的参数。
 * <p>
 * 独立的值对象，不继承任何基类。{@code content} 每一项至少包含 {@code text}，
 * 其余同名字段原样透传给本机 TTS 服务。
 * </p>
 */
public final class AudioParams {

	/** 角色（音色）标识。 */
	public final String chara;
	/** 朗读内容分段。 */
	public final List<Map<String, String>> content;

	public AudioParams(String chara, List<Map<String, String>> content) {
		this.chara = chara == null ? "" : chara;
		this.content = content == null ? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(content));
	}

	/** 所有分段的文本拼接，用于计费。 */
	public int textLength() {
		int len = 0;
		for (Map<String, String> segment : content) {
			String text = segment == null ? null : segment.get("text");
			if (text != null) {
				len += text.length();
			}
		}
		return len;
	}
}
