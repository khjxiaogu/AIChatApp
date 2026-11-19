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
package com.khjxiaogu.aiwuxia.mcp.knowledge;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * 项目知识库 MCP：在固定目录里按关键字搜文件名/文件内容，并读取 Markdown。
 * <p>
 * 默认范围（相对项目根目录）：
 * </p>
 * <ul>
 *   <li>{@code design/}、{@code docs/}、{@code diary/}、{@code discussion/} 及它们的子文件夹；</li>
 *   <li>项目根目录下的文件（不递归，根目录的子文件夹不算，除非在上面四个目录里）。</li>
 * </ul>
 * <p>
 * 工具：
 * </p>
 * <ul>
 *   <li>{@code kb_search(keyword, max_results)}：搜文件名与文本内容，返回相对路径和片段；</li>
 *   <li>{@code kb_read(path, max_chars)}：读取范围内的 {@code .md} 文件。</li>
 * </ul>
 */
public final class KnowledgeBaseMcp {

    /** 默认递归搜索的目录。 */
    private static final String[] KNOWLEDGE_DIRS = {"design", "docs", "diary", "discussion"};
    /** 跳过的目录名，避免把构建产物/依赖当知识库。 */
    private static final Set<String> SKIP_DIRS = new LinkedHashSet<>(java.util.Arrays.asList(
            ".git", ".svn", ".hg", "node_modules", "build", "target", "out", "bin", "obj",
            ".gradle", ".idea", ".vscode", "dist", "coverage"));
    /** 单个文件参与内容搜索的最大字节数。 */
    private static final long MAX_SEARCH_BYTES = 2L * 1024 * 1024;
    /** 读取 Markdown 的默认/最大字符数。 */
    private static final int DEFAULT_READ_CHARS = 12000;
    private static final int MAX_READ_CHARS = 40000;

    /** 目录列表缓存刷新间隔：5 分钟（按需刷新，避免每次搜索都遍历目录）。 */
    private static final long DIR_CACHE_MILLIS = 5L * 60 * 1000;

    private final File projectRoot;
    /** 私人知识库根目录；null 表示只读项目知识库。 */
    private final File personalRoot;
    /** 缓存的知识库目录：项目根的标准目录 + 一级子文件夹下的标准目录。 */
    private volatile List<File> recursiveDirs = new ArrayList<>();
    private volatile long dirsRefreshedAt;

    private KnowledgeBaseMcp(File projectRoot, File personalRoot) {
        this.projectRoot = projectRoot.getAbsoluteFile();
        this.personalRoot = personalRoot == null ? null : personalRoot.getAbsoluteFile();
        refreshDirs();
    }

    /**
     * 为项目创建知识库工具集。
     *
     * @param projectRoot 项目根目录（通常是 Codex 的 cwd）
     * @return 工具集
     */
    public static ToolProvider forProject(File projectRoot) {
        KnowledgeBaseMcp kb = new KnowledgeBaseMcp(projectRoot, null);
        return ToolProvider.of(kb.searchTool(), kb.readTool());
    }

    /**
     * 为私人 agent 创建可写知识库：项目知识库 + 个人知识库（可搜索/读取/写入）。
     *
     * @param projectRoot  项目根目录
     * @param personalRoot 个人知识库根目录（不存在会自动创建）
     * @return 工具集
     */
    public static ToolProvider forPrivate(File projectRoot, File personalRoot) {
        KnowledgeBaseMcp kb = new KnowledgeBaseMcp(projectRoot, personalRoot);
        if (personalRoot != null) {
            personalRoot.mkdirs();
        }
        return ToolProvider.of(kb.searchTool(), kb.readTool(), kb.writeTool());
    }

