package com.khjxiaogu.aiwuxia.mcp;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.utils.MCPTools;

public class AgentPingMcp {
	public static Map<String,BiConsumer<String,String>> agents=new HashMap<>();
	public static MCPTools create(String name,BiConsumer<String,String> onping) {
		agents.put(name, onping);
		MCPTools tools=new MCPTools();
		tools.register(new ToolData.Builder("online_character", "获取现在在线的角色。")
				.tool((data) -> {
					StringBuilder ret=new StringBuilder();
					for(String s:agents.keySet()) {
						ret.append(s).append(",");
					}
					if(ret.length()>1) {
						ret.deleteCharAt(ret.length()-1);
					}
					return ret.toString();
				}).build());
		tools.register(
				new ToolData.Builder("ping_character", "呼叫其他角色。")
				.putParam("character", "角色全名，要与online_character里的一致。")
				.putParam("message", "给对应角色发送的信息，包含呼叫原因等内容。")
				.tool((data) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					String chara=jo.get("character").getAsString().trim();
					String message=jo.get("message").getAsString();
					BiConsumer<String, String> call=agents.get(chara);
					if(call==null)
						return "角色不在线或不存在";
					call.accept(name,message);
					return "呼叫成功";
				}).build());

		return tools;
	}
}
