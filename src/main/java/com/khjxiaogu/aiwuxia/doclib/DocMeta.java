package com.khjxiaogu.aiwuxia.doclib;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 文档文件开头的 JSON meta（扩展头）。
 * <p>
 * 文件内容形如：
 * </p>
 * <pre>
 * {"kind":"ticket","status":"待创建",...}
 * 正文第一行
 * 正文……
 * </pre>
 * <p>
 * 约定：只有文件<b>第一个字符</b>（UTF-8 BOM 除外）就是 <code>{</code>，并且从该位置开始的第一个
 * JSON 对象能成功解析为 object 时，这个对象才被视为 meta；其它情况整个文件都是正文，旧文档因此完全兼容。
 * meta 只在文档系统内部（例如工单系统扩展点）使用，不属于文档可见内容：正文查询、搜索、标题推导都不包含它。
 * </p>
 */
public final class DocMeta {

    /** 拆分结果：meta + 可见正文。 */
    public static final class Parts {
        /** 解析出的 meta；为 null 表示文件没有 meta。 */
        public final JsonObject meta;
        /** 可见正文（已去掉 meta 头和紧随的一个换行）。 */
        public final String body;
        /** meta 头的结束下标（不含）；没有 meta 时为 0。 */
        public final int headerEnd;

        Parts(JsonObject meta, String body, int headerEnd) {
            this.meta = meta;
            this.body = body == null ? "" : body;
            this.headerEnd = headerEnd;
        }

        public boolean hasMeta() {
            return meta != null;
        }
    }

    private DocMeta() {
    }

    /**
     * 拆分文件原文。
     *
     * @param raw 文件原文，可为 null
     * @return 拆分结果，永不 null
     */
    public static Parts split(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Parts(null, raw == null ? "" : raw, 0);
        }
        int start = raw.charAt(0) == '\uFEFF' ? 1 : 0;
        if (start >= raw.length() || raw.charAt(start) != '{') {
            return new Parts(null, raw, 0);
        }
        int end = findObjectEnd(raw, start);
        if (end < 0) {
            return new Parts(null, raw, 0);
        }
        JsonObject meta;
        try {
            JsonElement element = JsonParser.parseString(raw.substring(start, end));
            if (!element.isJsonObject()) {
                return new Parts(null, raw, 0);
            }
            meta = element.getAsJsonObject();
        } catch (RuntimeException ex) {
            // 开头虽然是 {，但不是合法 JSON：按普通正文处理，保证旧文档可读
            return new Parts(null, raw, 0);
        }
        int bodyStart = end;
        if (bodyStart < raw.length() && raw.charAt(bodyStart) == '\r') {
            bodyStart++;
            if (bodyStart < raw.length() && raw.charAt(bodyStart) == '\n') {
                bodyStart++;
            }
        } else if (bodyStart < raw.length() && raw.charAt(bodyStart) == '\n') {
            bodyStart++;
        }
        return new Parts(meta, raw.substring(bodyStart), end);
    }

    /**
     * 拼接 meta 与可见正文。meta 为空时直接返回正文。
     *
     * @param meta meta，可为 null 或空对象
     * @param body 可见正文
     * @return 文件原文
     */
    public static String join(JsonObject meta, String body) {
        String visible = body == null ? "" : body;
        if (meta == null || meta.size() == 0) {
            return visible;
        }
        String header = meta.toString();
        return visible.isEmpty() ? header : header + "\n" + visible;
    }

    /** 深拷贝 meta；null 原样返回。 */
    public static JsonObject copy(JsonObject meta) {
        return meta == null ? null : meta.deepCopy();
    }

    /** 解析数据库中保存的 meta 文本；空或非法时返回 null。 */
    public static JsonObject parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(json);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 从 {@code start}（必须是 <code>{</code>）开始找配对的 <code>}</code>，返回结束下标（不含）。 */
    private static int findObjectEnd(String raw, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }
}
