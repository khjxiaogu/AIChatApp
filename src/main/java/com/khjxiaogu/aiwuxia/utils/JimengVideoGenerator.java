package com.khjxiaogu.aiwuxia.utils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class JimengVideoGenerator extends JimengApiBase<String> {


    public JimengVideoGenerator(JsonObject config) {
		super("visual.volcengineapi.com","cn-north-1","cv","pippit_iv2v_v20_cvtob_with_vinput",GSON.fromJson(config, Config.class));
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
    		System.out.println(taskId);
    		return CompletableFuture.supplyAsync(() -> {
				try {
					return pollResult(taskId);
				} catch (Exception e) {
					e.printStackTrace();
					if(e instanceof RuntimeException)
						throw ((RuntimeException)e);
					throw new RuntimeException(e);
				}
			});
    	}catch(Throwable t) {
    		return CompletableFuture.failedFuture(t);
    	}
        // 提交成功，异步轮询结果
        
    }

    private String submitTask(List<String> imageUrls, String prompt) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("req_key", reqKey);
            if (imageUrls != null && !imageUrls.isEmpty()) {
                body.put("img_url_list", imageUrls);
            }
            body.put("prompt", prompt);
            body.put("duration", "～15s");   // 强制只生成一张图
            body.put("language", "Chinese");
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

	@Override
	protected String handleResult(JsonObject data) {
		System.out.println(data);
		if (data.has("video_url") && !data.get("video_url").isJsonNull()) {
        	return data.get("video_url").getAsString();
        }
		throw new RuntimeException("No video in response");
	}

}
