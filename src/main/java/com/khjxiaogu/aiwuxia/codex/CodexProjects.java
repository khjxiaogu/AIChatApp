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

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 把「Codex 桌面端里的项目名」解析成项目 id。
 * <p>
 * Codex 的项目（Project）保存在 {@code CODEX_HOME} 下的状态库里（{@code state_*.sqlite} 的
 * {@code projects} 表）。app-server 有 {@code thread/start.projectId} 可以挂项目，但只接受 id，
 * 所以这里做一次只读查询：按名字（忽略大小写）找到 id；配置里直接写 UUID 时原样返回。
 * </p>
 * <p>
 * 这是对 Codex 内部存储的<b>只读</b>依赖：库不存在、表不存在、被锁住时都只记一条日志并返回 null，
 * 线程照常创建（只是不带项目）。升级 Codex 后若表结构变了，也只是退化成不挂项目。
 * </p>
 */
final class CodexProjects {

    private static volatile String cachedKey;
    private static volatile String cachedId;
    private static volatile String lastFailedKey;

    private CodexProjects() {
    }

    /**
     * 解析配置里的项目。
     *
     * @param config 配置
     * @return 项目 id；未配置或解析失败返回 null
     */
    static String resolve(CodexConfig config) {
        return config == null ? null : resolve(config, config.project, config.projectId);
    }

    /**
     * 按显式的项目名/id 解析；供多 agent 每个用户不同项目时使用。
     *
     * @param config    配置（用于 CODEX_HOME）
     * @param project   项目名，可为空
     * @param projectId 项目 id，可为空
     * @return 项目 id；未配置或解析失败返回 null
     */
    static String resolve(CodexConfig config, String project, String projectId) {
        if (config == null) {
            return null;
        }
        // 显式 id 优先：不查库，项目改名/状态库被锁也能用
        if (projectId != null && !projectId.trim().isEmpty()) {
            return projectId.trim();
        }
        if (project == null || project.trim().isEmpty()) {
            return null;
        }
        String wanted = project.trim();
        if (isUuid(wanted)) {
            return wanted;
        }
        File home = home(config);
        String key = home.getPath() + "|" + wanted.toLowerCase();
        if (key.equals(cachedKey)) {
            return cachedId;
        }
        String id = lookup(home, wanted);
        if (id != null) {
            cachedKey = key;
            cachedId = id;
            lastFailedKey = null;
            System.out.println("[Codex] 已匹配 Codex 项目「" + wanted + "」：" + id);
        } else if (!key.equals(lastFailedKey)) {
            // 失败只提示一次，且不缓存失败结果：Codex 重启/解锁后下次还能再试
            lastFailedKey = key;
            System.out.println("[Codex] 没有在 " + home.getPath() + " 里找到项目「" + wanted
                    + "」；将不挂项目（可用 -Dcodex_project=<项目id> 指定）");
        }
        return id;
    }

    /** CODEX_HOME：配置优先，其次用户默认。 */
    private static File home(CodexConfig config) {
        File configured = config.homeFolder();
        if (configured != null) {
            return configured;
        }
        return new File(System.getProperty("user.home", "."), ".codex");
    }

    private static String lookup(File home, String name) {
        File[] files = home.listFiles((dir, fileName) ->
                fileName.startsWith("state") && fileName.endsWith(".sqlite"));
        if (files == null || files.length == 0) {
            return null;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (File file : files) {
            String id = query(file, name);
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    private static String query(File database, String name) {
        String url = "jdbc:sqlite:" + database.getAbsolutePath();
        String sql = "select id from projects where lower(name) = lower(?) limit 1";
        // 先按只读打开，避免在 Codex 正在用的状态库上产生任何写操作
        try {
            org.sqlite.SQLiteConfig readOnly = new org.sqlite.SQLiteConfig();
            readOnly.setReadOnly(true);
            readOnly.setBusyTimeout(3000);
            try (Connection connection = readOnly.createConnection(url);
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        return result.getString(1);
                    }
                }
            }
            return null;
        } catch (Throwable readOnlyFailure) {
            // WAL 库在只读模式下可能打不开（拿不到 shm），退一步用普通连接；仍然只执行 SELECT
            try (Connection connection = DriverManager.getConnection(url);
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, name);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        return result.getString(1);
                    }
                }
            } catch (Throwable t) {
                System.out.println("[Codex] 读取项目库失败（" + database.getName() + "）：" + t.getMessage());
            }
        }
        return null;
    }

    /** 是否是 UUID 形态的项目 id。 */
    private static boolean isUuid(String value) {
        return value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }
}
