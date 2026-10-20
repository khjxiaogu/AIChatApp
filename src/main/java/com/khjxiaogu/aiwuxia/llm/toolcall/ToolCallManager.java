package com.khjxiaogu.aiwuxia.llm.toolcall;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;

/**
 * 工具调用管理器（可变）。
 * <p>
 * 同时承担“工具注册表”“异步任务管理”与“调用规则”
 * （轮次上限、总次数上限、单工具次数上限、共享次数组、不限次工具、权限监听器、许可、各类超限提示文本）
 * 三个职责，是 {@code AIRequest} 携带的工具调用配置载体。
 * </p>
 * <p>
 * 每次工具调用统一返回 {@link CompletableFuture}。注册时声明为异步的工具，管理器会立即创建
 * {@link ToolTask} 并向模型返回“任务已创建，任务ID：...”，任务完成后通过
 * {@link ToolTaskListener} 通知会话；未声明异步的工具由管理器等待 future 完成后返回真实结果。
 * </p>
 * <p>
 * 多个工具可以通过 {@link #bindTools(int, String...)} 绑定为共享调用次数的组。组 ID 由管理器
 * 自动递增生成，一个工具同一时间只能属于一个组。
 * </p>
 * <p>
 * 本类<b>可变</b>：工具表与规则都可以随时增删改，修改对之后所有使用该管理器的生成立即生效。
 * 可变的代价是线程安全由调用方保证：注册工具、调整规则应在生成开始前完成；
 * 生成过程中只读取规则，不要在生成线程之外并发修改。
 * </p>
 *
 * @see ToolCallContext
 */
public class ToolCallManager {

	/** 默认允许的工具调用轮次上限（与旧版 {@code AIRequest.maxToolCall} 默认值一致）。 */
	public static final int DEFAULT_MAX_ROUNDS = 10;

	/** 默认轮次超限提示文本。 */
	public static final String DEFAULT_LIMIT_MESSAGE = "已达最大工具调用轮次，请暂停工作并明确用户指示。";

	/** 默认单个工具超限提示文本。 */
	public static final String DEFAULT_TOOL_LIMIT_MESSAGE = "该工具调用次数已达上限，请暂停使用该工具或明确用户指示。";

	/** 默认共享次数组超限提示文本。 */
	public static final String DEFAULT_GROUP_LIMIT_MESSAGE = "本组工具调用次数已达上限，请暂停使用这些工具或明确用户指示。";

	/** 默认拒绝提示文本。 */
	public static final String DEFAULT_DENY_MESSAGE = ToolCallPermission.DEFAULT_DENY_REASON;

	/** 默认未许可提示文本。 */
	public static final String DEFAULT_UNAPPROVED_MESSAGE = ToolCallApprover.DEFAULT_DENY_RESULT;

	/** 默认工具不存在提示文本。 */
	public static final String DEFAULT_MISSING_TOOL_MESSAGE = "tool不存在或已禁用。";

	/** 默认工具内部错误提示文本。 */
	public static final String DEFAULT_TOOL_ERROR_MESSAGE = "tool发生内部错误。";

	/** 默认异步任务创建提示文本。 */
	public static final String DEFAULT_ASYNC_MESSAGE = "任务已创建，任务ID：%s（工具：%s）。任务完成后会自动通知你。";

	/** 已完成任务在内存中保留的最大数量。 */
	private static final int MAX_FINISHED_TASKS = 128;

	/** 工具注册表（名称 → 注册记录），随注册与注销直接变化。 */
	private final Map<String, ToolRegistration> registrations = new LinkedHashMap<>();
	/** 共享次数组：组 ID → 最大调用次数（-1 表示不限）。 */
	private final Map<Integer, Integer> groupMaxCalls = new ConcurrentHashMap<>();
	/** 组 ID 自增序列；0 保留给“未分组”。 */
	private final AtomicInteger groupSequence = new AtomicInteger();
	/** 注册时尚未存在的工具的显式次数配置。 */
	private final Map<String, Integer> pendingMaxCalls = new LinkedHashMap<>();

	/** 异步任务：任务 ID → 任务。 */
	private final Map<String, ToolTask> tasks = new ConcurrentHashMap<>();
	/** 已完成任务的 ID，用于限制内存占用。 */
	private final Deque<String> finishedTasks = new ArrayDeque<>();
	/** 任务 ID 自增序列。 */
	private final AtomicLong taskSequence = new AtomicLong();
	/** 异步任务监听器。 */
	private volatile ToolTaskListener taskListener;
	/** 是否已关闭（关闭后不再登记/通知任务）。 */
	private volatile boolean closed;
	/** 异步任务创建提示文本模板。 */
	private String asyncMessage = DEFAULT_ASYNC_MESSAGE;

