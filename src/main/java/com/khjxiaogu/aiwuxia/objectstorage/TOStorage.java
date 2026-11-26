package com.khjxiaogu.aiwuxia.objectstorage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.volcengine.tos.TOSV2;
import com.volcengine.tos.TOSV2ClientBuilder;
import com.volcengine.tos.TosException;
import com.volcengine.tos.model.object.PutObjectInput;
import com.volcengine.tos.model.object.PutObjectOutput;

public class TOStorage implements ObjectStorageProvider {
	TOSV2 client;
	public static class TOSConfig{
		String region = "region to access";
		String endpoint = "endpoint to access";
		String accessKey = "your access key";
		String secretKey = "your secret key";
		String bucket = "your bucket name";
	}
	TOSConfig cfg;
	public TOStorage(JsonObject config) {
		Gson gs=new GsonBuilder().create();
		cfg=gs.fromJson(config, TOSConfig.class);
		client = new TOSV2ClientBuilder().build(cfg.region, cfg.endpoint, cfg.accessKey, cfg.secretKey);
	}

	@Override
	public boolean exists(String fn,Consumer<UsageIntf<?>> addUsage) {
		HttpURLConnection connection = null;
		try {
			URL url = new URL(getHttpUrl(fn,addUsage));
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("HEAD");
			connection.setConnectTimeout(5000); // 连接超时 5 秒
			connection.setReadTimeout(5000); // 读取超时 5 秒
			connection.setInstanceFollowRedirects(true); // 自动跟随重定向（默认即为 true）

			int responseCode = connection.getResponseCode();
			// 2xx 或 3xx（如 200, 301, 302 等跟随后最终成功）视为存在
			return (responseCode >= 200 && responseCode < 400);
		} catch (Exception e) {
			// 发生任何异常（网络错误、协议错误、URL 格式错误等）视为文件不存在
			return false;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	@Override
	public String upload(String fn, byte[] data,Consumer<UsageIntf<?>> addUsage) throws IOException {
		try {
			PutObjectInput input = new PutObjectInput().setBucket(cfg.bucket).setKey(fn).setContent(new ByteArrayInputStream(data));
			PutObjectOutput output = client.putObject(input);
			if(addUsage!=null)
				addUsage.accept(new TOSUsage(data.length));
			System.out.println("Put object success, the object's etag is " + output.getEtag());

			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				e.printStackTrace();
				throw new IOException("interrupted");
			}
			while(!exists(fn,addUsage)) {
				try {
					Thread.sleep(300);
				} catch (InterruptedException e) {
					e.printStackTrace();
					throw new IOException("interrupted");
				}
			}
			return fn;
		} catch (TosException e) {
			e.printStackTrace();
			throw new IOException(e);
		}
	}

	@Override
	public String getHttpUrl(String fn,Consumer<UsageIntf<?>> addUsage) {
		return "https://" + cfg.bucket + "." + cfg.endpoint + "/" + fn;
	}

	/**
	 * 优先按对象键扩展名判断；没有扩展名时用 HEAD 只读取 Content-Type，不下载对象内容。
	 */
	@Override
	public String contentType(String fn, Consumer<UsageIntf<?>> addUsage) throws IOException {
		String byExtension = LocalStorage.detectMimeType(null, fn);
		if (!"application/octet-stream".equals(byExtension)) {
			return byExtension;
		}
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(getHttpUrl(fn, addUsage)).openConnection();
			connection.setRequestMethod("HEAD");
			connection.setConnectTimeout(5000);
			connection.setReadTimeout(5000);
			connection.setInstanceFollowRedirects(true);
			String type = connection.getContentType();
			if (type != null && !type.trim().isEmpty()) {
				String mime = type.trim().toLowerCase(java.util.Locale.ROOT);
				int semicolon = mime.indexOf(';');
				if (semicolon >= 0) {
					mime = mime.substring(0, semicolon).trim();
				}
				return mime;
			}
		} catch (Exception ex) {
			// 类型探测失败不影响调用方：返回通用类型，由内容识别兜底
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
		return "application/octet-stream";
	}

}
