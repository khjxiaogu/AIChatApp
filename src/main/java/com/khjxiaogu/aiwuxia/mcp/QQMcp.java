package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.objectstorage.TOSUsage;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;

/**
 * QQ 头像相关工具。
 * <p>
 * 这些工具与会话相关（需要群号），但相互之间没有必须共享的实例状态，因此提供独立工厂，
 * 不创建 Provider 类。发送图片等媒体工具在 {@link QQMediaMcp}。
 * </p>
 */
public class QQMcp {

	/**
	 * 获取用户头像图片 id。
	 *
	 * @param tos 对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createAvatarFetch(ObjectStorageProvider tos) {
		return new ToolData.Builder("avatar_fetch", "获取用户头像的图片id以便后续处理。")
				.putParam("user_id", "用户的id，可以从消息的senderId中取得。").toolText((data, output, manager) -> {
					Pattern patt = Pattern.compile("\"user_id\"\\s*:\\s*\"([0-9]+)\"");
					Matcher mtch = patt.matcher(data);
					if (mtch.find()) {
						String id = mtch.group(1);

						try {
							byte[] picture = HttpRequestBuilder.create("q1.qlogo.cn").defUA()
									.url("/g?b=qq&nk=" + id + "&s=640").get().readBytes();

							if (output != null) {
								output.addUsage(new TOSUsage(picture.length));
							}
							return tos.uploadIfNotExists(picture, output == null ? null : output::addUsage);
						} catch (IOException e) {
							e.printStackTrace();
							return "头像获取失败";
						}

					}
					return "参数格式错误";
				}).build();
	}

	/**
	 * 获取群头像图片 id。
	 *
	 * @param groupId 群号
	 * @param tos     对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createAvatarGroupRecognition(String groupId, ObjectStorageProvider tos) {
		return new ToolData.Builder("avatar_group_recognition", "获取群头像的图片id以便后续处理。")
				.toolText((data, output, manager) -> {
					try {
						byte[] picture = HttpRequestBuilder.create("p.qlogo.cn").defUA()
								.url("/gh/" + groupId + "/" + groupId + "/640").get().readBytes();

						if (output != null) {
							output.addUsage(new TOSUsage(picture.length));
						}
						return tos.uploadIfNotExists(picture, output == null ? null : output::addUsage);
					} catch (IOException e) {
						e.printStackTrace();
						return "头像获取失败";
					}
				}).build();
	}
}
