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
	public String getUrl(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException;
	public String getPublicUrl(String fn,Consumer<UsageIntf<?>> addUsage) throws IOException;
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
		MessageDigest md;
		try {
			md = getSha256Digest();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
			throw new IllegalArgumentException(e);
		}
		String name=bytesToHex(md.digest(data))+bytesToHex(ByteBuffer.allocate(4).putInt(data.length).array());
		if(exists(name,addUsage))
			return name;
		return upload(name,data,addUsage);
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
		return FileUtil.readAll(FileUtil.fetch(getUrl(fn,addUsage)));
		
	}
	public static MessageDigest getSha256Digest() throws NoSuchAlgorithmException {
		return MessageDigest.getInstance("SHA-256");
	}
}
