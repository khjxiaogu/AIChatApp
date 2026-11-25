package com.khjxiaogu.aiwuxia.mcp;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.chat.ChatAdapter;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.objectstorage.LocalStorage;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;

/**
 * 群文件工具：下载群里上传的文件，保存到对象存储并返回文件 id。
 * <p>
 * 不同协议对“文件引用”的定义不同（QQ 官方协议只能靠附件 URL / 消息 id / 文件名，OneBot 则提供
 * 文件 id），因此工具把定位与下载逻辑交给 {@link ChatAdapter#downloadGroupFile(String, String)}，
 * 自身只负责参数校验、对象存储写入与结果格式化。只有
 * {@link ChatAdapter#supportsGroupFileDownload()} 为 true 的协议才应注册本工具。
 * </p>
 * <p>
 * 下载的文件按内容哈希保存：对象键是 72 位十六进制 id（远端存储可能再带一个扩展名），
 * 返回给模型的就是这个 id，后续识别、转存、发送等工具可以直接使用。
 * </p>
 */
public final class GroupFileMcp {

	private GroupFileMcp() {
	}

	/**
	 * 创建“下载群文件”工具。
	 *
	 * @param adapter 协议适配器
	 * @param tos     对象存储（本地对象存储或本地+远端组合）
	 * @param groupId 当前群 id，模型省略 group_id 时使用
	 * @return 独立的工具定义
	 */
	public static ToolData createDownloadGroupFile(ChatAdapter adapter, ObjectStorageProvider tos, String groupId) {
		return new ToolData.Builder("download_group_file",
				"下载群里上传的群文件并保存到对象存储，返回文件 id。file 参数传消息里给出的群文件名、"
						+ "消息 id 或下载链接；只能下载最近收到的群文件，找不到时让用户重新上传。"
						+ "返回的文件 id 可用于后续处理。")
				.putParam("file", "群文件名、消息 id 或下载链接；优先传消息里的文件名。", "文件")
				.putOptionalParam("group_id", "群 id，省略时使用当前群。", "群 id")
				.orderSensitive()
				.presenter(ToolCallPresenter.ofArgument("下载群文件", "file"))
				.tool((data, output, manager) -> CompletableFuture
						.completedFuture(download(adapter, tos, groupId, data, output)))
				.build();
	}

	/**
	 * 执行下载：解析参数 → 交给协议下载 → 写入对象存储 → 返回“文件id”。
	 *
	 * @param adapter 协议适配器
	 * @param tos     对象存储
	 * @param groupId 当前群 id
	 * @param data    模型给出的参数 JSON
	 * @param output  输出对象（用于记录存储用量），可为 null
	 * @return 工具结果
	 */
	private static ToolResult download(ChatAdapter adapter, ObjectStorageProvider tos, String groupId, String data,
			AIOutput output) {
		JsonObject json;
		try {
			json = data == null || data.trim().isEmpty() ? new JsonObject()
					: JsonParser.parseString(data).getAsJsonObject();
		} catch (RuntimeException e) {
			return ToolResult.text("参数格式错误，请检查输入。");
		}
		String reference = JsonConfig.getNonBlankString(json, "file");
		if (reference == null) {
			return ToolResult.text("参数错误：缺少群文件引用 file（文件名、消息 id 或下载链接）。");
		}
		String target = JsonConfig.getNonBlankString(json, "group_id");
		if (target == null) {
			target = groupId;
		}
		try {
			byte[] bytes = adapter.downloadGroupFile(target, reference);
			if (bytes == null || bytes.length == 0) {
				return ToolResult.failure("下载群文件失败：文件内容为空。");
			}
			String extension = extensionOf(reference, bytes);
			String id = tos.uploadIfNotExists(bytes, extension, output == null ? null : output::addUsage);
			if (id == null || id.isEmpty()) {
				return ToolResult.failure("下载群文件失败：对象存储没有返回文件 id。");
			}
			return ToolResult.text("群文件已保存，文件id：" + id + "（大小：" + bytes.length + " 字节）。");
		} catch (Throwable t) {
			t.printStackTrace();
			return ToolResult.failure("下载群文件失败：" + describe(t));
		}
	}

	/**
	 * 推断保存时使用的扩展名：优先按内容魔数识别 MIME，识别不出再退回引用里的文件名后缀。
	 *
	 * @param reference 模型给出的文件引用（可能是文件名/消息 id/链接）
	 * @param bytes     文件内容
	 * @return 扩展名（不带点）；无法判断时为空串
	 */
	private static String extensionOf(String reference, byte[] bytes) {
		String mime = LocalStorage.detectMimeType(bytes, reference);
		String fromMime = extensionForMime(mime);
		if (!fromMime.isEmpty()) {
			return fromMime;
		}
		if (reference == null) {
			return "";
		}
		String name = reference;
		int query = name.indexOf('?');
		if (query >= 0) {
			name = name.substring(0, query);
		}
		int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (slash >= 0) {
			name = name.substring(slash + 1);
		}
		int dot = name.lastIndexOf('.');
		if (dot > 0 && dot < name.length() - 1) {
			String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
			if (ext.matches("[0-9a-z]{1,8}")) {
				return ext;
			}
		}
		return "";
	}

	private static String extensionForMime(String mime) {
		if (mime == null) {
			return "";
		}
		switch (mime) {
		case "image/png":
			return "png";
		case "image/jpeg":
			return "jpg";
		case "image/gif":
			return "gif";
		case "image/webp":
			return "webp";
		case "image/svg+xml":
			return "svg";
		case "video/mp4":
			return "mp4";
		case "video/webm":
			return "webm";
		case "audio/mpeg":
			return "mp3";
		case "audio/wav":
			return "wav";
		case "audio/ogg":
			return "ogg";
		case "application/json":
			return "json";
		case "text/plain":
			return "txt";
		default:
			return "";
		}
	}

	/** 异常的可读描述。 */
	private static String describe(Throwable t) {
		if (t == null) {
			return "未知错误";
		}
		Throwable cause = t;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message == null || message.trim().isEmpty() ? cause.getClass().getSimpleName() : message;
	}
}
