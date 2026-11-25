package com.khjxiaogu.aiwuxia.mcp;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.FileContent;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;

/**
 * 多媒体输出工具集：把图片、视频、文件、表情包等媒体作为工具结果附件交给会话，由会话按自己的方式下发。
 * <p>
 * 工具只校验参数并构造 {@link ImageContent} / {@link VideoContent} / {@link FileContent} 附件，
 * 本身不直接发送任何消息。发送图片按用途提供两个变体：网页/绘画会话用带 {@code description} 的描述版，
 * 聊天软件用带 {@code is_nsfw} 的不宜内容标记版；两者的工具名同为 {@code send_image}，由不同会话分别注册。
 * 发送视频目前只提供网页/绘画会话使用的描述版 {@code send_video}；发送文件是聊天软件用的
 * {@code send_file}，接收者看到的文件名由调用者指定。
 * </p>
 * <p>
 * 附件里保存的是<b>媒体 id</b>（对象存储键），不是图片/视频内容本身，发送时再按 id 从对象存储取用。
 * 这样历史记录不会因为媒体被写成 base64 data URL 而膨胀，摘要提示也不会把整个文件当成正文。
 * </p>
 */
public final class MediaOutputMcp {

	/** 媒体描述的最大长度：描述会进入网页 alt/下载文件名与模型上下文。 */
	private static final int MAX_DESCRIPTION_LENGTH = 120;

	/** 媒体 id 的标准长度（16 进制内容哈希）。 */
	private static final int MEDIA_ID_LENGTH = 72;

	private MediaOutputMcp() {
	}

