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
import com.khjxiaogu.aiwuxia.llm.ModelBudget;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice;
import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallContext;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallHistory;
import com.khjxiaogu.aiwuxia.state.history.message.AudioContent;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public class MimoModelProvider implements ModelProvider{
	SimpleLogger logger=new SimpleLogger("Mimo");

	/**
	 * MiMo 的多模态内容项序列化，格式以小米官方 Chat Completions 文档为准。
	 * <ul>
	 *   <li>图片：{@code {"type":"image_url","image_url":{"url":...}}}；</li>
	 *   <li>视频：{@code {"type":"video_url","video_url":{"url":...}}}。官方还接受内容项顶层的
	 *       {@code fps}（默认 2）与 {@code media_resolution}（默认 default），这里不设置；</li>
	 *   <li>音频：{@code {"type":"input_audio","input_audio":{"data":...}}}，{@code data} 同时
	 *       接受公网 URL 与 {@code data:{mime};base64,...}。</li>
	 * </ul>
	 * <p>
	 * 视频/音频是 MiMo 特有的写法，因此由本提供者自己维护，不放进公共的消息构造器。
	 * </p>
	 */
	static final HistoryRequestBuilder.MediaPartSerializer MEDIA_PARTS = (content, resolver) -> {
		if (content instanceof ImageContent) {
			JsonObject part = new JsonObject();
			part.addProperty("type", "image_url");
			JsonObject image = new JsonObject();
			image.addProperty("url", resolver.resolveUrl(((ImageContent) content).getImageUrl()));
			part.add("image_url", image);
			return part;
		}
		if (content instanceof VideoContent) {
			JsonObject part = new JsonObject();
			part.addProperty("type", "video_url");
			JsonObject video = new JsonObject();
			video.addProperty("url", resolver.resolveUrl(((VideoContent) content).getVideoUrl()));
			part.add("video_url", video);
			return part;
		}
		if (content instanceof AudioContent) {
			JsonObject part = new JsonObject();
			part.addProperty("type", "input_audio");
			JsonObject audio = new JsonObject();
			audio.addProperty("data", resolver.resolveUrl(((AudioContent) content).getAudioUrl()));
			part.add("input_audio", audio);
			return part;
		}
		return null;
	};
	@Override
	public boolean supports(AIRequest request) {
		// 视频/音频由 HistoryRequestBuilder 按官方 Chat Completions 的 video_url / input_audio 序列化；
		// 图片模态故意不声明：没有模型提示的图片请求按既定路由交给火山方舟，避免改变现有看图链路
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
		main.addProperty("strict", schema.optionalParams.isEmpty());
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
			if(!schema.isOptionalParam(ent.getKey()))
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
		
		jo.add("messages", HistoryRequestBuilder.createRequest(request, false, MEDIA_PARTS));
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
			// 本轮工具调用上下文是否以失败收尾；由 endRound 交给需要感知轮次的工具子系统
			boolean failed=false;
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
					if(readable.isInterrupted()) {
						// 中断时流可能没有走到 finish_reason=tool_calls：把已经收到的工具调用补写进历史，
						// 结果统一为“用户已停止生成。”，保证 tool_calls 与 tool 消息成对
						toolContext.flushInterrupted(reasoner.toText());
					}
					usage.add(crnusage);
				}
			} catch (Exception e) {
				failed=true;
				e.printStackTrace();
				if(e instanceof IOException)
					readable.exception((IOException)e);
			}
			System.out.println();
			logger.info("=================Usage===============\n");
			logger.info(usage);
			logger.info("finish generation");
			// 记账监听器可能抛异常，必须保证输出流被关闭，否则读取方会一直阻塞
			try {
				readable.addUsage(usage);
			} finally {
				// 本轮工具调用结束：先回调轮次监听器（文档库在这里提交草稿并解锁），再关闭输出流
				toolContext.endRound(readable.isInterrupted()
						? ToolCallContext.RoundEndReason.INTERRUPTED
						: failed ? ToolCallContext.RoundEndReason.ERROR
								: ToolCallContext.RoundEndReason.NORMAL);
				readable.endContent();
			}
		});
	
		return readable;
	}

	@Override
	public boolean supportsHinted(AIRequest request) {
		return request.isModelNamed("mimo");
	}

	@Override
	public double budget(AIRequest request) {
		// 与 createUsage 的模型选择保持一致：pro 用专业模型，其余为 flash
		if (request.hasModelProperty("pro")) {
			return ModelBudget.ratio(request, 1.5, 3);
		}
		return ModelBudget.ratio(request, 0.5, 1);
	}
}
