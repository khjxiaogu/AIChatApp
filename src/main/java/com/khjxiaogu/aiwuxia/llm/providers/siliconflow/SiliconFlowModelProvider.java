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
package com.khjxiaogu.aiwuxia.llm.providers.siliconflow;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ModelCategory;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ResponseFormat;
import com.khjxiaogu.aiwuxia.llm.HistoryRequestBuilder;
import com.khjxiaogu.aiwuxia.llm.ModelProvider;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallContext;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallHistory;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public class SiliconFlowModelProvider implements ModelProvider{
	SimpleLogger logger=new SimpleLogger("SiliconFlow");
	@Override
	public boolean supports(AIRequest request) {
		return request.multimodal==MultimodalType.TEXT_ONLY;
	}
	public static class ToolCallSerilizer implements JsonSerializer<ToolCall>{

		Gson rawGs=new Gson();
		@Override
		public JsonElement serialize(ToolCall src, Type typeOfSrc, JsonSerializationContext context) {
			
			JsonElement serailized =rawGs.toJsonTree(src);
			if(serailized.isJsonObject())
				serailized.getAsJsonObject().addProperty("type", "function");
			return serailized;
		}

	}
	@Override
	public AIOutput execute(ExecutorService exec,AIRequest request) throws IOException {
		//if(request.stream) {
		//deepseek:总是使用流式来加速网络
		return sendAIStreamedRequest(exec,request);
		//}
		//return sendAIRequest(request).toOutput();
	}
	static Gson gs=new GsonBuilder().registerTypeHierarchyAdapter(ToolCall.class, new ToolCallSerilizer()).create();
	public RespScheme sendAIRequest(AIRequest request) throws IOException {
		ModelCategory category;
		if(request.hasModelProperty("reasoning"))
			category=ModelCategory.REASONING;
		else
			category=request.category;
		JsonObject jo=createRequest(request,category);
		jo.addProperty("stream", true);
		String tosend = gs.toJson(jo);
		JsonObject retjs = HttpRequestBuilder.create("api.siliconflow.cn").url("/v1/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer "+System.getProperty("silicontoken"))
	
				.post(true).send(tosend).readJson();
		//System.out.println(ppgs.toJson(retjs));
		RespScheme resp = gs.fromJson(retjs, RespScheme.class);
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
	public static SiliconUsage createUsage(AIRequest request,ModelCategory category) {
		if(request.modelHint.length>1) {
			switch(request.modelHint[1]) {
			case "deepseek3.2":return new DeepseekV32Usage();
			case "deepseek3":return (category==ModelCategory.REASONING)?new DeepseekR1Usage():new DeepseekV3Usage();
			}
		}
		return new DeepseekV32Usage();
	}
	public static String mapModelName(AIRequest request,ModelCategory category) {
		if(request.modelHint.length>1) {
			switch(request.modelHint[1]) {
			case "deepseek3.2":return "deepseek-ai/DeepSeek-V3.2";
			case "deepseek3":return (category==ModelCategory.REASONING)?"deepseek-ai/DeepSeek-R1":"deepseek-ai/DeepSeek-V3";
			}
		}
		return "deepseek-ai/DeepSeek-V3.2";
	}
	private static JsonObject createRequest(AIRequest request,ModelCategory category) {
		JsonObject jo=new JsonObject();
		// 历史消息（含工具调用与工具结果）统一由 HistoryRequestBuilder 构造
		jo.add("messages", HistoryRequestBuilder.createRequest(request));

		
		jo.addProperty("model", mapModelName(request,category));
		
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
			jo.addProperty("enable_thinking", true);
			if(request.strength==ReasoningStrength.STRONG)
				jo.addProperty("reasoning_effort", "max");
			else
				jo.addProperty("reasoning_effort", "high");
			switch(request.strength) {
			case NONE:jo.addProperty("thinking_budget", 32);break;
			case WEAK:jo.addProperty("thinking_budget", 256);break;
			case STRONG:jo.addProperty("thinking_budget", 32768);break;
			case MEDIUM:
			default:jo.addProperty("thinking_budget", 1024);break;
			}
		}else {
			jo.addProperty("enable_thinking", false);
		}
		jo.addProperty("temperature", request.temperature);
		jo.addProperty("max_tokens", request.maxToken);
		return jo;
	}
	public AIOutput sendAIStreamedRequest(ExecutorService exec,AIRequest request) throws IOException {
		ModelCategory category;
		if(request.hasModelProperty("reasoning"))
			category=ModelCategory.REASONING;
		else
			category=request.category;
		JsonObject jo=createRequest(request,category);
		JsonArray ja=jo.get("messages").getAsJsonArray();
		jo.addProperty("stream", true);
		StreamedAIOutput readable=new StreamedAIOutput();
		SiliconUsage usage=createUsage(request,category);
		boolean usesTool=!request.getTools().isEmpty();
		// 工具调用的收集、次数限制、权限、许可与执行统一交给 ToolCallManager
		ToolCallContext toolContext=ToolCallContext.create(request.getToolCallManager(), readable, ToolCallHistory.of(ja));
		exec.submit(()->{
			try {
				AtomicBoolean shouldContinueRequest =new AtomicBoolean(true);
				while(shouldContinueRequest.get()) {
					// 用户已停止生成：不再发起新的请求。本轮流式输出照常读完，
					// 已经生成的内容与工具调用都保留进历史
					if(readable.isInterrupted()) {
						logger.info("interrupted generation");
						break;
					}
					shouldContinueRequest.set(false);

					//System.out.println(ja);
					SiliconUsage crnusage=createUsage(request,category);
					MutableMessageContents reasoner=new MutableMessageContents();
						HttpRequestBuilder.create("api.siliconflow.cn").url("/v1/chat/completions")
								.header("Content-Type", "application/json")
								.header("Authorization", "Bearer "+System.getProperty("silicontoken"))
			
								.post(true).send(gs.toJson(jo)).readSSE((ev,s)->{
									if(s==null||"[DONE]".equals(s)) {
										return false;
									}
									//if(readable.isEnded())
									//	throw new ClientTruncatedException();
									SiliconRespScheme scheme=gs.fromJson(s, SiliconRespScheme.class);
									if(scheme.choices!=null&&scheme.choices.size()>0) {
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
					if(readable.isInterrupted()) {
						// 中断时流可能没有走到 finish_reason=tool_calls：把已经收到的工具调用补写进历史，
						// 结果统一为“用户已停止生成。”，保证 tool_calls 与 tool 消息成对
						toolContext.flushInterrupted(reasoner.toText());
					}
					crnusage.zoomEquivantly();
					usage.add(crnusage);
				}
			} catch (Exception e) {
				e.printStackTrace();
				if(e instanceof IOException)
					readable.exception((IOException)e);
				System.out.println(ja);
			}
			System.out.println();
			logger.info("=================Usage===============\n");
			logger.info(usage);
			logger.info("finish generation");
			try {
				readable.addUsage(usage);
			}finally {
				readable.endContent();
			}
		});
	
		return readable;
	}

	@Override
	public boolean supportsHinted(AIRequest request) {
		return request.isModelNamed("silicon");
	}
}
