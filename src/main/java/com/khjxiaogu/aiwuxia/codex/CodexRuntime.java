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

import com.khjxiaogu.aiwuxia.mcp.codex.CodexMcp;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;

/**
 * Codex 运行时入口：全局只保留一个 {@link CodexService}（一个 app-server 进程），
 * 供 QQ 连接器、MCP 服务端与 agent 应用共用。
 * <p>
 * 这样做的原因：
 * </p>
 * <ul>
 *   <li>Codex 的线程（会话）可以同时存在很多个，一个进程足够服务所有群；</li>
 *   <li>每个群/每个 MCP 用户用自己的归属键隔离线程，互不串话；</li>
 *   <li>审批桥只需要装一次，按每次审批自带的管理员账号投递。</li>
 * </ul>
 * <p>
 * 线程安全：所有公开方法都是同步的，重复调用只会创建一次服务。
 * </p>
 */
public final class CodexRuntime {

    /** 私聊发送器（QQ 连接器提供）。 */
    @FunctionalInterface
    public interface PrivateTextSender {
        /**
         * 发送一条私聊文本。
         *
         * @param userId 账号 id（QQ 号 / openid）
         * @param text   文本
         * @throws Exception 发送失败
         */
        void send(String userId, String text) throws Exception;

        /**
         * 发送带按钮的审批卡片；默认不支持，调用方退回文本。
         *
         * @param userId   接收者 id
         * @param markdown 卡片正文
         * @param buttons  按钮
         * @return true 表示发送成功
         * @throws Exception 发送失败
         */
        default boolean sendCard(String userId, String markdown,
                java.util.List<com.khjxiaogu.aiwuxia.chat.ChatButton> buttons) throws Exception {
            return false;
        }
    }

    private static volatile CodexService service;
    private static volatile CodexConfig config;
    private static volatile boolean bridgeInstalled;
    /** 对话 AI 预审回调；服务启动前设置也会在创建时应用。 */
    private static volatile CodexReviewHandler reviewHandler;

    private CodexRuntime() {
    }

    /** 设置对话 AI 预审回调（QQ 连接器注入）。 */
    public static synchronized void setReviewHandler(CodexReviewHandler handler) {
        reviewHandler = handler;
        CodexService current = service;
        if (current != null) {
            current.setReviewHandler(handler);
        }
    }

    /**
     * 取得（必要时创建并后台启动）全局服务。
     *
     * @param cfg 配置；null 表示未启用
     * @return 服务；未启用返回 null
     */
    public static synchronized CodexService ensureService(CodexConfig cfg) {
        return ensureService(cfg, null);
    }

    /**
     * 取得（必要时创建并后台启动）全局服务，并绑定对象存储。
     *
     * @param cfg     配置；null 表示未启用
     * @param storage 对象存储；Codex 产出的图片会先存进去换成 id，可为 null
     * @return 服务；未启用返回 null
     */
    public static synchronized CodexService ensureService(CodexConfig cfg, ObjectStorageProvider storage) {
        if (cfg == null) {
            return null;
        }
        if (service == null) {
            config = cfg;
            service = new CodexService(cfg, storage);
            CodexReviewHandler handler = reviewHandler;
            if (handler != null) {
                service.setReviewHandler(handler);
            }
            service.startAsync();
            System.out.println("[Codex] 服务已初始化：cwd=" + cfg.cwd.getPath()
                    + "，sandbox=" + cfg.sandbox + "，approval=" + cfg.approval);
        } else if (storage != null && service.getStorage() == null) {
            service.setStorage(storage);
        }
        return service;
    }

    /** 已创建的服务，可能为 null。 */
    public static CodexService getService() {
        return service;
    }

    /** 当前配置，可能为 null。 */
    public static CodexConfig getConfig() {
        return config;
    }

    /**
     * 为一个归属键创建 MCP 工具集，并（首次调用时）安装 QQ 审批桥。
     *
     * @param owner      归属键，例如 {@code qq:123:456}
     * @param cfg        配置，null 时使用已有配置；都没有则返回 null
     * @param sender     私聊发送器，可为 null（此时审批只能由 agent 用 codex_feedback 处理）
     * @param adminId    该归属键的管理员账号（QQ 号 / openid），空串表示没有
     * @return MCP 工具集；未启用返回 null
     */
    public static synchronized CodexMcp mcpFor(String owner, CodexConfig cfg, PrivateTextSender sender,
            String adminId) {
        return mcpFor(owner, cfg, sender, adminId, false, null);
    }

