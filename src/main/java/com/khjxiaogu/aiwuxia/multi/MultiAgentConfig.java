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
package com.khjxiaogu.aiwuxia.multi;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * 多 agent 配置（{@code save/config/multi.json}）。
 * <p>
 * 每个公屏/私聊会话都是一个配置对象，像 {@code agents.json} 的一项：可单独配置
 * {@code tools}（默认 + 额外）、{@code codex}、{@code enabled}、审核人、提示词文件。
 * </p>
 * <pre>{@code
 * {
 *   "enabled": true,
 *   "botId": "123456",
 *   "app": "frostedheartirc",
 *   "promptDir": "multi-prompts",
 *   "defaultTools": ["fetch", "multimodal", "qq", "knowledge"],
 *   "defaultCodex": { "sandbox": "workspace-write" },
 *   "publicSessions": [
 *     {
 *       "groupId": "789012",
 *       "enabled": true,
 *       "adminId": ["345678"],
 *       "extraTools": ["codex"],
 *       "codex": { "cwd": "D:\\projects\\project" },
 *       "docLibrary": { "id": "default", "write": true, "listen": ["*"], "wakeOnNotify": true },
 *       "ticket": { "libraryId": "tickets", "wakeOnNotify": true }
 *     }
 *   ],
 *   "privateSessions": [
 *     {
 *       "userId": "345678",
 *       "enabled": true,
 *       "extraTools": ["codex"],
 *       "codex": { "cwd": "D:\\projects\\project-user" },
 *       "promptFile": "users/345678.txt"
 *     }
 *   ]
 * }
 * }</pre>
 */
public final class MultiAgentConfig {

    /** 一个公屏/私聊会话的配置。 */
    public static final class Session {
        /** 公屏会话为群 id，私人会话为用户 id。 */
        public final String id;
        public final boolean publicSession;
        public final boolean enabled;
        public final String name;
        public final String save;
        public final List<String> extraTools;
        public final JsonObject codex;
        /** 该会话的 doclib 配置：id / write / listen / wakeOnNotify。 */
        public final JsonObject docLibrary;
        /** 该会话的工单配置：libraryId / wakeOnNotify。 */
        public final JsonObject ticket;
        public final List<String> adminIds;
        /** 除自身群外，还要接收哪些群的消息（公屏会话的自身群始终包含）。 */
        public final List<String> contextGroups;
        /** 可移交/传递信息的 agent 名称；`*` 表示任意可接手 agent。 */
        public final List<String> handoverTargets;
        /** 是否只有被 @ 才触发回复（其他消息只记录，不触发）。 */
        public final boolean mentionOnly;
        /** 会话专属提示词文件（相对 promptDir）；为空时用默认。 */
        public final String promptFile;
        /** promptFile 的内容；没配置时为空串。 */
        public final String prompt;

        Session(String id, boolean publicSession, boolean enabled, String name, String save,
                List<String> extraTools, JsonObject codex, JsonObject docLibrary, JsonObject ticket,
                List<String> adminIds, List<String> contextGroups, List<String> handoverTargets,
                boolean mentionOnly, String promptFile, String prompt) {
            this.id = id;
            this.publicSession = publicSession;
            this.enabled = enabled;
            this.name = name;
            this.save = save;
            this.extraTools = extraTools;
            this.codex = codex;
            this.docLibrary = docLibrary;
            this.ticket = ticket;
            this.adminIds = adminIds;
            this.contextGroups = contextGroups;
            this.handoverTargets = handoverTargets;
            this.mentionOnly = mentionOnly;
            this.promptFile = promptFile;
            this.prompt = prompt;
        }
    }

