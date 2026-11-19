package com.khjxiaogu.aiwuxia.objectstorage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Consumer;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

public interface ObjectStorageProvider {
	public boolean exists(String fn,Consumer<UsageIntf<?>> addUsage);
	public String upload(String fn,byte[] data,Consumer<UsageIntf<?>> addUsage) throws IOException;
	/**
	 * 内容的内联地址（通常是 {@code data:} URL），供只接受内联数据的调用方使用。
	 * <p>
	 * 默认实现会下载对象内容再编码成 data URL；本地存储按文件名猜 MIME 的实现可以覆盖它，
	 * 避免多一次下载。
	 * </p>
	 *
	 * @param fn       对象存储键
	 * @param addUsage 用量回调，可为 null
	 * @return 可直接读取内容的数据地址
	 * @throws IOException 读取失败
	 */
	default String getDataUrl(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException {
		return FileUtil.toDataUrl(download(fn,addUsage));
	}
	/**
	 * 内容的公网可访问 http(s) 地址。
	 * <p>
	 * 无法提供公网地址的实现（例如只能读本地文件、只能给 data URL 的存储）返回 {@code null}，
	 * 调用方应退回 {@link #getDataUrl(String, Consumer)}。
	 * </p>
	 *
	 * @param fn       对象存储键
	 * @param addUsage 用量回调，可为 null
	 * @return 公网 http(s) 地址；不支持时返回 null
	 * @throws IOException 读取/转存失败
	 */
	default String getHttpUrl(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException {
		return null;
	}
	/**
	 * 优先公网 http 地址；存储不支持公网访问时退回 {@link #getDataUrl(String, Consumer)}。
	 *
	 * @param fn       对象存储键
	 * @param addUsage 用量回调，可为 null
	 * @return 内容地址
	 * @throws IOException 读取/转存失败
	 */
	default String getPreferredUrl(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException {
		String http = getHttpUrl(fn,addUsage);
		if (http != null && !http.isEmpty()) {
			return http;
		}
		return getDataUrl(fn,addUsage);
	}
	default boolean exists(byte[] data,Consumer<UsageIntf<?>> addUsage) {
		MessageDigest md;
		try {
			md = getSha256Digest();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
			throw new IllegalArgumentException(e);
		}
		String name=bytesToHex(md.digest(data))+bytesToHex(ByteBuffer.allocate(4).putInt(data.length).array());
		return exists(name,addUsage);
	}
	default String uploadIfNotExists(byte[] data,Consumer<UsageIntf<?>> addUsage) throws IOException {
		return uploadIfNotExists(data, "", addUsage);
	}

	/**
	 * 按内容寻址上传，并给对象键追加扩展名。
	 * <p>
	 * 键仍然是 72 位的内容哈希（SHA-256 + 长度），只是末尾多一个扩展名，例如
	 * {@code <72位哈希>.mp4}。带扩展名的键便于下游按 MIME/文件名识别媒体类型
	 * （远端对象存储的下载链接会因此得到正确的后缀），现有的多媒体 ID 解析规则
	 * （72 位哈希 + 可选扩展名）同样接受这种键。
	 * </p>
	 * <p>
	 * 注意：{@link LocalStorage} 会忽略媒体扩展名，统一使用无后缀的哈希作为键，并改为按
	 * 文件内容推断 MIME；只有远端存储保留扩展名。
	 * </p>
	 *
	 * @param data      对象内容
	 * @param extension 扩展名，可带或不带前导点，例如 {@code ".mp4"}；空串表示不加扩展名
	 * @param addUsage  用量回调，可为 null
	 * @return 对象存储键
	 * @throws IOException 上传失败
	 */
	default String uploadIfNotExists(byte[] data, String extension, Consumer<UsageIntf<?>> addUsage)
			throws IOException {
		MessageDigest md;
		try {
			md = getSha256Digest();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
			throw new IllegalArgumentException(e);
		}
		String suffix = extension == null ? "" : extension.trim();
		if (!suffix.isEmpty() && !suffix.startsWith(".")) {
			suffix = "." + suffix;
		}
		String name = bytesToHex(md.digest(data)) + bytesToHex(ByteBuffer.allocate(4).putInt(data.length).array())
				+ suffix;
		if (exists(name, addUsage)) {
			return name;
		}
		return upload(name, data, addUsage);
	}
	public static String bytesToHex(byte[] hash) {
		StringBuilder hexString = new StringBuilder();
		for (int i = 0; i < hash.length; i++) {
			String hex = Integer.toHexString(0xff & hash[i]);
			if (hex.length() == 1) {
				hexString.append('0');
			}
			hexString.append(hex);
		}
		return hexString.toString();
	}
	default byte[] download(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException {
		String url = getHttpUrl(fn,addUsage);
		if(url == null)
			throw new IOException("对象存储没有公网 http 地址：" + fn);
		return FileUtil.readAll(FileUtil.fetch(url));
		
	}
	public static MessageDigest getSha256Digest() throws NoSuchAlgorithmException {
		return MessageDigest.getInstance("SHA-256");
	}
}
