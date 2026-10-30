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
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;
import com.khjxiaogu.aiwuxia.state.session.AISession;

/**
 * 角色互发消息工具集。
 * <p>
 * 不再通过 {@code AgentMessager} 回调，而是把消息登记为目标会话工具管理器上的异步任务，
 * 由目标会话自己的完成监听器处理。
 * </p>
 * <p>
 * <b>按群聊隔离</b>：在线角色表以群号为作用域，{@code online_character} 只列出当前群的角色，
 * {@code ping_character} 也只能解析当前群的角色。同一个角色名可以同时存在于不同群聊，
 * 互不影响；不同群聊之间既看不到对方，也不能互相呼叫。
 * </p>
 */
public class AgentPingMcp implements ToolProvider {

	/**
	 * 在线角色：作用域 → (角色名 → 会话)。
	 * <p>
	 * 群聊会话的作用域是群号，因此同一群里的多个机器人（不同 botId）仍可互相呼叫；
	 * 非群聊会话退化为按会话对象隔离，避免回退成全局可见。
	 * </p>
	 */
	private static final Map<Object, Map<String, AISession>> AGENTS = new LinkedHashMap<>();

	private final String name;
	private final AISession session;
	private final Object scope;

	/**
	 * 注册当前角色并创建工具集。
	 *
	 * @param name    当前角色名
	 * @param session 当前会话
	 */
	public AgentPingMcp(String name, AISession session) {
		this.name = name;
		this.session = session;
		this.scope = scopeOf(session);
		if (name != null && session != null) {
			synchronized (AGENTS) {
				registry().put(name, session);
			}
		}
	}

	/**
	 * 会话所属的作用域：群聊会话按群号（不区分 botId，同群多机器人可互相呼叫），
	 * 其它会话按对象本身隔离。
	 */
	private static Object scopeOf(AISession session) {
		if (session instanceof AIGroupSession) {
			return Long.valueOf(((AIGroupSession) session).groupId);
		}
		return session;
	}

	/** 当前实例所属作用域的在线角色表；调用方必须持有 {@link #AGENTS} 锁。 */
	private Map<String, AISession> registry() {
		Map<String, AISession> agents = AGENTS.get(scope);
		if (agents == null) {
			agents = new LinkedHashMap<>();
			AGENTS.put(scope, agents);
		}
		return agents;
	}

	/** 注销一个角色（会话关闭时调用）；只在角色所属群聊内生效。 */
	public static void unregister(String name, AISession session) {
		if (name == null || session == null) {
			return;
		}
		Object scope = scopeOf(session);
		synchronized (AGENTS) {
			Map<String, AISession> agents = AGENTS.get(scope);
			if (agents == null) {
				return;
			}
			if (agents.get(name) == session) {
				agents.remove(name);
			}
			// 该群已没有在线角色时顺手移除空表，避免静态表随群数增长
			if (agents.isEmpty()) {
				AGENTS.remove(scope);
			}
		}
	}

	/** 在当前群聊内查找角色；不会跨群查找。 */
	private AISession find(String name) {
		if (name == null) {
			return null;
		}
		synchronized (AGENTS) {
			Map<String, AISession> agents = AGENTS.get(scope);
			return agents == null ? null : agents.get(name);
		}
	}

	@Override
	public Collection<ToolData> getTools() {
		List<ToolData> tools = new ArrayList<>(2);
		tools.add(new ToolData.Builder("online_character", "获取现在在本群在线的角色。")
				.tool((data) -> {
					StringBuilder ret = new StringBuilder();
					synchronized (AGENTS) {
						Map<String, AISession> agents = AGENTS.get(scope);
						if (agents != null) {
							for (String s : agents.keySet()) {
								ret.append(s).append(",");
							}
						}
					}
					if (ret.length() > 1) {
						ret.deleteCharAt(ret.length() - 1);
					}
					return ret.toString();
				}).build());
		tools.add(new ToolData.Builder("ping_character", "呼叫本群的其他角色。")
				.putParam("character", "角色全名，要与online_character里的一致。", "角色")
				.putParam("message", "给对应角色发送的信息，包含呼叫原因等内容。", "消息")
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
