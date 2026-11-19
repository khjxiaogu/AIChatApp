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
package com.khjxiaogu.aiwuxia.codex;

/**
 * Codex 审批的「对话 AI 预审」回调。
 * <p>
 * 与 {@link CodexApprovalBridge} 的分工：
 * </p>
 * <ul>
 *   <li>本接口把审批请求交给<b>当前对话的 AI</b>（QQ agent），由它核对命令/路径后调用
 *       {@code codex_review_approval} 决定放行、拒绝还是转人工；</li>
 *   <li>{@link CodexApprovalBridge} 只在 AI 标记 {@code ask_user} 或超时后，才把请求交给管理员。</li>
 * </ul>
 * <p>
 * 实现方通常把 {@link Notice} 包装成一条消息注入会话并触发一轮 AI；不要在这里阻塞等待结果。
 * </p>
 */
public interface CodexReviewHandler {

    /** 一条等待对话 AI 预审的审批请求。 */
    final class Notice {
        /** 预审任务 id，回答 {@code codex_review_approval} 时回传。 */
        public final String taskId;
        /** 线程归属键（例如 {@code qq:botId:groupId}）。 */
        public final String owner;
        /** Codex 线程 id。 */
        public final String threadId;
        /** 类型：command / file。 */
        public final String kind;
        /** 标题（命令行或文件列表）。 */
        public final String title;
        /** 细节（工作目录、原因、网络目标等）。 */
        public final String detail;
        /** 负责最终人工审批的管理员账号 id（QQ 号 / openid），空串表示没有。 */
        public final String adminId;

        public Notice(String taskId, String owner, String threadId, String kind, String title,
                String detail, String adminId) {
            this.taskId = taskId;
            this.owner = owner;
            this.threadId = threadId;
            this.kind = kind;
            this.title = title;
            this.detail = detail;
            this.adminId = adminId == null ? "" : adminId;
        }

        /** 兼容旧调用：数字 QQ 号转字符串。 */
        public Notice(String taskId, String owner, String threadId, String kind, String title,
                String detail, long adminId) {
            this(taskId, owner, threadId, kind, title, detail,
                    adminId > 0 ? String.valueOf(adminId) : "");
        }
    }

    /**
     * 请求对话 AI 预审。
     *
     * @param notice 审批摘要
     */
    void onReview(Notice notice);

    /**
     * 审批已有结果（可选）。实现方可以把它写进对话上下文，但不要在这里阻塞。
     *
     * @param notice   审批摘要
     * @param decision 决定：accept / acceptForSession / decline / cancel
     * @param note     结果来源：agent / qq / timeout / no-admin 等
     */
    default void onResolved(Notice notice, String decision, String note) {
    }
}
