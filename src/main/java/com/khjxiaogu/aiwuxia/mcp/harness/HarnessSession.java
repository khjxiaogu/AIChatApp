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
package com.khjxiaogu.aiwuxia.mcp.harness;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * 一个由 MCP 服务端管理的 DeepSeek Harness 无头实例。
 * <p>
 * 启动方式参考 {@link com.khjxiaogu.aiwuxia.DeepseekHarnessConnector}：
 * 用 {@code --profile web --no-open --port 0} 拉起 DSH，再通过 {@code --patch}
 * 注入 {@code harness-mcp-bridge.mjs}。插件与 Java 之间走
 * {@link HarnessBridge} 的本机回环 TCP，不占用 DSH 的 stdout 协议通道。
 * </p>
 * <p>
 * 进程、心跳和 patch 文件都由本类管理；MCP 工具只通过 {@link #getBridge()} 发请求。
 * 关闭 JVM 时会先请求 DSH 正常退出，再兜底强杀，避免留下孤儿进程。
 * </p>
 */
public class HarnessSession implements Closeable {

    /** 启动/等待插件的总超时。 */
    private static final long START_TIMEOUT_MS = 180000L;

    private final HarnessMcpConfig config;
    private final HarnessBridge bridge;
    private final File heartbeatFile;
    private final ScheduledExecutorService heartbeat;
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final Object startLock = new Object();

    private volatile Process process;
    private volatile String lastError = "";
    private volatile boolean shutdownHookInstalled;
    private final AtomicBoolean heartbeatStarted = new AtomicBoolean(false);

    /** 实际使用的启动器与 CLI 路径。 */
    private String launcherPath;
    private String cliPath;
    /** 解析出的安装根目录。 */
    private String resolvedRoot;

    public HarnessSession(HarnessMcpConfig config, HarnessBridge bridge) {
        this.config = config;
        this.bridge = bridge;
        config.dataFolder.mkdirs();
        this.heartbeatFile = new File(config.dataFolder, "harness-heartbeat");
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "harness-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 插件桥。 */
    public HarnessBridge getBridge() {
        return bridge;
    }

    /** DSH 进程是否存活。 */
    public boolean isProcessAlive() {
        Process current = process;
        return current != null && current.isAlive();
    }

    /** 插件是否已连接。 */
    public boolean isReady() {
        return bridge.isConnected();
    }

    /** 最近一次启动错误。 */
    public String getLastError() {
        return lastError;
    }

    /** 启动心跳线程与关闭钩子（幂等）。 */
    public void start() {
        if (!shutdownHookInstalled) {
            shutdownHookInstalled = true;
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "harness-shutdown"));
            } catch (IllegalStateException ignored) {
                // JVM 已在关闭中
            }
        }
        if (config.watchdog && heartbeatStarted.compareAndSet(false, true)) {
            heartbeat.scheduleWithFixedDelay(this::touchHeartbeat, 0, config.watchdogIntervalMs,
                    TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 确保 DSH 与插件都已就绪；未启动或断开时自动重启。
     *
     * @throws IOException 启动失败或超时
     */
    public void ensureReady() throws IOException {
        if (bridge.isConnected() && isProcessAlive()) {
            return;
        }
        synchronized (startLock) {
            if (bridge.isConnected() && isProcessAlive()) {
                return;
            }
            if (closing.get()) {
                throw new IOException("Harness 会话正在关闭");
            }
            stopProcess();
            startProcess();
            waitForPlugin();
        }
    }

    /** 轮询等待插件握手。 */
    private void waitForPlugin() throws IOException {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (bridge.isConnected()) {
                return;
            }
            Process current = process;
            if (current != null && !current.isAlive()) {
                throw new IOException("DeepSeek Harness 启动后立即退出：" + lastError);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("等待 DeepSeek Harness 启动被中断");
            }
        }
        throw new IOException("DeepSeek Harness 启动超时（插件未连接）：" + lastError);
    }

    /** 刷新心跳文件，供 DSH 侧看门狗判断 Java 端是否还活着。 */
    private void touchHeartbeat() {
        try {
            FileUtil.transfer(String.valueOf(System.currentTimeMillis()), heartbeatFile);
        } catch (Throwable t) {
            // 心跳写失败不致命
        }
    }

    /** 解析启动器与 CLI；两者都允许被系统属性覆盖。 */
    private void resolvePaths() throws IOException {
        String root = config.dshRoot;
        if (root == null || root.trim().isEmpty()) {
            File candidate = new File("C:/Users/" + System.getProperty("user.name", "")
                    + "/AppData/Local/Programs/DeepSeek Harness");
            if (candidate.exists()) {
                root = candidate.getPath();
            }
        }
        if (root == null || root.trim().isEmpty()) {
            throw new IOException("未找到 DeepSeek Harness 安装目录，请用 -Ddsh_root=... 指定");
        }
        resolvedRoot = root;
        File rootDir = new File(root);
        File candidateLauncher = config.launcher != null && !config.launcher.trim().isEmpty()
                ? new File(config.launcher) : new File(rootDir, "DeepSeek Harness.exe");
        File candidateCli = config.cli != null && !config.cli.trim().isEmpty()
                ? new File(config.cli)
                : new File(rootDir,
                        "resources/app.asar/dsh/node_modules/@deepseek-ai/dsh-desktop-host/lib/cli.js");
        if (candidateLauncher.exists()) {
            launcherPath = candidateLauncher.getPath();
            cliPath = candidateCli.getPath();
            return;
        }
        // 退化方案：随包提供的 dsh.cmd
        File candidateCmd = new File(rootDir, "resources/runtime/cli/bin/dsh.cmd");
        if (candidateCmd.exists()) {
            launcherPath = candidateCmd.getPath();
            cliPath = null;
            return;
        }
        throw new IOException("无法找到 DeepSeek Harness 启动器，请用 -Ddsh_launcher=... 指定");
    }

    /** 拉起 DSH 进程。 */
    private void startProcess() throws IOException {
        if (launcherPath == null) {
            resolvePaths();
        }
        File patch = preparePatch();
        startProcessAttempt(launcherPath, cliPath, patch);
        // 如果进程在 1.5 秒内退出，尝试 dsh.cmd 兜底
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Process current = process;
        if (current != null && current.isAlive()) {
            return;
        }
        File fallback = new File(new File(resolvedRoot == null ? "." : resolvedRoot,
                "resources/runtime/cli/bin/dsh.cmd").getPath());
        if (fallback.isFile() && (launcherPath == null || !launcherPath.endsWith("dsh.cmd"))) {
            startProcessAttempt(fallback.getPath(), null, patch);
        }
    }

    private void startProcessAttempt(String launcher, String cli, File patch) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(launcher);
        if (cli != null) {
            command.add("--expose-internals");
            command.add(cli);
        }
        command.add("--profile");
        command.add(config.profile);
        if (patch != null) {
            command.add("--patch");
            command.add(patch.getAbsolutePath());
        }
        if ("web".equals(config.profile)) {
            command.add("--port");
            command.add(String.valueOf(config.webPort));
            if (!config.openBrowser) {
                command.add("--no-open");
            }
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        pb.environment().put("ELECTRON_RUN_AS_NODE", "1");
        pb.environment().put("DSH_HOME", config.dshHome);
        pb.directory(primaryWorkspace());
        lastError = "";
        Process startedProcess = pb.start();
        process = startedProcess;
        Thread out = new Thread(() -> drain(startedProcess.getInputStream(), false), "harness-dsh-out");
        out.setDaemon(true);
        out.start();
        Thread err = new Thread(() -> drain(startedProcess.getErrorStream(), true), "harness-dsh-err");
        err.setDaemon(true);
        err.start();
        System.out.println("[Harness MCP] 已启动 DeepSeek Harness：" + config.model + "@" + config.provider
                + "，工作目录 " + primaryWorkspace().getPath());
    }

    /** 读取 DSH 输出；只保留最后一段用于报错，不阻塞管道。 */
    private void drain(InputStream stream, boolean error) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                if (error || line.contains("error") || line.contains("Error") || line.contains("失败")) {
                    lastError = line;
                    System.out.println("[Harness MCP][dsh] " + line);
                }
            }
        } catch (IOException ignored) {
            // 进程退出时流关闭属正常现象
        }
    }

    /** 第一个配置的工作区，作为 DSH 进程的启动目录。 */
    private File primaryWorkspace() {
        for (File workspace : config.workspaces) {
            if (workspace != null && workspace.isDirectory()) {
                return workspace;
            }
        }
        File fallback = config.workspaces.isEmpty() ? new File(".") : config.workspaces.get(0);
        fallback.mkdirs();
        return fallback;
    }

    /** 生成 patch：把插件插入 web 表层。 */
    private File preparePatch() throws IOException {
        File plugin = extractPlugin("harness-mcp-bridge.mjs", "/dsh/harness-mcp-bridge.mjs");
        StringBuilder yml = new StringBuilder();
        yml.append("# 由 Harness MCP 自动生成，每次启动都会覆盖，请勿手工修改\n")
                .append("- insert:\n")
                .append("    - id: harness-mcp-bridge\n")
                .append("      name: '").append(fileUrl(plugin)).append("'\n")
                .append("      config:\n")
                .append("        host: 127.0.0.1\n")
                .append("        port: ").append(bridge.getPort()).append('\n')
                .append("        token: '").append(bridge.getToken()).append("'\n")
                .append("        approvalMode: '").append(config.approval).append("'\n")
                .append("        approvalTimeoutMs: ").append(config.approvalTimeoutMs).append('\n')
                .append("        questionTimeoutMs: ").append(config.questionTimeoutMs).append('\n')
                .append("        agentPreset: '").append(config.agentPreset).append("'\n")
                .append("        provider: '").append(config.provider).append("'\n")
                .append("        model: '").append(config.model).append("'\n")
                .append("        reasoning: '").append(config.reasoning).append("'\n")
                .append("        watchdog: ").append(config.watchdog).append('\n')
                .append("        heartbeatFile: '").append(pathOf(heartbeatFile)).append("'\n")
                .append("        watchdogIntervalMs: ").append(config.watchdogIntervalMs).append('\n')
                .append("        heartbeatMaxAgeMs: ").append(config.heartbeatMaxAgeMs).append('\n');
        File patch = new File(config.dataFolder, "harness-mcp-patch.yml");
        FileUtil.transfer(yml.toString(), patch);
        return patch;
    }

    /** 把插件源码落到数据目录。 */
    private File extractPlugin(String fileName, String resource) throws IOException {
        File target = new File(config.dataFolder, fileName);
        try (InputStream in = HarnessSession.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("缺少资源 " + resource);
            }
            FileUtil.transfer(in, target);
        }
        return target;
    }

    private static String fileUrl(File file) {
        return "file:///" + pathOf(file);
    }

    private static String pathOf(File file) {
        return file.getAbsolutePath().replace('\\', '/');
    }

    /** 停止 DSH 进程。 */
    public void stop() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        heartbeat.shutdownNow();
        bridge.close();
        stopProcess();
    }

    private void stopProcess() {
        Process current = process;
        process = null;
        if (current == null || !current.isAlive()) {
            return;
        }
        try {
            Writer writer = new OutputStreamWriter(current.getOutputStream(), StandardCharsets.UTF_8);
            // SDK 入口从 stdin 读 JSON-RPC；发 shutdown 让运行时自己收尾
            writer.write("{\"jsonrpc\":\"2.0\",\"id\":\"mcp-shutdown\",\"method\":\"shutdown\",\"params\":{}}\n");
            writer.flush();
        } catch (IOException ignored) {
            // 进程可能已退出
        }
        current.destroy();
        try {
            if (!current.waitFor(3, TimeUnit.SECONDS)) {
                current.destroyForcibly();
                current.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            current.destroyForcibly();
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            current.destroyForcibly();
        }
        System.out.println("[Harness MCP] DeepSeek Harness 已停止");
    }

    @Override
    public void close() {
        stop();
    }
}
