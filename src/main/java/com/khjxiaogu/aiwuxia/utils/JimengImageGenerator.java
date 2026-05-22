package com.khjxiaogu.aiwuxia.utils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
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
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class JimengImageGenerator {

    private static final String HOST = "visual.volcengineapi.com";
    private static final String REGION = "cn-north-1";
    private static final String SERVICE = "cv";
    private static final String ALGORITHM = "HMAC-SHA256";
    private static final Gson GSON = new Gson();
	public static class Config{
		String accessKey = "your access key";
		String secretKey = "your secret key";
	}
    // 从环境变量获取AK/SK，实际使用时请替换为有效值
    Config cfg;
    public JimengImageGenerator(JsonObject config) {
		super();
		cfg=GSON.fromJson(config, Config.class);
	}

	/**
     * 同步提交任务，若成功则异步轮询结果
     * @param referenceImageUrls 参考图链接列表（最多14张）
     * @param prompt 提示词（最长800字符）
     * @param width 图片宽度（与height同时传入才生效）
     * @param height 图片高度（与width同时传入才生效）
     * @return CompletableFuture 携带生成图片的URL（异步轮询）
     * @throws RuntimeException 提交任务失败时直接抛出
     */
    public CompletableFuture<String> generateImage(List<String> referenceImageUrls,
                                                           String prompt) {
        // 同步提交任务，失败立即抛异常
    	try {
    		String taskId = submitTask(referenceImageUrls, prompt);
    		return CompletableFuture.supplyAsync(() -> pollResult(taskId));
    	}catch(Throwable t) {
    		return CompletableFuture.failedFuture(t);
    	}
        // 提交成功，异步轮询结果
        
    }

    private String submitTask(List<String> imageUrls, String prompt) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("req_key", "jimeng_seedream46_cvtob");
            if (imageUrls != null && !imageUrls.isEmpty()) {
                body.put("image_urls", imageUrls);
            }
            body.put("prompt", prompt);
            body.put("force_single", true);   // 强制只生成一张图
            // 尺寸：同时传入width和height才生效
           
            
            // 不添加水印：不传logo_info

            String requestBody = GSON.toJson(body);
            String query = "Action=CVSync2AsyncSubmitTask&Version=2022-08-31";
            String url = "https://" + HOST + "?" + query;
            String response = doHttpRequest(url, "POST", requestBody);
            JsonObject respObj = JsonParser.parseString(response).getAsJsonObject();
            int code = respObj.get("code").getAsInt();
            if (code != 10000) {
                String msg = respObj.has("message") ? respObj.get("message").getAsString() : "Unknown error";
                throw new RuntimeException("Submit task failed, code=" + code + ", msg=" + msg);
            }
            return respObj.getAsJsonObject("data").get("task_id").getAsString();
        } catch (Exception e) {
            throw new RuntimeException("Submit task error", e);
        }
    }

    private String pollResult(String taskId) {
        try {
            int maxRetries = 300;
            int retryIntervalMs = 2000;
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
                System.out.println(status);
                if ("done".equals(status)) {
                    if (data.has("image_urls") && !data.get("image_urls").isJsonNull()) {
                        return data.getAsJsonArray("image_urls").get(0).getAsString();
                    } else if (data.has("binary_data_base64") && !data.get("binary_data_base64").isJsonNull()) {
                        return "data:image/png;base64," + data.getAsJsonArray("binary_data_base64").get(0).getAsString();
                    } else {
                        throw new RuntimeException("No image in response");
                    }
                } else if ("in_queue".equals(status) || "generating".equals(status)) {
                    continue;
                } else if ("not_found".equals(status) || "expired".equals(status)) {
                    throw new RuntimeException("Task not found or expired: " + taskId);
                } else {
                    throw new RuntimeException("Unexpected status: " + status);
                }
            }
            throw new RuntimeException("Polling timeout for task: " + taskId);
        } catch (Exception e) {
            throw new RuntimeException("Polling error", e);
        }
    }

    private String fetchTaskResult(String taskId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("req_key", "jimeng_seedream46_cvtob");
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

    // -------- HTTP请求 + 火山引擎V4签名 ----------
    private String doHttpRequest(String urlString, String method, String requestBody) throws Exception {
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

    private static String getXDate() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    private static String hashHex(String data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(data.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(digest);
    }

    private static String buildCanonicalRequest(String method, URL url, String body, String contentSha256) {
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

    private static String buildStringToSign(String xDate, String canonicalRequest) throws Exception {
        return ALGORITHM + "\n" +
                xDate + "\n" +
                xDate.substring(0, 8) + "/" + REGION + "/" + SERVICE + "/request" + "\n" +
                hashHex(canonicalRequest);
    }

    private String buildAuthorization(String xDate, String stringToSign) throws Exception {
        String credentialScope = xDate.substring(0, 8) + "/" + REGION + "/" + SERVICE + "/request";
        byte[] signingKey = getSigningKey(cfg.secretKey, xDate.substring(0, 8), REGION, SERVICE);
        String signature = hmacHex(signingKey, stringToSign);
        return ALGORITHM + " Credential=" + cfg.accessKey + "/" + credentialScope +
                ", SignedHeaders=host;x-content-sha256;x-date, Signature=" + signature;
    }

    private static byte[] getSigningKey(String secretKey, String date, String region, String service) throws Exception {
        byte[] kSecret = (secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmac(kSecret, date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        return hmac(kService, "request");
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
}