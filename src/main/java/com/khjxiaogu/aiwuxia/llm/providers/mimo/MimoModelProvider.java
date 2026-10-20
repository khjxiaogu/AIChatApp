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
package com.khjxiaogu.aiwuxia.llm.providers.mimo;

import java.io.IOException;
import java.util.Map.Entry;
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
import com.khjxiaogu.aiwuxia.llm.HistoryRequestBuilder;
import com.khjxiaogu.aiwuxia.llm.ModelProvider;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice;
import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallContext;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallHistory;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public class MimoModelProvider implements ModelProvider{
	SimpleLogger logger=new SimpleLogger("Mimo");
	@Override
	public boolean supports(AIRequest request) {
		return request.multimodal.canSupport(true, false, true, true);
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
		JsonObject jo=createRequest(request);
		jo.addProperty("stream", true);
		String tosend = gs.toJson(jo);
		JsonObject retjs = HttpRequestBuilder.create("api.xiaomimimo.com").url("/v1/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer "+System.getProperty("mimotoken"))
	
				.post(true).send(tosend).readJson();
		//System.out.println(ppgs.toJson(retjs));
		RespScheme resp = gs.fromJson(retjs, MimoRespScheme.class);
		logger.info("=================Usage===============");
		logger.info(resp.getUsage());
		return resp;
	}
	public static JsonObject createSchema(ToolData schema) {
		JsonObject outer=new JsonObject();
		outer.addProperty("type", "function");
		JsonObject main=new JsonObject();
		main.addProperty("name", schema.name);
		main.addProperty("description", schema.description);
		main.addProperty("strict", true);
		JsonObject parameters=new JsonObject();
		parameters.addProperty("type", "object");
		parameters.addProperty("additionalProperties", false);
		JsonObject properties=new JsonObject();
		JsonArray required=new JsonArray();
		for(Entry<String, String> ent:schema.params.entrySet()){
			JsonObject prop=new JsonObject();
			prop.addProperty("type","string");
			prop.addProperty("description", ent.getValue());
			properties.add(ent.getKey(), prop);
			required.add(ent.getKey());
		}
		parameters.add("properties", properties);
		parameters.add("required", required);
		main.add("parameters", parameters);
		outer.add("function", main);
		return outer;
	}
	private static JsonObject createRequest(AIRequest request) {

			
		JsonObject jo=new JsonObject();
		
		jo.add("messages", HistoryRequestBuilder.createRequest(request));
		if(request.hasModelProperty("pro"))
			jo.addProperty("model", "mimo-v2.6-pro");
		else
			jo.addProperty("model", "mimo-v2.6-flash");
		if(!request.getTools().isEmpty()) {
			JsonArray ja=new JsonArray();
			for(ToolData val:request.getTools().values()) {
				ja.add(createSchema(val));
			}
			jo.add("tools", ja);
		}
		
		jo.add("stream_options", JsonBuilder.object().add("include_usage", true).end());
		if(request.format==ResponseFormat.JSON)
			jo.add("response_format", JsonBuilder.object("type", "json_object"));
		if(request.category==ModelCategory.REASONING) {
			jo.add("thinking", JsonBuilder.object("type","enabled"));
			/*if(request.strength==ReasoningStrength.STRONG)
				jo.addProperty("reasoning_effort", "max");
			else
				jo.addProperty("reasoning_effort", "high");*/
		}else {
			jo.add("thinking", JsonBuilder.object("type","disabled"));
		}
		jo.addProperty("temperature", request.temperature);
		jo.addProperty("max_tokens", request.maxToken);
		return jo;
	}
	private static MimoUsage createUsage(AIRequest request) {
		if(request.hasModelProperty("pro"))
			return new MimoProUsage();
		return new MimoUsage();
		
	}
	public AIOutput sendAIStreamedRequest(ExecutorService exec,AIRequest request) throws IOException {
		JsonObject jo=createRequest(request);
		JsonArray ja=jo.get("messages").getAsJsonArray();
		jo.addProperty("stream", true);
		StreamedAIOutput readable=new StreamedAIOutput();
		MimoUsage usage=createUsage(request);
		boolean usesTool=!request.getTools().isEmpty();
		// 工具调用的收集、次数限制、权限、许可与执行统一交给 ToolCallManager
		ToolCallContext toolContext=ToolCallContext.create(request.getToolCallManager(), readable, ToolCallHistory.of(ja));
		exec.submit(()->{
			try {
				AtomicBoolean shouldContinueRequest =new AtomicBoolean(true);
				while(shouldContinueRequest.get()) {
					shouldContinueRequest.set(false);

					//System.out.println(ja);
					MimoUsage crnusage=createUsage(request);
					MutableMessageContents reasoner=new MutableMessageContents();
						HttpRequestBuilder.create("api.xiaomimimo.com").url("/v1/chat/completions")
								.header("Content-Type", "application/json")
								.header("Authorization", "Bearer "+System.getProperty("mimotoken"))
			
								.post(true).send(gs.toJson(jo)).readSSE((ev,s)->{
									if(s==null||"[DONE]".equals(s)) {
										return false;
									}
									//if(readable.isEnded())
									//	throw new ClientTruncatedException();
									MimoRespScheme scheme=gs.fromJson(s, MimoRespScheme.class);
									if(!scheme.choices.isEmpty()) {
										Choice choice=scheme.choices.get(0);
										if(choice.delta.reasoning_content!=null&&!choice.delta.reasoning_content.isEmpty()) {
											readable.putReasoner(new PlainText(choice.delta.reasoning_content));
											reasoner.append(choice.delta.reasoning_content);
										}
										if(choice.delta.content!=null&&!choice.delta.content.isEmpty()) {
											if(!usesTool) {
												readable.getReasoner().setEnded();
											}
											readable.putContent(choice.delta.content);
											reasoner.append(choice.delta.content);
										}
										toolContext.collect(choice.delta.tool_calls);
										if("tool_calls".equals(choice.finish_reason)) {
											ToolCallContext.ProcessResult result=toolContext.process(reasoner.toText());
											shouldContinueRequest.set(result!=null&&result.shouldContinue());
											if(result!=null&&result.isInterrupted())
												logger.info("interrupted generation");
										}
									}
									if(scheme.usage!=null)
										crnusage.set(scheme.usage);
									return true;
								});
						usage.add(crnusage);
				}
			} catch (Exception e) {
				e.printStackTrace();
				if(e instanceof IOException)
					readable.exception((IOException)e);
			}
			System.out.println();
			logger.info("=================Usage===============\n");
			logger.info(usage);
			logger.info("finish generation");
			readable.addUsage(usage);
			readable.endContent();
		});
	
		return readable;
	}

	@Override
	public boolean supportsHinted(AIRequest request) {
		return request.isModelNamed("mimo");
	}
}