	/** 工具调用轮次上限（模型每请求一批工具调用记为一轮），小于等于 0 表示不限制。 */
	private int maxRounds = DEFAULT_MAX_ROUNDS;
	/** 工具调用总次数上限，小于等于 0 表示不限制。 */
	private int maxCalls = 0;
	/** 共享次数组的提示文本。 */
	private String groupLimitMessage = DEFAULT_GROUP_LIMIT_MESSAGE;
	/** 不受任何调用次数限制的工具名称。 */
	private final Set<String> unlimitedTools = new LinkedHashSet<>();
	/** 权限监听器：按注册顺序逐个询问，结果按“拒绝 > 询问 > 允许 > 忽略”合并。 */
	private final List<ToolCallPermission> permissionListeners = new ArrayList<>();
	/** 许可方，可为 null（此时需要许可的调用按不予许可处理）。 */
	private ToolCallApprover approver = ToolCallApprover.autoApprove();
	private String limitMessage = DEFAULT_LIMIT_MESSAGE;
	private String toolLimitMessage = DEFAULT_TOOL_LIMIT_MESSAGE;
	private String missingToolMessage = DEFAULT_MISSING_TOOL_MESSAGE;
	private String toolErrorMessage = DEFAULT_TOOL_ERROR_MESSAGE;
	private String unapprovedMessage = DEFAULT_UNAPPROVED_MESSAGE;

	/** 创建一个不含任何工具、使用默认规则的管理器。 */
	public ToolCallManager() {
	}

	// ==================== 工具注册表 ====================

	/**
	 * 注册一个工具，异步模式与次数使用 {@link ToolData} 上的默认值。
	 *
	 * @param tool 工具定义
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTool(ToolData tool) {
		return registerTool(tool, tool != null && tool.async, tool == null ? -1 : tool.maxCalls);
	}

	/**
	 * 注册一个工具，指定是否异步。
	 *
	 * @param tool  工具定义
	 * @param async 是否异步
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTool(ToolData tool, boolean async) {
		return registerTool(tool, async, tool == null ? -1 : tool.maxCalls);
	}

	/**
	 * 注册一个工具，指定单工具最大调用次数。
	 *
	 * @param tool     工具定义
	 * @param maxCalls 最大调用次数，-1 表示不限
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTool(ToolData tool, int maxCalls) {
		return registerTool(tool, tool != null && tool.async, maxCalls);
	}

	/**
	 * 注册一个工具，同时指定异步模式与单工具最大调用次数。
	 *
	 * @param tool     工具定义
	 * @param async    是否异步
	 * @param maxCalls 最大调用次数，-1 表示不限
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTool(ToolData tool, boolean async, int maxCalls) {
		if (tool == null || tool.name == null) {
			return this;
		}
		Integer pending = pendingMaxCalls.remove(tool.name);
		int effectiveMax = pending == null ? maxCalls : pending;
		ToolRegistration old = registrations.get(tool.name);
		int oldGroup = old == null ? 0 : old.getGroupId();
		registrations.put(tool.name, new ToolRegistration(tool, async, effectiveMax));
		pruneGroup(oldGroup);
		return this;
	}

	/**
	 * 批量注册工具，各工具使用自身默认的异步模式与次数。
	 *
	 * @param tools 工具定义集合，可为 null
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTools(Collection<ToolData> tools) {
		return registerToolsInternal(tools, null, null);
	}

	/**
	 * 批量注册工具，并让这些工具共享同一个调用次数上限。
	 *
	 * @param tools        工具定义集合，可为 null
	 * @param sharedMaxCalls 共享最大调用次数，-1 表示不限
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTools(Collection<ToolData> tools, int sharedMaxCalls) {
		return registerToolsInternal(tools, null, sharedMaxCalls);
	}

	/**
	 * 批量注册工具，强制统一异步模式，并共享同一个调用次数上限。
	 *
	 * @param tools        工具定义集合，可为 null
	 * @param async        是否异步
	 * @param sharedMaxCalls 共享最大调用次数，-1 表示不限
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTools(Collection<ToolData> tools, boolean async, int sharedMaxCalls) {
		return registerToolsInternal(tools, Boolean.valueOf(async), sharedMaxCalls);
	}

	/**
	 * 注册一个工具集，各工具使用自身默认的异步模式与次数。
	 *
	 * @param provider 工具集，可为 null
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTools(ToolProvider provider) {
		return registerTools(provider == null ? null : provider.getTools());
	}

	/**
	 * 注册一个工具集，并让其中全部工具共享调用次数。
	 *
	 * @param provider      工具集，可为 null
	 * @param sharedMaxCalls 共享最大调用次数，-1 表示不限
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTools(ToolProvider provider, int sharedMaxCalls) {
		return registerTools(provider == null ? null : provider.getTools(), sharedMaxCalls);
	}

	/**
	 * 注册一个工具集，强制统一异步模式，并让其中全部工具共享调用次数。
	 *
	 * @param provider      工具集，可为 null
	 * @param async         是否异步
	 * @param sharedMaxCalls 共享最大调用次数，-1 表示不限
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTools(ToolProvider provider, boolean async, int sharedMaxCalls) {
		return registerTools(provider == null ? null : provider.getTools(), async, sharedMaxCalls);
	}

	private ToolCallManager registerToolsInternal(Collection<ToolData> tools, Boolean asyncOverride,
			Integer sharedMaxCalls) {
		if (tools == null || tools.isEmpty()) {
			return this;
		}
		List<String> names = new ArrayList<>(tools.size());
		for (ToolData tool : tools) {
			if (tool == null || tool.name == null) {
				continue;
			}
			boolean async = asyncOverride == null ? tool.async : asyncOverride.booleanValue();
			registerTool(tool, async, tool.maxCalls);
			names.add(tool.name);
		}
		if (sharedMaxCalls != null && !names.isEmpty()) {
			bindTools(sharedMaxCalls.intValue(), names);
		}
		return this;
	}

	/**
	 * 批量注册工具（名称 → 工具），各工具使用自身默认值。
	 *
	 * @param tools 工具表，可为 null
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager registerTools(Map<String, ToolData> tools) {
		if (tools == null) {
			return this;
		}
		return registerTools(new ArrayList<>(tools.values()));
	}

	/**
	 * 把已经注册的一组工具绑定为共享调用次数的组。
	 * <p>
	 * 每次调用都会生成一个新的组 ID；工具会从原组移出（一个工具同一时间只能属于一个组）。
	 * 不存在的工具名会被忽略。
	 * </p>
	 *
	 * @param maxCalls 该组共享的最大调用次数，-1 表示不限
	 * @param names    工具名称
	 * @return 新的组 ID；没有任何有效工具时返回 0
	 */
	public int bindTools(int maxCalls, String... names) {
		if (names == null) {
			return 0;
		}
		List<String> list = new ArrayList<>(names.length);
		Collections.addAll(list, names);
		return bindTools(maxCalls, list);
	}

