package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;
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
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.MCPTools;

public class MultiModalMcp {
	public static MCPTools create(ObjectStorageProvider tos,Consumer<UsageIntf<?>> addUsage) {
		MCPTools tools=new MCPTools();
		tools.register(new ToolData.Builder("image_recognition", "使用多模态模型查看图片并返回图片描述。该功能仅负责描述图片内容，注意需要甄别描述中的内容是否合理，是否符合人物设定，人物是否应该知道对应内容。描述中的分析仅供参考，你需要独立判断是否正确。")
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身，不得包含任何其他内容").tool((data) -> {
					Pattern patt = Pattern.compile("\"picture_id\"\\s*:\\s*\"([0-9a-fA-F]{72})\"");
					Matcher mtch = patt.matcher(data);
					if (mtch.find()) {
						String id = mtch.group(1);
						return recognizeImage(tos,id,addUsage);

					}
					return "参数格式错误";
				}).build());
		return tools;
	}
	public static MCPTools createOutput(AISession state,ObjectStorageProvider tos,Map<String,String> images,Consumer<UsageIntf<?>> addUsage) {
		
		MCPTools tools=new MCPTools();

		tools.register(new ToolData.Builder("send_image", "发送聊天图片")
				.putParam("picture_id", "72位16进制的图片id，只包含图片id本身").tool((data) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String fn=jo.get("picture_id").getAsString();
						if(tos.exists(fn,addUsage)) {
							state.appendCh(Role.ASSISTANT, new ImageContent(fn), false);
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
				.putParam("emoji_id", "表情id").tool((data) -> {
					try {
						JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
						String fn=images.get(jo.get("emoji_id").getAsString());
						if(fn==null)
							return "表情id不存在";
						if(tos.exists(fn,addUsage)) {
							state.appendCh(Role.ASSISTANT, new ImageContent(fn), false);
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
	public static String recognizeImage(ObjectStorageProvider tos,String id,Consumer<UsageIntf<?>> addUsage) {
		if (tos.exists(id,addUsage)) {

			try {
				if(tos.exists(id+".caption", addUsage)) {
					try {
						return new String(tos.download(id+".caption", addUsage),StandardCharsets.UTF_8);
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
						new DirectHistoryItem(Role.USER, new ImageContent(tos.getPublicUrl(id,addUsage))));
				AIOutput output=LLMConnector.call(builder.temperature(1.3f).maxTokens(3000).build());
				output.addUsageListener(addUsage);
				String caption= FileUtil.printAndCollectContent(
					output.getContent());
				tos.upload(id+".caption", caption.getBytes(StandardCharsets.UTF_8), addUsage);
				return caption;
			} catch (ModelRouteException | IOException e) {
				e.printStackTrace();
				return "服务暂不可用";
			}
		}
		return "图片不存在或被清理";
	}
}
