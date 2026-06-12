package com.khjxiaogu.aiwuxia.utils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public abstract class JimengApiBase<T> {
	protected final String HOST;
	protected final String REGION;
	protected final String SERVICE;
	protected final String reqKey;
	private static final String ALGORITHM = "HMAC-SHA256";
	protected static final Gson GSON = new Gson();

	public static class Config{
		String accessKey = "your access key";
		String secretKey = "your secret key";
	}

	protected Config cfg;

	public static byte[] fetch(String urlStr) {
		HttpURLConnection connection = null;
		try {
			URL url = new URL(urlStr);
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(5000); // 连接超时5秒
			connection.setReadTimeout(10000); // 读取超时10秒
			connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; MyFetcher/1.0)");
			connection.setInstanceFollowRedirects(true);
	
			int responseCode = connection.getResponseCode();
			if (responseCode != HttpURLConnection.HTTP_OK) {
				 throw new RuntimeException("Unexpected status: " + responseCode);
			}
	
			return FileUtil.readAll(connection.getInputStream());
	
		} catch (Exception e) {
			// 发生任何异常（网络错误、协议异常等）都返回无法识别
			throw new RuntimeException("Unexpcected Exception",e);
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}
	protected abstract T handleResult(JsonObject data);
	public T pollResult(String taskId) throws Exception {
	   
        int maxRetries = 600;
        int retryIntervalMs = 10000;
        for (int i = 0; i < maxRetries; i++) {
            Thread.sleep(retryIntervalMs);
            String result = fetchTaskResult(taskId);
            JsonObject respObj = JsonParser.parseString(result).getAsJsonObject();
            int code = respObj.get("code").getAsInt();
            if (code != 10000) {
                String msg = respObj.has("message") ? respObj.get("message").getAsString() : "Unknown error";
                throw new RuntimeException("Query task failed, code=" + code + ", msg=" + msg);
            }
            JsonObject data = respObj.getAsJsonObject("data");
            String status = data.get("status").getAsString();
            System.out.println(data);
            if ("done".equals(status)) {
            	return handleResult(data);
                
            } else if ("in_queue".equals(status) || "generating".equals(status)) {
                continue;
            } else if ("not_found".equals(status) || "expired".equals(status)) {
                throw new RuntimeException("Task not found or expired: " + taskId);
            } else {
                throw new RuntimeException("Unexpected status: " + status);
            }
        }
        throw new RuntimeException("Polling timeout for task: " + taskId);
	    
	}

	private static String getXDate() {
	    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
	    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
	    return sdf.format(new Date());
	}

	private String fetchTaskResult(String taskId) throws Exception {
	    Map<String, Object> body = new LinkedHashMap<>();
	    body.put("req_key", reqKey);
	    body.put("task_id", taskId);
	    // 请求返回图片URL，不添加水印
	    Map<String, Object> reqJson = new HashMap<>();
	    reqJson.put("return_url", true);
	    body.put("req_json", GSON.toJson(reqJson));
	
	    String requestBody = GSON.toJson(body);
	    String query = "Action=CVSync2AsyncGetResult&Version=2022-08-31";
	    String url = "https://" + HOST + "/?" + query;
	    return doHttpRequest(url, "POST", requestBody);
	}

	protected String doHttpRequest(String urlString, String method, String requestBody) throws Exception {
	    URL url = new URL(urlString);
	    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
	    conn.setRequestMethod(method);
	    conn.setDoOutput(true);
	    conn.setConnectTimeout(15000);
	    conn.setReadTimeout(30000);
	    conn.setRequestProperty("Content-Type", "application/json");
	
	    String xDate = getXDate();
	    String contentSha256 = hashHex(requestBody);
	    String canonicalRequest = buildCanonicalRequest(method, url, requestBody, contentSha256);
	    String stringToSign = buildStringToSign(xDate, canonicalRequest);
	    String authorization = buildAuthorization(xDate, stringToSign);
	
	    conn.setRequestProperty("X-Date", xDate);
	    conn.setRequestProperty("Content-Type", "application/json");
	    conn.setRequestProperty("X-Content-Sha256", contentSha256);
	    conn.setRequestProperty("Authorization", authorization);
	
	    try (OutputStream os = conn.getOutputStream()) {
	        os.write(requestBody.getBytes(StandardCharsets.UTF_8));
	    }
	
	    int responseCode = conn.getResponseCode();
	    InputStream inputStream = (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream();
	    try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
	        StringBuilder sb = new StringBuilder();
	        String line;
	        while ((line = reader.readLine()) != null) {
	            sb.append(line);
	        }
	        if (responseCode >= 300) {
	            throw new RuntimeException("HTTP error " + responseCode + ": " + sb.toString());
	        }
	        return sb.toString();
	    }
	}

	private static String hashHex(String data) throws Exception {
	    MessageDigest md = MessageDigest.getInstance("SHA-256");
	    byte[] digest = md.digest(data.getBytes(StandardCharsets.UTF_8));
	    return bytesToHex(digest);
	}

	private String buildCanonicalRequest(String method, URL url, String body, String contentSha256) {
	    String path = url.getPath();
	    if (path.isEmpty()) path = "/";
	    String query = url.getQuery() != null ? url.getQuery() : "";
	    String hostHeader = HOST.toLowerCase();
	    String signedHeaders = "host;x-content-sha256;x-date";
	    String canonicalHeaders = "host:" + hostHeader + "\n" +
	            "x-content-sha256:" + contentSha256 + "\n" +
	            "x-date:" + getXDate() + "\n";
	    return method + "\n" + path + "\n" + query + "\n" +
	            canonicalHeaders + "\n" +
	            signedHeaders + "\n" +
	            contentSha256;
	}

	private String buildStringToSign(String xDate, String canonicalRequest) throws Exception {
	    return ALGORITHM + "\n" +
	            xDate + "\n" +
	            xDate.substring(0, 8) + "/" + REGION + "/" + SERVICE + "/request" + "\n" +
	            hashHex(canonicalRequest);
	}

	private static byte[] getSigningKey(String secretKey, String date, String region, String service) throws Exception {
	    byte[] kSecret = (secretKey).getBytes(StandardCharsets.UTF_8);
	    byte[] kDate = hmac(kSecret, date);
	    byte[] kRegion = hmac(kDate, region);
	    byte[] kService = hmac(kRegion, service);
	    return hmac(kService, "request");
	}

	private String buildAuthorization(String xDate, String stringToSign) throws Exception {
	    String credentialScope = xDate.substring(0, 8) + "/" + REGION + "/" + SERVICE + "/request";
	    byte[] signingKey = getSigningKey(cfg.secretKey, xDate.substring(0, 8), REGION, SERVICE);
	    String signature = hmacHex(signingKey, stringToSign);
	    return ALGORITHM + " Credential=" + cfg.accessKey + "/" + credentialScope +
	            ", SignedHeaders=host;x-content-sha256;x-date, Signature=" + signature;
	}

	private static byte[] hmac(byte[] key, String msg) throws Exception {
	    Mac mac = Mac.getInstance("HmacSHA256");
	    mac.init(new SecretKeySpec(key, "HmacSHA256"));
	    return mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
	}

	private static String hmacHex(byte[] key, String msg) throws Exception {
	    return bytesToHex(hmac(key, msg));
	}

	private static String bytesToHex(byte[] bytes) {
	    StringBuilder sb = new StringBuilder();
	    for (byte b : bytes) {
	        sb.append(String.format("%02x", b));
	    }
	    return sb.toString();
	}


	JimengApiBase(String hOST, String rEGION, String sERVICE,String reqKey, Config cfg) {
		super();
		HOST = hOST;
		REGION = rEGION;
		SERVICE = sERVICE;
		this.reqKey=reqKey;
		this.cfg = cfg;
	}

}