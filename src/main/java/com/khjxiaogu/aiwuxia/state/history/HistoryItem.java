package com.khjxiaogu.aiwuxia.state.history;

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

/**
 * 历史条目接口。
 * <p>
 * 表示对话历史中的一条记录，包含角色、显示内容、上下文内容、推理内容、
 * 以及与之关联的音频标识符、应用状态、令牌长度等元数据。
 * 通过 {@link #getPrevIdentifier()} 支持链表式的条目追溯。
 * </p>
 *
 * @see MutableHistoryItem
 * @see HistoryMemoryItem
 */
public interface HistoryItem {

	/**
	 * 获取该历史条目的上下文内容。
	 * <p>
	 * 上下文内容是发送给 LLM 的完整输入，可能包含系统提示或内部状态信息。
	 * </p>
	 *
	 * @return 上下文内容的 {@link MessageContents}，可能为 null
	 */
	MessageContents getContextContent();

	/**
	 * 获取该历史条目的显示内容。
	 * <p>
	 * 显示内容是呈现给用户看的友好格式，可能与实际上下文内容不同。
	 * </p>
	 *
	 * @return 显示内容的字符序列，可能为 null
	 */
	MessageContents getDisplayContent();

	/**
	 * 获取该历史条目关联的角色。
	 *
	 * @return 角色的枚举值 {@link Role}（如用户、助手、系统等）
	 */
	Role getRole();

	/**
	 * 获取该历史条目的唯一标识符。
	 * <p>
	 * 标识符由 {@link HistoryHolder} 实现自动分配，用于在容器中定位特定条目。
	 * </p>
	 *
	 * @return 整型标识符
	 */
	int getIdentifier();

	/**
	 * 获取该条目的推理内容。
	 * <p>
	 * 推理内容是 AI 模型在生成回复前的思考或分析文本（如 chain-of-thought）。
	 * </p>
	 *
	 * @return 推理内容的 {@link MessageContents}，可能为 null
	 */
	MessageContents getReasoningContent();

	/**
	 * 判断该条目是否为有效的上下文内容。
	 * <p>
	 * 无效的上下文条目在发送给 LLM 时会被过滤掉。
	 * </p>
	 *
	 * @return {@code true} 如果可作为有效上下文；{@code false} 否则
	 */
	boolean isValidContext();

	/**
	 * 判断该条目是否已删除。
	 * <p>
	 * 已删除的条目通常不会被迭代器返回，但仍保留在容器中以供追溯。
	 * </p>
	 *
	 * @return {@code true} 如果已删除；{@code false} 否则
	 */
	boolean isDeleted();

	/**
	 * 获取与该条目关联的音频标识符。
	 * <p>
	 * 如果条目包含语音信息，该 ID 可用于检索对应的音频数据。
	 * </p>
	 *
	 * @return 音频 ID 字符串，可能为 null
	 */
	String getAudioId();

	/**
	 * 获取该条目最后一次关联的应用状态。
	 * <p>
	 * 应用状态可能包含界面状态、会话上下文等快照信息。
	 * </p>
	 *
	 * @return {@link ApplicationState} 对象，可能为 null
	 */
	ApplicationState getLastState();

	/**
	 * 获取前一条目的标识符。
	 * <p>
	 * 用于维护条目之间的链表关系，-1 表示无前驱（即该条目为容器中的第一条）。
	 * </p>
	 *
	 * @return 前一条目的标识符，-1 表示无前驱
	 */
	int getPrevIdentifier();

	/**
	 * 获取该条目的令牌长度。
	 * <p>
	 * 用于上下文窗口管理，记录该条目所消耗的令牌数量。
	 * </p>
	 *
	 * @return 令牌长度
	 */
	long getTokenLength();

	/**
	 * 判断是否允许将推理内容发送给客户端。
	 *
	 * @return {@code true} 如果允许发送推理内容；{@code false} 否则
	 */
	boolean maySendReasoner();

}
