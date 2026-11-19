package com.khjxiaogu.aiwuxia.doclib;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档 patch：支持统一 diff（{@code @@ -1,3 +1,4 @@}）和
 * {@code <<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE} 两种格式。
 * <p>与 {@code KnowledgeBaseMcp} 的行为保持一致，单独抽出来给 doclib 复用。</p>
 */
public final class PatchApplier {

    private PatchApplier() {
    }

    public static String apply(String source, String patch) {
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
        List<String> source = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        List<String> lines = new ArrayList<>(Arrays.asList(patch.split("\n", -1)));
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
        List<String> source = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        List<String> lines = new ArrayList<>(Arrays.asList(patch.split("\n", -1)));
        Pattern header = Pattern.compile("@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@");
        int i = 0;
        int offset = 0;
        int applied = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (!line.startsWith("@@")) {
                i++;
                continue;
            }
            Matcher matcher = header.matcher(line);
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
}
