/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.state.session;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.providers.grok.GrokUsage;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolApprovalManager;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolApprovalRequest;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallApprover;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallDisplay;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPermission;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPolicy;
import com.khjxiaogu.aiwuxia.utils.MCPTools;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.ISaveData;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.UsageTracker;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.HistoryWriteContext;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolContent;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

/**
 * AI会话类，管理与特定用户的对话历史、推理过程和状态。 该类提供了添加消息、管理历史条目、处理推理内容以及控制会话状态的功能。
 */
public class AISession implements ISaveData,AutoCloseable{

	/**
	 * 对话会话的额外状态数据，封装了与 UI 状态、对话轮次、使用统计等相关的信息。
	 */
	public static class ExtraData {
		/** 应用程序的当前状态（如界面状态） */
		private ApplicationState state = new ApplicationState();
		/** 额外的自定义数据映射，用于存储会话相关的任意键值对 */
		public Map<String, String> extraData = new HashMap<>();
		/** 当前对话的轮次/回合数，从 0 开始递增，表示已进行的对话轮次 */
		private int row;
		/** 支持撤回的最早对话轮次，即用户允许撤回的对话起始轮次，早于此轮次的条目不可撤回 */
		private int minRow = 0;
		/** 当前对话阶段（如初始化、进行中、结束等） */
		private ApplicationStage stage = ApplicationStage.INITIALIZE;
		/** 会话的使用量统计（如 token 消耗、费用等） */
		private UsageTracker usage = UsageTracker.create();
		/** 标记是否为音频会话，影响处理逻辑（如语音识别、语音合成） */
		public boolean isAudioSession = false;
		
		public String modelHint="";
		
		public String voiceModel="";
	}

	/** 会话的额外状态数据，包含轮次、阶段、使用统计等 */
	protected ISaveData data;
	/** 关联的 AI 应用程序实例，用于处理具体的 AI 逻辑（如生成回复） */
	protected AIApplication aiapp;
	/**
	 * 当前打开的历史写入上下文。
	 * <p>
	 * 应用通过 {@link #beginWrite(Role)} / {@link #continueWrite(Role)} 取得可写入上下文，
	 * 并用 try-with-resources 完成写入，会话本身不再提供任何"追加到历史记录"的方法。
	 * 生成过程中其它组件（例如工具）可以先用 {@link #getCurrentWrite()} 取得该上下文再写入。
	 * </p>
	 */
	protected SessionWriteContext currentWrite;
	/** 当前会话关联的用户标识 */
	public final String user;
	/**
	 * 单线程执行器，用于串行执行会话中的命令操作（如处理用户输入、生成回复）。 确保在多线程环境下指令按提交顺序依次执行，避免并发状态不一致。
	 */
	protected final ExecutorService commandExec;
	/** 标记当前是否正在生成 AI 输出（用于避免重复触发或状态冲突） */
	volatile transient boolean isGenerating;
	
	volatile transient AIOutput currentOutput;

	/**
	 * 工具调用许可管理器：同一会话的许可请求串行询问，等待方阻塞至用户答复或超时。
	 * <p>
	 * 通过 {@link #requestToolCallApproval(ToolApprovalRequest)} 询问用户，
	 * 通过 {@link #completeToolApproval(String, boolean)} 回填答复。
	 * </p>
	 */
	protected final ToolApprovalManager toolApprovalManager = new ToolApprovalManager(this::requestToolCallApproval);

	/**
	 * 本会话的工具注册点：所有工具都注册到这里，由工具调用管理器统一处理。
	 */
	private final ToolCallManager.Builder toolBuilder = ToolCallManager.builder();
	/**
	 * 本会话持有的工具调用管理器（工具注册表 + 调用次数/权限/许可规则），惰性构建并缓存。
	 * <p>
	 * 由 {@link #toolBuilder} 中注册的工具与 {@link #getToolCallPolicy()} 装配而成；
	 * 注册工具或调用规则变化后由 {@link #invalidateToolCallManager()} 失效重建。
	 * {@code AIRequest} 直接取用本管理器，不再自行维护工具列表。
	 * </p>
	 */
	private volatile ToolCallManager toolCallManager;

