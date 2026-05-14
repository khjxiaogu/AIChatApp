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
package com.khjxiaogu.aiwuxia.apps;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.DirectHistoryItem;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.Tool;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.message.ImageContent;
import com.khjxiaogu.aiwuxia.llm.message.MessageContents;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.objectstorage.TOStorage;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.TokenSimulatedCounter;

public class AIGroupApplication extends AIApplication {
	String name;
	String charaName;
	String system;
	List<ToolData> tools=new ArrayList<>();
	@Override
	public void provideInitial(AISession state) {
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String constructSystem(ApplicationState state) {
		return "";
	}

	@Override
	public String getBrief(AISession state) {
		return name;
	}
	public ApplicationState sendAndProcessResultStreamed(AISession state, AIRequest req) throws IOException {
		AIOutput resp = LLMConnector.call(req);
		resp.addUsageListener(state::addUsage);
		return precessResponse(resp, state);
		
	}
	public ApplicationState precessResponse(AIOutput resp, AISession state) throws IOException {
		boolean isWaiting = true;
		
		ApplicationState oldstate = new ApplicationState(state.getState());
		BufferedReader reader=new BufferedReader(resp.getContent());
		String last;
		StringBuilder sendContent=new StringBuilder();
		handleReasonerContent(resp,state);
		while (true) {
			last=reader.readLine();
			if(last==null) {
				break;
			}
			if (isWaiting && last.isEmpty()) {
				continue;
			}
			if (isWaiting) {
				isWaiting = false;
			}
			sendContent.append(last).append("\n");
		}
	
		state.add(Role.ASSISTANT, sendContent.toString().trim(), sendContent.toString().trim());
		
		return oldstate;
	}
	public AIRequest constructAIrequest(AISession state) throws IOException {

		Builder builder=AIRequest.builder(state).taskType(TaskType.STORY)
			.strength(ReasoningStrength.WEAK).multimodal(MultimodalType.TEXT_ONLY)
			.addTools(tools);
		builder.addHistoryItem(Role.SYSTEM, system);

		// if (status != null&&!status.isEmpty())
		// b.object().add("role", "system").add("content", "目前对话轮次："+row).end();
		HistoryHolder history = state.getHistory();
		if (history != null && !history.isEmpty()) {
			
			int len=0;
			Iterator<HistoryItem> it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				long tokenLen=hi.getTokenLength();
				if(tokenLen==0) {
					hi.setTokenLength(tokenLen=TokenSimulatedCounter.fastCountLength(hi.getContextContent()));
				}
				len+=tokenLen;
			}
			if(len>=100000) {//more than 100000 text:about 60k context,remove until 20000
				StringBuilder summery=new StringBuilder();
				List<HistoryItem> his=new ArrayList<>();
				it=history.validContextIterator();
				while(it.hasNext()) {//calculate total dialog rows
					HistoryItem hi=it.next();
					len-=hi.getTokenLength();
					
					if(hi.getRole()!=Role.SYSTEM) {
						summery.append(getRoleName(state,hi.getRole())).append("：").append(hi.getContextContent()).append("\n");
					}
					his.add(hi);
					if(len<=20000&&hi.getRole()==Role.ASSISTANT) {
						break;
					}
					
				}
				state.getExtra().put("lastSummary", makeSummaryrequest(state,summery.toString()));
				his.forEach(t->t.setValidContext(false));

				state.setDialogRows((int) (history.getContextLimit()-5));
			}
			if(state.getExtra().containsKey("lastSummary")) {
				builder.addHistoryItem(Role.SYSTEM,state.getExtra().get("lastSummary"));
			}
			it=history.validContextIterator();
			while(it.hasNext()) {
				HistoryItem hi=it.next();
				builder.addHistoryItem(hi);
			}
				
		}

		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		//头几次用思维链版本构建格式
		
		return builder.temperature(1.3f).maxTokens(1000).build();

	}
	public AIRequest constructSummaryrequest(AISession state,String summary) {
		Builder builder=AIRequest.builder(state).taskType(TaskType.STORY).strength(ReasoningStrength.STRONG);
		builder.addHistoryItem(Role.SYSTEM, this.summary);
			StringBuilder sumerize=new StringBuilder();
			if(state.getExtra().containsKey("lastSummary")) {
				sumerize.append("=== 前情提要 ===\\n");
				sumerize.append( state.getExtra().get("lastSummary"));
			}
			sumerize.append("=== 对话块 ===\n");
			sumerize.append(summary.trim());
			// if (status != null&&!status.isEmpty())
			builder.addHistoryItem(Role.USER, sumerize.toString());


		// b.object().add("role", "assistant").add("content", "你选择：").add("prefix",
		// true);
		return builder.maxTokens(8192).temperature(1.3f).build();

	}
	public String makeSummaryrequest(AISession state,String summary) throws IOException {
		
			AIOutput resp=LLMConnector.call(constructSummaryrequest(state,summary));
			resp.addUsageListener(state::addUsage);
			printReasonerContent(resp);
			//System.out.println(resp.choices.get(0).message.reasoning_content);
			return resp.getContentText();
		

	}
	@Override
	public String getRoleName(AISession state, Role role) {
		return role==Role.ASSISTANT?charaName:super.getRoleName(state, role);
	}

