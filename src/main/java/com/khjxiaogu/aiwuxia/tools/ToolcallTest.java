package com.khjxiaogu.aiwuxia.tools;

import java.io.IOException;

import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

public class ToolcallTest {

	@SuppressWarnings("unused")
	public static void main(String[] args) throws IOException {
		LLMConnector.initDefault();
		Builder ar=AIRequest.builder("admin").enableDeepThink()
			.taskType(TaskType.STORY).strength(ReasoningStrength.MEDIUM)
			.streamed().temperature(1f).maxTokens(8192);
		ar.addHistoryItem(Role.SYSTEM,"你是一个绘画人工智能，系统内置参考图列表：姚枫茜-礼服，姚枫茜-校服，姚枫茜-常服，唐海音-汉服，唐海音-校服。");
		ar.addHistoryItem(Role.USER,"用户发言：参考图片id:03565558973dbed6ddb702cd59ebc3120eba3f92ed0b49fe0a4e90bcd99d353e001700f1的姿势，绘制一张姚枫茜海音在海边穿泳装的图片");
		

		AIOutput ao=LLMConnector.call(ar.build());
		
		MessageContents reasoner=FileUtil.printAndCollectContent(ao.getReasoner());
		String content=FileUtil.printAndCollectContent(ao.getContent());
		/*ar.addHistoryItem(new DirectHistoryItem(Role.ASSISTANT,content,reasoner));
		ar.addHistoryItem(Role.USER,"拍张照片？");
		ao=LLMConnector.call(ar.build());
		reasoner=FileUtil.printAndCollectContent(ao.getReasoner());
		content=FileUtil.printAndCollectContent(ao.getContent());
		ar.addHistoryItem(new DirectHistoryItem(Role.ASSISTANT,content,reasoner));
		ar.addHistoryItem(Role.USER,"篮球运动有哪些注意事项？");
		ao=LLMConnector.call(ar.build());
		reasoner=FileUtil.printAndCollectContent(ao.getReasoner());
		content=FileUtil.printAndCollectContent(ao.getContent());*/
		
	}

}
