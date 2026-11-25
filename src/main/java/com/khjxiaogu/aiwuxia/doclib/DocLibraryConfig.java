package com.khjxiaogu.aiwuxia.doclib;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.io.File;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * 一个 agent 侧 doclib 工具配置，以及对应库的默认参数。
 * <p>
 * agent 配置里的 {@code docLibrary} 决定“用哪个库、能不能写、监听什么”；
 * 库级参数（大小、轮次、通知批大小）从 {@code save/config/doclibs.json} 读取。
 * 同一个 id 的多个 agent 共享同一个 {@link DocLibraryRuntime}。
 * </p>
 */
public final class DocLibraryConfig {

    public final String id;
    public final String path;
    public final boolean write;
    public final List<String> listen;
    public final boolean wakeOnNotify;

    public final int maxDocChars;
    public final long maxRoundMs;
    public final int maxWritesPerRound;
    public final long waitTimeoutMs;
    public final int notifyBatchMs;
    public final int maxDocsPerNotice;
    public final int retainRevisions;
    public final List<String> allowedListeners;

    private DocLibraryConfig(String id, String path, boolean write, List<String> listen, boolean wakeOnNotify,
            int maxDocChars, long maxRoundMs, int maxWritesPerRound, long waitTimeoutMs,
            int notifyBatchMs, int maxDocsPerNotice, int retainRevisions, List<String> allowedListeners) {
        this.id = id;
        this.path = path;
        this.write = write;
        this.listen = listen == null ? Collections.<String>emptyList() : listen;
        this.wakeOnNotify = wakeOnNotify;
        this.maxDocChars = maxDocChars;
        this.maxRoundMs = maxRoundMs;
        this.maxWritesPerRound = maxWritesPerRound;
        this.waitTimeoutMs = waitTimeoutMs;
        this.notifyBatchMs = notifyBatchMs;
        this.maxDocsPerNotice = maxDocsPerNotice;
        this.retainRevisions = retainRevisions;
        this.allowedListeners = allowedListeners == null ? Collections.<String>emptyList() : allowedListeners;
    }

    /**
     * 从 agent 配置解析 doclib 配置。
     *
     * @param dataFolder  数据目录（save/）
     * @param agentConfig agent 的 JSON 配置，可为 null
     * @return 配置
     */
    public static DocLibraryConfig forAgent(File dataFolder, JsonObject agentConfig) {
        JsonObject dl = JsonConfig.getObjectOrEmpty(agentConfig, "docLibrary");
        String id = JsonConfig.getString(dl, "id", "default").trim();
        if (id.isEmpty()) {
            id = "default";
        }
        boolean write = JsonConfig.getBool(dl, "write", true);
        List<String> listen = JsonConfig.getStringList(dl, "listen");
        boolean wake = JsonConfig.getBool(dl, "wakeOnNotify", false);
        return build(dataFolder, id, write, listen, wake);
    }

    /**
     * 解析一个“系统库”的配置：不绑定 AI 会话，允许直写。
     * <p>工单系统等基于文档库的扩展用它打开自己的库；库参数同样来自
     * {@code save/config/doclibs.json}，没有配置时使用默认值和默认目录 {@code doclibs/<id>}。</p>
     *
     * @param dataFolder 数据目录（save/）
     * @param libraryId  库 id
     * @return 配置
     */
    public static DocLibraryConfig forLibrary(File dataFolder, String libraryId) {
        return forLibrary(dataFolder, libraryId, Collections.<String>emptyList());
    }

    /**
     * 解析一个“系统库”的配置，并指定系统监听者关心的文档前缀。
     *
     * @param dataFolder 数据目录（save/）
     * @param libraryId  库 id
     * @param listen     监听模式，例如 {@code ["ticket/*"]}
     * @return 配置
     */
    public static DocLibraryConfig forLibrary(File dataFolder, String libraryId, List<String> listen) {
        return forLibrary(dataFolder, libraryId, listen, false);
    }

