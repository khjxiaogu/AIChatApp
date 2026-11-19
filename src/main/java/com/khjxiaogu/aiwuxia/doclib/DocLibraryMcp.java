package com.khjxiaogu.aiwuxia.doclib;

import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;

/**
 * doclib 的工具集：doc_list / doc_search / doc_read / doc_write / doc_delete / doc_wait_unlock。
 * <p>每个 agent 一套，捕获 agent 身份；不能通过公共 MCP 暴露。</p>
 */
public final class DocLibraryMcp implements ToolProvider {

    private final DocLibraryRuntime runtime;
    private final DocAgent agent;
    private final DocLibraryConfig config;
    private final List<ToolData> tools = new ArrayList<>();

    private DocLibraryMcp(DocLibraryRuntime runtime, DocAgent agent, DocLibraryConfig config) {
        this.runtime = runtime;
        this.agent = agent;
        this.config = config;
        build();
    }

    public static DocLibraryMcp forAgent(DocLibraryRuntime runtime, DocAgent agent, DocLibraryConfig config) {
        return new DocLibraryMcp(runtime, agent, config);
    }

    @Override
    public Collection<ToolData> getTools() {
        return tools;
    }

    @Override
    public String getName() {
        return "doclib:" + runtime.id();
    }

    private void build() {
        tools.add(new ToolData.Builder("doc_list", "枚举共享文档库里的文档（不返回正文；只读元数据）。")
                .putOptionalParam("prefix", "文档 id 前缀；不传列全部。", "前缀")
                .putOptionalParam("limit", "最多返回条数，默认 50，最大 200。", "条数")
                .putOptionalParam("offset", "分页偏移，默认 0。", "偏移")
                .putOptionalParam("include_deleted", "是否包含已删除的 tombstone，默认 false。", "含删除")
                .sync().orderSensitive().sessionOnly().maxCalls(64)
                .toolText((params, output, manager) -> safe(() -> doList(json(params))))
                .build());

        tools.add(new ToolData.Builder("doc_search", "在共享文档库里搜索关键字；只返回摘要，不更新已读版本。")
                .putParam("keyword", "关键字；多个词用 | 分隔。", "关键字")
                .putOptionalParam("limit", "最多返回条数，默认 20，最大 50。", "条数")
                .putOptionalParam("offset", "分页偏移，默认 0。", "偏移")
                .sync().orderSensitive().sessionOnly().maxCalls(64)
                .toolText((params, output, manager) -> safe(() -> doSearch(json(params))))
                .build());

        tools.add(new ToolData.Builder("doc_read", "读取共享文档；完整读取后才允许写入。")
                .putParam("doc_id", "文档 id。", "文档")
                .putOptionalParam("revision", "读取指定历史修订；不传读当前。", "修订")
                .putOptionalParam("max_chars", "最多返回字符数；截断后不能写，默认读全。", "最大字符")
                .sync().orderSensitive().sessionOnly().maxCalls(64)
                .toolText((params, output, manager) -> safe(() -> doRead(json(params))))
                .build());

        if (config.write) {
            tools.add(new ToolData.Builder("doc_write", "写入共享文档；本轮结束一定提交并解锁。")
                    .putParam("doc_id", "文档 id。", "文档")
                    .putParam("mode", "overwrite / append / patch。", "模式")
                    .putParam("content", "内容；patch 时放 diff。", "内容")
                    .putOptionalParam("title", "标题；不传则取首个 Markdown 标题/首行。", "标题")
                    .putOptionalParam("base_revision", "期望的当前修订号。", "基础修订")
                    .putOptionalParam("message", "提交说明。", "说明")
                    .sync().orderSensitive().sessionOnly().maxCalls(64)
                    .toolText((params, output, manager) -> safe(() -> doWrite(json(params))))
                    .build());

            tools.add(new ToolData.Builder("doc_delete", "删除共享文档（写 tombstone）；本轮结束提交。")
                    .putParam("doc_id", "文档 id。", "文档")
                    .putOptionalParam("message", "提交说明。", "说明")
                    .sync().orderSensitive().sessionOnly().maxCalls(32)
                    .toolText((params, output, manager) -> safe(() -> doDelete(json(params))))
                    .build());
        }

        tools.add(new ToolData.Builder("doc_wait_unlock", "等待某个文档解锁，最多 10 秒；不拿锁、不更新已读。")
                .putParam("doc_id", "文档 id。", "文档")
                .putOptionalParam("timeout_ms", "等待毫秒数，默认/最大 10000。", "超时")
                .sync().orderSensitive().sessionOnly().maxCalls(32)
                .toolText((params, output, manager) -> safe(() -> doWait(json(params))))
                .build());
    }

