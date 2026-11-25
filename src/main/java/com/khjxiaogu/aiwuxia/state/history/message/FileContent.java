package com.khjxiaogu.aiwuxia.state.history.message;

/**
 * 文件消息内容（普通文件，非图片/视频/语音）。
 * <p>
 * 工具只产生本内容，具体如何发送由会话按协议处理。文件内容不进入模型请求，因此
 * {@code file_id} 保存的是对象存储 ID（内容哈希，可带扩展名），发送时再由会话解析成
 * 协议可访问的地址；历史里只保留 id 与显示文件名。
 * </p>
 * <p>
 * {@code name} 是接收者看到的文件名（含扩展名）。构造时会去掉路径部分与控制字符，
 * 避免把目录结构带进聊天界面；空名退回 {@code file}。
 * </p>
 */
public class FileContent implements MessageContent {

	final String file_id;
	final String name;
	final String description;

	/**
	 * 使用文件 id 与显示文件名构造文件内容（无描述）。
	 *
	 * @param file_id 文件 id（对象存储键，可带扩展名）
	 * @param name    发送时的文件名（含扩展名）
	 */
	public FileContent(String file_id, String name) {
		this(file_id, name, null);
	}

	/**
	 * 使用文件 id、显示文件名与描述构造文件内容。
	 *
	 * @param file_id     文件 id（对象存储键，可带扩展名）
	 * @param name        发送时的文件名（含扩展名）
	 * @param description 文件描述，可为 null
	 */
	public FileContent(String file_id, String name, String description) {
		this.file_id = file_id;
		this.name = normalizeName(name);
		this.description = description;
	}

	/**
	 * 规范化显示文件名：只保留文件名部分（去掉 {@code /}、{@code \} 之前的内容），
	 * 去掉控制字符，空白回退为 {@code file}。
	 *
	 * @param name 原始文件名，可为 null
	 * @return 可直接用于聊天界面的文件名
	 */
	private static String normalizeName(String name) {
		String value = name == null ? "" : name.trim();
		int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
		if (slash >= 0) {
			value = value.substring(slash + 1);
		}
		value = value.replaceAll("\\p{Cntrl}", "").trim();
		if (value.length() > 120) {
			value = value.substring(0, 120);
		}
		return value.isEmpty() ? "file" : value;
	}

	/** 文件 id（对象存储键，可带扩展名）。 */
	public String getFileId() {
		return file_id;
	}

	/** 发送时的文件名（含扩展名）。 */
	public String getName() {
		return name;
	}

	/** 文件描述，可能为 null。 */
	public String getDescription() {
		return description;
	}

	@Override
	public boolean isPlainText() {
		return false;
	}

	@Override
	public boolean isTextRepresentable() {
		return true;
	}

	@Override
	public String toText() {
		return description == null ? "<file id=" + file_id + " name=\"" + name + "\">"
				: "<file id=" + file_id + " name=\"" + name + "\" alt=\"" + description + "\">";
	}

	@Override
	public String getType() {
		return "file";
	}

	@Override
	public MessageContent copy() {
		return this;
	}
}
