package com.khjxiaogu.aiwuxia.mcp;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;

/**
 * 绘画会话专属的图片发送工具。
 * <p>
 * 只产生 {@link ImageContent} 附件，由绘画会话通过自己的内容帧机制下发，不直接发送消息。
 * 绘画会话不需要表情包发送工具，因此这里只保留 {@code send_image}。
 * </p>
 */
public class PainterMediaMcp {

	private PainterMediaMcp() {
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
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身")
				.putParam("description", "图片的简短描述，用作网页描述（alt）与下载文件名；建议 20 字以内，可为空字符串。")
				.presenter(ToolCallPresenter.ofBrief(call -> "发送图片"))
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String fn = jo.get("picture_id").getAsString();
						String description = jo.has("description") && !jo.get("description").isJsonNull()
								? jo.get("description").getAsString().replaceAll("\\s+", " ").trim() : "";
						// 描述会进入网页 alt/文件名与模型上下文，限制长度，避免异常长的文本
						if (description.length() > 120) {
							description = description.substring(0, 120);
						}
						if (!tos.exists(fn, output == null ? null : output::addUsage)) {
							return CompletableFuture.completedFuture(ToolResult.text("图片id不存在"
									+ (fn.length() == 72 ? "" : "，该错误是因为图片ID长度不是72导致，请检查输入") + "。"));
						}
						return CompletableFuture.completedFuture(ToolResult.of("图片已提交发送",
								new ImageContent(fn, description.isEmpty() ? null : description)));
					} catch (Throwable t) {
						t.printStackTrace();
					}
					return CompletableFuture.completedFuture(ToolResult.text("参数格式错误。"));
				}).build();
	}
}
