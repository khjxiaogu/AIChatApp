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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.respscheme.Usage;
import com.khjxiaogu.aiwuxia.state.ApplicationStage;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryHolder;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

/**
 * AI会话类，管理与特定用户的对话历史、推理过程和状态。 该类提供了添加消息、管理历史条目、处理推理内容以及控制会话状态的功能。
 */
public class AISession {

	/**
	 * 对话会话的额外状态数据，封装了与 UI 状态、对话轮次、使用统计等相关的信息。
	 */
	public static class ExtraData {
		/** 应用程序的当前状态（如界面状态） */
		private ApplicationState state = new ApplicationState();
		/** 额外的自定义数据映射，用于存储会话相关的任意键值对 */
		protected Map<String, String> extraData = new HashMap<>();
		/** 当前对话的轮次/回合数，从 0 开始递增，表示已进行的对话轮次 */
		private int row;
		/** 支持撤回的最早对话轮次，即用户允许撤回的对话起始轮次，早于此轮次的条目不可撤回 */
		private int minRow = 0;
		/** 当前对话阶段（如初始化、进行中、结束等） */
		private ApplicationStage stage = ApplicationStage.INITIALIZE;
		/** 会话的使用量统计（如 token 消耗、费用等） */
		private Usage usage = new Usage();
		/** 标记是否为音频会话，影响处理逻辑（如语音识别、语音合成） */
		public boolean isAudioSession = false;
	}

	/** 保存对话历史条目的容器，提供对历史记录的增删改查 */
	protected HistoryHolder history;
	/** 会话的额外状态数据，包含轮次、阶段、使用统计等 */
	protected ExtraData data;
	/** 关联的 AI 应用程序实例，用于处理具体的 AI 逻辑（如生成回复） */
	protected AIApplication aiapp;
	/** 当前正在构建的推理内容（AI 生成过程中的中间思考文本）的缓冲区 */
	protected StringBuilder currentReasoner = null;
	/** 当前会话关联的用户标识 */
	public final String user;
	/**
	 * 单线程执行器，用于串行执行会话中的命令操作（如处理用户输入、生成回复）。 确保在多线程环境下指令按提交顺序依次执行，避免并发状态不一致。
	 */
	protected ExecutorService commandExec = Executors.newFixedThreadPool(1);
	/** 标记当前是否正在生成 AI 输出（用于避免重复触发或状态冲突） */
	volatile transient boolean isGenerating;

	/**
	 * 构造一个 AI 会话实例。
	 *
	 * @param user     用户标识
	 * @param historym 历史条目容器（不能为 null）
	 * @param data     额外状态数据（不能为 null）
	 * @param aiapp    关联的 AI 应用实例
	 */
	public AISession(String user, HistoryHolder historym, ExtraData data, AIApplication aiapp) {
		super();
		this.history = historym;
		this.data = data;
		this.user = user;
		this.aiapp = aiapp;
	}

	/**
	 * 获取历史条目容器。
	 *
	 * @return {@link HistoryHolder} 实例
	 */
	public HistoryHolder getHistory() {
		return history;
	}

	 /**
     * 追加内容到当前的推理内容缓冲区。
     * 如果缓冲区为空，会先创建一个新的 {@link StringBuilder}。
     * 同时会标记会话为已更新（{@link #setUpdated()}）。
     *
     * @param content 要追加的推理内容字符串
     */
    public void appendReasoner(String content) {
        if (currentReasoner == null)
            currentReasoner = new StringBuilder();
        currentReasoner.append(content);
        this.setUpdated();
    }

	/**
	 * 重置当前推理内容缓冲区，将其置为 null。 通常在一个完整的推理过程结束后调用。
	 */
	public void resetReasoner() {
		currentReasoner = null;
	}

	/**
	 * 向当前会话追加一行内容，并根据参数决定是否将其视为有效的上下文内容。 如果最后一条历史条目与给定角色相同且其上下文有效性状态与参数一致，
	 * 则在该条目中追加内容；否则创建新的历史条目。 若当前有未完成的推理内容且角色为助手，则会将推理内容关联到新条目。
	 *
	 * @param role            条目的角色（用户、助手等）
	 * @param content         要追加的内容字符串（会自动追加换行符）
	 * @param appendToContext 如果为 true，表示该内容应被视为有效的上下文内容 （即对后续对话有影响），会更新条目的上下文有效性标记
	 */
	public void appendLine(Role role, String content, boolean appendToContext) {
		HistoryItem hi = null;
		if (!history.isEmpty()) {
			hi = getLast();
			if (role != hi.getRole() || (!hi.isValidContext() && appendToContext)) {
				hi = null;
			}
		}
		if (hi == null) {
			hi = history.add(role, content + "\n", true);
			if (currentReasoner != null && role == Role.ASSISTANT) {
				hi.appendReasoner(currentReasoner.toString());
				currentReasoner = null;
			}
			postMessage(hi.getIdentifier(), hi.getRole(), hi.getDisplayContent().toString());
		} else {
			hi.appendLine(content, appendToContext);
			appendMessage(hi.getIdentifier(), content + "\n");
		}
	}

