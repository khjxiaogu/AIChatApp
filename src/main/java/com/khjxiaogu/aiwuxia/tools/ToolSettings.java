package com.khjxiaogu.aiwuxia.tools;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * QQ agent 的工具配置。
 * <p>
 * 对应 {@code config/agents.json} 中每个机器人的 {@code tools} 字段。为兼容旧配置，
 * 条目仍然可以直接写工具名字符串；需要工具调用审核时，把条目写成对象并加上
 * {@code approval}：
 * </p>
 *
 * <pre>{@code
 * "tools": [
 *   "fetch",
 *   "multimodal",
 *   {"name": "seedvideo", "approval": true}
 * ]
 * }</pre>
 * <p>
 * 也支持集中声明需要审核的工具（数组、对象或单个字符串均可）：
 * </p>
 *
 * <pre>{@code
 * "toolApprovals": ["seedvideo"]
 * // 或 "toolApprovals": {"seedvideo": true}
 * }</pre>
 * <p>
 * {@code approval} 的别名还有 {@code review}、{@code needApproval}、{@code audit}，
 * 便于不同部署沿用既有习惯。条目名既可以写技能名（{@code seedvideo}，必须同时出现在
 * {@code tools} 里），也可以直接写真实工具名（{@code jimeng_video}）。
 * </p>
 */
public final class ToolSettings {

	/** 已启用的工具名（保持配置顺序）。 */
	private final Set<String> enabled = new LinkedHashSet<>();
	/** 需要管理员审核的条目名（技能名或真实工具名，展开由连接器负责）。 */
	private final Set<String> approval = new LinkedHashSet<>();

	private ToolSettings() {
	}

	/**
	 * 解析 agent 配置里的工具设置。
	 *
	 * @param config agent 配置，可为 null
	 * @return 解析结果，永不为 null
	 */
	public static ToolSettings parse(JsonObject config) {
		ToolSettings settings = new ToolSettings();
		if (config == null) {
			return settings;
		}
		JsonElement tools = config.get("tools");
		if (tools != null && tools.isJsonArray()) {
			for (JsonElement element : tools.getAsJsonArray()) {
				settings.readTool(element);
			}
		}
		JsonElement approvals = config.get("toolApprovals");
		if (approvals == null || approvals.isJsonNull()) {
			approvals = config.get("toolApproval");
		}
		settings.readApprovals(approvals);
		return settings;
	}

	/** 读取 {@code tools} 里的一个条目。 */
	private void readTool(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return;
		}
		if (element.isJsonPrimitive()) {
			String name = element.getAsString().trim();
			if (!name.isEmpty()) {
				enabled.add(name);
			}
			return;
		}
		if (!element.isJsonObject()) {
			return;
		}
		JsonObject object = element.getAsJsonObject();
		String name = firstString(object, "name", "tool", "id");
		if (name == null || name.isEmpty()) {
			return;
		}
		enabled.add(name);
		if (needsApproval(object)) {
			approval.add(name);
		}
	}

	/** 读取集中声明需要审核的工具。 */
	private void readApprovals(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return;
		}
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			for (JsonElement item : array) {
				if (item != null && item.isJsonPrimitive()) {
					String name = item.getAsString().trim();
					if (!name.isEmpty()) {
						approval.add(name);
					}
				}
			}
			return;
		}
		if (element.isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
				JsonElement value = entry.getValue();
				if (value == null || value.isJsonNull()) {
					continue;
				}
				if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
					if (value.getAsBoolean()) {
						approval.add(entry.getKey());
					}
				} else if (value.isJsonPrimitive()) {
					if ("true".equalsIgnoreCase(value.getAsString())) {
						approval.add(entry.getKey());
					}
				}
			}
			return;
		}
		if (element.isJsonPrimitive()) {
			String name = element.getAsString().trim();
			if (!name.isEmpty()) {
				approval.add(name);
			}
		}
	}

	/** 工具条目是否要求审核。 */
	private static boolean needsApproval(JsonObject object) {
		return bool(object, "approval") || bool(object, "review") || bool(object, "needApproval")
				|| bool(object, "audit");
	}

	/** 读取布尔字段。 */
	private static boolean bool(JsonObject object, String key) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return false;
		}
		try {
			return element.getAsBoolean();
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** 取第一个非空字符串字段。 */
	private static String firstString(JsonObject object, String... keys) {
		for (String key : keys) {
			JsonElement element = object.get(key);
			if (element != null && !element.isJsonNull() && element.isJsonPrimitive()) {
				String value = element.getAsString().trim();
				if (!value.isEmpty()) {
					return value;
				}
			}
		}
		return null;
	}

	/**
	 * 指定工具是否启用。
	 *
	 * @param name 工具名
	 * @return 是否启用
	 */
	public boolean isEnabled(String name) {
		return name != null && enabled.contains(name);
	}

	/**
	 * 已启用的工具名（只读，保持配置顺序）。
	 *
	 * @return 工具名集合
	 */
	public Set<String> getEnabled() {
		return Collections.unmodifiableSet(enabled);
	}

	/**
	 * 需要管理员审核的工具名（只读）。
	 *
	 * @return 工具名集合
	 */
	public Set<String> getApproval() {
		return Collections.unmodifiableSet(approval);
	}

	/** 是否需要审核的工具。 */
	public boolean needsApproval(String name) {
		return name != null && approval.contains(name);
	}
}
