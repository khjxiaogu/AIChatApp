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

	@Override
	public String getUrl(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException {
		return local.getUrl(fn,addUsage);
	}

	@Override
	public String getPublicUrl(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException {
		if (!remote.exists(fn,addUsage)) {
			byte[] data = local.download(fn,addUsage);
			remote.upload(fn, data,addUsage);
		}
		return remote.getPublicUrl(fn,addUsage);
	}

	@Override
	public byte[] download(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException {
		if (local.exists(fn,addUsage)) {
			return local.download(fn,addUsage);
		}
		return remote.download(fn,addUsage);
	}
}