	/**
	 * 注册一个工具，由本会话的工具调用管理器统一处理。
	 *
	 * @param tool 工具定义
	 * @return 本会话，便于链式注册
	 */
	public AISession registerTool(ToolData tool) {
		toolBuilder.tool(tool);
		invalidateToolCallManager();
		return this;
	}

	/**
	 * 批量注册工具，由本会话的工具调用管理器统一处理。
	 *
	 * @param tools 工具定义集合
	 * @return 本会话，便于链式注册
	 */
	public AISession registerTools(Collection<ToolData> tools) {
		toolBuilder.tools(tools);
		invalidateToolCallManager();
		return this;
	}

	/**
	 * 注册一个 MCP 模块提供的全部工具。
	 *
	 * @param tools MCP 模块的工具集合
	 * @return 本会话，便于链式注册
	 */
	public AISession registerTools(MCPTools tools) {
		if (tools != null) {
			registerTools(tools.getTools());
		}
		return this;
	}

	/**
	 * 获取本会话的工具调用管理器，{@code AIRequest} 直接取用。
	 * <p>
	 * 管理器由已注册的工具（{@link #registerTool}/{@link #registerTools}）与
	 * {@link #getToolCallPolicy()}（调用次数、权限、许可规则）装配而成。
	 * </p>
	 *
	 * @return 工具调用管理器，无工具时其工具表为空
	 */
	public ToolCallManager getToolCallManager() {
		ToolCallManager manager = toolCallManager;
		if (manager == null) {
			synchronized (this) {
				if (toolCallManager == null) {
					toolCallManager = toolBuilder.policy(getToolCallPolicy()).build();
				}
				manager = toolCallManager;
			}
		}
		return manager;
	}

	/**
	 * 使缓存的工具调用管理器失效（注册工具或调用规则变化后调用），下次获取时按当前工具与规则重新装配。
	 * <p>注册工具时已自动失效，无需手动调用。</p>
	 */
	public void invalidateToolCallManager() {
		synchronized (this) {
			toolCallManager = null;
		}
	}

	/**
	 * 获取一次工具调用的用户友好显示。
	 * <p>
	 * 生成过程中已将显示算好并缓存在 {@link ToolCallContent} 上，直接返回；
	 * 历史从存储重新加载后显示信息不存在（不持久化），此时按当前注册的工具重算，
	 * 保证加载后工具调用简述与具体内容同样可见。
	 * </p>
	 *
	 * @param content 工具调用内容
	 * @param index   工具调用下标
	 * @return 用户友好显示，永不为 null
	 */
	public ToolCallDisplay getToolCallDisplay(ToolCallContent content, int index) {
		if (content == null) {
			return ToolCallDisplay.EMPTY;
		}
		if (content.hasDisplays()) {
			return content.getDisplay(index);
		}
		List<ToolCall> calls = content.getToolCalls();
		if (index < 0 || index >= calls.size()) {
			return ToolCallDisplay.EMPTY;
		}
		return getToolCallManager().displayFor(calls.get(index));
	}

	/**
	 * 获取一次工具结果的用户友好显示。
	 * <p>
	 * 生成过程中已算好的显示直接返回；历史重新加载后按当前注册的工具重算，
	 * 需要提供对应的原始调用（可按 {@link ToolContent#getToolId()} 与
	 * {@link ToolCallContent#getToolCalls()} 配对）。
	 * </p>
	 *
	 * @param call   对应的原始工具调用，可为 null
	 * @param result 工具结果内容
	 * @return 用户友好显示，永不为 null
	 */
	public ToolCallDisplay getToolResultDisplay(ToolCall call, ToolContent result) {
		if (result == null) {
			return ToolCallDisplay.EMPTY;
		}
		if (result.hasDisplay()) {
			return result.getDisplay();
		}
		if (call == null) {
			return result.getDisplay();
		}
		ToolCallManager manager = getToolCallManager();
		return manager.resultDisplayFor(call, manager.displayFor(call), result.getResult());
	}

