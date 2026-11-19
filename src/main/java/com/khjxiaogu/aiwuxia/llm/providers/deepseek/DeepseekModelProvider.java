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
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIOutput.StreamedAIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.HistoryRequestBuilder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ModelCategory;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ResponseFormat;
import com.khjxiaogu.aiwuxia.llm.ModelProvider;
import com.khjxiaogu.aiwuxia.llm.ModelBudget;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice;
import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallContext;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallHistory;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.history.message.ServerToolUseContent;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public class DeepseekModelProvider implements ModelProvider{
	/** 出错阶段描述：流式对话请求 */
	private static final String PHASE_STREAM="流式对话请求";
	/** 出错阶段描述：非流式对话请求 */
	private static final String PHASE_CHAT="对话请求";
	/** 参数错误时打印请求体的最大长度，避免日志被完整历史淹没 */
	private static final int MAX_LOGGED_REQUEST=2000;
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
		JsonObject jo=createRequest(request, useServerSearch(request));
		jo.addProperty("stream", true);
		String tosend = gs.toJson(jo);
		JsonObject retjs;
		try {
			retjs = HttpRequestBuilder.create("api.deepseek.com").url("/beta/chat/completions")
					.header("Content-Type", "application/json")
					.header("Authorization", "Bearer "+System.getProperty("deepseektoken"))
	
					.post(true).send(tosend).readJson();
		}catch(IOException e) {
			throw logApiError(e,PHASE_CHAT,tosend);
		}
		RespScheme resp;
		try {
			resp = gs.fromJson(retjs, RespScheme.class);
		}catch(RuntimeException e) {
			// 返回内容不是预期的对话响应结构，说明接口/模型不匹配
			throw logApiError(new IOException("返回内容无法解析为对话响应："+e.getMessage(),e),PHASE_CHAT,tosend);
		}
		if(resp==null)
			throw logApiError(new IOException("返回内容为空"),PHASE_CHAT,tosend);
		logger.info("=================Usage===============");
		logger.info(resp.getUsage());
		return resp;
	}
	/**
	 * 解析并记录 DeepSeek 接口错误，同时返回可直接抛出的异常。
	 * <p>
	 * 服务端返回的错误信息（{@code error.message/type/code/param}）会被解析出来，
	 * 请求参数类错误额外打印截断后的请求体，便于定位是哪个字段不合法。
	 * </p>
	 *
	 * @param error       原始异常
	 * @param phase       出错阶段的中文描述
	 * @param requestJson 本次请求体，可为 null（不打印）
	 * @return 携带完整错误描述的异常
	 */
	private IOException logApiError(Throwable error,String phase,String requestJson) {
		DeepseekApiError apiError=DeepseekApiError.from(error, phase);
		logger.severe(apiError.getSummary());
		if(requestJson!=null&&apiError.isRequestParameterError())
			logger.severe("请求体（已截断）: "+truncate(requestJson,MAX_LOGGED_REQUEST));
		if(error!=null)
			logger.printStackTrace(error);
		return apiError.toException(error);
	}
	/**
	 * 记录流内已经解析好的错误，返回可直接交给输出流的异常。
	 *
	 * @param apiError    已解析的错误信息
	 * @param requestJson 本次请求体，可为 null（不打印）
	 * @return 携带完整错误描述的异常
	 */
	private IOException logApiError(DeepseekApiError apiError,String requestJson) {
		logger.severe(apiError.getSummary());
		if(requestJson!=null&&apiError.isRequestParameterError())
			logger.severe("请求体（已截断）: "+truncate(requestJson,MAX_LOGGED_REQUEST));
		return apiError.toException(null);
	}
	private static String truncate(String text,int max) {
		if(text==null)
			return "";
		return text.length()<=max?text:text.substring(0,max)+"……（已截断）";
	}
	/**
	 * 构造一个客户端函数工具（允许声明 {@code strict}）。
	 *
	 * @param schema 工具定义
	 * @return 工具 JSON
	 * @see #createSchema(ToolData, boolean)
	 */
	public static JsonObject createSchema(ToolData schema) {
		return createSchema(schema, true);
	}

	/**
	 * 构造一个客户端函数工具。
	 *
	 * @param schema      工具定义
	 * @param allowStrict 是否允许声明 {@code strict}。与官方联网工具
	 *                    （{@code deepseek_web_search}）一起声明时，官方不接受该字段，会返回 400
	 *                    「{@code strict} cannot be used together with {@code deepseek_web_search}」；
	 *                    此时整个字段都不下发（而不是置为 false）
	 * @return 工具 JSON
	 */
	public static JsonObject createSchema(ToolData schema, boolean allowStrict) {
		JsonObject outer=new JsonObject();
		outer.addProperty("type", "function");
		JsonObject main=new JsonObject();
		main.addProperty("name", schema.name);
		main.addProperty("description", schema.description);
		// strict 是 Beta 能力，和联网工具互斥：开启服务端联网后一律不带这个字段
		if(allowStrict)
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
	/**
	 * 判断本次请求是否改用 DeepSeek 官方的服务端联网工具。
	 * <p>
	 * 前提是会话注册了项目的 DeepSeek 搜索工具（{@code web_search}，它只在配置了
	 * {@code deepseektoken} 时才会注册）。以下情况继续用客户端的 {@code web_search} 工具：
	 * </p>
	 * <ul>
	 *   <li>选用 {@code deepseek-v4-pro}：按要求只对 {@code deepseek-flash} 启用服务端联网；</li>
	 *   <li>需要 JSON 输出（{@code response_format=json_object} 与联网工具同时声明会返回 400）；</li>
	 *   <li>带 {@code prefix} 的续写请求（用过联网工具的回复不能续写）。</li>
	 * </ul>
	 * <p>
	 * 官方在声明联网工具时会自行决定生成参数（{@code temperature}、{@code top_p}、{@code stop} 等
	 * 在请求里被忽略），联网工具本身不计次收费，也不占用工具调用轮次与次数配额。
	 * </p>
	 *
	 * @param request 请求
	 * @return 是否声明 {@code deepseek_web_search} 并剔除客户端搜索工具
	 */
	private static boolean useServerSearch(AIRequest request) {
		if(request.multimodal!=MultimodalType.TEXT_ONLY)
			return false;
		if(!request.getTools().containsKey(DeepseekServerTools.CLIENT_SEARCH_TOOL))
			return false;
		// 专业模型不使用该优化：官方文档要求联网工具配合 deepseek-flash，
		// pro 继续走客户端 web_search（含 strict 在内的一切行为保持原样）
		if(request.hasModelProperty("pro"))
			return false;
		if(request.format==ResponseFormat.JSON)
			return false;
		if(request.prefix!=null)
			return false;
		return true;
	}

	/**
	 * 构造对话请求体。
	 *
	 * @param request      请求
	 * @param serverSearch 是否改用官方服务端联网工具（见 {@link #useServerSearch(AIRequest)}）
	 */
	private static JsonObject createRequest(AIRequest request, boolean serverSearch) {

			
		JsonObject jo=new JsonObject();
		
		// 服务端联网工具的调用记录（server_tool_calls）按官方要求原样回传，read_link 的网页正文
		// （signature）靠它拼回上下文；其它提供商走默认分支，把这类记录降级成占位文本
		jo.add("messages", HistoryRequestBuilder.createRequest(request, true));
		if(request.hasModelProperty("pro"))
			jo.addProperty("model", "deepseek-v4-pro");
		else
			jo.addProperty("model", "deepseek-flash");
		if(!request.getTools().isEmpty()||serverSearch) {
			JsonArray ja=new JsonArray();
			for(ToolData val:request.getTools().values()) {
				// 改用服务端联网后不再下发客户端的 web_search，避免两个“搜索”能力互相抢调用；
				// 同时去掉 strict：官方不允许 strict 与 deepseek_web_search 同时出现（400）
				if(serverSearch&&DeepseekServerTools.CLIENT_SEARCH_TOOL.equals(val.name))
					continue;
				ja.add(createSchema(val, !serverSearch));
			}
			if(serverSearch) {
				// 只声明类型：搜索与网页抓取都在服务端完成，客户端不执行任何工具
				JsonObject webSearch=new JsonObject();
				webSearch.addProperty("type", DeepseekServerTools.WEB_SEARCH);
				ja.add(webSearch);
			}
			if(ja.size()>0)
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
	private static DeepseekV4Usage createUsage(AIRequest request) {
		if(request.hasModelProperty("pro"))
			return new DeepseekV4ProUsage();
		return new DeepseekV4Usage();
		
	}
	public AIOutput sendAIStreamedRequest(ExecutorService exec,AIRequest request) throws IOException {
		boolean serverSearch=useServerSearch(request);
		JsonObject jo=createRequest(request, serverSearch);
		JsonArray ja=jo.get("messages").getAsJsonArray();
		jo.addProperty("stream", true);
		StreamedAIOutput readable=new StreamedAIOutput();
		DeepseekV4Usage usage=createUsage(request);
		// 服务端联网工具也算“有工具”：推理流要一直开着，等最后一个 chunk 的 server_tool_calls
		boolean usesTool=!request.getTools().isEmpty()||serverSearch;
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

					// 每轮发送前按最新的 messages 重新序列化请求体：
					// 上一轮的 assistant(tool_calls) 与 tool 结果已由 ToolCallHistory 写进 jo 的 messages 数组，
					// 若沿用同一个字符串，模型就看不到工具结果，只会反复请求同一次调用（死循环）
					String requestJson=gs.toJson(jo);
					//System.out.println(ja);
					DeepseekV4Usage crnusage=createUsage(request);
					MutableMessageContents reasoner=new MutableMessageContents();
					// 本轮服务端联网工具的调用记录：完整信息只在带 finish_reason 的最后一个 chunk 下发一次，
					// 这里先用 server_tool_event 兜底累积，断流时也能留下已经发生的搜索
					AtomicReference<JsonArray> serverToolCalls=new AtomicReference<>(new JsonArray());
					// readSSE 现在会把监听器异常转成 IOException 抛出；流内错误仍统一暂存在这里，
					// 由监听器返回 false 提前结束读取，读完流后再抛出（保持原有的错误处理优先级）
					AtomicReference<DeepseekApiError> streamError=new AtomicReference<>();
						HttpRequestBuilder.create("api.deepseek.com").url("/beta/chat/completions")
								.header("Content-Type", "application/json")
								.header("Authorization", "Bearer "+System.getProperty("deepseektoken"))
			
								.post(true).send(requestJson).readSSE((ev,s)->{
									if(s==null||"[DONE]".equals(s)) {
										return false;
									}
									if(streamError.get()!=null)
										return false;
									//if(readable.isEnded())
									//	throw new ClientTruncatedException();
									// DeepSeek 可能在 HTTP 200 的流中直接下发错误对象，需要先识别出来
									if(isStreamError(s,streamError))
										return false;
									DeepseekRespScheme scheme;
									try {
										scheme=gs.fromJson(s, DeepseekRespScheme.class);
									}catch(RuntimeException e) {
										streamError.set(DeepseekApiError.fromText(s, PHASE_STREAM));
										return false;
									}
									if (scheme.choices != null && !scheme.choices.isEmpty()) {
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
										// 服务端联网工具的进度事件：兜底累积每次 call_started；
										// 完整记录（含 signature/results）以最后一个 chunk 为准
										if(choice.server_tool_event!=null) {
											recordServerToolEvent(serverToolCalls.get(), choice.server_tool_event);
										}
										if(choice.delta.server_tool_calls!=null
												&&choice.delta.server_tool_calls.size()>0) {
											serverToolCalls.set(choice.delta.server_tool_calls);
										}
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
					// 把本轮的服务端联网调用写进推理流：前端据此展示“搜过什么”，并随历史保存，
					// 下一轮请求原样回传 server_tool_calls（其它模型则降级成占位文本）。
					// 这一步不产生任何用量：官方已取消服务端搜索的按次计费，只按模型 token 计费
					JsonArray recordedServerToolCalls=serverToolCalls.get();
					if(recordedServerToolCalls.size()>0&&!readable.getReasoner().isEnded()) {
						readable.putReasoner(new ServerToolUseContent(recordedServerToolCalls));
					}
					DeepseekApiError streamFailure=streamError.get();
					if(streamFailure!=null) {
						// 流内错误：停止生成并把原因交给输出流，已经产出的内容保留
						readable.exception(logApiError(streamFailure,requestJson));
						break;
					}
					crnusage.zoomEquivantly();
					usage.add(crnusage);
				}
			} catch (Exception e) {
				// 统一转换为携带服务端错误信息的 IOException：非 IO 异常同样要让调用方拿到失败原因
				readable.exception(logApiError(e,PHASE_STREAM,gs.toJson(jo)));
			}
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
	/**
	 * 用 {@code server_tool_event} 兜底累积服务端联网调用。
	 * <p>
	 * 官方只在带 {@code finish_reason} 的最后一个 chunk 里下发完整的 {@code server_tool_calls}；
	 * 这里先记录每次 {@code call_started} 的调用参数。正常情况会被完整记录整体覆盖，
	 * 断流时则保证历史里仍留下“这一轮搜过什么”，展示与下一轮回传都不会完全丢失。
	 * </p>
	 *
	 * @param steps 按步骤累积的调用数组（{@code [{index, calls:[...]}]}）
	 * @param event 一条进度事件，可为 null
	 */
	private static void recordServerToolEvent(JsonArray steps, Choice.ServerToolEvent event) {
		if(event==null||!"call_started".equals(event.type)||event.call==null)
			return;
		int stepIndex=event.step==null?0:event.step.intValue();
		JsonObject step=null;
		for(JsonElement element:steps) {
			if(element.isJsonObject()
					&&JsonConfig.getInt(element.getAsJsonObject(), "index", -1)==stepIndex) {
				step=element.getAsJsonObject();
				break;
			}
		}
		if(step==null) {
			step=new JsonObject();
			step.addProperty("index", stepIndex);
			step.add("calls", new JsonArray());
			steps.add(step);
		}
		JsonArray calls=step.getAsJsonArray("calls");
		if(calls==null) {
			calls=new JsonArray();
			step.add("calls", calls);
		}
		calls.add(event.call.deepCopy());
	}

	/**
	 * 识别流式响应中直接下发的错误对象。
	 * <p>
	 * 这类错误发生在 HTTP 200 之后，例如 {@code {"error":{"message":"...","type":"...","code":"..."}}}，
	 * 无法通过异常捕获，只能在此提前截获；正常的数据块返回 false，交由后续解析处理。
	 * </p>
	 *
	 * @param data        一条 SSE 数据
	 * @param streamError 用于暂存流内错误的引用
	 * @return 该数据块是否为错误对象（是则应停止读取流）
	 */
	private static boolean isStreamError(String data,AtomicReference<DeepseekApiError> streamError) {
		String trimmed=data==null?"":data.trim();
		if(!trimmed.startsWith("{"))
			return false;
		try {
			JsonElement parsed=JsonParser.parseString(trimmed);
			if(parsed.isJsonObject()&&parsed.getAsJsonObject().has("error")) {
				streamError.set(DeepseekApiError.fromText(trimmed, PHASE_STREAM));
				return true;
			}
		}catch(RuntimeException e) {
			// 不是合法 JSON，后续的结构化解析会失败，由那里统一按错误处理
		}
		return false;
	}

	@Override
	public boolean supportsHinted(AIRequest request) {
		return request.isModelNamed("deepseek");
	}

	@Override
	public double budget(AIRequest request) {
		// 与 createUsage 的模型选择保持一致：pro 用专业模型，其余为 flash
		if (request.hasModelProperty("pro")) {
			return ModelBudget.ratio(request, 2.25, 6.75);
		}
		return ModelBudget.ratio(request, ModelBudget.BASE_PROMPT, ModelBudget.BASE_COMPLETION);
	}
}
