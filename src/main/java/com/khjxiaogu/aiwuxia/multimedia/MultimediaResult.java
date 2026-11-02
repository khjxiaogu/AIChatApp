package com.khjxiaogu.aiwuxia.multimedia;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;

/**
 * 通用多媒体生成结果：输出附件 + 提示词。
 * <p>
 * {@link #getPrompt()} 是本次生成产出的说明/提示文本（例如图片描述、状态文本或语音原文）；
 * {@link #getAttachments()} 是生成出的图片、视频或音频。失败时 {@link #isSuccess()} 为 false，
 * {@code prompt} 携带错误原因，附件为空。
 * </p>
 */
public final class MultimediaResult {

	private final boolean success;
	private final String prompt;
	private final List<MessageContent> attachments;

	private MultimediaResult(boolean success, String prompt, Collection<? extends MessageContent> attachments) {
		this.success = success;
		this.prompt = prompt == null ? "" : prompt;
		List<MessageContent> list = new ArrayList<>();
		if (attachments != null) {
			for (MessageContent attachment : attachments) {
				if (attachment != null) {
					list.add(attachment);
				}
			}
		}
		this.attachments = Collections.unmodifiableList(list);
	}

	/** 成功结果。 */
	public static MultimediaResult success(String prompt, MessageContent... attachments) {
		return new MultimediaResult(true, prompt, java.util.Arrays.asList(attachments));
	}

	/** 成功结果。 */
	public static MultimediaResult success(String prompt, Collection<? extends MessageContent> attachments) {
		return new MultimediaResult(true, prompt, attachments);
	}

	/** 失败结果，prompt 即错误原因。 */
	public static MultimediaResult failure(String message) {
		return new MultimediaResult(false, message, Collections.<MessageContent>emptyList());
	}

	/** 是否成功。 */
	public boolean isSuccess() {
		return success;
	}

	/** 输出提示词/说明文本；失败时为错误原因。 */
	public String getPrompt() {
		return prompt;
	}

	/** 输出附件，只读，永不为 null。 */
	public List<MessageContent> getAttachments() {
		return attachments;
	}

	/** 是否带输出附件。 */
	public boolean hasAttachments() {
		return !attachments.isEmpty();
	}

	@Override
	public String toString() {
		return "MultimediaResult [success=" + success + ", prompt=" + prompt + ", attachments="
				+ attachments.size() + "]";
	}
}