	public AIOutput getCurrentOutput() {
		return currentOutput;
	}

	public void setCurrentOutput(AIOutput currentOutput) {
		this.currentOutput = currentOutput;
	}

	/**
	 * 构造一个 AI 会话实例。
	 *
	 * @param user     用户标识
	 * @param historym 历史条目容器（不能为 null）
	 * @param data     额外状态数据（不能为 null）
	 * @param aiapp    关联的 AI 应用实例
	 */
	public AISession(String user, ISaveData data, AIApplication aiapp) {
		super();
		this.data = data;
		this.user = user;
		this.aiapp = aiapp;
		commandExec = Executors.newSingleThreadExecutor(r -> {
	        Thread t = new Thread(r, "ai-session-"+user);
	        return t;
	    });
	}

	/**
	 * 获取历史条目容器。
	 *
	 * @return {@link HistoryHolder} 实例
	 */
	public HistoryHolder getHistory() {
		return data.getHistory();
	}

	/**
	 * 开始一次历史写入，返回可写入上下文。
	 * <p>
	 * 每次调用都会新起一条记录：History 在创建时即分配好标识符，但记录尚未进入历史容器，
	 * 因此不参与上下文构建、总结清理与历史分页，也不会落盘。应用用 try-with-resources
	 * 持有该上下文写入本轮内容，块结束时由 {@link HistoryWriteContext#close()} 结算：
	 * 写过内容就提交进历史，只写了思维链的空记录直接撤回。
	 * </p>
	 * <p>
	 * 会话同一时间只保留一个打开的写入上下文：调用本方法时若上一个还没结束，会先按内容结算它。
	 * </p>
	 *
	 * @param role 本条记录的角色
	 * @return 可写入上下文
	 */
	public HistoryWriteContext beginWrite(Role role) {
		closeCurrentWrite();
		currentWrite = new SessionWriteContext(this, getHistory().beginPending(role, true), true);
		setUpdated();
		return currentWrite;
	}

	/**
	 * 续写最后一条同角色记录，返回可写入上下文。
	 * <p>
	 * 用于同一条记录由多次到达的输入拼成的场景（例如绘画前端先发文本、再发图片）：
	 * 最后一条历史条目与角色相同且仍是有效上下文时就地续写它，否则等价于
	 * {@link #beginWrite(Role)} 新起一条记录。续写的记录本来就在历史里，
	 * 因此 {@link HistoryWriteContext#close()} 不会提交或撤回任何内容。
	 * </p>
	 *
	 * @param role 要写入的角色
	 * @return 可写入上下文
	 */
	public HistoryWriteContext continueWrite(Role role) {
		HistoryItem last = getHistory().peekLast();
		// 已删除的条目不算数：撤回后容器里可能只剩软删除条目，peekLast() 此时返回 null
		if (last != null && last.getRole() == role && last.isValidContext()) {
			closeCurrentWrite();
			currentWrite = new SessionWriteContext(this, last, false);
			return currentWrite;
		}
		return beginWrite(role);
	}

	/**
	 * 获取当前打开的写入上下文。
	 * <p>
	 * 生成过程中需要往本轮记录里追加内容的组件（例如执行工具时发送图片）通过本方法取得上下文，
	 * 再调用其写入方法；没有正在进行的写入时返回 null。
	 * </p>
	 *
	 * @return 当前写入上下文，没有时返回 null
	 */
	public HistoryWriteContext getCurrentWrite() {
		return currentWrite;
	}

	/**
	 * 结束当前打开的写入上下文（按其内容提交或撤回），没有打开时不做任何处理。
	 */
	private void closeCurrentWrite() {
		SessionWriteContext write = currentWrite;
		if (write != null && !write.isResolved()) {
			write.close();
		}
	}