	@Override
	public String getMemory(AISession state) {
		return state.getExtra().get("lastSummary");
	}
	public String recognizeImage(String id) {
		if(tos.exists(id)) {
			Builder builder=AIRequest.builder("picutreTool").taskType(TaskType.STORY)
				.multimodal(MultimodalType.IMAGE_ONLY);
			builder.addHistoryItem(Role.SYSTEM, "请观察图片，详细具体客观描述其中的内容，文字，人物，细节特征，位置等信息，并原样提供图片中所有文本原文内容。注意仅忠实描述图片外观，禁止进行分析。");
			builder.addHistoryItem(new DirectHistoryItem(Role.USER,new MessageContents(new ImageContent(tos.getUrl(id)))));
			try {
				return FileUtil.printAndCollectContent(LLMConnector.call(builder.temperature(1.3f).maxTokens(3000).build()).getContent());
			} catch (ModelRouteException | IOException e) {
				e.printStackTrace();
				return "服务暂不可用";
			}
		}else {
			return "图片不存在或被清理";
		}
	}
    /**
     * 从输入流中读取文本，使用指定字符集（若未指定则用 UTF-8）
     */
    private String readText(InputStream inputStream, String charsetName) throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        if (charsetName != null && Charset.isSupported(charsetName)) {
            charset = Charset.forName(charsetName);
        }
        try (InputStreamReader reader = new InputStreamReader(inputStream, charset);
             BufferedReader bufferedReader = new BufferedReader(reader)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            int len;
            while ((len = bufferedReader.read(buffer)) != -1) {
                sb.append(buffer, 0, len);
            }
            return sb.toString();
        }
    }
    /**
     * 根据URL获取内容
     * @param urlStr 目标URL
     * @return 文本内容、图片识别结果或错误提示
     */
    public String fetch(String urlStr) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlStr);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);   // 连接超时5秒
            connection.setReadTimeout(10000);     // 读取超时10秒
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; MyFetcher/1.0)");
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return "无法识别的内容";   // 非200状态码直接视为无法识别
            }

            String contentType = connection.getContentType();
            String mimeType = extractMimeType(contentType);

            // 文本类型：text/*, application/json, application/xml, text/xml, application/javascript 等
            if (isTextType(mimeType)) {
                String charset = extractCharset(contentType);
                String text = readText(connection.getInputStream(), charset);
                return text;
            }
            // 图片类型：只处理 JPEG 和 PNG
            else if (isImageType(mimeType)) {
                byte[] imageData = FileUtil.readAll(connection.getInputStream());
                return recognizeImage(tos.uploadIfNotExists(imageData));
            }
            // 其他不支持的类型，不下载正文
            else {
                return "无法识别的内容";
            }
        } catch (Exception e) {
            // 发生任何异常（网络错误、协议异常等）都返回无法识别
            return "无法识别的内容";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 提取 MIME 类型（去除参数部分）
     * @param contentType 原始 Content-Type 头，例如 "text/html; charset=utf-8"
     * @return MIME 类型，如 "text/html"
     */
    private String extractMimeType(String contentType) {
        if (contentType == null) return "";
        int semi = contentType.indexOf(';');
        if (semi == -1) {
            return contentType.trim().toLowerCase();
        } else {
            return contentType.substring(0, semi).trim().toLowerCase();
        }
    }

    /**
     * 从 Content-Type 中提取 charset 参数
     * @param contentType 原始 Content-Type 头
     * @return 字符集名称，未找到则返回 null
     */
    private String extractCharset(String contentType) {
        if (contentType == null) return null;
        Pattern pattern = Pattern.compile("charset\\s*=\\s*([^\\s;]+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(contentType);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 判断是否为文本类型（html, json, text, xml, javascript 等）
     */
    private boolean isTextType(String mimeType) {
        if (mimeType.isEmpty()) return false;
        if (mimeType.startsWith("text/")) return true;
        return "application/json".equals(mimeType) ||
               "application/xml".equals(mimeType) ||
               "text/xml".equals(mimeType) ||
               "application/javascript".equals(mimeType) ||
               "application/x-javascript".equals(mimeType);
    }

    /**
     * 判断是否为指定的图片类型（jpeg 和 png）
     */
    private boolean isImageType(String mimeType) {
        return "image/jpeg".equals(mimeType) || "image/jpg".equals(mimeType) || "image/png".equals(mimeType);
    }
	TOStorage tos;
	String summary;
	public AIGroupApplication(File base,File path,String name,JsonObject meta) throws IOException {
		super();
		this.name=name;
		tos=new TOStorage(JsonParser.parseString(FileUtil.readString(new File(base,"tos.json"))).getAsJsonObject());
		system = 
			
			FileUtil.readString(new File(path, "role.txt")).replace("\r", "")+
			FileUtil.readString(new File(path, "charaset.txt")).replace("\r", "")+
			FileUtil.readString(new File(path, "rules.txt")).replace("\r", "");
		summary = FileUtil.readString(new File(path, "summary.txt")).replace("\r", "")+
			FileUtil.readString(new File(path, "charaset.txt")).replace("\r", "");
		this.charaName=meta.get("charaName").getAsString();
		// AI response, always valid
		handlers.add((state, ret) -> {
			state.add(Role.USER, ret, true);
			ApplicationState airet = sendAndProcessResultStreamed(state, constructAIrequest(state));
			state.getLast().setLastState(airet);
			state.addDialogRow();

			return null;
		});
		tools.add(new ToolData.Builder("image_recognition","使用多模态模型查看图片并返回图片描述。")
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身，不得包含任何其他内容")
				.tool((data)->{
					Pattern patt=Pattern.compile("\"picture_id\"\\s*:\\s*\"([0-9a-fA-F]{72})\"");
					Matcher mtch=patt.matcher(data);
					if(mtch.find()) {
						String id=mtch.group(1);
						return recognizeImage(id);
						
					}
					return "参数格式错误";
				}).build());
		tools.add(new ToolData.Builder("avatar_recognition","使用多模态模型查看用户头像并返回图片描述，24小时内对同一个人只能调用该工具一次。")
				.putParam("user_id", "用户的id，可以从消息的senderId中取得。")
				.tool((data)->{
					Pattern patt=Pattern.compile("\"user_id\"\\s*:\\s*\"([0-9]+)\"");
					Matcher mtch=patt.matcher(data);
					if(mtch.find()) {
						String id=mtch.group(1);
						
						try {
							byte[] picture = HttpRequestBuilder.create("q1.qlogo.cn")
							.defUA()
							.url("/g?b=qq&nk="+id+"&s=640")
							.get().readBytes();
							String fn=tos.uploadIfNotExists(picture);

							return recognizeImage(fn);
						} catch (IOException e) {
							e.printStackTrace();
							return "头像获取失败";
						}
						

					}
					return "参数格式错误";
				}).build());
		tools.add(new ToolData.Builder("web_view","获取链接指定的内容，只支持ftp/http/https链接")
				.putParam("url", "网页链接")
				.tool((data)->{
					try {
						JsonObject jo=JsonParser.parseString(data).getAsJsonObject();
						if(jo.has("url")) {
							String url=jo.get("url").getAsString();
							if(url.startsWith("ftp")||url.startsWith("http"))
								return fetch(url);
							
						}
					}catch(Throwable err) {
						err.printStackTrace();
					}
					return "参数格式错误";
				}).build());
		tools.add(new ToolData.Builder("moegirl_search","搜索萌娘百科")
				.putParam("keyword", "关键词")
				.tool((data)->{
					try {
						JsonObject jo=JsonParser.parseString(data).getAsJsonObject();
						if(jo.has("keyword")) {
							String keyword=jo.get("keyword").getAsString();
								return fetch("https://zh.moegirl.org.cn/index.php?search="+URLEncoder.encode(keyword,StandardCharsets.UTF_8)+"&title=Special%3A%E6%90%9C%E7%B4%A2");
							
						}
					}catch(Throwable err) {
						err.printStackTrace();
					}
					return "参数格式错误";
				}).build());
	}
}
