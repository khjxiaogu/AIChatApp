package com.khjxiaogu.aiwuxia.multimedia;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;

/**
 * 通用多媒体生成请求：可选附件 + 提示词。
 * <p>
 * 除统一的附件与提示词外，提供可选的 {@link #getOptions() options} 承载具体实现需要的参数
 * （例如语音的说话人、视频时长），以及可选的 {@link #getStorage() 对象存储} 和
 * {@link #getOutput() 用量接收方}。核心输入始终只有附件和提示词。
 * </p>
 */
public final class MultimediaRequest {

	/** 没有附件、没有提示词的请求。 */
	public static final MultimediaRequest EMPTY = builder("").build();

	private final List<MessageContent> attachments;
	private final String prompt;
	private final Map<String, String> options;
	private final ObjectStorageProvider storage;
	private final AIOutput output;

	private MultimediaRequest(Builder builder) {
		this.attachments = Collections.unmodifiableList(new ArrayList<>(builder.attachments));
		this.prompt = builder.prompt == null ? "" : builder.prompt;
		this.options = Collections.unmodifiableMap(new LinkedHashMap<>(builder.options));
		this.storage = builder.storage;
		this.output = builder.output;
	}

	/** 以提示词创建请求构建器。 */
	public static Builder builder(String prompt) {
		return new Builder(prompt);
	}

	/** 以提示词创建请求（无附件、无额外参数）。 */
	public static MultimediaRequest of(String prompt) {
		return builder(prompt).build();
	}

	/** 输入附件，只读，永不为 null。 */
	public List<MessageContent> getAttachments() {
		return attachments;
	}

	/** 是否带附件。 */
	public boolean hasAttachments() {
		return !attachments.isEmpty();
	}

	/** 输入提示词；未提供时为空串。 */
	public String getPrompt() {
		return prompt;
	}

	/** 实现相关参数的只读视图，永不为 null。 */
	public Map<String, String> getOptions() {
		return options;
	}

	/** 读取一个实现相关参数。 */
	public String getOption(String key) {
		return options.get(key);
	}

	/** 读取一个实现相关参数，缺失时使用兜底值。 */
	public String getOption(String key, String fallback) {
		String value = options.get(key);
		return value == null || value.isEmpty() ? fallback : value;
	}

	/** 本次调用使用的对象存储，可为 null。 */
	public ObjectStorageProvider getStorage() {
		return storage;
	}

	/** 本次调用的用量接收方，可为 null。 */
	public AIOutput getOutput() {
		return output;
	}

	/** {@link MultimediaRequest} 构建器。 */
	public static final class Builder {
		private final List<MessageContent> attachments = new ArrayList<>();
		private final Map<String, String> options = new LinkedHashMap<>();
		private String prompt;
		private ObjectStorageProvider storage;
		private AIOutput output;

		private Builder(String prompt) {
			this.prompt = prompt;
		}

		/** 追加一个输入附件，null 忽略。 */
		public Builder attachment(MessageContent attachment) {
			if (attachment != null) {
				attachments.add(attachment);
			}
			return this;
		}

		/** 追加一组输入附件，null 元素忽略。 */
		public Builder attachments(Collection<? extends MessageContent> attachments) {
			if (attachments != null) {
				for (MessageContent attachment : attachments) {
					attachment(attachment);
				}
			}
			return this;
		}

		/** 设置实现相关参数。 */
		public Builder option(String key, String value) {
			if (key != null && value != null) {
				options.put(key, value);
			}
			return this;
		}

		/** 设置对象存储，用于解析/上传附件。 */
		public Builder storage(ObjectStorageProvider storage) {
			this.storage = storage;
			return this;
		}

		/** 设置用量接收方。 */
		public Builder output(AIOutput output) {
			this.output = output;
			return this;
		}

		/** 替换提示词。 */
		public Builder prompt(String prompt) {
			this.prompt = prompt;
			return this;
		}

		public MultimediaRequest build() {
			return new MultimediaRequest(this);
		}
	}
}