	/**
	 * 发送图片（网页/绘画会话）：额外提供图片描述，供网页 alt 与下载文件名使用。
	 *
	 * @param tos 对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createSendImage(ObjectStorageProvider tos) {
		return new ToolData.Builder("send_image", "发送图片")
				.sessionOnly()
				.orderSensitive()
				.putUrlParam("picture_id", "输入图片", "图片ID")
				.putParam("description", "图片的简短描述，用作网页描述（alt）与下载文件名；建议 20 字以内，可为空字符串。",
						"图片描述")
				.presenter(ToolCallPresenter.ofBrief(call -> "发送图片"))
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String fn = jo.get("picture_id").getAsString();
						if (!tos.exists(fn, output == null ? null : output::addUsage)) {
							return CompletableFuture.completedFuture(missingImage(fn));
						}
						// 只保存图片 id，发送时由会话从对象存储取图；不要把 base64 写进附件
						return CompletableFuture.completedFuture(ToolResult.of("图片已提交发送",
								new ImageContent(fn, readDescription(jo))));
					} catch (Throwable t) {
						t.printStackTrace();
					}
					return CompletableFuture.completedFuture(ToolResult.text("参数格式错误，请检查输入。"));
				}).build();
	}

	/**
	 * 发送视频（网页/绘画会话）：额外提供视频描述，供网页说明与下载文件名使用。
	 *
	 * @param tos 对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createSendVideo(ObjectStorageProvider tos) {
		return new ToolData.Builder("send_video",
				"把已生成/上传的视频发送到当前对话：video_id 传 72 位视频 id。"
						+ "只有需要让用户直接看到视频时才调用；如果只是告知视频 id，不必调用本工具。")
				.sessionOnly()
				.orderSensitive()
				.putUrlParam("video_id", "输入视频", "视频ID")
				.putParam("description", "视频的简短描述，用作网页说明与下载文件名；建议 20 字以内，可为空字符串。",
						"视频描述")
				.presenter(ToolCallPresenter.ofBrief(call -> "发送视频"))
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String fn = jo.get("video_id").getAsString();
						if (!tos.exists(fn, output == null ? null : output::addUsage)) {
							return CompletableFuture.completedFuture(missingVideo(fn));
						}
						// 只保存视频 id，发送时由会话按 id 取用；不要把视频内容写进附件
						return CompletableFuture.completedFuture(ToolResult.of("视频已提交发送",
								new VideoContent(fn, readDescription(jo))));
					} catch (Throwable t) {
						t.printStackTrace();
					}
					return CompletableFuture.completedFuture(ToolResult.text("参数格式错误，请检查输入。"));
				}).build();
	}

	/**
	 * 发送图片（聊天软件）：不带描述，额外提供不宜内容标记，供聊天端做遮挡处理。
	 *
	 * @param tos 对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createSendImageForChat(ObjectStorageProvider tos) {
		return new ToolData.Builder("send_image", "发送图片")
				.sessionOnly()
				.orderSensitive()
				.putUrlParam("picture_id", "输入图片", "图片ID")
				.putParam("is_nsfw",
						"是否不宜内容（NSFW），true/false字符串，留空按false处理；聊天软件会据此做遮挡处理。",
						"是否不宜内容")
				.presenter(ToolCallPresenter.ofBrief(call -> "发送图片"))
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String fn = jo.get("picture_id").getAsString();
						Boolean isNsfw = readNsfw(jo);
						if (isNsfw == null) {
							return CompletableFuture.completedFuture(ToolResult.text("is_nsfw错误，必须为true/false之一"));
						}
						if (!tos.exists(fn, output == null ? null : output::addUsage)) {
							return CompletableFuture.completedFuture(missingImage(fn));
						}
						// 只保存图片 id，发送时由会话从对象存储取图；不要把 base64 写进附件
						return CompletableFuture.completedFuture(ToolResult.of("图片已提交发送",
								new ImageContent(fn, null, isNsfw.booleanValue())));
					} catch (Throwable t) {
						t.printStackTrace();
					}
					return CompletableFuture.completedFuture(ToolResult.text("参数格式错误，请检查输入。"));
				}).build();
	}

	/**
	 * 发送文件（聊天软件）：把对象存储里的文件以指定文件名发给当前会话。
	 * <p>
	 * 与发送图片/视频不同，{@code name} 是接收者看到的文件名（含扩展名）：对象存储 id 是内容哈希，
	 * 直接发送会显示成一串 hex，因此需要由调用者给出可读的名字。文件内容由会话按 id 从对象存储
	 * 取用，工具本身不读取文件内容。
	 * </p>
	 *
	 * @param tos 对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createSendFileForChat(ObjectStorageProvider tos) {
		return new ToolData.Builder("send_file",
				"把对象存储里的文件以指定文件名发送到当前聊天。file_id 传 72 位文件 id，"
						+ "name 传接收者看到的文件名（含扩展名，例如 报告.pdf，不能带路径）。"
						+ "只有需要把文件直接发给用户时才调用；仅告知文件 id 时不必调用本工具。")
				.sessionOnly()
				.orderSensitive()
				.putUrlParam("file_id", "输入文件", "文件ID")
				.putParam("name", "发送时的文件名，含扩展名，例如 报告.pdf；只写文件名，不能带路径", "文件名")
				.presenter(ToolCallPresenter.ofBrief(call -> "发送文件"))
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String fn = jo.get("file_id").getAsString();
						String name = jo.has("name") && !jo.get("name").isJsonNull()
								? jo.get("name").getAsString().trim() : "";
						if (name.isEmpty()) {
							return CompletableFuture.completedFuture(
									ToolResult.text("参数格式错误：缺少 name（发送时的文件名）。"));
						}
						if (!tos.exists(fn, output == null ? null : output::addUsage)) {
							return CompletableFuture.completedFuture(missingFile(fn));
						}
						// 只保存文件 id 与显示名，发送时由会话按 id 取用；不要把文件内容写进附件
						return CompletableFuture.completedFuture(ToolResult.of("文件已提交发送，文件名：" + name,
								new FileContent(fn, name)));
					} catch (Throwable t) {
						t.printStackTrace();
					}
					return CompletableFuture.completedFuture(ToolResult.text("参数格式错误，请检查输入。"));
				}).build();
	}

	/** 图片 id 不存在时的统一提示（顺带提示长度错误这一常见原因）。 */
	private static ToolResult missingImage(String fn) {
		return ToolResult.text("图片id不存在"
				+ (fn.length() == MEDIA_ID_LENGTH ? "" : "，该错误是因为图片ID长度不是72导致，请检查输入") + "。");
	}

