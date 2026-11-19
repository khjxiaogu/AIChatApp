package com.khjxiaogu.aiwuxia.doclib;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库 id -> 运行时单例。配置相同 id 的 agent 共享同一份文档库。
 */
public final class DocLibraryRegistry {

    private static final Map<String, DocLibraryRuntime> RUNTIMES = new ConcurrentHashMap<>();
    private static final Object LOCK = new Object();
    private static boolean shutdownHookInstalled;

    private DocLibraryRegistry() {
    }

    public static DocLibraryRuntime open(File dataFolder, DocLibraryConfig config) {
        synchronized (LOCK) {
            DocLibraryRuntime runtime = RUNTIMES.get(config.id);
            if (runtime != null) {
                try {
                    if (!runtime.root().getCanonicalFile().equals(config.root(dataFolder).getCanonicalFile())) {
                        throw new DocException("DOC_BAD_REQUEST",
                                "库 " + config.id + " 配置了不同目录：" + runtime.root() + " vs "
                                        + config.root(dataFolder));
                    }
                } catch (java.io.IOException ignored) {
                }
                return runtime;
            }
            try {
                runtime = new DocLibraryRuntime(dataFolder, config);
            } catch (Exception ex) {
                throw new DocException("DOC_INTERNAL",
                        "打开文档库 " + config.id + " 失败：" + ex.getMessage(), ex);
            }
            RUNTIMES.put(config.id, runtime);
            installShutdownHook();
            System.out.println("[DocLib] 打开库 id=" + config.id + " dir=" + runtime.root());
            return runtime;
        }
    }

    public static DocLibraryRuntime get(String id) {
        return RUNTIMES.get(id);
    }

    public static void closeAll() {
        synchronized (LOCK) {
            for (DocLibraryRuntime runtime : new ArrayList<>(RUNTIMES.values())) {
                try {
                    runtime.close();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
            RUNTIMES.clear();
        }
    }

    private static void installShutdownHook() {
        if (shutdownHookInstalled) {
            return;
        }
        shutdownHookInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(DocLibraryRegistry::closeAll, "doclib-shutdown"));
        } catch (Throwable t) {
            // 某些受限环境不允许注册 hook，不影响主流程
        }
    }
}