    /** 机器人自身 id。 */
    public final String botId;
    /** 复用现有 app 目录名（save/apps/<app>）。 */
    public final String app;
    /** 默认工具（每个会话都会带上）。 */
    public final List<String> defaultTools;
    /** 是否在收到未配置用户的私聊时自动创建私人会话。 */
    public final boolean autoCreatePrivate;
    /** 默认 Codex 配置，会话的 codex 覆盖同名项。 */
    public final JsonObject defaultCodex;
    /** 公屏会话。 */
    public final List<Session> publicSessions;
    /** 私聊会话。 */
    public final List<Session> privateSessions;
    /** 提示词目录（save/config/<promptDir>）。 */
    public final File promptFolder;
    /** common.txt 内容。 */
    public final String commonPrompt;
    /** public.txt 内容（会话没配 promptFile 时用）。 */
    public final String publicPrompt;
    /** default.txt 内容（私聊会话没配专属文件时用）。 */
    public final String defaultPrivatePrompt;
    /** NapCat WebSocket 地址；为空时用 -Dnapcat_url。 */
    public final String napcatUrl;
    /** NapCat token；为空时用 -Dnapcat_token。 */
    public final String napcatToken;
    /** QQ 官方机器人 AppID；配置后走官方 API，不再用 NapCat。 */
    public final String qqbotAppId;
    /** QQ 官方机器人 Secret。 */
    public final String qqbotSecret;
    /** 是否启用 MultiAgent 自带的 MCP 服务端。 */
    public final boolean mcpEnabled;
    /** MCP 服务端监听端口（默认 8996）。 */
    public final int mcpPort;
    /** MCP 服务端路径（默认 {@code /mcp}）。 */
    public final String mcpPath;
    /** 对外公开的 MCP 地址；为空时按 {@code site.json} + 端口推导。 */
    public final String mcpPublicUrl;

    private MultiAgentConfig(JsonObject json, File dataFolder) {
        this.botId = JsonConfig.getNonBlankString(json, "botId", "");
        this.app = JsonConfig.getNonBlankString(json, "app", "group");
        this.defaultTools = JsonConfig.getStringList(json, "defaultTools");
        this.autoCreatePrivate = json.has("autoCreatePrivate") && !json.get("autoCreatePrivate").isJsonNull()
                && json.get("autoCreatePrivate").isJsonPrimitive()
                && json.get("autoCreatePrivate").getAsBoolean();
        this.defaultCodex = object(json, "defaultCodex");
        String promptDir = JsonConfig.getNonBlankString(json, "promptDir", "multi-prompts");
        this.promptFolder = new File(new File(dataFolder, "config"), promptDir);
        this.commonPrompt = firstNonEmpty(readPrompt("common.txt"), "");
        this.publicPrompt = firstNonEmpty(readPrompt("public.txt"), "");
        this.defaultPrivatePrompt = firstNonEmpty(readPrompt("default.txt"),
                readPrompt("default_private.txt"), "");
        this.publicSessions = readSessions(json, "publicSessions", true);
        this.privateSessions = readSessions(json, "privateSessions", false);
        this.napcatUrl = JsonConfig.getNonBlankString(json, "napcatUrl", System.getProperty("napcat_url", ""));
        this.napcatToken = JsonConfig.getNonBlankString(json, "napcatToken", System.getProperty("napcat_token", ""));
        this.qqbotAppId = JsonConfig.getNonBlankString(json, "qqbotAppId", System.getProperty("qqbot_appid", ""));
        this.qqbotSecret = JsonConfig.getNonBlankString(json, "qqbotSecret", System.getProperty("qqbot_secret", ""));
        JsonObject mcp = JsonConfig.getObjectOrEmpty(json, "mcp");
        this.mcpEnabled = !mcp.has("enabled") || mcp.get("enabled").isJsonNull()
                || mcp.get("enabled").getAsBoolean();
        int port = JsonConfig.getInt(mcp, "port", 8996);
        this.mcpPort = port <= 0 || port > 65535 ? 8996 : port;
        this.mcpPath = normalizeMcpPath(JsonConfig.getNonBlankString(mcp, "path", "/mcp"));
        this.mcpPublicUrl = JsonConfig.getNonBlankString(mcp, "publicUrl", "");
    }

