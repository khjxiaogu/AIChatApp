package com.khjxiaogu.aiwuxia.mcp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolTask;
import com.khjxiaogu.aiwuxia.state.session.AISession;

/**
 * 角色互发消息工具集。
 * <p>
 * 不再通过 {@code AgentMessager} 回调，而是把消息登记为目标会话工具管理器上的异步任务，
 * 由目标会话自己的完成监听器处理。
 * </p>
 */
public class AgentPingMcp implements ToolProvider {

	/** 在线角色：角色名 → 会话。 */
	private static final Map<String, AISession> AGENTS = new LinkedHashMap<>();

	private final String name;
	private final AISession session;

	/**
	 * 注册当前角色并创建工具集。
	 *
	 * @param name    当前角色名
	 * @param session 当前会话
	 */
	public AgentPingMcp(String name, AISession session) {
		this.name = name;
		this.session = session;
		if (name != null && session != null) {
			synchronized (AGENTS) {
				AGENTS.put(name, session);
			}
		}
	}

	/** 注销一个角色（会话关闭时调用）。 */
	public static void unregister(String name, AISession session) {
		if (name == null) {
			return;
		}
		synchronized (AGENTS) {
			if (AGENTS.get(name) == session) {
				AGENTS.remove(name);
			}
		}
	}

	private static AISession find(String name) {
		synchronized (AGENTS) {
			return name == null ? null : AGENTS.get(name);
		}
	}

	@Override
	public Collection<ToolData> getTools() {
		List<ToolData> tools = new ArrayList<>(2);
		tools.add(new ToolData.Builder("online_character", "获取现在在线的角色。")
				.tool((data) -> {
					StringBuilder ret = new StringBuilder();
					synchronized (AGENTS) {
						for (String s : AGENTS.keySet()) {
							ret.append(s).append(",");
						}
					}
					if (ret.length() > 1) {
						ret.deleteCharAt(ret.length() - 1);
					}
					return ret.toString();
				}).build());
		tools.add(new ToolData.Builder("ping_character", "呼叫其他角色。")
				.putParam("character", "角色全名，要与online_character里的一致。")
				.putParam("message", "给对应角色发送的信息，包含呼叫原因等内容。")
				.orderSensitive()
				.tool((data) -> {
					JsonObject jo = JsonParser.parseString(data).getAsJsonObject();
					String chara = jo.get("character").getAsString().trim();
					String message = jo.get("message").getAsString();
					AISession target = find(chara);
					if (target == null)
						return "角色不在线或不存在";
					ToolTask task = target.getToolCallManager().submitTask("ping_character", null, CompletableFuture
							.<ToolResult>completedFuture(ToolResult.text("<message senderName=\"" + name + "\">" + message + "</message>")));
					return task == null ? "角色会话不可用" : "呼叫成功";
				}).build());
		return tools;
	}

	/** 当前角色名。 */
	public String getName0() {
		return name;
	}

	/** 当前会话。 */
	public AISession getSession() {
		return session;
	}
}
