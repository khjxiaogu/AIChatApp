package com.khjxiaogu.aiwuxia.objectstorage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import com.khjxiaogu.aiwuxia.mcp.MultiModalMcp;

/**
 * {@link LocalStorage} 的对象键规则自检：
 * 媒体后缀不进入对象键、读取时按内容推断 MIME、{@code .caption} 等非媒体后缀保留、
 * 旧带后缀文件仍可读取。
 */
public class LocalStorageTest {

	private static final byte[] PNG = java.util.Base64.getDecoder()
			.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zf7sAAAAASUVORK5CYII=");
	private static final byte[] MP4 = new byte[] { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 0, 0,
			0, 0, 0, 0, 0, 0, 0, 0 };

	public static void main(String[] args) throws Exception {
		File dir = Files.createTempDirectory("localstorage-test").toFile();
		try {
			LocalStorage storage = new LocalStorage(dir);
			String pngId = storage.uploadIfNotExists(PNG, ".png", null);
			check(pngId.matches("[0-9a-f]{72}"), "媒体键应无后缀，实际：" + pngId);
			check(storage.exists(pngId, null), "无后缀键应能查到文件");
			check(!new File(dir, pngId + ".png").exists(), "不应再写出带 .png 后缀的文件");
			check(storage.getDataUrl(pngId, null).startsWith("data:image/png;base64,"), "PNG MIME 应由内容推断");

			String mp4Id = storage.uploadIfNotExists(MP4, ".mp4", null);
			check(mp4Id.matches("[0-9a-f]{72}"), "MP4 键应无后缀，实际：" + mp4Id);
			check(storage.getDataUrl(mp4Id, null).startsWith("data:video/mp4;base64,"), "MP4 MIME 应由内容推断");

			String caption = pngId + ".caption";
			storage.upload(caption, "缓存描述".getBytes(StandardCharsets.UTF_8), null);
			check(storage.exists(caption, null), ".caption 不应被去后缀");
			check(new File(dir, caption).exists(), ".caption 应写成独立文件");
			check(storage.getDataUrl(caption, null).startsWith("data:"), ".caption 应可读取");
			check("缓存描述".equals(MultiModalMcp.recognizeImage(storage, pngId, null)),
					"图片识别应优先命中 .caption 缓存");

			// 旧数据：把无后缀文件改名成带后缀，仍应能按旧键读取
			File bare = new File(dir, mp4Id);
			File legacy = new File(dir, mp4Id + ".mp4");
			check(bare.renameTo(legacy), "应能模拟旧带后缀文件");
			check(storage.exists(mp4Id + ".mp4", null), "旧带后缀键应存在");
			check(storage.download(mp4Id + ".mp4", null).length == MP4.length, "旧带后缀键应能下载");
			check(storage.getDataUrl(mp4Id + ".mp4", null).startsWith("data:video/mp4;base64,"),
					"旧带后缀键的 MIME 仍应正确");
			System.out.println("LocalStorageTest: 全部用例通过");
		} finally {
			delete(dir);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private static void delete(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				delete(child);
			}
		}
		file.delete();
	}
}
