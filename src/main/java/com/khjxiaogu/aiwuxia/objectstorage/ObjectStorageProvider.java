package com.khjxiaogu.aiwuxia.objectstorage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public interface ObjectStorageProvider {
	public boolean exists(String fn);
	public String upload(String fn,byte[] data) throws IOException;
	public String getUrl(String fn);
	default boolean exists(byte[] data) {
		MessageDigest md;
		try {
			md = getSha256Digest();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
			throw new IllegalArgumentException(e);
		}
		String name=bytesToHex(md.digest(data))+bytesToHex(ByteBuffer.allocate(4).putInt(data.length).array());
		return exists(name);
	}
	default String uploadIfNotExists(byte[] data) throws IOException {
		MessageDigest md;
		try {
			md = getSha256Digest();
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
			throw new IllegalArgumentException(e);
		}
		String name=bytesToHex(md.digest(data))+bytesToHex(ByteBuffer.allocate(4).putInt(data.length).array());
		if(exists(name))
			return name;
		return upload(name,data);
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

	public static MessageDigest getSha256Digest() throws NoSuchAlgorithmException {
		return MessageDigest.getInstance("SHA-256");
	}
}