	/**
	 * 向当前会话追加一个字符块（不自动添加换行），并决定是否同时将其添加到上下文内容中。 逻辑与 {@link #appendLine}
	 * 类似，区别在于内容直接追加而不加换行。
	 *
	 * @param role            条目的角色
	 * @param ch              要追加的字符块字符串
	 * @param appendToContext 是否同时将其添加到上下文内容中
	 */
	public void appendCh(Role role, String ch, boolean appendToContext) {
		HistoryItem hi = null;
		if (!history.isEmpty()) {
			hi = getLast();
			if (role != hi.getRole() || (!hi.isValidContext() && appendToContext)) {
				hi = null;
			}
		}
		if (hi == null) {
			hi = history.add(role, ch, true);
			if (currentReasoner != null && role == Role.ASSISTANT) {
				hi.appendReasoner(currentReasoner.toString());
				currentReasoner = null;
			}
			postMessage(hi.getIdentifier(), hi.getRole(), hi.getDisplayContent().toString());
		} else {
			hi.append(ch, appendToContext);
			appendMessage(hi.getIdentifier(), ch);
		}
	}

	/**
	 * 向当前会话追加一行内容到上下文中，不影响显示内容。 如果最后一条历史条目与给定角色相同且是有效的上下文内容，则在其上下文中追加； 否则创建新的历史条目。
	 *
	 * @param role    条目的角色
	 * @param content 要追加到上下文的内容（会自动追加换行符）
	 */
	public void appendContextLine(Role role, String content) {
		HistoryItem hi = null;
		if (!history.isEmpty()) {
			hi = getLast();
			if (role != hi.getRole() || (!hi.isValidContext())) {
				hi = null;
			}
		}
		if (hi == null) {
			hi = history.add(role, "", content + "\n");
			if (currentReasoner != null && role == Role.ASSISTANT) {
				hi.appendReasoner(currentReasoner.toString());
				currentReasoner = null;
			}
			postMessage(hi.getIdentifier(), hi.getRole(), hi.getDisplayContent().toString());
		} else {
			hi.appendContext(content + "\n");
		}
	}

	/**
	 * 添加一个新的历史条目，指定角色、显示内容和是否可发送。 如果当前有未完成的推理内容且角色为助手，则将其关联到新条目。
	 *
	 * @param role           角色
	 * @param content        内容
	 * @param isValidContext 指示该条目是否可作为有效的上下文内容
	 */
	public void add(Role role, String content, boolean isValidContext) {
		HistoryItem hi = history.add(role, content, isValidContext);
		if (currentReasoner != null && role == Role.ASSISTANT)
			hi.appendReasoner(currentReasoner.toString());
		postMessage(hi.getIdentifier(), hi.getRole(), hi.getDisplayContent().toString());
	}

	/**
	 * 添加一个新的历史条目，指定角色、显示内容和完整的上下文内容。 如果当前有未完成的推理内容且角色为助手，则将其关联到新条目。
	 *
	 * @param role           角色
	 * @param displayContent 显示内容
	 * @param contextContent 完整的上下文内容（用于 AI 输入）
	 */
	public void add(Role role, String displayContent, String contextContent) {
		HistoryItem hi = history.add(role, displayContent, contextContent);
		if (currentReasoner != null && role == Role.ASSISTANT) {
			hi.appendReasoner(currentReasoner.toString());
			currentReasoner = null;
		}
		postMessage(hi.getIdentifier(), hi.getRole(), hi.getDisplayContent().toString());
	}

	/**
	 * 根据标识符移除指定的历史条目。
	 *
	 * @param num 要移除的条目的标识符（参见 {@link HistoryItem#getIdentifier()}）
	 */
	public void removeOf(int num) {
		history.removeOf(num);
		delMessage(num);
	}

	/**
	 * 移除并返回最后一个历史条目。
	 *
	 * @return 被移除的最后一个 {@link HistoryItem} 对象
	 */
	public HistoryItem removeLast() {
		HistoryItem removed = history.removeLast();
		delMessage(removed.getIdentifier());
		return removed;
	}
	/**
	 * 撤回并返回最后一个历史条目。
	 *
	 * @return 被移除的最后一个 {@link HistoryItem} 对象
	 */
	public HistoryItem deleteLast() {
		HistoryItem removed = history.deleteLast();
		delMessage(removed.getIdentifier());
		return removed;
	}
	/**
	 * 获取最后一个历史条目（不移除）。
	 *
	 * @return 最后一个 {@link HistoryItem}，如果历史为空则返回 null
	 */
	public HistoryItem getLast() {
		return history.peekLast();
	}