    /**
     * 解析一个“系统库”的配置，并指定系统监听者关心的文档前缀与是否主动唤醒。
     * <p>工单系统等扩展用它按会话注册监听者：{@code wakeOnNotify} 由调用方从会话配置
     * （{@code ticket.wakeOnNotify}）透传，默认 false 只入队不唤醒。</p>
     *
     * @param dataFolder    数据目录（save/）
     * @param libraryId     库 id
     * @param listen        监听模式，例如 {@code ["ticket/*"]}
     * @param wakeOnNotify  收到通知后是否主动唤醒会话
     * @return 配置
     */
    public static DocLibraryConfig forLibrary(File dataFolder, String libraryId, List<String> listen,
            boolean wakeOnNotify) {
        String id = libraryId == null ? "" : libraryId.trim();
        if (id.isEmpty()) {
            id = "default";
        }
        return build(dataFolder, id, true, listen, wakeOnNotify);
    }

    private static DocLibraryConfig build(File dataFolder, String id, boolean write, List<String> listen,
            boolean wake) {
        JsonObject lib = loadLibrary(dataFolder, id);
        String path = JsonConfig.getString(lib, "path", "doclibs/" + safeName(id));
        int maxDocChars = Math.max(1000, JsonConfig.getInt(lib, "maxDocChars", 40000));
        long maxRoundMs = Math.max(10000L, JsonConfig.getLong(lib, "maxRoundMs", 600000L));
        int maxWrites = Math.max(1, JsonConfig.getInt(lib, "maxWritesPerRound", 32));
        long waitMs = JsonConfig.getLong(lib, "waitTimeoutMs", 10000L);
        if (waitMs <= 0) {
            waitMs = 10000L;
        }
        if (waitMs > 10000L) {
            waitMs = 10000L;
        }
        int batchMs = Math.max(0, JsonConfig.getInt(lib, "notifyBatchMs", 200));
        int maxDocs = Math.max(1, JsonConfig.getInt(lib, "maxDocsPerNotice", 20));
        int retain = Math.max(0, JsonConfig.getInt(lib, "retainRevisions", 50));
        List<String> allowed = JsonConfig.getStringList(lib, "allowedListeners");
        if (allowed.isEmpty()) {
            allowed.add("*");
        }
        return new DocLibraryConfig(id, path, write, listen, wake, maxDocChars, maxRoundMs,
                maxWrites, waitMs, batchMs, maxDocs, retain, allowed);
    }

    /** 库目录。 */
    public File root(File dataFolder) {
        File f = new File(path);
        if (!f.isAbsolute()) {
            f = new File(dataFolder, path);
        }
        return f;
    }

    /** 该 agent 是否被允许监听。 */
    public boolean isListenerAllowed(String agentId) {
        if (allowedListeners.isEmpty() || allowedListeners.contains("*")) {
            return true;
        }
        return allowedListeners.contains(agentId);
    }

    /** 读取库定义；不存在 / 解析失败时返回空对象。 */
    private static JsonObject loadLibrary(File dataFolder, String id) {
        File file = new File(new File(dataFolder, "config"), "doclibs.json");
        if (!file.isFile()) {
            return new JsonObject();
        }
        try {
            JsonObject root = JsonParser.parseString(FileUtil.readString(file)).getAsJsonObject();
            JsonElement libraries = root.get("libraries");
            if (libraries != null && libraries.isJsonObject()) {
                JsonElement item = libraries.getAsJsonObject().get(id);
                if (item != null && item.isJsonObject()) {
                    return item.getAsJsonObject();
                }
            }
        } catch (Throwable t) {
            System.out.println("[DocLib] 读取 " + file + " 失败，使用默认配置：" + t.getMessage());
        }
        return new JsonObject();
    }

    /** 把 id 变成 Windows 安全目录名，并用短 hash 防撞。 */
    public static String safeName(String id) {
        String base = id.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isEmpty()) {
            base = "lib";
        }
        return base + "-" + DocStore.sha256(id).substring(0, 8);
    }
}