	/** 视频 id 不存在时的统一提示（顺带提示长度错误这一常见原因）。 */
	private static ToolResult missingVideo(String fn) {
		return ToolResult.text("视频id不存在"
				+ (fn.length() == MEDIA_ID_LENGTH ? "" : "，该错误是因为视频ID长度不是72导致，请检查输入") + "。");
	}

	/** 文件 id 不存在时的统一提示（顺带提示长度错误这一常见原因）。 */
	private static ToolResult missingFile(String fn) {
		return ToolResult.text("文件id不存在"
				+ (fn.length() == MEDIA_ID_LENGTH ? "" : "，该错误是因为文件ID长度不是72导致，请检查输入") + "。");
	}

	/**
	 * 发送表情包图片。
	 * <p>
	 * 表情配置里保存的就是可直接访问的图片地址，因此不需要再经过对象存储转换。
	 * </p>
	 *
	 * @param images 表情 id 到图片地址的映射
	 * @return 独立的工具定义
	 */
	public static ToolData createSendEmojiImage(Map<String, String> images) {
		return new ToolData.Builder("send_emoji_image", "发送表情包图片")
				.sessionOnly()
				.orderSensitive()
				.putParam("emoji_id", "表情id", "表情ID")
				.presenter(ToolCallPresenter.ofArgument("发送表情包", "emoji_id"))
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String fn = images == null ? null : images.get(jo.get("emoji_id").getAsString());
						if (fn == null)
							return CompletableFuture.completedFuture(ToolResult.text("表情id不存在"));
						return CompletableFuture.completedFuture(
								ToolResult.of("图片已提交发送", new ImageContent(fn)));
					} catch (Throwable t) {
						t.printStackTrace();
					}
					return CompletableFuture.completedFuture(ToolResult.text("参数格式错误"));
				}).build();
	}

	/** 读取并规范化可选的图片描述；缺省或空串返回 null。 */
	private static String readDescription(JsonObject jo) {
		if (!jo.has("description") || jo.get("description").isJsonNull()) {
			return null;
		}
		String description = jo.get("description").getAsString().replaceAll("\\s+", " ").trim();
		// 描述会进入网页 alt/文件名与模型上下文，限制长度，避免异常长的文本
		if (description.length() > MAX_DESCRIPTION_LENGTH) {
			description = description.substring(0, MAX_DESCRIPTION_LENGTH);
		}
		return description.isEmpty() ? null : description;
	}

	/**
	 * 读取可选的不宜内容标记；缺省或留空按 false 处理。
	 *
	 * @return 解析结果；取值无法识别时返回 null
	 */
	private static Boolean readNsfw(JsonObject jo) {
		String nsfw = jo.has("is_nsfw") && !jo.get("is_nsfw").isJsonNull()
				? jo.get("is_nsfw").getAsString().trim() : "";
		if (nsfw.isEmpty()) {
			return Boolean.FALSE;
		}
		if ("true".equalsIgnoreCase(nsfw) || "是".equals(nsfw) || "yes".equalsIgnoreCase(nsfw)
				|| "y".equalsIgnoreCase(nsfw)) {
			return Boolean.TRUE;
		}
		if ("false".equalsIgnoreCase(nsfw) || "否".equals(nsfw) || "no".equalsIgnoreCase(nsfw)
				|| "n".equalsIgnoreCase(nsfw)) {
			return Boolean.FALSE;
		}
		return null;
	}
}