	/**
	 * 当新消息添加后调用，用于通知外部（如 UI）更新。 子类可重写此方法以实现自定义处理。
	 *
	 * @param id      消息的标识符
	 * @param role    消息的角色
	 * @param message 消息的显示内容
	 */
	public void postMessage(int id, Role role, String message) {
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
	public void refillChatBox(String text) {
		
	}

	/**
	 * 当生成完成时调用，清除生成标记并设置更新标记。
	 */
	public void onGenComplete() {
		setUpdated();
		isGenerating = false;
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
	 * @param usage 要累加的 {@link Usage} 对象
	 */
	public void addUsage(Usage usage) {
		data.usage.add(usage);
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
		return data.usage.toString();
	}

	/**
	 * 计算并获取使用量对应的价格字符串。
	 *
	 * @return 价格字符串
	 */
	public String getPrice() {
		return data.usage.calculatePrice();
	}

	/**
	 * 获取当前对话阶段。
	 *
	 * @return {@link ApplicationStage} 枚举值
	 */
	public ApplicationStage getStage() {
		return data.stage;
	}
	Map<String,Consumer<String>> pendingInputs=new ConcurrentHashMap<>();
	public void requestUserInput(String input,String prompt,Consumer<String> consumer) {
		pendingInputs.put(input, consumer);
	}
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
		return data.extraData;
	}

	/**
	 * 设置当前对话阶段。
	 *
	 * @param stage 新的阶段
	 */
	public void setStage(ApplicationStage stage) {
		data.stage = stage;
	}

	/**
	 * 增加对话轮次计数（row + 1）。 每次完成一轮完整的对话交互后调用。
	 */
	public void addDialogRow() {
		data.row++;
	}

	/**
	 * 减少对话轮次计数（row - 1）。 当撤回一轮对话时调用。
	 */
	public void minDialogRow() {
		data.row--;
	}

	/**
	 * 获取支持撤回的最早对话轮次。
	 *
	 * @return 最小轮次数
	 */
	public int getMinDialogRow() {
		return data.minRow;
	}

	/**
	 * 设置支持撤回的最早对话轮次。
	 *
	 * @param minRow 新的最小轮次数
	 */
	public void setMinDialogRow(int minRow) {
		data.minRow = minRow;
	}

	/**
	 * 获取当前对话轮次。
	 *
	 * @return 当前轮次数
	 */
	public int getDialogRow() {
		return data.row;
	}

	/**
	 * 获取应用状态对象（可能包含 UI 或会话状态快照）。
	 *
	 * @return {@link ApplicationState} 实例
	 */
	public ApplicationState getState() {
		return data.state;
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
		data.row -= rows;
	}

	/**
	 * 判断是否为音频会话。
	 *
	 * @return 如果是音频会话则返回 true
	 */
	public boolean isAudioSession() {
		return data.isAudioSession;
	}

	/**
	 * 获取当前的推理内容。 如果 {@link #currentReasoner} 不为空则返回其内容；
	 * 否则如果最后一个条目是助手且包含推理内容，则返回该推理内容； 否则返回空字符串。
	 *
	 * @return 推理内容字符串
	 */
	public String getReasonerContent() {
		if (currentReasoner != null)
			return currentReasoner.toString();
		if (!history.isEmpty() && getLast().getRole() == Role.ASSISTANT)
			return getLast().getReasoningContent();
		return "";
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
	 * 追加语音识别的 token 长度到使用量统计中。
	 *
	 * @param length 语音 token 长度
	 */
	public void appendVoiceToken(int length) {
		data.usage.appendVoiceTokens(length);
	}

	/**
	 * 提供初始提示（例如在会话开始时由 AI 应用触发）。 通过 {@link #commandExec} 提交任务以确保顺序执行。
	 */
	public void provideInitialHint() {
		getCommandExec().submit(() -> getAiapp().provideInitial(this));
	}

	/**
	 * 处理用户语音输入的消息。 通过 {@link #commandExec} 提交任务以确保顺序执行。
	 *
	 * @param message 语音识别后的文本消息
	 */
	public void handleUserSpeech(String message) {
		getCommandExec().submit(() -> getAiapp().handleSpeech(this, message));
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
	public ExtraData getData() {
		return data;
	}

	public void onLoad() {
		getCommandExec().submit(()->this.aiapp.onload(this));
	}
}