    private String doList(JsonObject o) {
        String prefix = JsonConfig.getString(o, "prefix", "");
        int limit = clamp(JsonConfig.getInt(o, "limit", 50), 1, 200);
        int offset = Math.max(0, JsonConfig.getInt(o, "offset", 0));
        boolean includeDeleted = JsonConfig.getBool(o, "include_deleted", false);
        List<DocRecord> docs = runtime.list(prefix, includeDeleted, limit, offset);
        if (docs.isEmpty()) {
            return "文档库 " + runtime.id() + " 中没有匹配的文档。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("文档库 ").append(runtime.id()).append(" 共 ").append(docs.size()).append(" 条：\n");
        for (DocRecord doc : docs) {
            sb.append("- ").append(doc.docId)
                    .append("  rev=").append(doc.revision)
                    .append("  size=").append(doc.size)
                    .append("  ").append(formatTime(doc.updatedAt))
                    .append("  ").append(doc.updatedBy);
            if (doc.deleted) {
                sb.append("  [已删除]");
            }
            if (runtime.isStale(agent.id, doc)) {
                sb.append("  stale=true");
            }
            if (!doc.title.isEmpty()) {
                sb.append("  ").append(doc.title);
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private String doSearch(JsonObject o) {
        String keyword = JsonConfig.getString(o, "keyword", "");
        if (keyword.trim().isEmpty()) {
            throw new DocException("DOC_BAD_REQUEST", "keyword 不能为空");
        }
        int limit = clamp(JsonConfig.getInt(o, "limit", 20), 1, 50);
        int offset = Math.max(0, JsonConfig.getInt(o, "offset", 0));
        List<DocStore.SearchHit> hits = runtime.search(keyword, limit, offset);
        if (hits.isEmpty()) {
            return "没有在文档库 " + runtime.id() + " 中找到「" + keyword + "」。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("在文档库 ").append(runtime.id()).append(" 中找到 ").append(hits.size()).append(" 个文档：\n");
        for (DocStore.SearchHit hit : hits) {
            sb.append("- ").append(hit.docId).append("  rev=").append(hit.revision);
            if (!hit.title.isEmpty()) {
                sb.append("  ").append(hit.title);
            }
            sb.append('\n');
            if (!hit.snippet.isEmpty()) {
                sb.append("  片段：").append(hit.snippet).append('\n');
            }
        }
        sb.append("（搜索只给摘要；要修改请先 doc_read。）");
        return sb.toString();
    }

    private String doRead(JsonObject o) {
        String docId = require(o, "doc_id");
        Long revision = o.has("revision") && !o.get("revision").isJsonNull()
                ? Long.valueOf(o.get("revision").getAsLong()) : null;
        int maxChars = o.has("max_chars") && !o.get("max_chars").isJsonNull()
                ? JsonConfig.getInt(o, "max_chars", config.maxDocChars) : config.maxDocChars;
        DocLibraryRuntime.ReadResult result = runtime.read(agent.id, docId, revision, maxChars);
        DocRecord doc = result.record;
        StringBuilder sb = new StringBuilder();
        sb.append("<doc id=\"").append(doc.docId).append("\" revision=\"").append(doc.revision)
                .append("\" hash=\"").append(doc.hash).append("\" size=\"").append(doc.size)
                .append("\" updated_at=\"").append(formatTime(doc.updatedAt))
                .append("\" updated_by=\"").append(doc.updatedBy).append("\"");
        if (result.draft) {
            sb.append(" draft=\"true\" base_revision=\"").append(doc.revision).append("\"");
        }
        sb.append(" complete=\"").append(result.complete).append("\"");
        if (doc.deleted) {
            sb.append(" deleted=\"true\" />\n");
            sb.append("（文档已删除，正文为空。可以用 doc_write mode=overwrite 重建。）");
            return sb.toString();
        }
        sb.append(">\n").append(doc.body).append("\n</doc>");
        if (result.truncated) {
            sb.append("\n（内容被截断，未完整读取；请用更大的 max_chars 重新 doc_read，否则不能写入。）");
        }
        return sb.toString();
    }

    private String doWrite(JsonObject o) {
        String docId = require(o, "doc_id");
        String mode = JsonConfig.getString(o, "mode", "overwrite");
        String content = JsonConfig.getString(o, "content", "");
        String title = JsonConfig.has(o, "title") ? JsonConfig.getString(o, "title", "") : null;
        Long baseRevision = JsonConfig.has(o, "base_revision") ? Long.valueOf(JsonConfig.getLong(o, "base_revision", 0)) : null;
        String message = JsonConfig.getString(o, "message", "");
        DocLibraryRuntime.StageResult result = runtime.stageWrite(agent.id, docId, mode, content,
                title, baseRevision, message);
        if (result.noop) {
            return "[DOC_NOOP] 内容与当前草稿/当前版本一致，没有产生修订。";
        }
        DocDraft d = result.draft;
        return "[DOC_OK] 已暂存 " + docId + "（" + d.lastOp + "，base revision " + d.baseRevision + "）。\n"
                + "本轮结束时会提交为一个新修订；提交前其他 agent 不能写这篇文档，也不会收到通知。\n"
                + "本轮无论正常结束、异常还是超时，都会提交并解锁。";
    }

    private String doDelete(JsonObject o) {
        String docId = require(o, "doc_id");
        String message = JsonConfig.getString(o, "message", "");
        DocLibraryRuntime.StageResult result = runtime.stageDelete(agent.id, docId, message);
        if (result.canceled) {
            return "[DOC_OK] 文档 " + docId + " 是本轮新建的，删除已撤销草稿，不会产生文档/通知。";
        }
        if (result.noop) {
            return "[DOC_NOOP] 文档 " + docId + " 已经是删除状态。";
        }
        DocDraft d = result.draft;
        return "[DOC_OK] 已暂存删除 " + docId + "（base revision " + d.baseRevision + "）。\n"
                + "本轮结束提交后变为 tombstone；监听者会在解锁后收到 deleted 通知。";
    }

    private String doWait(JsonObject o) {
        String docId = require(o, "doc_id");
        long configured = config.waitTimeoutMs <= 0 ? 10000L : config.waitTimeoutMs;
        long timeout = JsonConfig.has(o, "timeout_ms") ? JsonConfig.getLong(o, "timeout_ms", configured) : configured;
        timeout = Math.min(Math.max(1L, timeout), 10000L);
        DocLibraryRuntime.WaitInfo info = runtime.waitUnlock(agent.id, docId, timeout);
        if (!info.result.unlocked) {
            DocLease lease = info.result.lease;
            StringBuilder sb = new StringBuilder();
            sb.append("[DOC_WAIT_TIMEOUT] ").append(docId).append(" 仍被 ")
                    .append(lease == null ? "其他 agent" : lease.agentId)
                    .append(" 锁定");
            if (lease != null) {
                sb.append("（已持有 ").append(lease.heldMs()).append("ms，最晚 ")
                        .append(formatTime(lease.expiresAt)).append(" 释放）");
            }
            sb.append("；已等待 ").append(info.result.waitedMs).append("ms。")
                    .append("可以重试，或先处理其它文档。");
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[DOC_UNLOCKED] ").append(docId).append(" 已解锁");
        if (!info.exists) {
            sb.append("（文档不存在，可以直接创建）");
            return sb.toString();
        }
        sb.append("，当前 revision ").append(info.current.revision);
        if (info.current.deleted) {
            sb.append("（已删除）");
        }
        if (info.stale) {
            sb.append("；你上次读取的不是这一版，写之前请先 doc_read。");
        } else {
            sb.append("；你已经读过这一版。");
        }
        return sb.toString();
    }

    private static String safe(Supplier<String> action) {
        try {
            return action.get();
        } catch (DocException ex) {
            return "[" + ex.code + "] " + ex.getMessage();
        } catch (Throwable t) {
            t.printStackTrace();
            return "[DOC_INTERNAL] " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    private static JsonObject json(String params) {
        if (params == null || params.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement e = JsonParser.parseString(params);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ex) {
            throw new DocException("DOC_BAD_REQUEST", "工具参数不是合法 JSON：" + ex.getMessage());
        }
    }

    private static String require(JsonObject o, String key) {
        String value = JsonConfig.getString(o, key, "");
        if (value.trim().isEmpty()) {
            throw new DocException("DOC_BAD_REQUEST", key + " 不能为空");
        }
        return value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String formatTime(long epoch) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(epoch));
    }
}
