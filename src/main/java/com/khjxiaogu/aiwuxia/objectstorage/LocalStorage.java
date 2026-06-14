package com.khjxiaogu.aiwuxia.objectstorage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.function.Consumer;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

public class LocalStorage implements ObjectStorageProvider {
	private final File storageDir;

	public LocalStorage(String path) {
		storageDir = new File(path);
		if (!storageDir.exists()) {
			storageDir.mkdirs();
		}
	}	public LocalStorage(File path) {
		storageDir = path;
		if (!storageDir.exists()) {
			storageDir.mkdirs();
		}
	}

	@Override
	public boolean exists(String fn,Consumer<UsageIntf<?>> addUsage) {
		return new File(storageDir, fn).exists();
	}

	@Override
	public String upload(String fn, byte[] data,Consumer<UsageIntf<?>> addUsage) throws IOException {
		File file = new File(storageDir, fn);
		file.getParentFile().mkdirs();
		try (FileOutputStream fos = new FileOutputStream(file)) {
			fos.write(data);
		}
		return fn;
	}

	@Override
	public String getUrl(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException {
		File file = new File(storageDir, fn);
		byte[] data = FileUtil.readAll(file);
		String base64 = Base64.getEncoder().encodeToString(data);
		String mime = getMimeType(fn);
		return "data:" + mime + ";base64," + base64;
	}

	@Override
	public String getPublicUrl(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException {
		return getUrl(fn,addUsage);
	}

	@Override
	public byte[] download(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException {
		return FileUtil.readAll(new File(storageDir, fn));
	}

	private static String getMimeType(String fn) {
		if (fn.endsWith(".png")) return "image/png";
		if (fn.endsWith(".jpg") || fn.endsWith(".jpeg")) return "image/jpeg";
		if (fn.endsWith(".gif")) return "image/gif";
		if (fn.endsWith(".webp")) return "image/webp";
		if (fn.endsWith(".svg")) return "image/svg+xml";
		if (fn.endsWith(".mp3")) return "audio/mpeg";
		if (fn.endsWith(".wav")) return "audio/wav";
		if (fn.endsWith(".ogg")) return "audio/ogg";
		if (fn.endsWith(".mp4")) return "video/mp4";
		if (fn.endsWith(".json")) return "application/json";
		if (fn.endsWith(".txt")) return "text/plain";
		return "application/octet-stream";
	}
}