    /** 兼容旧调用：数字 QQ 号转字符串；0 表示没有。 */
    public static synchronized CodexMcp mcpFor(String owner, CodexConfig cfg, PrivateTextSender sender,
            long adminId) {
        return mcpFor(owner, cfg, sender, adminId > 0 ? String.valueOf(adminId) : "", false, null);
    }

    /**
     * 为一个归属键创建 MCP 工具集，并（首次调用时）安装 QQ 审批桥。
     *
     * @param owner      归属键，例如 {@code qq:123:456}
     * @param cfg        配置，null 时使用已有配置；都没有则返回 null
     * @param sender     私聊发送器，可为 null（此时审批只能由 agent 用 codex_feedback 处理）
     * @param adminId    该归属键的管理员账号（QQ 号 / openid），空串表示没有
     * @param asyncSend  {@code codex_send} 是否异步：投递后立即返回，轮次结束由系统触发 AI
     * @return MCP 工具集；未启用返回 null
     */
    public static synchronized CodexMcp mcpFor(String owner, CodexConfig cfg, PrivateTextSender sender,
            String adminId, boolean asyncSend) {
        return mcpFor(owner, cfg, sender, adminId, asyncSend, null);
    }

    /** 兼容旧调用：数字 QQ 号转字符串；0 表示没有。 */
    public static synchronized CodexMcp mcpFor(String owner, CodexConfig cfg, PrivateTextSender sender,
            long adminId, boolean asyncSend) {
        return mcpFor(owner, cfg, sender, adminId > 0 ? String.valueOf(adminId) : "", asyncSend, null);
    }

    /**
     * 为一个归属键创建 MCP 工具集，并（首次调用时）安装 QQ 审批桥。
     *
     * @param owner      归属键，例如 {@code qq:123:456}
     * @param cfg        配置，null 时使用已有配置；都没有则返回 null
     * @param sender     私聊发送器，可为 null（此时审批只能由 agent 用 codex_feedback 处理）
     * @param adminId    该归属键的管理员账号（QQ 号 / openid），空串表示没有
     * @param asyncSend  {@code codex_send} 是否异步：投递后立即返回，轮次结束由系统触发 AI
     * @param storage    对象存储；Codex 产出的图片先存进去换成 id，可为 null
     * @return MCP 工具集；未启用返回 null
     */
    public static synchronized CodexMcp mcpFor(String owner, CodexConfig cfg, PrivateTextSender sender,
            String adminId, boolean asyncSend, ObjectStorageProvider storage) {
        CodexService current = ensureService(cfg != null ? cfg : config, storage);
        if (current == null) {
            return null;
        }
        current.setOwnerAdmin(owner, adminId);
        if (cfg != null && cfg.cwd != null) {
            // 多 agent：每个用户可以有自己的一套 codex 配置/项目目录
            current.setOwnerCwd(owner, cfg.cwd);
            current.setOwnerProject(owner, cfg.project, cfg.projectId);
        }
        if (sender != null && !bridgeInstalled) {
            current.setApprovalBridge(new QqApprovalBridge(sender));
            bridgeInstalled = true;
        }
        return new CodexMcp(current, owner, asyncSend);
    }

    /** 兼容旧调用：数字 QQ 号转字符串；0 表示没有。 */
    public static synchronized CodexMcp mcpFor(String owner, CodexConfig cfg, PrivateTextSender sender,
            long adminId, boolean asyncSend, ObjectStorageProvider storage) {
        return mcpFor(owner, cfg, sender, adminId > 0 ? String.valueOf(adminId) : "", asyncSend, storage);
    }

    /**
     * 处理管理员私聊里的审批答复。
     *
     * @param botId    收到私聊的机器人账号 id（事件里的 self_id），空串表示不限制
     * @param senderId 发消息的账号 id
     * @param text     正文
     * @return true 表示已被消费
     */
    public static boolean answerApproval(String botId, String senderId, String text) {
        CodexService current = service;
        return current != null && current.answerApproval(botId, senderId, text);
    }

    /** 兼容旧调用：数字账号转字符串。 */
    public static boolean answerApproval(long botId, long senderId, String text) {
        return answerApproval(botId > 0 ? String.valueOf(botId) : "",
                senderId > 0 ? String.valueOf(senderId) : "", text);
    }

