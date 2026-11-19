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

import java.util.List;

/**
 * Codex 审批桥：把 Codex 的审批请求送到人面前（QQ 私聊、控制台、别的 UI）。
 * <p>
 * 与 {@code DeepseekHarnessConnector} 不同，Codex 的审批是<b>协议原生</b>的服务端请求
 * （{@code item/commandExecution/requestApproval} / {@code item/fileChange/requestApproval}），
 * 不需要插件，也不需要本机回环 TCP。本接口只负责「问」和「通知结果」，
 * 超时与回包由 {@link CodexService} 统一处理。
 * </p>
 */
public interface CodexApprovalBridge {

    /** 一次审批请求的可读摘要。 */
    final class Notice {
        /** 任务 id，答复时用。 */
        public final String taskId;
        /** 线程 id。 */
        public final String threadId;
        /** 轮次 id。 */
        public final String turnId;
        /** 类型：command / file。 */
        public final String kind;
        /** 标题（命令行或文件列表）。 */
        public final String title;
        /** 细节（工作目录、原因、网络目标等）。 */
        public final String detail;
        /** 负责审批的管理员账号 id（QQ 号 / openid），空串表示没有。 */
        public final String adminId;
        /** 全部负责审批的管理员；第一个与 adminId 相同。 */
        public final List<String> adminIds;
        /** 可选决定，例如 accept/acceptForSession/decline/cancel。 */
        public final List<String> options;

        public Notice(String taskId, String threadId, String turnId, String kind, String title,
                String detail, String adminId, List<String> options) {
            this(taskId, threadId, turnId, kind, title, detail,
                    adminId == null || adminId.isEmpty() ? java.util.Collections.<String>emptyList()
                            : java.util.Collections.singletonList(adminId),
                    options);
        }

        /** 兼容旧调用：数字 QQ 号转字符串。 */
        public Notice(String taskId, String threadId, String turnId, String kind, String title,
                String detail, long adminId, List<String> options) {
            this(taskId, threadId, turnId, kind, title, detail,
                    adminId > 0 ? String.valueOf(adminId) : "", options);
        }

        public Notice(String taskId, String threadId, String turnId, String kind, String title,
                String detail, List<String> adminIds, List<String> options) {
            this.taskId = taskId;
            this.threadId = threadId;
            this.turnId = turnId;
            this.kind = kind;
            this.title = title;
            this.detail = detail;
            this.adminIds = adminIds == null ? java.util.Collections.<String>emptyList()
                    : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(adminIds));
            String first = "";
            for (String id : this.adminIds) {
                if (id != null && !id.isEmpty()) {
                    first = id;
                    break;
                }
            }
            this.adminId = first;
            this.options = options;
        }
    }

    /**
     * 请求审批。实现方应尽快返回，不要阻塞协议读线程。
     *
     * @param notice 审批摘要
     */
    void onAsked(Notice notice);

    /**
     * 审批已有结果（用户答复、超时或被服务端清除）。
     *
     * @param notice   审批摘要
     * @param decision 最终决定
     * @param note     补充说明，可为空
     */
    default void onResolved(Notice notice, String decision, String note) {
    }
}