	/**
	 * 写入上下文结束（提交、撤回或关闭）时的回调。
	 *
	 * @param write 结束的上下文
	 */
	void onWriteResolved(SessionWriteContext write) {
		if (currentWrite == write) {
			currentWrite = null;
		}
		setUpdated();
	}

	/**
	 * 思维链写入钩子。
	 * <p>
	 * 可写入上下文每次追加思维链时调用。需要把思考过程实时呈现给前端的会话可以重写本方法
	 * （例如按自己的帧格式下发 thinking 帧），默认不做任何处理。
	 * </p>
	 *
	 * @param reasoner 本次追加的思维链内容
	 */
	protected void onReasonerAppend(MessageContent reasoner) {

	}

	/**
	 * 根据标识符移除指定的历史条目；标识符指向本轮正在写入的记录时改为撤回该次写入。
	 *
	 * @param num 要移除的条目的标识符（参见 {@link HistoryItem#getIdentifier()}）
	 */
	public void removeOf(int num) {
		SessionWriteContext write = currentWrite;
		if (write != null && write.getIdentifier() == num) {
			write.rollback();
			return;
		}
		getHistory().removeOf(num);
		delMessage(num);
	}

	/**
	 * 移除并返回最后一个历史条目；本轮正在写入的记录尚未提交时改为撤回该次写入。
	 *
	 * @return 被移除的最后一个 {@link HistoryItem} 对象
	 */
	public HistoryItem removeLast() {
		SessionWriteContext write = currentWrite;
		if (write != null) {
			HistoryItem removed = write.item();
			write.rollback();
			return removed;
		}
		HistoryItem removed = getHistory().removeLast();
		delMessage(removed.getIdentifier());
		return removed;
	}
	/**
	 * 撤回并返回最后一个历史条目；本轮正在写入的记录尚未提交时改为撤回该次写入。
	 *
	 * @return 被移除的最后一个 {@link HistoryItem} 对象
	 */
	public HistoryItem deleteLast() {
		SessionWriteContext write = currentWrite;
		if (write != null) {
			HistoryItem removed = write.item();
			write.rollback();
			return removed;
		}
		HistoryItem removed = getHistory().deleteLast();
		delMessage(removed.getIdentifier());
		return removed;
	}
	/**
	 * 获取最后一个历史条目（不移除）。
	 * <p>
	 * 本轮存在打开的写入上下文时返回它正在写入的记录：该记录可能尚未提交进历史，
	 * 因此不会随历史分页下发，也不会落盘。
	 * </p>
	 *
	 * @return 最后一个 {@link HistoryItem}，如果历史为空则返回 null
	 */
	public HistoryItem getLast() {
		SessionWriteContext write = currentWrite;
		if (write != null)
			return write.item();
		return getHistory().peekLast();
	}

	/**
	 * 当新消息添加后调用，用于通知外部（如 UI）更新。 子类可重写此方法以实现自定义处理。
	 *
	 * @param id      消息的标识符
	 * @param role    消息的角色
	 * @param message 消息的显示内容
	 */
	public void postMessage(int id, Role role, MessageContents message) {
		setUpdated();
	}

	/**
	 * 当现有消息被追加内容时调用，用于通知外部更新。
	 *
	 * @param id    消息标识符
	 * @param title 追加的内容
	 */
	public void appendMessage(int id, String title) {
		setUpdated();
	}

	/**
	 * 当现有消息被追加多模态内容时调用，用于通知外部更新。
	 * <p>
	 * 默认实现将内容转为文本后委托给 {@link #appendMessage(int, String)}。
	 * 子类可重写此方法以支持图片、视频等非文本内容的传输。
	 * </p>
	 *
	 * @param id      消息标识符
	 * @param content 要追加的多模态内容
	 */
	public void appendMessage(int id, MessageContent content) {
		appendMessage(id, content.toText());
	}

	/**
	 * 当消息被删除时调用，用于通知外部。
	 *
	 * @param id 被删除消息的标识符
	 */
	public void delMessage(int id) {
		setUpdated();
	}

