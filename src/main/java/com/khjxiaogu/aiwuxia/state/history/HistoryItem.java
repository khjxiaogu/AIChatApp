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
package com.khjxiaogu.aiwuxia.state.history;

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

/**
 * 表示一个上下文条目，通常用于存储和管理对话历史中的单个条目。
 * 该条目可以包含显示内容、上下文内容、推理内容以及与对话相关的元数据，
 * 如角色、可发送状态、音频标识符等。
 */
public interface HistoryItem {

    /**
     * 获取该历史条目的上下文内容。
     * 上下文内容通常是与对话模型相关的完整输入，可能包含系统提示或内部状态信息。
     *
     * @return 上下文内容的字符序列，可能为 null 或空
     */
    CharSequence getContextContent();

    /**
     * 获取该历史条目的显示内容。
     * 显示内容是呈现给用户看的友好格式，可能与实际上下文内容不同。
     *
     * @return 显示内容的字符序列，可能为 null 或空
     */
    CharSequence getDisplayContent();

    /**
     * 追加一行内容到当前条目，并指定是否同时将其添加到上下文内容中。
     *
     * @param content       要追加的内容字符串
     * @param addToContext  若为 true，则将该内容也追加到上下文内容中
     */
    void appendLine(String content, boolean addToContext);

    /**
     * 追加内容到当前条目，并指定是否同时将其添加到上下文内容中。
     * 与 {@link #appendLine} 的区别在于此方法不会自动添加换行符。
     *
     * @param content       要追加的内容字符串
     * @param addToContext  若为 true，则将该内容也追加到上下文内容中
     */
    void append(String content, boolean addToContext);

    /**
     * 直接将内容追加到上下文内容中，不影响其他显示或推理内容。
     *
     * @param content 要追加到上下文的内容字符串
     */
    void appendContext(String content);

    /**
     * 设置完整的上下文内容，替换现有的上下文内容。
     *
     * @param fullContent 新的完整上下文内容字符串
     */
    void setContextContent(String fullContent);

    /**
     * 追加推理器相关的内容。
     * 该方法可能用于记录 AI 模型的推理过程或中间结果。
     *
     * @param fullContent 要追加的推理器内容字符串
     */
    void appendReasoner(String fullContent);

    /**
     * 获取该历史条目关联的角色（例如用户、助手、系统等）。
     *
     * @return 角色的枚举值 {@link Role}
     */
    Role getRole();

    /**
     * 获取该历史条目的唯一标识符。
     *
     * @return 整型标识符
     */
    int getIdentifier();

    /**
     * 获取该条目的推理内容，即 AI 模型生成的思考或分析文本。
     *
     * @return 推理内容的字符串，可能为 null 或空
     */
    String getReasoningContent();

    /**
     * 判断该条目是否可作为有效的上下文内容。
     *
     * @return 如果可发送则返回 true，否则返回 false
     */
    boolean isValidContext();

    /**
     * 设置该条目是否可作为有效的上下文内容。
     *
     * @param sendable 新的可发送状态
     */
    void setValidContext(boolean sendable);
    /**
     * 判断该条目是否已撤回。
     *
     * @return 如果可发送则返回 true，否则返回 false
     */
    boolean isDeleted();

    /**
     * 设置该条目是否已撤回。
     *
     * @param sendable 新的可发送状态
     */
    void setDeleted(boolean sendable);
    /**
     * 获取与该条目关联的音频标识符。
     * 如果条目包含语音信息，该 ID 可用于检索对应的音频数据。
     *
     * @return 音频 ID 字符串，可能为 null
     */
    String getAudioId();

    /**
     * 设置与该条目关联的音频标识符。
     *
     * @param audioId 新的音频 ID 字符串
     */
    void setAudioId(String audioId);

    /**
     * 获取该条目最后一次关联的应用状态。
     * 应用状态可能包含界面状态、会话上下文等快照信息。
     *
     * @return {@link ApplicationState} 对象，可能为 null
     */
    ApplicationState getLastState();

    /**
     * 设置该条目最后一次关联的应用状态。
     *
     * @param lastState 新的应用状态对象
     */
    void setLastState(ApplicationState lastState);
}