	/**
	 * 把已经注册的一组工具绑定为共享调用次数的组。
	 *
	 * @param maxCalls 该组共享的最大调用次数，-1 表示不限
	 * @param names    工具名称集合
	 * @return 新的组 ID；没有任何有效工具时返回 0
	 */
	public int bindTools(int maxCalls, Collection<String> names) {
		if (names == null || names.isEmpty()) {
			return 0;
		}
		List<ToolRegistration> found = new ArrayList<>();
		Set<Integer> oldGroups = new LinkedHashSet<>();
		for (String name : names) {
			ToolRegistration registration = name == null ? null : registrations.get(name);
			if (registration == null) {
				continue;
			}
			found.add(registration);
			if (registration.getGroupId() > 0) {
				oldGroups.add(registration.getGroupId());
			}
		}
		if (found.isEmpty()) {
			return 0;
		}
		int groupId = groupSequence.incrementAndGet();
		groupMaxCalls.put(groupId, normalizeMaxCalls(maxCalls));
		for (ToolRegistration registration : found) {
			registration.setGroupId(groupId);
		}
		for (Integer oldGroup : oldGroups) {
			if (oldGroup != groupId) {
				pruneGroup(oldGroup);
			}
		}
		return groupId;
	}

	/**
	 * 注销一个工具。
	 *
	 * @param toolName 工具名称
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager unregisterTool(String toolName) {
		if (toolName != null) {
			ToolRegistration old = registrations.remove(toolName);
			if (old != null) {
				pruneGroup(old.getGroupId());
			}
		}
		return this;
	}

	/**
	 * 清空工具表。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager clearTools() {
		registrations.clear();
		groupMaxCalls.clear();
		pendingMaxCalls.clear();
		return this;
	}

	/**
	 * 可用工具表（名称 → 工具，只读视图，随注册与注销实时变化）。
	 */
	public Map<String, ToolData> getTools() {
		Map<String, ToolData> result = new LinkedHashMap<>();
		for (Map.Entry<String, ToolRegistration> entry : registrations.entrySet()) {
			result.put(entry.getKey(), entry.getValue().getData());
		}
		return Collections.unmodifiableMap(result);
	}

