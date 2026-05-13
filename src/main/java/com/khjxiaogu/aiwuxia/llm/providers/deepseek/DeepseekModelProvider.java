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
package com.khjxiaogu.aiwuxia.llm.providers.deepseek;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ModelCategory;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ResponseFormat;
import com.khjxiaogu.aiwuxia.llm.message.MessageContent;
import com.khjxiaogu.aiwuxia.llm.message.MessageContents;
import com.khjxiaogu.aiwuxia.llm.message.PlainText;
import com.khjxiaogu.aiwuxia.llm.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.llm.message.ToolContent;
import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;
import com.khjxiaogu.aiwuxia.llm.scheme.ToolCallCollector;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.ModelProvider;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public class DeepseekModelProvider implements ModelProvider{
	SimpleLogger logger=new SimpleLogger("Deepseek");
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
		JsonObject jo=createRequest(request);
		jo.addProperty("stream", true);
		String tosend = gs.toJson(jo);
		JsonObject retjs = HttpRequestBuilder.create("api.deepseek.com").url("/beta/chat/completions")
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer "+System.getProperty("deepseektoken"))
	
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
	private static JsonObject createRequest(AIRequest request) {
		JsonArray messages=new JsonArray();
		for(HistoryItem hi:request.history) {
			boolean shouldContainReasoner=false;
			if(hi.getReasoningContent()!=null&&!hi.getReasoningContent().isEmpty()) {
				for(MessageContent msgc:hi.getReasoningContent()) {
					if(msgc instanceof ToolContent) {
						shouldContainReasoner=true;
						break;
					}
				}
				boolean hasPrevious=false;
				if(shouldContainReasoner) {
					for(MessageContent msgc:hi.getReasoningContent()) {
						if(msgc instanceof ToolContent) {
							messages.add(createToolMessage((ToolContent) msgc));
						}else if(msgc instanceof ToolCallContent){
							if(hasPrevious) {
								messages.get(messages.size()-1).getAsJsonObject().add("tool_calls", gs.toJsonTree(((ToolCallContent) msgc).getToolCalls()));
							}else {
	
								messages.add(createReasonerMessage("",((ToolCallContent) msgc).getToolCalls()));
							}
						}else {
							hasPrevious=true;
							messages.add(createReasonerMessage(msgc.toText(),null));
						}
					}
				}
			}


			JsonObject msg=new JsonObject();
			msg.addProperty("role", hi.getRole().getRoleName());
			msg.addProperty("content", hi.getContextContent().toText());
			messages.add(msg);
			
		}
		
		if(request.prefix!=null&&request.category!=ModelCategory.REASONING) {
			JsonObject msg=new JsonObject();
			msg.addProperty("role", "assistant");
			msg.addProperty("content", request.prefix);
			msg.addProperty("prefix", true);
			messages.add(msg);
		}
			
		JsonObject jo=new JsonObject();
		
		jo.add("messages", messages);
		if(request.hasModelProperty("pro"))
			jo.addProperty("model", "deepseek-v4-pro");
		else
			jo.addProperty("model", "deepseek-v4-flash");
		if(!request.tools.isEmpty()) {
			JsonArray ja=new JsonArray();
			for(ToolData val:request.tools.values()) {
				ja.add(createSchema(val));
			}
			jo.add("tools", ja);
		}
		
		jo.add("stream_options", JsonBuilder.object().add("include_usage", true).end());
		if(request.format==ResponseFormat.JSON)
			jo.add("response_format", JsonBuilder.object("type", "json_object"));
		if(request.category==ModelCategory.REASONING) {
			jo.add("thinking", JsonBuilder.object("type","enabled"));
			if(request.strength==ReasoningStrength.STRONG)
				jo.addProperty("reasoning_effort", "max");
			else
				jo.addProperty("reasoning_effort", "high");
		}else {
			jo.add("thinking", JsonBuilder.object("type","disabled"));
		}
		jo.addProperty("temperature", request.temperature);
		jo.addProperty("max_tokens", request.maxToken);
		return jo;
	}
	private static DeepseekUsage createUsage(AIRequest request) {
		if(request.hasModelProperty("pro"))
			return new DeepseekProUsage();
		return new DeepseekUsage();
		
	}
	public static JsonObject createToolMessage(ToolContent tool) {
		JsonObject toolmsg=new JsonObject();
		toolmsg.addProperty("role", Role.TOOL.getRoleName());
		toolmsg.addProperty("tool_call_id", tool.getToolId());
		toolmsg.addProperty("content", tool.getResult());
		return toolmsg;
	}
	public static JsonObject createReasonerMessage(String message,List<ToolCall> toolcalls) {
		JsonObject messageContent=new JsonObject();
		messageContent.addProperty("role", Role.ASSISTANT.getRoleName());
	
		messageContent.addProperty("reasoning_content", message);
		if(toolcalls!=null)
			messageContent.add("tool_calls", gs.toJsonTree(toolcalls));
		return messageContent;
	}
	public AIOutput sendAIStreamedRequest(ExecutorService exec,AIRequest request) throws IOException {
		JsonObject jo=createRequest(request);
		JsonArray ja=jo.get("messages").getAsJsonArray();
		jo.addProperty("stream", true);
		StreamedAIOutput readable=new StreamedAIOutput();
		DeepseekUsage usage=createUsage(request);
		boolean usesTool=!request.tools.isEmpty();
		exec.submit(()->{
			try {
				AtomicBoolean shouldContinueRequest =new AtomicBoolean(true);
				while(shouldContinueRequest.get()) {
					ToolCallCollector toolCalls=new ToolCallCollector();
					shouldContinueRequest.set(false);

					//System.out.println(ja);
					DeepseekUsage crnusage=createUsage(request);
					MessageContents reasoner=new MessageContents();
						HttpRequestBuilder.create("api.deepseek.com").url("/beta/chat/completions")
								.header("Content-Type", "application/json")
								.header("Authorization", "Bearer "+System.getProperty("deepseektoken"))
			
								.post(true).send(gs.toJson(jo)).readSSE((ev,s)->{
									if(readable.isInterrupted()) {
										logger.info("interrupted generation");
										
										shouldContinueRequest.set(false);
										return false;
									}
									if(s==null||"[DONE]".equals(s)) {
										return false;
									}
									//if(readable.isEnded())
									//	throw new ClientTruncatedException();
									DeepseekRespScheme scheme=gs.fromJson(s, DeepseekRespScheme.class);
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
									if(choice.delta.tool_calls!=null) {
										for(ToolCall tc:choice.delta.tool_calls) {
											toolCalls.collect(tc);
										}
									}
									if("tool_calls".equals(choice.finish_reason)) {

										ToolCallContent toolcall=new ToolCallContent(toolCalls.build());
										if(!reasoner.isEmpty()) {
											ja.add(createReasonerMessage(reasoner.toText(),toolcall.getToolCalls()));
										}
										readable.putReasoner(toolcall);
										for(ToolCall i:toolcall.getToolCalls()) {
											ToolData data=request.tools.get(i.function.name);
											String result=data.tool.run(i.function.arguments);
											
											ToolContent tool=new ToolContent(i.id,result);
											ja.add(createToolMessage(tool));
											
											readable.putReasoner(tool);
											
										}
										shouldContinueRequest.set(true);
										
										
									}
										
									if(scheme.usage!=null)
										crnusage.set(scheme.usage);
									return true;
								});
						usage.add(crnusage);
				}
			} catch (IOException e) {
				e.printStackTrace();
				readable.exception(e);
			}
			System.out.println();
			logger.info("=================Usage===============\n");
			logger.info(usage);
			logger.info("finish generation");
			readable.setUsage(usage);
			readable.endContent();
		});
	
		return readable;
	}

	@Override
	public boolean supportsHinted(AIRequest request) {
		return request.isModelNamed("deepseek");
	}
}
