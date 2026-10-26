package com.khjxiaogu.aiwuxia.mcp;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;

/**
 * QQ 会话专属的图片发送工具。
 * <p>
 * 工具只校验图片 id 并把 {@link ImageContent} 放进工具结果，真正的发送由 QQ 会话处理，
 * 通用工具不直接调用任何 QQ 接口。
 * </p>
 */
public class QQMediaMcp {

	private QQMediaMcp() {
	}

	/**
	 * 发送聊天图片。
	 *
	 * @param tos 对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createSendImage(ObjectStorageProvider tos) {
		return new ToolData.Builder("send_image", "发送聊天图片")
				.sessionOnly()
				.orderSensitive()
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身", "图片ID")
				.putParam("is_nsfw", "是否不宜内容（NSFW），不宜内容会标记为不宜内容，值范围是true/false字符串。", "是否不宜内容")
				.presenter(ToolCallPresenter.ofBrief(call -> "发送图片"))
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String fn = jo.get("picture_id").getAsString();
						String nsfw = jo.has("is_nsfw") && !jo.get("is_nsfw").isJsonNull()
								? jo.get("is_nsfw").getAsString() : "false";
						boolean isNsfw;
						if ("true".equalsIgnoreCase(nsfw) || "是".equals(nsfw) || "yes".equalsIgnoreCase(nsfw)
								|| "y".equalsIgnoreCase(nsfw))
							isNsfw = true;
						else if ("false".equalsIgnoreCase(nsfw) || "否".equals(nsfw) || "no".equalsIgnoreCase(nsfw)
								|| "n".equalsIgnoreCase(nsfw))
							isNsfw = false;
						else
							return CompletableFuture.completedFuture(ToolResult.text("is_nsfw错误，必须为true/false之一"));
						if (!tos.exists(fn, output == null ? null : output::addUsage)) {
							return CompletableFuture.completedFuture(ToolResult.text("图片id不存在"
									+ (fn.length() == 72 ? "" : "，该错误是因为图片ID长度不是72导致，请检查输入") + "。"));
						}
						return CompletableFuture.completedFuture(ToolResult.of("图片已提交发送", new ImageContent(
								tos.getUrl(fn, output == null ? null : output::addUsage), null, isNsfw)));
					} catch (Throwable t) {
						t.printStackTrace();
					}
					return CompletableFuture.completedFuture(ToolResult.text("参数格式错误，请检查输入。"));
				}).build();
	}

	/**
	 * 发送表情包图片。
	 *
	 * @param tos    对象存储
	 * @param images 表情 id 到图片 id 的映射
	 * @return 独立的工具定义
	 */
	public static ToolData createSendEmojiImage(ObjectStorageProvider tos, Map<String, String> images) {
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
						// 表情配置里保存的就是可直接访问的图片 URL，不需要再经过对象存储转换
						return CompletableFuture.completedFuture(
								ToolResult.of("图片已提交发送", new ImageContent(fn)));
					} catch (Throwable t) {
						t.printStackTrace();
					}
					return CompletableFuture.completedFuture(ToolResult.text("参数格式错误"));
				}).build();
	}
}
