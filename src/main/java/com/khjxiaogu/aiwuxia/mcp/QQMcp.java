package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.khjxiaogu.aiwuxia.chat.ChatAdapter;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.objectstorage.TOSUsage;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;

/**
 * QQ 头像相关工具。
 * <p>
 * 这些工具与会话相关（需要群号），但相互之间没有必须共享的实例状态，因此提供独立工厂，
 * 不创建 Provider 类。发送图片等媒体工具在 {@link MediaOutputMcp}。
 * </p>
 */
public class QQMcp {
	private static final long AVATAR_WAIT_SECONDS = 20L;

	/**
	 * 获取用户头像图片 id。
	 *
	 * @param tos 对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createAvatarFetch(ObjectStorageProvider tos) {
		return avatarFetch(null, tos);
	}

	/**
	 * 获取用户头像图片 id（通过适配器，QQ 号 / openid 都可以）。
	 *
	 * @param adapter 协议适配器
	 * @param tos     对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createAvatarFetch(ChatAdapter adapter, ObjectStorageProvider tos) {
		return avatarFetch(adapter, tos);
	}

	private static ToolData avatarFetch(ChatAdapter adapter, ObjectStorageProvider tos) {
		return new ToolData.Builder("avatar_fetch", "获取用户头像的图片id以便后续处理。")
				.putParam("user_id", "用户的id，可以从消息的senderId中取得。", "用户QQ").toolText((data, output, manager) -> {
					Pattern patt = Pattern.compile("\"user_id\"\\s*:\\s*\"([0-9A-Za-z_\\-]+)\"");
					Matcher mtch = patt.matcher(data);
					if (mtch.find()) {
						String id = mtch.group(1);
						try {
							byte[] picture = adapter != null
									? adapter.getUserAvatar(id).get(AVATAR_WAIT_SECONDS, TimeUnit.SECONDS)
									: fetchAvatarByQq(id);
							if (picture == null || picture.length == 0) {
								return "头像获取失败";
							}
							if (output != null) {
								output.addUsage(new TOSUsage(picture.length));
							}
							return tos.uploadIfNotExists(picture, output == null ? null : output::addUsage);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							return "头像获取失败";
						} catch (Exception e) {
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
		return avatarGroup(groupId, null, tos);
	}

	/**
	 * 获取群头像图片 id（通过适配器，QQ 群号 / group_openid 都可以）。
	 *
	 * @param groupId 群号 / group_openid
	 * @param adapter 协议适配器
	 * @param tos     对象存储
	 * @return 独立的工具定义
	 */
	public static ToolData createAvatarGroupRecognition(String groupId, ChatAdapter adapter,
			ObjectStorageProvider tos) {
		return avatarGroup(groupId, adapter, tos);
	}

	private static ToolData avatarGroup(final String groupId, final ChatAdapter adapter,
			final ObjectStorageProvider tos) {
		return new ToolData.Builder("avatar_group_recognition", "获取群头像的图片id以便后续处理。")
				.toolText((data, output, manager) -> {
					try {
						byte[] picture = adapter != null
								? adapter.getGroupAvatar(groupId).get(AVATAR_WAIT_SECONDS, TimeUnit.SECONDS)
								: fetchGroupAvatarByQq(groupId);
						if (picture == null || picture.length == 0) {
							return "头像获取失败";
						}
						if (output != null) {
							output.addUsage(new TOSUsage(picture.length));
						}
						return tos.uploadIfNotExists(picture, output == null ? null : output::addUsage);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return "头像获取失败";
					} catch (Exception e) {
						e.printStackTrace();
						return "头像获取失败";
					}
				}).build();
	}

	private static byte[] fetchAvatarByQq(String id) throws IOException {
		return HttpRequestBuilder.create("q1.qlogo.cn").defUA()
				.url("/g?b=qq&nk=" + id + "&s=640").get().readBytes();
	}

	private static byte[] fetchGroupAvatarByQq(String groupId) throws IOException {
		return HttpRequestBuilder.create("p.qlogo.cn").defUA()
				.url("/gh/" + groupId + "/" + groupId + "/640").get().readBytes();
	}
}
