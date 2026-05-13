package com.khjxiaogu.aiwuxia.objectstorage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
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
	public boolean exists(String fn) {
		HttpURLConnection connection = null;
		try {
			URL url = new URL(getUrl(fn));
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
	public String upload(String fn, byte[] data) throws IOException {
		try {
			PutObjectInput input = new PutObjectInput().setBucket(cfg.bucket).setKey(fn).setContent(new ByteArrayInputStream(data));
			PutObjectOutput output = client.putObject(input);
			System.out.println("Put object success, the object's etag is " + output.getEtag());
			return fn;
		} catch (TosException e) {
			e.printStackTrace();
			throw new IOException(e);
		}
	}

	@Override
	public String getUrl(String fn) {
		return "https://" + cfg.bucket + "." + cfg.endpoint + "/" + fn;
	}

}
