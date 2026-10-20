package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.DirectHistoryItem;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.MCPTools;

public class MultiModalMcp {

	public static MCPTools create(ObjectStorageProvider tos) {
		MCPTools tools=new MCPTools();
		tools.register(new ToolData.Builder("image_recognition", "使用多模态模型查看图片并返回图片描述。该功能仅负责描述图片内容，注意需要甄别描述中的内容是否合理，是否符合人物设定，人物是否应该知道对应内容。描述中的分析仅供参考，你需要独立判断是否正确。")
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身，不得包含任何其他内容")
				.presenter(ToolCallPresenter.ofBrief(call -> "查看图片"))
				.tool((data, output, manager) -> {
					Pattern patt = Pattern.compile("\"picture_id\"\\s*:\\s*\"([0-9a-fA-F]{72})\"");
					Matcher mtch = patt.matcher(data);
					if (mtch.find()) {
						String id = mtch.group(1);
						return recognizeImage(tos,id,output);

					}
					return "参数格式错误";
				}).build());
		return tools;
	}
	public static MCPTools createOutput(ObjectStorageProvider tos,Map<String,String> images) {
		
		MCPTools tools=new MCPTools();

		tools.register(new ToolData.Builder("send_image", "发送聊天图片")
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身")
				.putParam("description", "图片的简短描述，用作网页描述（alt）与下载文件名；建议 20 字以内，可为空字符串。")
				.presenter(ToolCallPresenter.ofBrief(call -> "发送图片"))
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String fn=jo.get("picture_id").getAsString();
						String description=jo.has("description")&&!jo.get("description").isJsonNull()
								?jo.get("description").getAsString().replaceAll("\\s+", " ").trim():"";
						// 描述会进入网页 alt/文件名与模型上下文，限制长度，避免异常长的文本
						if(description.length()>120) {
							description=description.substring(0,120);
						}
						if(tos.exists(fn, output == null ? null : output::addUsage)) {
							// 工具不持有会话：把图片直接附加到本次生成的输出，由会话接收后写入当前消息
							if (output != null) {
								output.attachContent(new ImageContent(fn, description.isEmpty() ? null : description));
							}
							return "发送成功";
						}
						return "图片id不存在"+(fn.length()==72?"":"，该错误是因为图片ID长度不是72导致，请检查输入")+"。";
					}catch(Throwable t) {
						t.printStackTrace();
					}
					return "参数格式错误。";
				}).build());
		
		if(!images.isEmpty())
			tools.register(new ToolData.Builder("send_emoji_image", "发送表情包图片")
				.putParam("emoji_id", "表情id")
				.presenter(ToolCallPresenter.ofArgument("发送表情包", "emoji_id"))
				.tool((data, output, manager) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String fn=images.get(jo.get("emoji_id").getAsString());
						if(fn==null)
							return "表情id不存在";
						if(tos.exists(fn, output == null ? null : output::addUsage)) {
							if (output != null) {
								output.attachContent(new ImageContent(fn));
							}
							return "发送成功";
						}
						return "图片id不存在";
					}catch(Throwable t) {
						t.printStackTrace();
					}
					return "参数格式错误，该错误是因为图片ID长度不是72导致，请检查输入。";
				}).build());
		return tools;
	}
	public static String recognizeImage(ObjectStorageProvider tos,String id,AIOutput output) {
		if (tos.exists(id, output == null ? null : output::addUsage)) {

			try {
				if(tos.exists(id+".caption", output == null ? null : output::addUsage)) {
					try {
						return new String(tos.download(id+".caption", output == null ? null : output::addUsage),StandardCharsets.UTF_8);
					} catch (IOException e) {
						e.printStackTrace();
						return "服务暂不可用";
					}
				}
				Builder builder = AIRequest.builder("imageRecognize").taskType(TaskType.STORY)
						.multimodal(MultimodalType.IMAGE_ONLY);
				builder.addHistoryItem(Role.SYSTEM,
						"请观察图片，详细具体客观描述其中的内容，文字，人物，细节特征，位置等信息，并原样提供图片中所有文本原文内容。最后要输出图片分辨率，图片中人物所在位置等信息。");

				builder.addHistoryItem(
						new DirectHistoryItem(Role.USER, new ImageContent(tos.getPublicUrl(id,output == null ? null : output::addUsage))));
				builder.modelHint("volces").temperature(1.3f).maxTokens(3000).streamed();
				String caption="";
				for(int t=0;t<4;t++) {
					AIOutput inner=LLMConnector.call(builder.build());
					if (output != null) {
						inner.addUsageListener(output::addUsage);
					}
					FileUtil.printAndCollectContent(inner.getReasoner());
					caption= FileUtil.printAndCollectContent(inner.getContent());
					if(caption.length()>=100)
						break;
				}
				tos.upload(id+".caption", caption.getBytes(StandardCharsets.UTF_8), output == null ? null : output::addUsage);
				return caption;
			} catch (ModelRouteException | IOException e) {
				e.printStackTrace();
				return "服务暂不可用";
			}
		}
		return "图片不存在或被清理";
	}
}
