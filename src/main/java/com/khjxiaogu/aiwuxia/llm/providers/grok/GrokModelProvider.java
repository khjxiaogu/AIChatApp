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
package com.khjxiaogu.aiwuxia.llm.providers.grok;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ModelCategory;
import com.khjxiaogu.aiwuxia.llm.ModelProvider;
import com.khjxiaogu.aiwuxia.respscheme.Choice.Message;
import com.khjxiaogu.aiwuxia.respscheme.RespScheme;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public class GrokModelProvider implements ModelProvider{
	SimpleLogger logger=new SimpleLogger("Grok");
	@Override
	public boolean supports(AIRequest request) {
		return request.multimodal.canSupport(true, true, false, false);
	}

	@Override
	public AIOutput execute(ExecutorService exec,AIRequest request) throws IOException {
		//if(request.stream) {
		//总是使用流式来加速网络
		return sendAIStreamedRequest(exec,request);
		//}
		//return sendAIRequest(request).toOutput();
	}
	Gson gs=new Gson();
	public RespScheme sendAIRequest(AIRequest request) throws IOException {
		request.request.addProperty("model", request.category==ModelCategory.REASONING?"grok-4-1-fast-reasoning":"grok-4-1-fast-non-reasoning");
		String tosend = gs.toJson(request.request);
		logger.info("trigger generation");
		JsonObject retjs = HttpRequestBuilder.create("api.x.ai").url("/v1/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer "+System.getProperty("groktoken"))
	
				.post(true).send(tosend).readJson();
		//System.out.println(ppgs.toJson(retjs));
		RespScheme resp = gs.fromJson(retjs, RespScheme.class);
		logger.info("=================Usage===============");
		logger.info(resp.getUsage());
		return resp;
	}
	public AIOutput sendAIStreamedRequest(ExecutorService exec,AIRequest request) throws IOException {

		request.request.addProperty("model", request.category==ModelCategory.REASONING?"grok-4-1-fast-reasoning":"grok-4-1-fast-non-reasoning");
		request.request.addProperty("stream", true);
		request.request.add("stream_options", JsonBuilder.object().add("include_usage", true).end());
		logger.info("trigger generation");
		String tosend = gs.toJson(request.request);
		StreamedAIOutput readable=new StreamedAIOutput();
		GrokUsage usage = new GrokUsage();
		AtomicBoolean isRefused=new AtomicBoolean(false);
		exec.submit(()->{
			try {
				HttpRequestBuilder.create("api.x.ai").url("/v1/chat/completions")
						.header("Content-Type", "application/json")
						.header("Authorization", "Bearer "+System.getProperty("groktoken"))
	
						.post(true).send(tosend).readSSE((ev,s)->{
							if(readable.isInterrupted()) {
								logger.info("interrupted generation");
								readable.setUsage(usage);
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
							GrokRespScheme scheme=gs.fromJson(s, GrokRespScheme.class);
							Message delta=scheme.choices.get(0).message;
							if(delta.reasoning_content!=null&&!delta.reasoning_content.isEmpty()) {
								readable.putReasoner(delta.reasoning_content);
							}
							if(delta.content!=null&&!delta.content.isEmpty()) {
								readable.putContent(delta.content);
							}
							if(delta.refusal!=null&&!delta.refusal.isEmpty()) {
								readable.putContent(delta.refusal);
								if(isRefused.compareAndSet(false, true))
									usage.completion_tokens+=100000;
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

	@Override
	public boolean supportsHinted(AIRequest request) {
		return "grok".equals(request.modelHint);
	}
}
