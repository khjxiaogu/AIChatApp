package com.khjxiaogu.aiwuxia.mcp;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.MultimodalType;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.DirectHistoryItem;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ModelRouteException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.message.ImageContent;
import com.khjxiaogu.aiwuxia.llm.message.MessageContents;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.state.Role;
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

	public static String recognizeImage(ObjectStorageProvider tos,String id,Consumer<UsageIntf<?>> addUsage) {
		if (tos.exists(id)) {
			Builder builder = AIRequest.builder("imageRecognize").taskType(TaskType.STORY)
					.multimodal(MultimodalType.IMAGE_ONLY);
			builder.addHistoryItem(Role.SYSTEM,
					"请观察图片，详细具体客观描述其中的内容，文字，人物，细节特征，位置等信息，并原样提供图片中所有文本原文内容。最后要输出图片分辨率。");
			builder.addHistoryItem(
					new DirectHistoryItem(Role.USER, new MessageContents(new ImageContent(tos.getUrl(id)))));
			try {
				AIOutput output=LLMConnector.call(builder.temperature(1.3f).maxTokens(3000).build());
				output.addUsageListener(addUsage);
				return FileUtil.printAndCollectContent(
					output.getContent());
			} catch (ModelRouteException | IOException e) {
				e.printStackTrace();
				return "服务暂不可用";
			}
		} else {
			return "图片不存在或被清理";
		}
	}
}