    private ToolData searchTool() {
        return new ToolData.Builder("kb_search",
                "在项目知识库里搜索关键字（文件名和文件内容）。默认范围：项目根目录下的文件，"
                        + "根目录及每个一级子文件夹下的 design/docs/diary/discussion 及其子文件夹。"
                        + "多个中英文/同义词用 | 分隔。返回相对路径，可用 kb_read 读取 .md。")
                .putParam("keyword", "要搜索的关键字（不区分大小写）；多个中英文/同义词用 | 分隔，如 天气|weather。", "关键字")
                .putOptionalParam("max_results", "最多返回多少个文件，默认 20，最多 50。", "数量")
                .tool((params, output, manager) -> {
                    JsonObject json = json(params);
                    String keyword = JsonConfig.getNonBlankString(json, "keyword");
                    if (keyword == null) {
                        return completed("缺少关键字 keyword");
                    }
                    int max = JsonConfig.getInt(json, "max_results", 20);
                    return completed(search(keyword, Math.max(1, Math.min(50, max))));
                })
                .build();
    }

    private ToolData readTool() {
        return new ToolData.Builder("kb_read",
                "读取项目知识库里的 Markdown 文件（只允许 .md，且必须在默认知识库范围内）。"
                        + "路径用 kb_search 返回的相对路径；个人知识库文件以 personal/ 开头。")
                .putParam("path", "相对路径的 .md，例如 docs/design.md 或 personal/notes.md。", "路径")
                .putOptionalParam("max_chars", "最多返回多少字符，默认 12000，最多 40000。", "字符数")
                .tool((params, output, manager) -> {
                    JsonObject json = json(params);
                    String path = JsonConfig.getNonBlankString(json, "path");
                    if (path == null) {
                        return completed("缺少路径 path");
                    }
                    int max = JsonConfig.getInt(json, "max_chars", DEFAULT_READ_CHARS);
                    return completed(read(path, Math.max(1000, Math.min(MAX_READ_CHARS, max))));
                })
                .build();
    }

    /** 仅私人 agent 可用：写入/创建个人知识库。 */
    private ToolData writeTool() {
        return new ToolData.Builder("kb_write",
                "写入或创建**当前用户个人知识库**里的 Markdown 文件（只会写到 personal/ 目录，"
                        + "不会改项目文件）。mode=append 追加（默认）、overwrite 覆盖、create 仅在不存在时创建、"
                        + "patch 按 diff 更新已有文件（支持 @@ 统一 diff 或 <<<<<<< SEARCH/======= />>>>>>> REPLACE）。"
                        + "写完后可用 kb_read(path=\"personal/...\") 读回。")
                .putParam("path", "相对个人知识库的 .md 路径，例如 notes/todo.md。", "路径")
                .putParam("content", "要写入的文本；mode=patch 时放 diff。", "内容")
                .putOptionalParam("mode", "append（默认）/ overwrite / create / patch。", "模式")
                .tool((params, output, manager) -> {
                    JsonObject json = json(params);
                    String path = JsonConfig.getNonBlankString(json, "path");
                    String content = JsonConfig.getNonBlankString(json, "content");
                    if (path == null) {
                        return completed("缺少路径 path");
                    }
                    if (content == null) {
                        return completed("缺少内容 content");
                    }
                    return completed(write(path, content, JsonConfig.getNonBlankString(json, "mode")));
                })
                .build();
    }

    /** 目录缓存过期时刷新：项目根标准目录 + 每个一级子文件夹下的标准目录。 */
    private void ensureDirsFresh() {
        if (System.currentTimeMillis() - dirsRefreshedAt < DIR_CACHE_MILLIS) {
            return;
        }
        refreshDirs();
    }