    /** 关闭全局服务（JVM 退出时由服务自己的关闭钩子兜底）。 */
    public static synchronized void shutdown() {
        CodexService current = service;
        service = null;
        bridgeInstalled = false;
        if (current != null) {
            current.close();
        }
    }

    /** 把审批请求转成 QQ 私聊的桥。 */
    private static final class QqApprovalBridge implements CodexApprovalBridge {

        private final PrivateTextSender sender;

        QqApprovalBridge(PrivateTextSender sender) {
            this.sender = sender;
        }

        @Override
        public void onAsked(Notice notice) {
            if (sender == null) {
                return;
            }
            java.util.List<String> admins = adminIds(notice);
            if (admins.isEmpty()) {
                return;
            }
            String text = buildAskText(notice, false);
            String markdown = buildAskText(notice, true);
            boolean canUseCard = notice.taskId != null && !notice.taskId.isEmpty();
            for (String admin : admins) {
                boolean cardSent = false;
                if (canUseCard) {
                    try {
                        cardSent = sender.sendCard(admin, markdown, buttons(notice));
                    } catch (Exception ex) {
                        System.out.println("[Codex] 审批卡片发送失败，退回文本：" + admin + "，" + ex);
                    }
                }
                if (cardSent) {
                    continue;
                }
                try {
                    sender.send(admin, text);
                } catch (Exception ex) {
                    System.out.println("[Codex] 审批私聊发送失败：" + admin + "，" + ex);
                }
            }
        }

        private static String buildAskText(Notice notice, boolean card) {
            StringBuilder text = new StringBuilder();
            text.append("【Codex 审批】").append("command".equals(notice.kind) ? "执行命令" : "修改文件").append('\n');
            text.append("内容：").append(notice.title).append('\n');
            if (notice.detail != null && !notice.detail.trim().isEmpty()) {
                text.append(notice.detail.trim()).append('\n');
            }
            if (card) {
                text.append("请点击下方按钮选择；如果按钮不可用，请回复 1/2/3/4。");
            } else {
                text.append("回复：1 允许 / 2 允许并记住本会话 / 3 拒绝 / 4 拒绝并中断\n");
                text.append("（任务 ").append(notice.taskId).append("，第一个回复生效）");
            }
            return text.toString();
        }

        private static java.util.List<com.khjxiaogu.aiwuxia.chat.ChatButton> buttons(Notice notice) {
            return java.util.Arrays.asList(
                    new com.khjxiaogu.aiwuxia.chat.ChatButton("allow", "允许", "已允许",
                            "codex:" + notice.taskId + ":1", 1),
                    new com.khjxiaogu.aiwuxia.chat.ChatButton("allow_session", "本会话允许", "已记住",
                            "codex:" + notice.taskId + ":2", 1),
                    new com.khjxiaogu.aiwuxia.chat.ChatButton("decline", "拒绝", "已拒绝",
                            "codex:" + notice.taskId + ":3", 3),
                    new com.khjxiaogu.aiwuxia.chat.ChatButton("cancel", "拒绝并中断", "已中断",
                            "codex:" + notice.taskId + ":4", 3));
        }

        @Override
        public void onResolved(Notice notice, String decision, String note) {
            if (sender == null) {
                return;
            }
            java.util.List<String> admins = adminIds(notice);
            if (admins.isEmpty()) {
                return;
            }
            String label;
            switch (decision) {
                case "accept":
                    label = "已允许";
                    break;
                case "acceptForSession":
                    label = "已允许（本会话记住）";
                    break;
                case "decline":
                    label = "已拒绝";
                    break;
                case "cancel":
                    label = "已拒绝并中断";
                    break;
                default:
                    label = "已处理（" + decision + "）";
                    break;
            }
            if ("timeout".equals(note)) {
                label = label + "（等待超时，按拒绝处理）";
            }
            for (String admin : admins) {
                try {
                    sender.send(admin, "【Codex 审批】" + label + "：" + notice.title);
                } catch (Exception ex) {
                    System.out.println("[Codex] 审批结果私聊发送失败：" + admin + "，" + ex);
                }
            }
        }

        private static java.util.List<String> adminIds(Notice notice) {
            if (notice.adminIds != null && !notice.adminIds.isEmpty()) {
                return notice.adminIds;
            }
            if (notice.adminId != null && !notice.adminId.isEmpty()) {
                return java.util.Collections.singletonList(notice.adminId);
            }
            return java.util.Collections.emptyList();
        }
    }
}
