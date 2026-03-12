package com.khjxiaogu.aiwuxia.llm.providers;

import java.io.IOException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.ModelProvider;
import com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ModelCategory;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.respscheme.Usage;
import com.khjxiaogu.aiwuxia.respscheme.Choice.Message;
import com.khjxiaogu.aiwuxia.utils.ClientTruncatedException;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public class VolcanoModelProvider implements ModelProvider{
	SimpleLogger logger=new SimpleLogger("Deepseek");
	@Override
	public boolean supports(AIRequest request) {
		return request.multimodal==MultimodalType.TEXT_ONLY;
	}

	@Override
	public AIOutput execute(AIRequest request) throws IOException {
		if(request.stream) {
			return sendAIStreamedRequest(request);
		}
		return sendAIRequest(request).toOutput();
	}


	public String getModelType(AIRequest request) {
		switch(request.taskType) {
		case CODE:return "doubao-seed-2-0-code-260215";
		case LOGIC:
		case ANALYSIS:return "doubao-seed-2-0-pro-260215";
		case STORY:
			if(request.multimodal==MultimodalType.TEXT_ONLY)
				return "doubao-1-5-pro-32k-250115";
			else 
				return "doubao-seed-2-0-lite-260215";
		}
		return "doubao-seed-2-0-lite-260215";
	}
	public String getEffort(AIRequest request) {
		switch(request.strength) {
		case NONE:return "minimal";   // 不需要思维链
		case WEAK:return "low";   // 简单推理
		case MEDIUM:return "medium"; // 中等推理
		case STRONG:return "high";  // 深度思考
		}
		return "low";
	}
	Gson gs=new Gson();
	public RespScheme sendAIRequest(AIRequest request) throws IOException {
		request.request.addProperty("model", getModelType(request));
		request.request.addProperty("reasoning_effort", getEffort(request));
		request.request.addProperty("service_tier", "default");
		request.request.add("thinking", JsonBuilder.object().add("type", request.category==ModelCategory.REASONING?"enabled":"disabled").end());
		String tosend = gs.toJson(request.request);
		
		logger.info("trigger generation");
		JsonObject retjs = HttpRequestBuilder.create("ark.cn-beijing.volces.com").url("/api/v3/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer "+System.getProperty("volcmodeltoken"))
	
				.post(true).send(tosend).readJson();
		//System.out.println(ppgs.toJson(retjs));
		RespScheme resp = gs.fromJson(retjs, RespScheme.class);
		if(resp.choices.get(0).message.reasoning_content!=null) {
			logger.info("=================Reasoner===============");
			logger.info(resp.choices.get(0).message.reasoning_content);
		}
		logger.info("=================Usage===============");
		logger.info(resp.usage);
		return resp;
	}
	public AIOutput sendAIStreamedRequest(AIRequest request) throws IOException {
		request.request.addProperty("model", getModelType(request));
		request.request.addProperty("reasoning_effort", getEffort(request));
		request.request.addProperty("stream", true);
		request.request.addProperty("service_tier", "default");
		request.request.add("thinking", JsonBuilder.object().add("type", request.category==ModelCategory.REASONING?"enabled":"disabled").end());
		request.request.add("stream_options", JsonBuilder.object().add("include_usage", true).add("chunk_include_usage", true).end());
		logger.info("trigger generation");
		String tosend = gs.toJson(request.request);
		StreamedAIOutput readable=new StreamedAIOutput();
		Usage usage=new Usage();
		HttpRequestBuilder.create("ark.cn-beijing.volces.com").url("/api/v3/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer "+System.getProperty("volcmodeltoken"))
	
				.post(true).send(tosend).readSSE(null, (ev,s)->{
					if(readable.isInterrupted()) {
						logger.info("interrupted generation");
						readable.setUsage(usage);
						readable.endContent();
						throw new ClientTruncatedException();
					}
					if(s==null||"[DONE]".equals(s)) {
						System.out.println();
						logger.info("=================Usage===============");
						logger.info(usage);
						logger.info("finish generation");
						readable.setUsage(usage);
						readable.endContent();
						return;
					}
					//if(readable.isEnded())
					//	throw new ClientTruncatedException();
					RespScheme scheme=gs.fromJson(s, RespScheme.class);
					Message delta=scheme.choices.get(0).delta;
					if(delta.reasoning_content!=null&&!delta.reasoning_content.isEmpty()) {
						readable.putReasoner(delta.reasoning_content);
					}
					if(delta.content!=null&&!delta.content.isEmpty()) {
						
						readable.putContent(delta.content);
					}
					if(scheme.usage!=null)
						usage.set(scheme.usage);
					
				});
	
	
		return readable;
	}
}
