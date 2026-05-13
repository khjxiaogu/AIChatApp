/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.llm.providers.volcano;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.ModelProvider;
import com.khjxiaogu.aiwuxia.llm.message.ImageContent;
import com.khjxiaogu.aiwuxia.llm.message.MessageContent;
import com.khjxiaogu.aiwuxia.llm.message.PlainText;
import com.khjxiaogu.aiwuxia.llm.message.VideoContent;
import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.Message;
import com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ModelCategory;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ResponseFormat;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public class VolcanoModelProvider implements ModelProvider{
	SimpleLogger logger=new SimpleLogger("Deepseek");
	@Override
	public boolean supports(AIRequest request) {
		return request.multimodal.canSupport(true, true, false, true);
	}

	@Override
	public AIOutput execute(ExecutorService exec,AIRequest request) throws IOException {
		if(request.stream) {
			return sendAIStreamedRequest(exec,request);
		}
		return sendAIRequest(request).toOutput();
	}


	public String getModelType(AIRequest request) {
		if(request.hasModelProperty("lite"))
			return "doubao-seed-2-0-lite-260215";
		else if(request.hasModelProperty("pro"))
			return "doubao-seed-2-0-pro-260215";
		else if(request.hasModelProperty("code"))
			return "doubao-seed-2-0-code-260215";
		else if(request.hasModelProperty("mini"))
			return "doubao-seed-2-0-mini-260215";
		switch(request.taskType) {
		case CODE:return "doubao-seed-2-0-code-260215";
		case LOGIC:
		case ANALYSIS:return "doubao-seed-2-0-pro-260215";
		case STORY:
			return "doubao-seed-2-0-lite-260215";
		}
		return "doubao-seed-2-0-lite-260215";
	}
	public VolcanoUsage getModelUsage(AIRequest request) {
		if(request.hasModelProperty("mini"))
			return new VolcanoUsageMini();
		else if(request.hasModelProperty("lite"))
			return new VolcanoUsageLite();
		else if(request.hasModelProperty("pro")||request.hasModelProperty("code"))
			return new VolcanoUsagePro();
		switch(request.taskType) {
		case CODE:
		case LOGIC:
		case ANALYSIS:return new VolcanoUsagePro();
		case STORY:
		default:break;
		}
		return new VolcanoUsageLite();
	}
	public String getEffort(AIRequest request) {
		ReasoningStrength strengh=request.strength;
		
		switch(strengh) {
		case NONE:return "minimal";   // 不需要思维链
		case WEAK:return "low";   // 简单推理
		case MEDIUM:return "medium"; // 中等推理
		case STRONG:return "high";  // 深度思考
		}
		return "low";
	}
	private JsonObject createRequest(AIRequest request) {
		JsonArray messages=new JsonArray();
		for(HistoryItem hi:request.history) {
			JsonObject msg=new JsonObject();
			msg.addProperty("role", hi.getRole().getRoleName());
			JsonArray contents=new JsonArray();
			for(MessageContent msgc:hi.getContextContent()) {
				JsonObject msgjo=new JsonObject();
				if(msgc instanceof VideoContent) {
					msgjo.addProperty("type", "video_url");
					msgjo.addProperty("video_url", ((VideoContent)msgc).getVideoUrl());
				}else if(msgc instanceof ImageContent) {
					
					msgjo.addProperty("type", "image_url");
					msgjo.addProperty("image_url", ((ImageContent)msgc).getImageUrl());
				}else{

					msgjo.addProperty("type", "text");
					msgjo.addProperty("text", msgc.toText());
				}
				contents.add(msgjo);
			}
			msg.add("content",contents);
			messages.add(msg);
		}
		if(request.prefix!=null&&request.category!=ModelCategory.REASONING) {
			JsonObject msg=new JsonObject();
			msg.addProperty("role", "assistant");
			msg.addProperty("content", request.prefix);
			messages.add(msg);
		}
		
		JsonObject jo=new JsonObject();
		jo.addProperty("model", getModelType(request));
		jo.addProperty("service_tier", "default");
		if(request.category==ModelCategory.REASONING) {
			jo.add("thinking", JsonBuilder.object("type","enabled"));
			jo.addProperty("reasoning_effort", getEffort(request));
		}
		jo.add("messages", messages);
		if(request.format==ResponseFormat.JSON)
			jo.add("response_format", JsonBuilder.object("type", "json_object"));
		jo.addProperty("temperature", request.temperature);
		jo.addProperty("max_tokens", request.maxToken);
		return jo;
	}
	Gson gs=new Gson();
	public RespScheme sendAIRequest(AIRequest request) throws IOException {
		VolcanoUsage realUsage=getModelUsage(request);
		JsonObject jo=createRequest(request);
		String tosend = gs.toJson(jo);
		JsonObject retjs = HttpRequestBuilder.create("ark.cn-beijing.volces.com").url("/api/v3/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer "+System.getProperty("volcmodeltoken"))
	
				.post(true).send(tosend).readJson();
		//System.out.println(ppgs.toJson(retjs));
		VolcanoRespScheme resp = gs.fromJson(retjs, VolcanoRespScheme.class);
		if(resp.choices.get(0).message.reasoning_content!=null) {
			logger.info("=================Reasoner===============");
			logger.info(resp.choices.get(0).message.reasoning_content);
		}
		logger.info("=================Usage===============");
		logger.info(resp.usage);
		realUsage.set(resp.usage);
		realUsage.zoomEquivantly();
		resp.usage=realUsage;
		
		return resp;
	}
	public AIOutput sendAIStreamedRequest(ExecutorService exec,AIRequest request) throws IOException {
		VolcanoUsage usage=getModelUsage(request);
		JsonObject jo=createRequest(request);
		jo.addProperty("stream", true);
		jo.add("stream_options", JsonBuilder.object().add("include_usage", true).add("chunk_include_usage", true).end());
		String tosend = gs.toJson(jo);
		StreamedAIOutput readable=new StreamedAIOutput();
		exec.submit(()->{
		try {
			HttpRequestBuilder.create("ark.cn-beijing.volces.com").url("/api/v3/chat/completions")
					.header("Content-Type", "application/json")
					.header("Authorization", "Bearer "+System.getProperty("volcmodeltoken"))

					.post(true).send(tosend).readSSE((ev,s)->{
						if(readable.isInterrupted()) {
							logger.info("=================Usage===============\n");
							logger.info(usage);
							logger.info("interrupted generation");
							usage.zoomEquivantly();
							readable.setUsage(usage);
							readable.endContent();
							return false;
						}
						if(s==null||"[DONE]".equals(s)) {
							System.out.println();
							logger.info("=================Usage===============\n");
							logger.info(usage);
							logger.info("finish generation");
							usage.zoomEquivantly();
							readable.setUsage(usage);
							readable.endContent();
							return false;
						}
						//if(readable.isEnded())
						//	throw new ClientTruncatedException();
						VolcanoRespScheme scheme=gs.fromJson(s, VolcanoRespScheme.class);
						if(scheme.choices.size()>0) {
							Message delta=scheme.choices.get(0).delta;
							if(delta!=null) {
								if(delta.reasoning_content!=null&&!delta.reasoning_content.isEmpty()) {
									readable.putReasoner(new PlainText(delta.reasoning_content));
								}
								if(delta.content!=null&&!delta.content.isEmpty()) {
									readable.getReasoner().setEnded();
									readable.putContent(delta.content);
								}
							}
							if("content_filter".equals(scheme.choices.get(0).finish_reason)) {
								readable.getReasoner().setEnded();
								readable.putContent("【该回答已被审核截断】");
							}
						}
						if(scheme.usage!=null)
							usage.set(scheme.usage);
						return true;
					});
		} catch (IOException e) {
			e.printStackTrace();
			readable.exception(e);
		}
		});
	
		return readable;
	}

	@Override
	public boolean supportsHinted(AIRequest request) {
		return request.getModelName().startsWith("volces");
	}
}
