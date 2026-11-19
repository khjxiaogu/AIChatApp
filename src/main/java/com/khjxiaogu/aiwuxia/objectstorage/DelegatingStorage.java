package com.khjxiaogu.aiwuxia.objectstorage;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;

public class DelegatingStorage implements ObjectStorageProvider {
	private final LocalStorage local;
	private final ObjectStorageProvider remote;

	public DelegatingStorage(File localPath, ObjectStorageProvider remote) {
		this.local = new LocalStorage(localPath);
		this.remote = remote;
	}
	public DelegatingStorage(LocalStorage localPath, ObjectStorageProvider remote) {
		this.local = localPath;
		this.remote = remote;
	}
	@Override
	public boolean exists(String fn,Consumer<UsageIntf<?>> addUsage) {
		return local.exists(fn,addUsage);
	}

	@Override
	public String upload(String fn, byte[] data,Consumer<UsageIntf<?>> addUsage) throws IOException {
		String result = local.upload(fn, data,addUsage);
		return result;
	}

	/**
	 * 对象键以本地存储为准（媒体文件不带后缀）；远端按需在 {@link #getHttpUrl} 时惰性同步。
	 */
	@Override
	public String uploadIfNotExists(byte[] data, String extension, Consumer<UsageIntf<?>> addUsage)
			throws IOException {
		return local.uploadIfNotExists(data, extension, addUsage);
	}

	@Override
	public String getDataUrl(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException {
		return local.getDataUrl(fn,addUsage);
	}

	@Override
	public String getHttpUrl(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException {
		// 远端不支持公网地址时直接返回 null，让调用方退回 data URL
		if (remote.getHttpUrl(fn,addUsage) == null) {
			return null;
		}
		if (!remote.exists(fn,addUsage)) {
			byte[] data = local.download(fn,addUsage);
			remote.upload(fn, data,addUsage);
		}
		return remote.getHttpUrl(fn,addUsage);
	}

	@Override
	public byte[] download(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException {
		if (local.exists(fn,addUsage)) {
			return local.download(fn,addUsage);
		}
		return remote.download(fn,addUsage);
	}
}