	/**
	 * 工具注册记录（名称 → 注册信息，只读快照）。
	 */
	public Map<String, ToolRegistration> getRegistrations() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(registrations));
	}

	/** 指定名称的工具是否可用。 */
	public boolean hasTool(String toolName) {
		return toolName != null && registrations.containsKey(toolName);
	}

	/** 指定名称的注册记录，不存在时返回 null。 */
	public ToolRegistration getRegistration(String toolName) {
		return toolName == null ? null : registrations.get(toolName);
	}

	/** 指定工具是否声明为异步。 */
	public boolean isAsync(String toolName) {
		ToolRegistration registration = getRegistration(toolName);
		return registration != null && registration.isAsync();
	}

	/** 指定工具所属共享组 ID，0 表示未分组。 */
	public int getGroupId(String toolName) {
		ToolRegistration registration = getRegistration(toolName);
		return registration == null ? 0 : registration.getGroupId();
	}

	/** 共享组的最大调用次数，-1 表示不限；组不存在时返回 -1。 */
	public int getGroupMaxCalls(int groupId) {
		Integer value = groupMaxCalls.get(groupId);
		return value == null ? -1 : value.intValue();
	}

	/** 共享组是否配置了次数上限。 */
	public boolean hasGroupLimit(int groupId) {
		return getGroupMaxCalls(groupId) >= 0;
	}

	/** 全部共享组（组 ID → 最大次数，只读快照）。 */
	public Map<Integer, Integer> getGroupMaxCalls() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(groupMaxCalls));
	}

	/** 从组表里移除已经没有任何工具使用的组。 */
	private void pruneGroup(int groupId) {
		if (groupId <= 0) {
			return;
		}
		for (ToolRegistration registration : registrations.values()) {
			if (registration.getGroupId() == groupId) {
				return;
			}
		}
		groupMaxCalls.remove(groupId);
	}

	/**
	 * 按当前注册的工具，把原始工具调用转换为用户友好显示。
	 * <p>
	 * 显示信息不随历史持久化，因此历史从存储重新加载后可用本方法重算，
	 * 保证加载后工具调用简述与具体内容同样可见。未注册该工具、转换器缺失或抛异常时，
	 * 回退为默认显示（简述为工具名称、具体内容为空）。
	 * </p>
	 *
	 * @param call 原始工具调用
	 * @return 用户友好显示，永不为 null
	 */
	public ToolCallDisplay displayFor(ToolCall call) {
		ToolRegistration data = toolOf(call);
		if (data != null && data.getData().presenter != null) {
			try {
				ToolCallDisplay display = data.getData().presenter.display(call);
				if (display != null) {
					return display;
				}
			} catch (Throwable ex) {
				ex.printStackTrace();
			}
		}
		return ToolCallPresenter.defaultDisplay(call);
	}

	/**
	 * 按当前注册的工具，把工具结果转换为用户友好显示。
	 *
	 * @param call        对应的原始工具调用，可为 null
	 * @param callDisplay 该调用的显示（用于回退简述），可为 null
	 * @param result      工具返回的原始结果文本
	 * @return 结果显示，永不为 null
	 */
	public ToolCallDisplay resultDisplayFor(ToolCall call, ToolCallDisplay callDisplay, String result) {
		ToolRegistration data = toolOf(call);
		if (data != null && data.getData().presenter != null) {
			try {
				ToolCallDisplay display = data.getData().presenter.resultDisplay(call, result);
				if (display != null && (!display.getBrief().isEmpty() || display.hasContent())) {
					return display;
				}
			} catch (Throwable ex) {
				ex.printStackTrace();
			}
		}
		String brief = callDisplay == null ? ToolCallPresenter.toolName(call) : callDisplay.getBrief();
		return ToolCallDisplay.of(brief);
	}

	private ToolRegistration toolOf(ToolCall call) {
		if (call == null || call.function == null || call.function.name == null) {
			return null;
		}
		return registrations.get(call.function.name);
	}

	// ==================== 工具附件 ====================

	/** 同步工具结果附件的接收方，通常由会话设置为“写入当前上下文”。 */
	private volatile Consumer<List<MessageContent>> attachmentHandler;

	/**
	 * 设置同步工具结果附件的接收方。
	 * <p>
	 * 工具不再通过 {@code AIOutput} 附加内容；{@link ToolCallContext} 只把附件交给本处理器，
	 * 由会话决定写入哪个上下文、是否发送到外部平台。
	 * </p>
	 *
	 * @param handler 附件处理器，可为 null
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setAttachmentHandler(Consumer<List<MessageContent>> handler) {
		this.attachmentHandler = handler;
		return this;
	}

	/** 同步工具结果附件的接收方，未设置时为 null。 */
	public Consumer<List<MessageContent>> getAttachmentHandler() {
		return attachmentHandler;
	}

	/**
	 * 把同步工具结果中的附件交给会话处理。
	 *
	 * @param attachments 附件列表，可为 null/空
	 */
	public void publishAttachments(List<MessageContent> attachments) {
		if (attachments == null || attachments.isEmpty()) {
			return;
		}
		Consumer<List<MessageContent>> handler = attachmentHandler;
		if (handler == null) {
			return;
		}
		try {
			handler.accept(attachments);
		} catch (Throwable ex) {
			ex.printStackTrace();
		}
	}

	// ==================== 异步任务 ====================

	/** 异步任务监听器，可为 null。 */
	public ToolTaskListener getTaskListener() {
		return taskListener;
	}

	/**
	 * 设置异步任务监听器。
	 *
	 * @param listener 监听器，可为 null
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setTaskListener(ToolTaskListener listener) {
		this.taskListener = listener;
		return this;
	}

	/** 异步任务创建提示文本模板。 */
	public String getAsyncMessage() {
		return asyncMessage;
	}

	/**
	 * 设置异步任务创建提示文本模板，支持 {@code %s} 依次替换任务 ID 与工具名称。
	 *
	 * @param asyncMessage 模板，null 恢复默认
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setAsyncMessage(String asyncMessage) {
		this.asyncMessage = asyncMessage == null ? DEFAULT_ASYNC_MESSAGE : asyncMessage;
		return this;
	}

	/** 生成异步任务的“任务已创建”回复。 */
	public String asyncReply(ToolTask task) {
		return String.format(asyncMessage, task.getId(), task.getToolName());
	}

	/**
	 * 登记一个异步任务。
	 * <p>
	 * 工具显式声明异步时由 {@link ToolCallContext} 调用；定时触发、角色互发消息等外部通知
	 * 也通过本方法登记，使会话可以用同一套完成回调处理。
	 * </p>
	 *
	 * @param toolName   工具/通知名称
	 * @param toolCallId 模型原始 tool_call id，外部通知可为 null
	 * @param future     结果 future
	 * @param display    工具调用的用户友好显示，可为 null
	 * @return 任务记录；管理器已关闭或 future 为 null 时返回 null
	 */
	public ToolTask submitTask(String toolName, String toolCallId, CompletableFuture<ToolResult> future,
			ToolCallDisplay display) {
		if (closed || future == null) {
			return null;
		}
		String id = "task-" + taskSequence.incrementAndGet();
		ToolTask task = new ToolTask(id, toolName, toolCallId, display, future);
		tasks.put(id, task);
		ToolTaskListener listener = taskListener;
		if (listener != null) {
			try {
				listener.onToolTaskCreated(task);
			} catch (Throwable ex) {
				ex.printStackTrace();
			}
		}
		future.whenComplete((result, error) -> {
			if (closed || task.isCancelled()) {
				return;
			}
			if (error != null) {
				task.fail(unwrap(error));
			} else {
				task.complete(result);
			}
			finishTask(task);
			ToolTaskListener current = taskListener;
			if (current == null) {
				return;
			}
			try {
				if (task.isFailed()) {
					current.onToolTaskFailed(task);
				} else {
					current.onToolTaskCompleted(task);
				}
			} catch (Throwable ex) {
				ex.printStackTrace();
			}
		});
		return task;
	}

	/**
	 * 登记一个异步任务（无显示转换器）。
	 *
	 * @param toolName   工具/通知名称
	 * @param toolCallId 模型原始 tool_call id，外部通知可为 null
	 * @param future     结果 future
	 * @return 任务记录
	 */
	public ToolTask submitTask(String toolName, String toolCallId, CompletableFuture<ToolResult> future) {
		return submitTask(toolName, toolCallId, future, null);
	}

	/**
	 * 登记一个只返回文本的异步任务。
	 *
	 * @param toolName   工具/通知名称
	 * @param toolCallId 模型原始 tool_call id，外部通知可为 null
	 * @param future     文本结果 future
	 * @return 任务记录
	 */
	public ToolTask submitTextTask(String toolName, String toolCallId, CompletableFuture<String> future) {
		if (future == null) {
			return null;
		}
		return submitTask(toolName, toolCallId, future.thenApply(ToolResult::text), null);
	}

	/** 按 ID 查找任务，不存在或已清理时返回 null。 */
	public ToolTask getTask(String taskId) {
		return taskId == null ? null : tasks.get(taskId);
	}

	/** 当前仍可查询的任务（运行中 + 最近完成），只读快照。 */
	public List<ToolTask> getTasks() {
		return Collections.unmodifiableList(new ArrayList<>(tasks.values()));
	}

	/** 当前运行中的任务，只读快照。 */
	public List<ToolTask> getRunningTasks() {
		List<ToolTask> result = new ArrayList<>();
		for (ToolTask task : tasks.values()) {
			if (task.isRunning()) {
				result.add(task);
			}
		}
		return Collections.unmodifiableList(result);
	}

	/**
	 * 取消一个任务。
	 *
	 * @param taskId 任务 ID
	 * @return 是否取消成功
	 */
	public boolean cancelTask(String taskId) {
		ToolTask task = getTask(taskId);
		if (task == null || !task.isRunning()) {
			return false;
		}
		task.cancel();
		task.getFuture().cancel(true);
		finishTask(task);
		return true;
	}

	/** 取消全部运行中的任务（会话关闭时调用）。 */
	public void cancelAllTasks() {
		for (ToolTask task : getRunningTasks()) {
			task.cancel();
			task.getFuture().cancel(true);
			finishTask(task);
		}
	}

	/** 关闭管理器：取消全部任务，此后不再登记或通知新任务。 */
	public void shutdown() {
		closed = true;
		cancelAllTasks();
	}

	/** 管理器是否已关闭。 */
	public boolean isClosed() {
		return closed;
	}

	private void finishTask(ToolTask task) {
		synchronized (finishedTasks) {
			finishedTasks.addLast(task.getId());
			while (finishedTasks.size() > MAX_FINISHED_TASKS) {
				String removed = finishedTasks.pollFirst();
				if (removed != null) {
					tasks.remove(removed);
				}
			}
		}
	}

	private static Throwable unwrap(Throwable error) {
		if (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
			return error.getCause();
		}
		if (error instanceof java.util.concurrent.ExecutionException && error.getCause() != null) {
			return error.getCause();
		}
		return error;
	}

	private static int normalizeMaxCalls(int maxCalls) {
		return maxCalls < 0 ? -1 : maxCalls;
	}

	// ==================== 调用规则：轮次与次数 ====================

	/**
	 * 工具调用轮次上限（模型每请求一批工具调用记为一轮）。
	 * <p>小于等于 0 表示不限制轮次。</p>
	 */
	public int getMaxRounds() {
		return maxRounds;
	}

	/**
	 * 设置轮次上限，小于等于 0 表示不限制。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setMaxRounds(int maxRounds) {
		this.maxRounds = maxRounds;
		return this;
	}

	/**
	 * 工具调用总次数上限（单个工具的成功执行记一次）。
	 * <p>小于等于 0 表示不限制总次数；声明为不限次的工具不占用该额度。</p>
	 */
	public int getMaxCalls() {
		return maxCalls;
	}

	/**
	 * 设置总次数上限，小于等于 0 表示不限制。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setMaxCalls(int maxCalls) {
		this.maxCalls = maxCalls;
		return this;
	}

	/** 轮次是否受限。 */
	public boolean hasRoundLimit() {
		return maxRounds > 0;
	}

	/** 总次数是否受限。 */
	public boolean hasCallLimit() {
		return maxCalls > 0;
	}

	/**
	 * 单个工具的执行次数上限。
	 * <p>声明为不限次的工具始终返回 0（不受单独限制）。</p>
	 *
	 * @param toolName 工具名称
	 * @return 上限值，未配置或该工具不限次时返回 -1
	 */
	public int getToolCallLimit(String toolName) {
		if (isUnlimited(toolName)) {
			return -1;
		}
		ToolRegistration registration = getRegistration(toolName);
		if (registration != null) {
			return registration.getMaxCalls();
		}
		Integer pending = pendingMaxCalls.get(toolName);
		return pending == null ? -1 : pending.intValue();
	}

	/**
	 * 单个工具是否配置了执行次数上限。
	 *
	 * @param toolName 工具名称
	 */
	public boolean hasToolCallLimit(String toolName) {
		return getToolCallLimit(toolName) >= 0;
	}

	/**
	 * 设置单个工具的执行次数上限。
	 * <p>属于共享次数组的工具以组上限为准，本设置对它不生效。</p>
	 *
	 * @param toolName 工具名称
	 * @param maxCalls 该工具的次数上限，-1 表示该工具不受单独限制
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setToolCallLimit(String toolName, int maxCalls) {
		if (toolName == null) {
			return this;
		}
		int normalized = normalizeMaxCalls(maxCalls);
		ToolRegistration registration = registrations.get(toolName);
		if (registration != null) {
			registration.setMaxCalls(normalized);
		} else {
			pendingMaxCalls.put(toolName, normalized);
		}
		return this;
	}

	/**
	 * 批量设置单个工具的执行次数上限，语义同 {@link #setToolCallLimit(String, int)}。
	 *
	 * @param limits 工具名称到次数上限的映射，可为 null
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setToolCallLimits(Map<String, Integer> limits) {
		if (limits == null) {
			return this;
		}
		for (Map.Entry<String, Integer> entry : limits.entrySet()) {
			setToolCallLimit(entry.getKey(), entry.getValue() == null ? -1 : entry.getValue().intValue());
		}
		return this;
	}

	/**
	 * 清空所有单工具次数上限。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager clearToolCallLimits() {
		for (ToolRegistration registration : registrations.values()) {
			registration.setMaxCalls(-1);
		}
		pendingMaxCalls.clear();
		return this;
	}

	/**
	 * 所有已配置的单个工具执行次数上限（只读视图）。
	 */
	public Map<String, Integer> getToolCallLimits() {
		Map<String, Integer> result = new LinkedHashMap<>();
		for (Map.Entry<String, ToolRegistration> entry : registrations.entrySet()) {
			if (entry.getValue().getMaxCalls() >= 0) {
				result.put(entry.getKey(), entry.getValue().getMaxCalls());
			}
		}
		return Collections.unmodifiableMap(result);
	}

	/**
	 * 不受任何调用次数限制的工具集合（只读视图）。
	 * <p>
	 * 集合中的工具：不校验总次数与单工具次数上限，也不计入轮次、不受轮次上限拦截，
	 * 即“调用多少次都可以”。默认空集合，即所有工具都受配置的上限约束。
	 * </p>
	 */
	public Set<String> getUnlimitedTools() {
		return Collections.unmodifiableSet(unlimitedTools);
	}

	/**
	 * 指定工具是否不受任何调用次数限制。
	 *
	 * @param toolName 工具名称
	 * @return 该工具是否不受次数与轮次限制
	 */
	public boolean isUnlimited(String toolName) {
		return toolName != null && unlimitedTools.contains(toolName);
	}

	/**
	 * 声明单个工具不受任何调用次数限制（轮次、总次数、单工具次数都不约束它）。
	 *
	 * @param toolName 工具名称
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager addUnlimitedTool(String toolName) {
		if (toolName != null) {
			unlimitedTools.add(toolName);
		}
		return this;
	}

	/**
	 * 批量声明不受任何调用次数限制的工具。
	 *
	 * @param toolNames 工具名称集合，可为 null（等价于清空）
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setUnlimitedTools(Collection<String> toolNames) {
		unlimitedTools.clear();
		if (toolNames != null) {
			for (String toolName : toolNames) {
				addUnlimitedTool(toolName);
			}
		}
		return this;
	}

	/**
	 * 取消某个工具的不限次声明。
	 *
	 * @param toolName 工具名称
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager removeUnlimitedTool(String toolName) {
		if (toolName != null) {
			unlimitedTools.remove(toolName);
		}
		return this;
	}

	// ==================== 调用规则：权限、许可与提示文本 ====================

	/**
	 * 已注册的权限监听器（只读视图，按注册顺序）。
	 */
	public List<ToolCallPermission> getPermissionListeners() {
		return Collections.unmodifiableList(permissionListeners);
	}

	/**
	 * 追加一个权限监听器。
	 *
	 * @param listener 监听器，null 忽略
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager addPermissionListener(ToolCallPermission listener) {
		if (listener != null) {
			permissionListeners.add(listener);
		}
		return this;
	}

	/**
	 * 移除一个权限监听器。
	 *
	 * @param listener 监听器
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager removePermissionListener(ToolCallPermission listener) {
		permissionListeners.remove(listener);
		return this;
	}

	/**
	 * 清空全部权限监听器。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager clearPermissionListeners() {
		permissionListeners.clear();
		return this;
	}

	/**
	 * 整批替换权限监听器。
	 *
	 * @param listeners 监听器集合，可为 null（等价于清空）
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setPermissionListeners(Collection<ToolCallPermission> listeners) {
		permissionListeners.clear();
		if (listeners != null) {
			for (ToolCallPermission listener : listeners) {
				addPermissionListener(listener);
			}
		}
		return this;
	}

	/**
	 * 询问全部权限监听器并合并出这次工具调用的权限判定。
	 *
	 * @param toolName  工具名称
	 * @param arguments 模型给出的原始参数
	 * @return 权限判定，永不为 null
	 */
	public ToolCallDecision checkPermission(String toolName, String arguments) {
		ToolCallDecision decision = ToolCallPermission.decide(permissionListeners, toolName, arguments);
		return decision.isIgnored() ? ToolCallDecision.ALLOW : decision;
	}

	/** 许可方，未配置时为 null。 */
	public ToolCallApprover getApprover() {
		return approver;
	}

	/**
	 * 设置许可方，可为 null（表示需要许可的调用一律不予许可）。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setApprover(ToolCallApprover approver) {
		this.approver = approver;
		return this;
	}

	/** 超过调用次数限制时回传给模型的内容。 */
	public String getLimitMessage() {
		return limitMessage;
	}

	/**
	 * 设置超过次数限制时的提示文本，传 null 表示恢复默认文本。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setLimitMessage(String limitMessage) {
		this.limitMessage = limitMessage == null ? DEFAULT_LIMIT_MESSAGE : limitMessage;
		return this;
	}

	/** 单个工具超过调用次数上限时回传给模型的内容。 */
	public String getToolLimitMessage() {
		return toolLimitMessage;
	}

	/**
	 * 设置单个工具超过次数上限时的提示文本，传 null 表示恢复默认文本。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setToolLimitMessage(String toolLimitMessage) {
		this.toolLimitMessage = toolLimitMessage == null ? DEFAULT_TOOL_LIMIT_MESSAGE : toolLimitMessage;
		return this;
	}

	/** 共享次数组超过上限时回传给模型的内容。 */
	public String getGroupLimitMessage() {
		return groupLimitMessage;
	}

	/**
	 * 设置共享次数组超限提示文本，传 null 表示恢复默认文本。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setGroupLimitMessage(String groupLimitMessage) {
		this.groupLimitMessage = groupLimitMessage == null ? DEFAULT_GROUP_LIMIT_MESSAGE : groupLimitMessage;
		return this;
	}

	/** 工具不存在或已禁用时回传给模型的内容。 */
	public String getMissingToolMessage() {
		return missingToolMessage;
	}

	/**
	 * 设置工具不存在时的提示文本，传 null 表示恢复默认文本。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setMissingToolMessage(String missingToolMessage) {
		this.missingToolMessage = missingToolMessage == null ? DEFAULT_MISSING_TOOL_MESSAGE : missingToolMessage;
		return this;
	}

	/** 工具执行抛异常时回传给模型的内容。 */
	public String getToolErrorMessage() {
		return toolErrorMessage;
	}

	/**
	 * 设置工具执行出错时的提示文本，传 null 表示恢复默认文本。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setToolErrorMessage(String toolErrorMessage) {
		this.toolErrorMessage = toolErrorMessage == null ? DEFAULT_TOOL_ERROR_MESSAGE : toolErrorMessage;
		return this;
	}

	/** 权限要求许可但未获许可时回传给模型的内容。 */
	public String getUnapprovedMessage() {
		return unapprovedMessage;
	}

	/**
	 * 设置权限要求许可但未获许可时的提示文本，传 null 表示恢复默认文本。
	 *
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setUnapprovedMessage(String unapprovedMessage) {
		this.unapprovedMessage = unapprovedMessage == null ? DEFAULT_UNAPPROVED_MESSAGE : unapprovedMessage;
		return this;
	}

	// ==================== 会话上下文 ====================

	/** 本次生成相关的会话上下文（例如 QQ 消息上下文），由会话在生成前写入。 */
	private volatile Object context;

	/**
	 * 设置本次生成的会话上下文。
	 *
	 * @param context 上下文对象，可为 null
	 * @return 本管理器，便于链式调用
	 */
	public ToolCallManager setContext(Object context) {
		this.context = context;
		return this;
	}

	/** 本次生成的会话上下文，未设置时为 null。 */
	public Object getContext() {
		return context;
	}

	@Override
	public String toString() {
		return "ToolCallManager [tools=" + registrations.size() + ", maxRounds=" + maxRounds + ", maxCalls="
				+ maxCalls + ", groups=" + groupMaxCalls + ", tasks=" + tasks.size() + ", unlimitedTools="
				+ unlimitedTools + ", permissionListeners=" + permissionListeners.size() + ", approver=" + approver
				+ "]";
	}
}