    private synchronized void refreshDirs() {
        List<File> dirs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        addDir(dirs, seen, projectRoot, KNOWLEDGE_DIRS);
        File[] firstLevel = projectRoot.listFiles(File::isDirectory);
        if (firstLevel != null) {
            for (File first : firstLevel) {
                String name = first.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith(".") || SKIP_DIRS.contains(name)) {
                    continue;
                }
                // 一级子文件夹下再找 design/docs/diary/discussion
                addDir(dirs, seen, first, KNOWLEDGE_DIRS);
            }
        }
        recursiveDirs = dirs;
        dirsRefreshedAt = System.currentTimeMillis();
        System.out.println("[Knowledge] 知识库目录缓存：" + dirs.size() + " 个（"
                + projectRoot.getPath() + "）");
    }

    private void addDir(List<File> dirs, Set<String> seen, File base, String[] names) {
        for (String name : names) {
            File dir = new File(base, name);
            if (!dir.isDirectory()) {
                continue;
            }
            try {
                String key = dir.getCanonicalPath();
                if (seen.add(key)) {
                    dirs.add(dir);
                }
            } catch (Throwable ignored) {
                // 路径解析失败时跳过
            }
        }
    }

    // ==================== 搜索 ====================

    private String search(String keyword, int maxResults) {
        ensureDirsFresh();
        String[] needles = terms(keyword);
        if (needles.length == 0) {
            return "缺少有效的搜索关键字";
        }
        List<Hit> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        // 1) 根目录文件（不递归）
        File[] roots = projectRoot.listFiles();
        if (roots != null) {
            for (File file : roots) {
                if (file.isFile()) {
                    collect(projectRoot, file, "", needles, hits, seen, maxResults);
                }
            }
        }
        // 2) design/docs/diary/discussion 及其子目录
        for (File dir : recursiveDirs) {
            walk(projectRoot, dir, "", needles, hits, seen, maxResults);
        }
        // 3) 个人知识库（只有私人 agent 有）
        if (personalRoot != null && personalRoot.isDirectory()) {
            walk(personalRoot, personalRoot, "personal/", needles, hits, seen, maxResults);
        }
        if (hits.isEmpty()) {
            return "没有在知识库里找到「" + keyword + "」。范围：项目根目录文件 + "
                    + String.join("/", KNOWLEDGE_DIRS) + " 子目录。";
        }
        hits.sort(Comparator.comparing(hit -> hit.relative));
        StringBuilder text = new StringBuilder("在知识库中找到 ").append(hits.size()).append(" 个文件：\n");
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            text.append(i + 1).append(". ").append(hit.relative);
            switch (hit.kind) {
                case "name":
                    text.append("（文件名匹配）");
                    break;
                case "content":
                    text.append("（内容匹配）");
                    break;
                default:
                    text.append("（文件名+内容匹配）");
                    break;
            }
            text.append('\n');
            if (hit.snippet != null && !hit.snippet.isEmpty()) {
                text.append("   片段：").append(hit.snippet).append('\n');
            }
        }
        return text.toString().trim();
    }

    private void walk(File base, File dir, String prefix, String[] needles, List<Hit> hits, Set<String> seen,
            int maxResults) {
        if (hits.size() >= maxResults) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (hits.size() >= maxResults) {
                return;
            }
            if (file.isDirectory()) {
                if (!SKIP_DIRS.contains(file.getName().toLowerCase(Locale.ROOT))) {
                    walk(base, file, prefix, needles, hits, seen, maxResults);
                }
            } else if (file.isFile()) {
                collect(base, file, prefix, needles, hits, seen, maxResults);
            }
        }
    }

    private void collect(File base, File file, String prefix, String[] needles, List<Hit> hits, Set<String> seen,
            int maxResults) {
        if (hits.size() >= maxResults) {
            return;
        }
        String relative = relative(base, file);
        if (relative == null || !seen.add(prefix + relative)) {
            return;
        }
        relative = prefix + relative;
        String lowerName = file.getName().toLowerCase(Locale.ROOT);
        boolean nameMatch = false;
        for (String needle : needles) {
            if (lowerName.contains(needle)) {
                nameMatch = true;
                break;
            }
        }
        String snippet = null;
        boolean contentMatch = false;
        if (file.length() <= MAX_SEARCH_BYTES && !isProbablyBinary(file)) {
            try {
                String text = FileUtil.readString(file);
                String lower = text.toLowerCase(Locale.ROOT);
                int index = -1;
                int matchedLength = 0;
                for (String needle : needles) {
                    int found = lower.indexOf(needle);
                    if (found >= 0 && (index < 0 || found < index)) {
                        index = found;
                        matchedLength = needle.length();
                    }
                }
                if (index >= 0) {
                    contentMatch = true;
                    int start = Math.max(0, index - 60);
                    int end = Math.min(text.length(), index + matchedLength + 100);
                    snippet = text.substring(start, end).replaceAll("\\s+", " ").trim();
                }
            } catch (Throwable ignored) {
                // 读不了就不参与内容匹配，文件名匹配仍然有效
            }
        }
        if (!nameMatch && !contentMatch) {
            return;
        }
        hits.add(new Hit(relative, nameMatch && contentMatch ? "both" : (nameMatch ? "name" : "content"), snippet));
    }

    // ==================== 读取 ====================

    private String read(String path, int maxChars) {
        ensureDirsFresh();
        String normalized = path.replace('\\', '/').trim();
        boolean personal = normalized.startsWith("personal/");
        File base = projectRoot;
        if (personal) {
            if (personalRoot == null) {
                return "当前 agent 没有个人知识库：" + path;
            }
            normalized = normalized.substring("personal/".length());
            base = personalRoot;
        }
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".md")) {
            return "只能读取 .md 文件：" + path;
        }
        File file;
        try {
            file = new File(base, normalized).getCanonicalFile();
        } catch (Throwable t) {
            return "路径不合法：" + path;
        }
        if (personal ? !isInside(personalRoot, file) : !isAllowed(file)) {
            return "只能读取知识库范围内的文件：" + path
                    + "（项目根目录文件、design/docs/diary/discussion 子目录，或 personal/ 个人知识库）";
        }
        if (!file.isFile()) {
            return "文件不存在：" + path;
        }
        if (file.length() > MAX_SEARCH_BYTES) {
            return "文件太大（超过 2MB），请用 kb_search 定位片段：" + path;
        }
        try {
            String text = FileUtil.readString(file);
            if (text.length() > maxChars) {
                return text.substring(0, maxChars) + "\n\n（已截断，原文件 " + text.length() + " 字符）";
            }
            return text;
        } catch (Throwable t) {
            return "读取失败：" + t.getMessage();
        }
    }

    /** 写入个人知识库；只允许 personal/ 范围。 */
    private String write(String path, String content, String mode) {
        if (personalRoot == null) {
            return "当前 agent 没有个人知识库，不能写入";
        }
        String normalized = path.replace('\\', '/').trim();
        if (normalized.startsWith("personal/")) {
            normalized = normalized.substring("personal/".length());
        }
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".md")) {
            return "个人知识库只允许写入 .md 文件：" + path;
        }
        if (content.length() > 200_000) {
            return "内容太长（超过 20 万字符），请拆分后再写";
        }
        File file;
        try {
            file = new File(personalRoot, normalized).getCanonicalFile();
        } catch (Throwable t) {
            return "路径不合法：" + path;
        }
        if (!isInside(personalRoot, file)) {
            return "只能写入个人知识库范围内：" + path;
        }
        String action = mode == null ? "append" : mode.trim().toLowerCase(Locale.ROOT);
        boolean exists = file.isFile();
        if ("create".equals(action) && exists) {
            return "文件已存在，未写入：" + path;
        }
        if ("patch".equals(action)) {
            if (!exists) {
                return "文件不存在，patch 模式需要先有文件：" + path;
            }
            try {
                String existing = FileUtil.readString(file);
                String patched = applyPatch(existing, content);
                java.nio.file.Files.write(file.toPath(),
                        patched.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.WRITE);
                return "已 patch personal/" + normalized + "（更新后 " + patched.length() + " 字符）";
            } catch (Throwable t) {
                return "patch 失败：" + t.getMessage();
            }
        }
        try {
            file.getParentFile().mkdirs();
            java.nio.file.StandardOpenOption[] options;
            if ("append".equals(action)) {
                options = new java.nio.file.StandardOpenOption[] {
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND,
                        java.nio.file.StandardOpenOption.WRITE };
            } else {
                options = new java.nio.file.StandardOpenOption[] {
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                        java.nio.file.StandardOpenOption.WRITE };
            }
            java.nio.file.Files.write(file.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    options);
            return "已写入 personal/" + normalized + "（" + ("append".equals(action) ? "追加" : "覆盖/创建")
                    + "，共 " + content.length() + " 字符）";
        } catch (Throwable t) {
            return "写入失败：" + t.getMessage();
        }
    }

    // ==================== patch ====================

    /**
     * 应用 patch：支持两种格式。
     * <ul>
     *   <li>统一 diff：{@code @@ -1,3 +1,4 @@} + {@code -}/{@code +}/{@code } 行；</li>
     *   <li>搜索/替换块：{@code <<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE}。</li>
     * </ul>
     */
    private static String applyPatch(String source, String patch) {
        String text = source.replace("\r\n", "\n").replace('\r', '\n');
        String diff = patch.replace("\r\n", "\n").replace('\r', '\n');
        if (diff.contains("<<<<<<< SEARCH")) {
            return applySearchReplace(text, diff);
        }
        if (diff.contains("@@")) {
            return applyUnified(text, diff);
        }
        throw new IllegalArgumentException("无法识别的 patch：需要 @@ 统一 diff 或 <<<<<<< SEARCH 块");
    }

    private static String applySearchReplace(String text, String patch) {
        List<String> source = new ArrayList<>(java.util.Arrays.asList(text.split("\n", -1)));
        List<String> lines = new ArrayList<>(java.util.Arrays.asList(patch.split("\n", -1)));
        int i = 0;
        int applied = 0;
        while (i < lines.size()) {
            if (!"<<<<<<< SEARCH".equals(lines.get(i).trim())) {
                i++;
                continue;
            }
            i++;
            List<String> oldLines = new ArrayList<>();
            while (i < lines.size() && !"=======".equals(lines.get(i).trim())) {
                oldLines.add(lines.get(i++));
            }
            if (i >= lines.size()) {
                throw new IllegalArgumentException("patch 缺少 =======");
            }
            i++;
            List<String> newLines = new ArrayList<>();
            while (i < lines.size() && !">>>>>>> REPLACE".equals(lines.get(i).trim())) {
                newLines.add(lines.get(i++));
            }
            if (i >= lines.size()) {
                throw new IllegalArgumentException("patch 缺少 >>>>>>> REPLACE");
            }
            i++;
            int index = find(source, oldLines, 0);
            if (index < 0) {
                throw new IllegalArgumentException("patch 找不到待替换内容：" + summarize(oldLines));
            }
            source.subList(index, index + oldLines.size()).clear();
            source.addAll(index, newLines);
            applied++;
        }
        if (applied == 0) {
            throw new IllegalArgumentException("patch 没有 SEARCH/REPLACE 块");
        }
        return String.join("\n", source);
    }

    private static String applyUnified(String text, String patch) {
        List<String> source = new ArrayList<>(java.util.Arrays.asList(text.split("\n", -1)));
        List<String> lines = new ArrayList<>(java.util.Arrays.asList(patch.split("\n", -1)));
        java.util.regex.Pattern header = java.util.regex.Pattern.compile(
                "@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@");
        int i = 0;
        int offset = 0;
        int applied = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (!line.startsWith("@@")) {
                i++;
                continue;
            }
            java.util.regex.Matcher matcher = header.matcher(line);
            if (!matcher.find()) {
                throw new IllegalArgumentException("无法解析 hunk 头：" + line);
            }
            int oldStart = Integer.parseInt(matcher.group(1));
            List<String> oldLines = new ArrayList<>();
            List<String> newLines = new ArrayList<>();
            i++;
            while (i < lines.size() && !lines.get(i).startsWith("@@")
                    && !lines.get(i).startsWith("***")) {
                String change = lines.get(i++);
                if (change.startsWith("\\")) {
                    continue;
                }
                if (change.startsWith("+")) {
                    newLines.add(change.substring(1));
                } else if (change.startsWith("-")) {
                    oldLines.add(change.substring(1));
                } else if (change.startsWith(" ")) {
                    String context = change.substring(1);
                    oldLines.add(context);
                    newLines.add(context);
                }
                // 空行通常是 patch 结尾/分隔，不作为上下文；真正的空上下文行以空格开头
            }
            int index;
            if (oldLines.isEmpty()) {
                index = Math.max(0, Math.min(source.size(), oldStart - 1 + offset));
            } else {
                index = find(source, oldLines, Math.max(0, oldStart - 1 + offset));
                if (index < 0) {
                    index = find(source, oldLines, 0);
                }
            }
            if (index < 0) {
                throw new IllegalArgumentException("patch 找不到上下文：" + summarize(oldLines));
            }
            source.subList(index, index + oldLines.size()).clear();
            source.addAll(index, newLines);
            offset += newLines.size() - oldLines.size();
            applied++;
        }
        if (applied == 0) {
            throw new IllegalArgumentException("patch 没有可应用的 hunk");
        }
        return String.join("\n", source);
    }

    private static int find(List<String> source, List<String> target, int from) {
        if (target.isEmpty()) {
            return Math.max(0, Math.min(from, source.size()));
        }
        outer:
        for (int i = Math.max(0, from); i + target.size() <= source.size(); i++) {
            for (int j = 0; j < target.size(); j++) {
                if (!target.get(j).equals(source.get(i + j))) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String summarize(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.size() && i < 5; i++) {
            if (text.length() > 0) {
                text.append(" / ");
            }
            text.append(lines.get(i));
        }
        return text.length() > 160 ? text.substring(0, 160) + "…" : text.toString();
    }

    /** 文件是否在某个目录内（含其本身）。 */
    private static boolean isInside(File base, File file) {        try {
            String root = base.getCanonicalPath();
            String path = file.getCanonicalPath();
            return path.equals(root) || path.startsWith(root + File.separator);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isAllowed(File file) {
        ensureDirsFresh();
        try {
            File canonical = file.getCanonicalFile();
            File root = projectRoot.getCanonicalFile();
            if (canonical.getParentFile() != null && canonical.getParentFile().equals(root)) {
                return true;
            }
            for (File dir : recursiveDirs) {
                File base = dir.getCanonicalFile();
                if (canonical.getPath().startsWith(base.getPath() + File.separator)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 路径解析失败按不允许处理
        }
        return false;
    }

    private String relative(File base, File file) {
        try {
            String root = base.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (path.equals(root)) {
                return null;
            }
            if (path.startsWith(root + File.separator)) {
                return path.substring(root.length() + 1).replace('\\', '/');
            }
        } catch (Throwable ignored) {
            // 解析失败不返回
        }
        return null;
    }

    private static boolean isProbablyBinary(File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[1024];
            int read = in.read(buffer);
            for (int i = 0; i < read; i++) {
                if (buffer[i] == 0) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }

    // ==================== 工具参数 ====================

    private static CompletableFuture<ToolResult> completed(String text) {
        return CompletableFuture.completedFuture(ToolResult.text(text == null ? "" : text));
    }

    /** 多语言/同义词搜索：用 | 分隔，如 {@code 天气|weather}。 */
    private static String[] terms(String keyword) {
        if (keyword == null) {
            return new String[0];
        }
        List<String> terms = new ArrayList<>();
        for (String part : keyword.split("\\s*\\|\\s*")) {
            String term = part.trim().toLowerCase(Locale.ROOT);
            if (!term.isEmpty() && !terms.contains(term)) {
                terms.add(term);
            }
        }
        return terms.toArray(new String[0]);
    }

    private static JsonObject json(String params) {
        if (params == null || params.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(params);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ex) {
            return new JsonObject();
        }
    }

    private static final class Hit {
        final String relative;
        final String kind;
        final String snippet;

        Hit(String relative, String kind, String snippet) {
            this.relative = relative;
            this.kind = kind;
            this.snippet = snippet;
        }
    }
}