	/** 标记会话是否已更新（用于外部检测变化） */
	boolean isUpdated;

	/**
	 * 检查并清除更新标记。通常由外部轮询调用以判断会话是否有变化。
	 *
	 * @return 如果会话在上次检查后有更新则返回 true，否则 false
	 */
	public boolean checkAndUnsetUpdated() {
		boolean res = isUpdated;
		isUpdated = false;
		return res;
	}

	/**
	 * 设置更新标记为 true，表示会话内容发生了变化。
	 */
	public void setUpdated() {
		isUpdated = true;
	}
	/**
	 * @param text  
	 */
	public void refillChatBox(MessageContents text) {
		
	}

	/**
	 * 当生成完成时调用，清除生成标记并设置更新标记。
	 */
	public void onGenComplete() {
		setUpdated();
		isGenerating = false;
		this.currentOutput=null;
		data.flush();
	}
	public boolean isAvailable() {
		return true;
	}

	/**
	 * 发送场景内容（可能用于传递特定类型的数据到 UI 或其他组件）。
	 *
	 * @param type  内容类型
	 * @param value 内容值
	 */
	public void sendSceneContent(String type, String value) {
		// 默认空实现，子类可覆盖
	}

	/**
	 * 累加使用量统计。
	 *
	 * @param usage 要累加的 {@link GrokUsage} 对象
	 */
	public void addUsage(UsageIntf<?> usage) {
		addStatUsage(usage);
	}
	public void addStatUsage(UsageIntf<?> usage) {
		getExtraData().usage.add(usage);
	}
	public boolean canGenerate() {
		return true;
	}
	/**
	 * 获取使用量统计的字符串表示。
	 *
	 * @return 使用量信息字符串
	 */
	public String getUsage() {
		return getExtraData().usage.toString();
	}

	/**
	 * 计算并获取使用量对应的价格字符串。
	 *
	 * @return 价格字符串
	 */
	public String getPrice() {
		return getExtraData().usage.calculatePrice();
	}
	public float getTokens() {
		return getExtraData().usage.getTotalTokenPrice();
	}
	/**
	 * 获取当前对话阶段。
	 *
	 * @return {@link ApplicationStage} 枚举值
	 */
	public ApplicationStage getStage() {
		return getExtraData().stage;
	}

	/**
	 * 获取当前会话应当使用的模型标识。
	 * <p>
	 * 基类直接返回会话中保存的模型提示（可能为空，表示未指定）；具体会话实现可重写本方法，
	 * 在未指定时回退到应用级默认模型。AI请求统一通过本方法取模型标识。
	 * </p>
	 *
	 * @return 模型标识（按 "/" 分层的完整路径），空字符串表示未指定
	 */
	public String getModelHint() {
		String hint = getExtraData().modelHint;
		return hint == null ? "" : hint;
	}

	Map<String,Consumer<String>> pendingInputs=new ConcurrentHashMap<>();
	/**
	 * @param prompt  
	 */
	public void requestUserInput(String input,String prompt,Consumer<String> consumer) {
		pendingInputs.put(input, consumer);
	}
	/**
	 * @param msg  
	 */
	public void sendNotice(String msg) {
		
	}
	public void handleUserInput(String input,String value) {
		Consumer<String> csm=pendingInputs.remove(input);
		if(csm!=null)
			csm.accept(value);
	}
	/**
	 * 获取额外数据映射。
	 *
	 * @return 键值对映射，可用于存储自定义数据
	 */
	public Map<String, String> getExtra() {
		return getExtraData().extraData;
	}
	public Map<String, String> getHistoryState() {
		return getExtraData().extraData;
	}
	public String getUserName() {
		return getExtraData().extraData.get("name");
	}
	public void setUserName(String name) {
		getExtraData().extraData.put("name", name);
	}
	public String getLastSummary() {
		return getHistoryState().get("lastSummary");
	}
	public void setLastSummary(String lastSummary) {
		getHistoryState().put("lastSummary",lastSummary);
	}
	public String getBackground() {
		return getExtraData().extraData.get("back");
	}
	public void setBackground(String back) {
		getExtraData().extraData.put("back",back);
	}
	public String getCharacter() {
		return getExtraData().extraData.get("chara");
	}
	public void setCharacter(String chara) {
		getExtraData().extraData.put("chara",chara);
	}
	/**
	 * 设置当前对话阶段。
	 *
	 * @param stage 新的阶段
	 */
	public void setStage(ApplicationStage stage) {
		getExtraData().stage = stage;
	}

