package com.khjxiaogu.aiwuxia.llm;

import java.io.IOException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ModelCategory;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.respscheme.Usage;
import com.khjxiaogu.aiwuxia.respscheme.Choice.Message;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public class DeepseekModelProvider implements ModelProvider{
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
	Gson gs=new Gson();
	public RespScheme sendAIRequest(AIRequest request) throws IOException {
		request.request.addProperty("model", request.category==ModelCategory.REASONING?"deepseek-reasoner":"deepseek-chat");
		String tosend = gs.toJson(request.request);
		logger.info("trigger generation");
		JsonObject retjs = HttpRequestBuilder.create("api.deepseek.com").url("/beta/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer "+System.getProperty("deepseektoken"))
	
				.post(true).send(tosend).readJson();
		//System.out.println(ppgs.toJson(retjs));
		RespScheme resp = gs.fromJson(retjs, RespScheme.class);
		logger.info("=================Usage===============");
		logger.info(resp.usage);
		return resp;
	}
	public AIOutput sendAIStreamedRequest(AIRequest request) throws IOException {

		request.request.addProperty("model", request.category==ModelCategory.REASONING?"deepseek-reasoner":"deepseek-chat");
		request.request.addProperty("stream", true);
		request.request.add("stream_options", JsonBuilder.object().add("include_usage", true).end());
		logger.info("trigger generation");
		String tosend = gs.toJson(request.request);
		StreamedAIOutput readable=new StreamedAIOutput();
		Usage usage=new Usage();
		HttpRequestBuilder.create("api.deepseek.com").url("/beta/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer "+System.getProperty("deepseektoken"))
	
				.post(true).send(tosend).readSSE(null, (ev,s)->{
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
						usage.add(scheme.usage);
					
				});
	
	
		return readable;
	}
}
