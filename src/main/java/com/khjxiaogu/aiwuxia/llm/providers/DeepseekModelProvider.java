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
package com.khjxiaogu.aiwuxia.llm.providers;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ModelCategory;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.ModelProvider;
import com.khjxiaogu.aiwuxia.respscheme.Choice.Message;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.respscheme.Usage;
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
	public AIOutput execute(ExecutorService exec,AIRequest request) throws IOException {
		//if(request.stream) {
		//deepseek:总是使用流式来加速网络
		return sendAIStreamedRequest(exec,request);
		//}
		//return sendAIRequest(request).toOutput();
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
	public AIOutput sendAIStreamedRequest(ExecutorService exec,AIRequest request) throws IOException {

		request.request.addProperty("model", request.category==ModelCategory.REASONING?"deepseek-reasoner":"deepseek-chat");
		request.request.addProperty("stream", true);
		request.request.add("stream_options", JsonBuilder.object().add("include_usage", true).end());
		logger.info("trigger generation");
		String tosend = gs.toJson(request.request);
		StreamedAIOutput readable=new StreamedAIOutput();
		Usage usage=new Usage();
		Usage simusage=new Usage();
		exec.submit(()->{
			try {
				HttpRequestBuilder.create("api.deepseek.com").url("/beta/chat/completions")
						.header("Content-Type", "application/json")
						.header("Authorization", "Bearer "+System.getProperty("deepseektoken"))
	
						.post(true).send(tosend).readSSE((ev,s)->{
							if(readable.isInterrupted()) {
								logger.info("interrupted generation");
								if(usage.completion_tokens>0)
									readable.setUsage(usage);
								else
									readable.setUsage(simusage);
								readable.endContent();
								return false;
							}
							if(s==null||"[DONE]".equals(s)) {
								System.out.println();
								logger.info("=================Usage===============");
								logger.info(usage);
								logger.info("finish generation");
								readable.setUsage(usage);
								readable.endContent();
								return false;
							}
							//if(readable.isEnded())
							//	throw new ClientTruncatedException();
							RespScheme scheme=gs.fromJson(s, RespScheme.class);
							Message delta=scheme.choices.get(0).delta;
							if(delta.reasoning_content!=null&&!delta.reasoning_content.isEmpty()) {
								simusage.completion_tokens++;
								readable.putReasoner(delta.reasoning_content);
							}
							if(delta.content!=null&&!delta.content.isEmpty()) {
								simusage.completion_tokens++;
								readable.putContent(delta.content);
							}
							
							if(scheme.usage!=null)
								usage.add(scheme.usage);
							return true;
						});
			} catch (IOException e) {
				e.printStackTrace();
				readable.exception(e);
			}
		});
	
		return readable;
	}
}