	/**
	 * 增加对话轮次计数（row + 1）。 每次完成一轮完整的对话交互后调用。
	 */
	public void addDialogRow() {
		getExtraData().row++;
	}

	/**
	 * 减少对话轮次计数（row - 1）。 当撤回一轮对话时调用。
	 */
	public void minDialogRow() {
		getExtraData().row--;
	}

	/**
	 * 获取支持撤回的最早对话轮次。
	 *
	 * @return 最小轮次数
	 */
	public int getMinDialogRow() {
		return getExtraData().minRow;
	}

	/**
	 * 设置支持撤回的最早对话轮次。
	 *
	 * @param minRow 新的最小轮次数
	 */
	public void setMinDialogRow(int minRow) {
		getExtraData().minRow = minRow;
	}

	/**
	 * 获取当前对话轮次。
	 *
	 * @return 当前轮次数
	 */
	public int getDialogRow() {
		return getExtraData().row;
	}

	/**
	 * 获取应用状态对象（可能包含 UI 或会话状态快照）。
	 *
	 * @return {@link ApplicationState} 实例
	 */
	public ApplicationState getState() {
		return getExtraData().state;
	}

	/**
	 * 标记生成过程开始，设置 {@link #isGenerating} 为 true。
	 */
	public void onGenerateStart() {
		isGenerating = true;
	}

	/**
	 * 批量发布历史条目更新通知。
	 *
	 * @param items 更新的条目列表
	 */
	public void postMessages(List<HistoryItem> items) {
		setUpdated();
	}

	/**
	 * 批量减少对话轮次计数。
	 *
	 * @param rows 要减少的轮次数
	 */
	public void minDialogRows(int rows) {
		getExtraData().row -= rows;
	}
	public void setDialogRows(int rows) {
		getExtraData().row = rows;
	}

	/**
	 * 判断是否为音频会话。
	 *
	 * @return 如果是音频会话则返回 true
	 */
	public boolean isAudioSession() {
		return getExtraData().isAudioSession;
	}

	/**
	 * 获取当前的推理内容。
	 * <p>
	 * 本轮存在打开的写入上下文时返回它已经写入的思维链；否则如果最后一个条目是助手且包含推理内容，
	 * 则返回该推理内容；都没有时返回 null。
	 * </p>
	 *
	 * @return 推理内容，可能为 null
	 */
	public MessageContents getReasonerContent() {
		SessionWriteContext write = currentWrite;
		if (write != null)
			return write.getReasoningContent();
		HistoryItem last = getHistory().peekLast();
		if (last!=null&&last.getRole() == Role.ASSISTANT) {
			MessageContents reasoner=last.getReasoningContent();
			return reasoner==null?null:reasoner;
		}
		return null;
	}

	/**
	 * 通知音频完成，关联音频 ID 到指定的历史条目。
	 *
	 * @param id      历史条目标识符
	 * @param audioId 音频标识符
	 */
	public void postAudioComplete(int id, String audioId) {
		// 默认空实现，子类可覆盖
	}
	/**
	 * 提供初始提示（例如在会话开始时由 AI 应用触发）。 通过 {@link #commandExec} 提交任务以确保顺序执行。
	 */
	public Future<?> provideInitialHint() {
		return getCommandExec().submit(() -> getAiapp().provideInitial(this));
	}