    /** 规范化 MCP 路径：补前导斜杠、去掉末尾斜杠。 */
    private static String normalizeMcpPath(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty()) {
            return "/mcp";
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /** 工具最终列表：默认 + 会话额外，去重保序。 */
    public List<String> toolsFor(Session session) {
        List<String> tools = new ArrayList<>(defaultTools);
        if (session != null) {
            for (String tool : session.extraTools) {
                if (!tools.contains(tool)) {
                    tools.add(tool);
                }
            }
        }
        return tools;
    }

    /** Codex 配置：默认 + 会话覆盖。 */
    public JsonObject codexFor(Session session) {
        JsonObject merged = defaultCodex == null ? new JsonObject() : defaultCodex.deepCopy();
        if (session != null && session.codex != null) {
            for (Map.Entry<String, JsonElement> entry : session.codex.entrySet()) {
                merged.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return merged;
    }

    /** 公屏会话的提示词：会话 promptFile 优先，其次 public.txt。 */
    public String promptFor(Session session) {
        if (session != null && !session.prompt.isEmpty()) {
            return session.prompt;
        }
        return publicPrompt;
    }

    /** 私聊会话的提示词：会话 promptFile > users/<QQ>.txt > default.txt。 */
    public String privatePromptFor(Session session) {
        if (session != null && !session.prompt.isEmpty()) {
            return session.prompt;
        }
        if (session != null) {
            String userPrompt = readPrompt("users/" + session.id + ".txt");
            if (userPrompt != null) {
                return userPrompt;
            }
        }
        return defaultPrivatePrompt;
    }

    /** 配置是否可用。 */
    public boolean isUsable() {
        return !botId.isEmpty() && (!publicSessions.isEmpty() || !privateSessions.isEmpty());
    }

    public static MultiAgentConfig load(File dataFolder) {
        File file = new File(new File(dataFolder, "config"), "multi.json");
        if (!file.isFile()) {
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(FileUtil.readString(file));
            if (parsed == null || !parsed.isJsonObject()) {
                return null;
            }
            JsonObject json = parsed.getAsJsonObject();
            if (!json.has("enabled") || !json.get("enabled").isJsonPrimitive()
                    || !json.get("enabled").getAsBoolean()) {
                return null;
            }
            MultiAgentConfig config = new MultiAgentConfig(json, dataFolder);
            return config.isUsable() ? config : null;
        } catch (Throwable t) {
            System.out.println("[Multi] 配置解析失败：" + t);
            return null;
        }
    }

    private List<Session> readSessions(JsonObject json, String key, boolean publicSession) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return Collections.emptyList();
        }
        List<Session> sessions = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray(key)) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            String id = JsonConfig.getNonBlankString(item, publicSession ? "groupId" : "userId", "");
            if (id.isEmpty()) {
                continue;
            }
            boolean enabled = !item.has("enabled") || item.get("enabled").isJsonNull()
                    || item.get("enabled").getAsBoolean();
            String name = JsonConfig.getNonBlankString(item, "name", "");
            String save = JsonConfig.getNonBlankString(item, "save", "");
            List<String> extraTools = JsonConfig.getStringList(item, "extraTools");
            JsonObject codex = object(item, "codex");
            List<String> admins = JsonConfig.getIds(item, "adminId", null);
            if (admins.isEmpty()) {
                admins = publicSession ? Collections.<String>emptyList() : Collections.singletonList(id);
            }
            String promptFile = JsonConfig.getNonBlankString(item, "promptFile", "");
            String prompt = promptFile.isEmpty() ? "" : firstNonEmpty(readPrompt(promptFile), "");
            List<String> contextGroups = JsonConfig.getStringList(item, "contextGroups");
            List<String> handoverTargets = JsonConfig.getStringList(item, "handoverTargets");
            boolean mentionOnly = item.has("mentionOnly") && !item.get("mentionOnly").isJsonNull()
                    && item.get("mentionOnly").isJsonPrimitive() && item.get("mentionOnly").getAsBoolean();
            sessions.add(new Session(id, publicSession, enabled, name, save, extraTools, codex,
                    object(item, "docLibrary"), object(item, "ticket"), admins, contextGroups,
                    handoverTargets, mentionOnly, promptFile, prompt));
        }
        return Collections.unmodifiableList(sessions);
    }

    // ==================== 提示词/配置读取 ====================

    private String readPrompt(String relative) {
        File file = new File(promptFolder, relative);
        if (!file.isFile()) {
            return null;
        }
        try {
            String text = FileUtil.readString(file).replace("\r", "").trim();
            return text.isEmpty() ? null : text;
        } catch (Throwable t) {
            System.out.println("[Multi] 读取提示词失败：" + file + "，" + t);
            return null;
        }
    }

    private static JsonObject object(JsonObject json, String key) {
        return JsonConfig.getObjectOrEmpty(json, key).deepCopy();
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }
}
