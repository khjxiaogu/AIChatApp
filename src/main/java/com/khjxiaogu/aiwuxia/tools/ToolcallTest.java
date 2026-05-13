package com.khjxiaogu.aiwuxia.tools;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.AIRequest;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.AIRequest.Builder;
import com.khjxiaogu.aiwuxia.llm.AIRequest.ReasoningStrength;
import com.khjxiaogu.aiwuxia.llm.AIRequest.TaskType;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

public class ToolcallTest {

	public static void main(String[] args) throws IOException {
		LLMConnector.initDefault();
		Builder ar=AIRequest.builder("admin").enableDeepThink()
			.taskType(TaskType.ANALYSIS).strength(ReasoningStrength.MEDIUM)
			.streamed().temperature(1f).maxTokens(8192);
		ar.addHistoryItem(Role.SYSTEM,"你是一个人工智能助手，请辅助用户完成任务，仅允许在思维链调用工具。");
		ar.addHistoryItem(Role.USER,"地点id:AEFB2031 用户发言：查询今天天气适合进行的户外活动");
		Map<String,String> param=new HashMap<>();
		param.put("location_id","地点id");
		ar.addTool(new ToolData((par)->{
			System.out.println(par);
			return "温度：29℃，湿度60%";
		}, "weather", "根据地点id查询当地温湿度数据", param));
		AIOutput ao=LLMConnector.call(ar.build());
		FileUtil.printAndCollectContent(ao.getReasoner());
		FileUtil.printAndCollectContent(ao.getContent());
		
			
		
	}

}
