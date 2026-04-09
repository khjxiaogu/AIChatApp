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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
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
import com.khjxiaogu.aiwuxia.utils.SSEListener;
import com.khjxiaogu.webserver.loging.SimpleLogger;

public class GrokModelProvider implements ModelProvider{
	SimpleLogger logger=new SimpleLogger("Grok");

	//Proxy px=new Proxy(Proxy.Type.SOCKS,new InetSocketAddress("127.0.0.1",1008));
	Proxy px=new Proxy(Proxy.Type.HTTP,new InetSocketAddress(System.getProperty("grokproxyhost"),Integer.parseInt(System.getProperty("grokproxyport"))));
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
		byte[] tosend = gs.toJson(request.request).getBytes(StandardCharsets.UTF_8);
		logger.info("trigger generation");
		JsonObject retjs = HttpRequestBuilder.create("api.x.ai").url("/v1/chat/completions")
				.proxy(px)
				.header("Content-Type", "application/json")
				.header("Content-Length", tosend.length+"")
				.header("Authorization", "Bearer "+System.getProperty("groktoken"))
				.defUA()
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
		byte[] tosend = gs.toJson(request.request).getBytes(StandardCharsets.UTF_8);
		StreamedAIOutput readable=new StreamedAIOutput();
		GrokUsage usage = new GrokUsage();
		AtomicBoolean isRefused=new AtomicBoolean(false);
		
		exec.submit(()->{
			try {
				java.net.URL url = new java.net.URL("https://api.x.ai/v1/chat/completions");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection(px);
				try {
                
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + System.getProperty("groktoken"));
                connection.setRequestProperty("Accept", "text/event-stream");
                connection.setDoOutput(true);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(120000);

                // 发送请求体
                try (OutputStream os = connection.getOutputStream()) {

                    os.write(tosend, 0, tosend.length);
                }
                // 检查响应状态
                if (connection.getResponseCode() != 200) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(connection.getErrorStream(), "utf-8"))) {
                        StringBuilder errorResponse = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) {
                            errorResponse.append(line);
                        }
                        throw (new IOException("API 请求失败: " + connection.getResponseCode() +
                                " - " + errorResponse));
                    }
                }
                SSEListener listener=(ev,s)->{
					if(readable.isInterrupted()) {
						logger.info("interrupted generation");
						readable.setUsage(usage);
						readable.endContent();
						return false;
					}
					if(s==null||"[DONE]".equals(s)) {
						System.out.println();
						logger.info("=================Usage===============\n");
						logger.info(usage);
						logger.info("finish generation");
						readable.setUsage(usage);
						readable.endContent();
						return false;
					}
					//if(readable.isEnded())
					//	throw new ClientTruncatedException();
					GrokRespScheme scheme=gs.fromJson(s, GrokRespScheme.class);
					if(!scheme.choices.isEmpty()) {
						Message delta=scheme.choices.get(0).delta;
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
					}
					if(scheme.usage!=null)
						usage.set(scheme.usage);
					return true;
				};
    			try(InputStream is=connection.getInputStream();Scanner scan=new Scanner(is,StandardCharsets.UTF_8)){
    				
    				try{
    					while(scan.hasNextLine()) {
    						String resp=scan.nextLine();
    						//System.out.println(resp);
    						if(resp.isEmpty()||resp.startsWith(":"))
    							continue;
    						int idx=resp.indexOf(":");
    						String dataEvent=resp.substring(0,idx);
    						if(resp.charAt(idx+1)==' ') idx++;
    						String dataElem=resp.substring(idx+1);
    						if(!listener.accept(dataEvent, dataElem)) {
    							break;
    						}
    					}
    				}catch(Throwable err) {
    					err.printStackTrace();
    				}
    			}
			}finally {
    	                if (connection != null) {
    	                    connection.disconnect();
    	                }
    	            }
/*
				HttpRequestBuilder.create("api.x.ai").url("/v1/chat/completions")
						.proxy(px)
						.header("Content-Type", "application/json")
						.header("Content-Length", tosend.length+"")
						.header("Authorization", "Bearer "+System.getProperty("groktoken"))
						.defUA()
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
						});*/
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