	/**
	 * 处理用户输入的消息。 通过 {@link #commandExec} 提交任务以确保顺序执行。
	 *
	 * @param message 语音识别后的文本消息
	 */
	public Future<?> handleUserSpeech(MessageContents message) {
		return getCommandExec().submit(() -> getAiapp().handleSpeech(this, message));
	}

	/**
	 * 获取关联的 AI 应用实例。
	 *
	 * @return {@link AIApplication} 对象
	 */
	public AIApplication getAiapp() {
		return aiapp;
	}

	/**
	 * 获取命令执行器（单线程），用于提交需要串行执行的任务。
	 *
	 * @return {@link ExecutorService} 实例
	 */
	public ExecutorService getCommandExec() {
		return commandExec;
	}

	/**
	 * 检查当前是否正在生成输出。
	 *
	 * @return 如果正在生成则返回 true
	 */
	public boolean isGenerating() {
		return isGenerating;
	}

	/**
	 * 获取会话的额外数据对象。
	 *
	 * @return {@link ExtraData} 实例
	 */
	public ExtraData getExtraData() {
		return data.getExtraData();
	}

	public Future<?> onLoad() {
		return getCommandExec().submit(()->this.aiapp.onload(this));
	}
	public String constructBackLog() {
		return aiapp.constructBackLog(this);
	}
	public String getBrief() {
		return aiapp.getBrief(this);
	}

	public String getMemory() {
		return aiapp.getMemory(this);
	}
	
	public String getRoleName(Role role) {
		return aiapp.getRoleName(this, role);
	}
	/**
	 * 获取本会话的工具调用管理策略（调用次数、权限、许可）。
	 * <p>
	 * 该策略与已注册的工具一起装配成会话持有的
	 * {@link ToolCallManager}（见 {@link #getToolCallManager()}），由 {@code ToolCallContext}
	 * 统一执行，会话本身不参与工具调用的执行过程。子类可重写此方法或其细粒度钩子
	 * （{@link #getToolCallPermission()}、{@link #getToolCallApprover()}、
	 * {@link #getMaxToolCallRounds()}、{@link #getMaxToolCalls()}、
	 * {@link #getToolCallLimits()}）来收紧或放宽限制；规则变化后可调用
	 * {@link #invalidateToolCallManager()} 立即生效。
	 * </p>
	 *
	 * @return 工具调用管理策略
	 */
	public ToolCallPolicy getToolCallPolicy() {
		return ToolCallPolicy.builder()
				.maxRounds(getMaxToolCallRounds())
				.maxCalls(getMaxToolCalls())
				.toolLimits(getToolCallLimits())
				.permission(getToolCallPermission())
				.approver(getToolCallApprover())
				.build();
	}

	/**
	 * 各工具的执行次数上限（工具名称 → 该工具在单次生成中最多执行几次）。
	 * <p>
	 * 未列出的工具不受单独限制；某项小于等于 0 也表示该工具不受单独限制。
	 * </p>
	 *
	 * @return 工具名称到次数上限的映射，默认空（不限制单个工具）
	 */
	public Map<String, Integer> getToolCallLimits() {
		return Collections.emptyMap();
	}

	/**
	 * 工具调用轮次上限（模型每请求一批工具调用记为一轮）。
	 *
	 * @return 最大轮次，小于等于 0 表示不限制，默认 {@link ToolCallPolicy#DEFAULT_MAX_ROUNDS}
	 */
	public int getMaxToolCallRounds() {
		return ToolCallPolicy.DEFAULT_MAX_ROUNDS;
	}

	/**
	 * 工具调用总次数上限（单个工具的成功执行记一次）。
	 *
	 * @return 最大次数，小于等于 0 表示不限制，默认 0
	 */
	public int getMaxToolCalls() {
		return 0;
	}

	/**
	 * 本会话的工具调用权限策略，默认全部允许。
	 *
	 * @return 权限策略
	 */
	public ToolCallPermission getToolCallPermission() {
		return ToolCallPermission.allowAll();
	}

