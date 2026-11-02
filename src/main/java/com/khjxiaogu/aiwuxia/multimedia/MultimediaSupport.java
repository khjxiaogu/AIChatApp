package com.khjxiaogu.aiwuxia.multimedia;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.khjxiaogu.aiwuxia.llm.AttachmentResolver;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.AudioContent;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;

/**
 * 多媒体 API 适配层共用的参数/结果转换工具。
 */
public final class MultimediaSupport {

	/** 文本中的图片 ID：兼容“图片id为：xxx”“图片id：xxx”“image id: xxx”等写法。 */
	private static final Pattern IMAGE_ID = Pattern.compile(
			"(?i)(?:图片|图像|image)\\s*id\\s*(?:为|是)?\\s*[:：]?\\s*([0-9a-f]{72}(?:\\.[0-9a-z]{1,16})?)");

	private MultimediaSupport() {
	}

	/**
	 * 取请求里的图片附件引用，并规范化为对象存储 ID。
	 * <p>
	 * 非图片附件会被拒绝：当前 SDXLAgent / 即梦图片 / 即梦视频都只接受参考图。
	 * </p>
	 */
	public static List<String> imageReferences(MultimediaRequest request, ObjectStorageProvider fallbackStorage) {
		List<String> references = new ArrayList<>();
		ObjectStorageProvider storage = request.getStorage() != null ? request.getStorage() : fallbackStorage;
		AttachmentResolver resolver = new AttachmentResolver(storage, usage(request));
		for (MessageContent attachment : request.getAttachments()) {
			if (!(attachment instanceof ImageContent)) {
				throw new IllegalArgumentException("该接口只支持图片附件，收到：" + attachment.getType());
			}
			String id = resolver.resolveId(((ImageContent) attachment).getImageUrl());
			if (id != null && !id.trim().isEmpty()) {
				references.add(id.trim());
			}
		}
		return references;
	}

	/** 以英文逗号连接附件引用，空列表返回空串。 */
	public static String joinReferences(List<String> references) {
		if (references == null || references.isEmpty()) {
			return "";
		}
		StringBuilder joined = new StringBuilder();
		for (String reference : references) {
			if (reference == null || reference.isEmpty()) {
				continue;
			}
			if (joined.length() > 0) {
				joined.append(',');
			}
			joined.append(reference);
		}
		return joined.toString();
	}

	/**
	 * 把现有工具的结果转换成通用结果。
	 *
	 * @param result           工具结果
	 * @param request          原始请求
	 * @param extractImageIds  文本里没有附件时，是否从“图片id：...”中提取图片附件
	 * @param promptOverride   非 null 时用它作为输出提示词
	 * @return 通用结果
	 */
	public static MultimediaResult fromToolResult(ToolResult result, MultimediaRequest request,
			boolean extractImageIds, String promptOverride) {
		List<MessageContent> attachments = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		String text = "";
		if (result != null) {
			for (MessageContent attachment : result.getAttachments()) {
				addMedia(attachments, seen, attachment);
			}
			if (result.getContent() != null) {
				for (MessageContent content : result.getContent()) {
					addMedia(attachments, seen, content);
				}
				text = plainText(result.getContent());
			}
		}
		if (extractImageIds && !containsImage(attachments)) {
			Matcher matcher = IMAGE_ID.matcher(text);
			while (matcher.find()) {
				String id = matcher.group(1);
				addMedia(attachments, seen, new ImageContent(id));
			}
		}
		String prompt = promptOverride;
		if (prompt == null || prompt.isEmpty()) {
			prompt = text;
		}
		if (prompt == null || prompt.isEmpty()) {
			prompt = request == null ? "" : request.getPrompt();
		}
		return MultimediaResult.success(prompt, attachments);
	}

	/** 成功转换后是否包含指定类型的附件。 */
	public static boolean hasAttachmentOfType(MultimediaResult result, Class<? extends MessageContent> type) {
		for (MessageContent attachment : result.getAttachments()) {
			if (type.isInstance(attachment)) {
				return true;
			}
		}
		return false;
	}

	/** 结果里是否有音频附件。 */
	public static boolean hasAudio(MultimediaResult result) {
		return hasAttachmentOfType(result, AudioContent.class);
	}

	/** 结果里是否有图片附件。 */
	public static boolean hasImage(MultimediaResult result) {
		return hasAttachmentOfType(result, ImageContent.class);
	}

	/** 附件列表里是否已经有图片。 */
	private static boolean containsImage(List<MessageContent> attachments) {
		for (MessageContent attachment : attachments) {
			if (attachment instanceof ImageContent) {
				return true;
			}
		}
		return false;
	}

	/** 结果里是否有视频附件。 */
	public static boolean hasVideo(MultimediaResult result) {
		return hasAttachmentOfType(result, VideoContent.class);
	}

	/** 用量接收方。 */
	public static Consumer<UsageIntf<?>> usage(MultimediaRequest request) {
		return request == null || request.getOutput() == null ? null : request.getOutput()::addUsage;
	}

	/** 工具结果的纯文本内容，失败时用来取错误原因。 */
	public static String toolText(ToolResult result) {
		return result == null || result.getContent() == null ? "" : plainText(result.getContent());
	}

	/** 只拼接纯文本段，忽略图片/音频/视频标签。 */
	private static String plainText(MessageContents contents) {
		StringBuilder text = new StringBuilder();
		for (MessageContent content : contents) {
			if (content != null && content.isPlainText()) {
				text.append(content.toText());
			}
		}
		return text.toString().trim();
	}

	/** 收集媒体附件并去重。 */
	private static void addMedia(List<MessageContent> attachments, Set<String> seen, MessageContent content) {
		if (!(content instanceof ImageContent) && !(content instanceof VideoContent)
				&& !(content instanceof AudioContent)) {
			return;
		}
		String key = content.getClass().getName() + ":" + referenceOf(content);
		if (!seen.add(key)) {
			return;
		}
		attachments.add(content);
	}

	/** 取内容里的媒体引用，用于去重。 */
	private static String referenceOf(MessageContent content) {
		if (content instanceof ImageContent) {
			return ((ImageContent) content).getImageUrl();
		}
		if (content instanceof VideoContent) {
			return ((VideoContent) content).getVideoUrl();
		}
		if (content instanceof AudioContent) {
			return ((AudioContent) content).getAudioUrl();
		}
		return String.valueOf(content);
	}
}
