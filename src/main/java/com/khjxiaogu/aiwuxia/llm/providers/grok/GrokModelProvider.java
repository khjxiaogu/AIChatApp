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
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ModelCategory;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ResponseFormat;
import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.Message;
import com.khjxiaogu.aiwuxia.llm.ModelProvider;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public class GrokModelProvider implements ModelProvider {
	SimpleLogger logger = new SimpleLogger("Grok");

	// Proxy px=new Proxy(Proxy.Type.SOCKS,new InetSocketAddress("127.0.0.1",1008));
	Proxy px = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(System.getProperty("grokproxyhost"),
			Integer.parseInt(System.getProperty("grokproxyport"))));

	@Override
	public boolean supports(AIRequest request) {
		return request.multimodal.canSupport(true, false, false, false);
	}

	@Override
	public AIOutput execute(ExecutorService exec, AIRequest request) throws IOException {
		// if(request.stream) {
		// 总是使用流式来加速网络
		return sendAIStreamedRequest(exec, request);
		// }
		// return sendAIRequest(request).toOutput();
	}

	private static JsonObject createRequest(AIRequest request) {
		JsonArray messages = new JsonArray();
		for (HistoryItem hi : request.history) {
			JsonObject msg = new JsonObject();
			msg.addProperty("role", hi.getRole().getRoleName());
			msg.addProperty("content", hi.getContextContent().toText());
			messages.add(msg);
		}

		JsonObject jo = new JsonObject();
		jo.addProperty("model", request.category == ModelCategory.REASONING ? "grok-4-1-fast-reasoning"
				: "grok-4-1-fast-non-reasoning");
		/*
		 * if(request.category==ModelCategory.REASONING) {
		 * jo.add("thinking", JsonBuilder.object("type","enabled"));
		 * jo.addProperty("reasoning_effort", getEffort(request));
		 * }
		 */
		jo.add("messages", messages);
		if (request.format == ResponseFormat.JSON)
			jo.add("response_format", JsonBuilder.object("type", "json_object"));
		jo.addProperty("temperature", request.temperature);
		jo.addProperty("max_tokens", request.maxToken);
		return jo;
	}

	Gson gs = new Gson();

	public RespScheme sendAIRequest(AIRequest request) throws IOException {
		JsonObject jo = createRequest(request);
		String tosend = gs.toJson(jo);
		logger.info("trigger generation");
		JsonObject retjs = HttpRequestBuilder.create("api.x.ai").url("/v1/chat/completions").proxy(px)
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + System.getProperty("groktoken")).defUA().post(true).send(tosend)
				.readJson();
		// System.out.println(ppgs.toJson(retjs));
		RespScheme resp = gs.fromJson(retjs, RespScheme.class);
		logger.info("=================Usage===============");
		logger.info(resp.getUsage());
		return resp;
	}

	public AIOutput sendAIStreamedRequest(ExecutorService exec, AIRequest request) throws IOException {
		JsonObject jo = createRequest(request);
		jo.addProperty("stream", true);
		jo.add("stream_options", JsonBuilder.object().add("include_usage", true).end());
		logger.info("trigger generation");
		String tosend = gs.toJson(jo);
		StreamedAIOutput readable = new StreamedAIOutput();
		GrokUsage usage = new GrokUsage();
		AtomicBoolean isRefused = new AtomicBoolean(false);

		exec.submit(() -> {
			try {

				HttpRequestBuilder.create("api.x.ai").url("/v1/chat/completions").proxy(px)
						.header("Content-Type", "application/json")
						.header("Authorization", "Bearer " + System.getProperty("groktoken")).defUA().post(true)
						.send(tosend).readSSE((ev, s) -> {
							if (readable.isInterrupted()) {
								logger.info("interrupted generation");
								readable.setUsage(usage);
								readable.endContent();
								return false;
							}
							if (s == null || "[DONE]".equals(s)) {
								System.out.println();
								logger.info("=================Usage===============\n");
								logger.info(usage);
								logger.info("finish generation");
								readable.setUsage(usage);
								readable.endContent();
								return false;
							}
							// if(readable.isEnded())
							// throw new ClientTruncatedException();
							GrokRespScheme scheme = gs.fromJson(s, GrokRespScheme.class);
							if (!scheme.choices.isEmpty()) {
								Message delta = scheme.choices.get(0).delta;
								if (delta.reasoning_content != null && !delta.reasoning_content.isEmpty()) {
									readable.putReasoner(new PlainText(delta.reasoning_content));
								}
								if (delta.content != null && !delta.content.isEmpty()) {
									readable.getReasoner().setEnded();
									readable.putContent(delta.content);
								}
								if (delta.refusal != null && !delta.refusal.isEmpty()) {
									readable.getReasoner().setEnded();
									readable.putContent(delta.refusal);
									if (isRefused.compareAndSet(false, true))
										usage.completion_tokens += 100000;
								}
							}
							if (scheme.usage != null)
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
		return request.isModelNamed("grok");
	}
}