	/**
	 * 本会话的工具调用许可方，默认自动许可。
	 * <p>
	 * 需要用户确认的场景可重写 {@link #getToolCallPermission()} 让目标工具返回
	 * {@code ToolCallDecision.ask(...)}，并在此返回 {@link #getUserApprovalApprover()}
	 * （或自建的 {@link ToolCallApprover}）以阻塞等待用户答复。
	 * </p>
	 * <p>
	 * 注意：本方法由模型生成线程调用；答复必须由<b>另一个线程</b>回填
	 * （见 {@link #completeToolApproval(String, boolean)}），不得在生成线程所占用的事件循环里
	 * 同步等待，否则会死锁。
	 * </p>
	 *
	 * @return 许可方
	 */
	public ToolCallApprover getToolCallApprover() {
		return ToolCallApprover.autoApprove();
	}

	/**
	 * 获取本会话的工具调用许可管理器（同一会话串行询问、超时按拒绝处理）。
	 *
	 * @return 许可管理器
	 */
	public ToolApprovalManager getToolApprovalManager() {
		return toolApprovalManager;
	}

	/**
	 * 阻塞等待用户许可的许可方，可直接由 {@link #getToolCallApprover()} 返回。
	 *
	 * @return 许可方
	 */
	public ToolCallApprover getUserApprovalApprover() {
		return toolApprovalManager.approverFor(user);
	}

	/**
	 * 异步发起一次工具调用许可请求。
	 * <p>
	 * 默认实现直接拒绝（失败关闭）；子类应重写本方法把请求交给用户
	 * （例如通过 WebSocket 下发确认框），并在收到答复后调用
	 * {@link #completeToolApproval(String, boolean)}。实现不得阻塞等待答复。
	 * </p>
	 *
	 * @param request 许可请求记录
	 */
	protected void requestToolCallApproval(ToolApprovalRequest request) {
		request.deny();
	}

	/**
	 * 回填一次工具调用许可答复（会话收到用户答复时调用）。
	 * <p>
	 * 该方法必须在发起工具调用的线程之外的线程上调用，否则等待方无法被唤醒。
	 * </p>
	 *
	 * @param requestId 请求 id
	 * @param approved  是否许可
	 * @return 答复是否被接受（请求不存在、已超时或重复答复时返回 false）
	 */
	public boolean completeToolApproval(String requestId, boolean approved) {
		return toolApprovalManager.complete(requestId, approved);
	}

	/**
	 * 放弃本会话全部待答复的许可请求（例如连接断开），一律按拒绝处理。
	 */
	public void cancelToolApprovals() {
		toolApprovalManager.cancelAll(user);
	}

	/**
	 * 工具调用通知钩子。
	 * <p>
	 * 当 {@code ToolCallManager} 决定发起一批工具调用并写入推理流时被调用，
	 * 仅用于对外通知/展示，工具的执行与计数由 {@code ToolCallManager} 负责。
	 * </p>
	 *
	 * @param content 本批工具调用内容
	 */
	public void onToolcall(ToolCallContent content) {
		
	}
	public void setValidContext(HistoryItem hi, boolean sendable) {
		getHistory().setValidContext(hi, sendable);
	}
	public void setDeleted(HistoryItem hi, boolean sendable) {
		getHistory().setDeleted(hi, sendable);
	}
	public void setAudioId(HistoryItem hi, String audioId) {
		getHistory().setAudioId(hi, audioId);
	}
	public void setSendReasoner(HistoryItem hi, boolean sendReasoner) {
		getHistory().setSendReasoner(hi, sendReasoner);
	}
	public void setTokenLength(HistoryItem hi, long l) {
		getHistory().setTokenLength(hi, l);
	}


	@Override
	public void flush() {
		data.flush();
	}

	public boolean onModifyAttempt() {
		return true;
	}

	public void onModifyComplete() {
		flush();
	}

	@Override
	public void close(){
		// 释放可能正在等待用户许可的生成线程
		cancelToolApprovals();
		commandExec.shutdown();
	}
}