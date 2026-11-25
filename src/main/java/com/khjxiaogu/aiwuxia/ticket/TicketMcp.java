package com.khjxiaogu.aiwuxia.ticket;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.doclib.DocAgent;
import com.khjxiaogu.aiwuxia.doclib.DocEvent;
import com.khjxiaogu.aiwuxia.doclib.DocEventBatch;
import com.khjxiaogu.aiwuxia.doclib.DocEventListener;
import com.khjxiaogu.aiwuxia.doclib.DocException;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;

/**
 * 工单系统的工具集：ticket_create / ticket_list / ticket_get / ticket_update / ticket_write。
 * <p>
 * 这是一组<b>agent 工具</b>：在 {@code save/config/agents.json} 或 {@code multi.json} 的 {@code tools}
 * 里加入 {@code "ticket"}（或别名 {@code "ticketmcp"}）后，由 {@code NapCatAIConnector#registerSkills}
 * 注册到对应会话；是否暴露给 MCP 由工具来源（{@code McpToolSource}）决定。
 * 工单是“基于文档系统的扩展”：结构化字段写在文档文件开头的 JSON meta 里，普通文档查询工具看不到这些字段。
 * </p>
 * <p>
 * 同时作为工单库的 {@link DocEventListener}：doclib 只交付原始事件，是否通知、通知文本
 * 都由本 MCP 决定。工单通知会带正文摘要和 meta 的具体旧值/新值。
 * </p>
 */
public final class TicketMcp implements ToolProvider, DocEventListener {

    private final TicketService service;
    private final AIGroupSession session;
    private final Runnable wake;
    private DocAgent agent;
    private final List<ToolData> tools = new ArrayList<>();

    public TicketMcp(TicketService service, AIGroupSession session, Runnable wake) {
        this.service = service;
        this.session = session;
        this.wake = wake;
        if (session != null) {
            try {
                this.agent = service.registerListener(session, this);
            } catch (Throwable t) {
                t.printStackTrace();
                System.out.println("[Ticket] 工单事件监听注册失败：" + t.getMessage());
            }
        }
        build();
    }

    @Override
    public Collection<ToolData> getTools() {
        return tools;
    }

    @Override
    public String getName() {
        return "ticket";
    }

    private void build() {
        tools.add(new ToolData.Builder("ticket_create", "新建工单。工单保存在共享文档库中，"
                + "状态、优先级、版本、负责人、标签等字段写在文档 meta 里，正文由标题和描述组成。")
                .putParam("title", "工单标题，必填；不能包含换行。", "标题")
                .putOptionalParam("description", "问题/需求描述。", "描述")
                .putOptionalParam("version", "工单对应的软件版本。", "版本")
                .putOptionalParam("reporter", "报告人。", "报告人")
                .putOptionalParam("assignee", "负责人；不传表示未分配。", "负责人")
                .putOptionalParam("priority", "优先级：low / normal / high / urgent，默认 normal。", "优先级")
                .putOptionalParam("labels", "标签，用逗号或 | 分隔。", "标签")
                .sync().maxCalls(64)
                .toolText((params, output, manager) -> safe(() -> doCreate(json(params))))
                .build());

        tools.add(new ToolData.Builder("ticket_list", "列出工单，可按状态、负责人、报告人、标签、关键词筛选。")
                .putOptionalParam("status", "状态筛选：" + TicketService.statusText()
                        + "；可用逗号分隔多个，all 表示全部。", "状态")
                .putOptionalParam("assignee", "负责人精确筛选。", "负责人")
                .putOptionalParam("reporter", "报告人精确筛选。", "报告人")
                .putOptionalParam("label", "标签筛选；多个用逗号或 | 分隔，命中任意一个即可。", "标签")
                .putOptionalParam("keyword", "标题或正文关键词（忽略大小写）。", "关键词")
                .putOptionalParam("limit", "最多返回条数，默认 50，最大 200。", "条数")
                .putOptionalParam("offset", "分页偏移，默认 0。", "偏移")
                .sync().maxCalls(64)
                .toolText((params, output, manager) -> safe(() -> doList(json(params))))
                .build());

        tools.add(new ToolData.Builder("ticket_get", "读取一张工单的完整内容（含正文）。")
                .putParam("ticket_id", "工单号（T-...）或文档 id（ticket/T-...）。", "工单")
                .putOptionalParam("include_body", "是否返回正文，默认 true。", "含正文")
                .sync().maxCalls(64)
                .toolText((params, output, manager) -> safe(() -> doGet(json(params))))
                .build());

        tools.add(new ToolData.Builder("ticket_update", "更新工单字段（状态、优先级、版本、负责人、报告人、标签、标题）。")
                .putParam("ticket_id", "工单号或文档 id。", "工单")
                .putOptionalParam("status", "新状态：" + TicketService.statusText()
                        + "；结束状态会自动记录关闭时间。", "状态")
                .putOptionalParam("priority", "新优先级：low / normal / high / urgent。", "优先级")
                .putOptionalParam("version", "新软件版本；传空串表示清空。", "版本")
                .putOptionalParam("assignee", "新负责人；传空串表示清空。", "负责人")
                .putOptionalParam("reporter", "新报告人；传空串表示清空。", "报告人")
                .putOptionalParam("labels", "新标签，用逗号或 | 分隔；传空串表示清空。", "标签")
                .putOptionalParam("title", "新标题（替换正文第一行）。", "标题")
                .putOptionalParam("author", "操作者；默认 ticket-mcp。", "操作者")
                .putOptionalParam("message", "提交说明。", "说明")
                .putOptionalParam("base_revision", "期望的当前修订号；不一致时拒绝更新，避免覆盖别人刚提交的内容。", "基础修订")
                .sync().maxCalls(64)
                .toolText((params, output, manager) -> safe(() -> doUpdate(json(params))))
                .build());

        tools.add(new ToolData.Builder("ticket_write", "编辑工单正文，语义与 doc_write 一致："
                + "overwrite 整篇替换、append 追加、patch 按 diff/搜索替换块修改；工单状态等 meta 字段不变。")
                .putParam("ticket_id", "工单号或文档 id。", "工单")
                .putParam("mode", "overwrite / append / patch。", "模式")
                .putParam("content", "新正文；patch 时放 diff。", "内容")
                .putOptionalParam("author", "操作者；默认 ticket-mcp。", "操作者")
                .putOptionalParam("message", "提交说明。", "说明")
                .putOptionalParam("base_revision", "期望的当前修订号；不一致时拒绝写入。", "基础修订")
                .sync().maxCalls(64)
                .toolText((params, output, manager) -> safe(() -> doWrite(json(params))))
                .build());
    }

    @Override
    public void onEvents(DocEventBatch batch) {
        if (batch == null || batch.isEmpty()) {
            if (batch != null) {
                batch.ack();
            }
            return;
        }
        // 同一批里同一工单合并成一条：正文摘要取最新，工单字段的 meta 明细累加。
        // detail 是 doclib 生成的结构化 meta diff；这里只挑工单字段，sheet_row / sheet_summary
        // / updated_at 等看板或内部时间戳字段直接过滤。
        java.util.Map<String, List<DocEvent>> grouped = new java.util.LinkedHashMap<>();
        for (DocEvent event : batch.getEvents()) {
            if (event.isMeta() && ticketMetaText(event.detail).isEmpty()) {
                continue;
            }
            grouped.computeIfAbsent(event.docId, key -> new ArrayList<>()).add(event);
        }
        if (grouped.isEmpty()) {
            batch.ack();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<ticket-update library=\"").append(escape(batch.getLibraryId()))
                .append("\" count=\"").append(grouped.size()).append("\" at=\"")
                .append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()))
                .append("\">\n");
        for (List<DocEvent> events : grouped.values()) {
            DocEvent latest = events.get(events.size() - 1);
            long from = latest.fromRevision;
            long revision = latest.toRevision;
            String summary = "";
            String detail = "";
            for (DocEvent event : events) {
                from = Math.min(from, event.fromRevision);
                revision = Math.max(revision, event.toRevision);
                if (event.hasContent() && !event.summary.isEmpty()) {
                    summary = event.summary;
                }
                if (event.hasMeta()) {
                    String metaText = ticketMetaText(event.detail);
                    if (!metaText.isEmpty()) {
                        if (detail.isEmpty()) {
                            detail = metaText;
                        } else if (!detail.equals(metaText) && !detail.contains(metaText)) {
                            detail = detail + "；" + metaText;
                        }
                    }
                }
            }
            if (summary.isEmpty() && detail.isEmpty()) {
                continue;
            }
            String ticketId = latest.docId.startsWith(TicketService.DOC_PREFIX)
                    ? latest.docId.substring(TicketService.DOC_PREFIX.length()) : latest.docId;
            sb.append("  <ticket id=\"").append(escape(latest.docId))
                    .append("\" ticket_id=\"").append(escape(ticketId))
                    .append("\" revision=\"").append(revision)
                    .append("\" from=\"").append(from)
                    .append("\" by=\"").append(escape(latest.author)).append("\"");
            if (!summary.isEmpty()) {
                sb.append(" change=\"").append(escape(summary)).append("\"");
            }
            if (!detail.isEmpty()) {
                sb.append(" changes=\"").append(escape(detail)).append("\"");
            }
            sb.append(" />\n");
        }
        sb.append("</ticket-update>\n");
        sb.append("这些工单发生了变化；changes 是扩展字段的具体旧值/新值，change 是正文摘要。")
                .append("修改前请先 ticket_get。");
        inject(sb.toString(), batch);
    }

    /** 工单字段白名单；其余 meta（sheet_row、sheet_summary、updated_at 等）不通知。 */
    private static final String[] TICKET_FIELDS = { "status", "priority", "version", "assignee", "reporter",
            "labels" };

    /**
     * 从 doclib 的结构化 meta diff 中挑出工单字段，生成通知文本。
     * 解析不了时按旧格式原样返回。
     */
    private static String ticketMetaText(String detail) {
        if (detail == null || detail.trim().isEmpty()) {
            return "";
        }
        JsonObject diff;
        try {
            JsonElement element = JsonParser.parseString(detail);
            if (!element.isJsonObject()) {
                return detail.trim();
            }
            diff = element.getAsJsonObject();
        } catch (RuntimeException ex) {
            return detail.trim();
        }
        List<String> parts = new ArrayList<>();
        for (String field : TICKET_FIELDS) {
            JsonElement changeElement = diff.get(field);
            if (changeElement == null || !changeElement.isJsonObject()) {
                continue;
            }
            JsonObject change = changeElement.getAsJsonObject();
            String from = displayValue(change.get("from"));
            String to = displayValue(change.get("to"));
            if (!from.equals(to)) {
                parts.add(field + ": " + from + " → " + to);
            }
        }
        return String.join("; ", parts);
    }

    private static String displayValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "(空)";
        }
        if (value.isJsonArray()) {
            List<String> items = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) {
                if (item == null || item.isJsonNull()) {
                    continue;
                }
                String text = item.isJsonPrimitive() ? item.getAsString() : item.toString();
                if (!text.isEmpty()) {
                    items.add(text);
                }
            }
            return items.isEmpty() ? "(空)" : "[" + String.join(", ", items) + "]";
        }
        if (value.isJsonPrimitive()) {
            String text = value.getAsString();
            return text.isEmpty() ? "(空)" : text;
        }
        return value.toString();
    }

    private void inject(String text, DocEventBatch batch) {        if (session == null) {
            batch.ack();
            return;
        }
        try {
            session.getCommandExec().submit(() -> {
                try {
                    session.addMessage(Collections.singletonList(() -> new PlainText(text)), true);
                    if (agent != null && agent.wakeOnNotify && !session.isGenerating() && wake != null) {
                        try {
                            wake.run();
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                } finally {
                    // 无论注入是否成功都消费本批，避免一条失败事件堵住后续事件
                    batch.ack();
                }
            });
        } catch (Throwable t) {
            t.printStackTrace();
            batch.ack();
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("\"", "&quot;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    private String doCreate(JsonObject o) {
        TicketService.Ticket ticket = service.create(
                JsonConfig.getString(o, "title", ""),
                JsonConfig.getString(o, "description", ""),
                JsonConfig.getString(o, "version", ""),
                JsonConfig.getString(o, "reporter", ""),
                JsonConfig.getString(o, "assignee", ""),
                JsonConfig.getString(o, "priority", "normal"),
                JsonConfig.getString(o, "labels", ""));
        return "[TICKET_OK] 已创建工单 " + ticket.ticketId + "（doc_id=" + ticket.docId
                + "，revision " + ticket.revision + "）。\n" + formatTicket(ticket, false);
    }

    private String doList(JsonObject o) {
        int limit = clamp(JsonConfig.getInt(o, "limit", 50), 1, 200);
        int offset = Math.max(0, JsonConfig.getInt(o, "offset", 0));
        List<TicketService.Ticket> tickets = service.list(
                optional(o, "status"),
                optional(o, "assignee"),
                optional(o, "reporter"),
                optional(o, "label"),
                optional(o, "keyword"),
                limit, offset);
        if (tickets.isEmpty()) {
            return "没有匹配的工单。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("工单 ").append(tickets.size()).append(" 条：\n");
        for (TicketService.Ticket ticket : tickets) {
            sb.append("- ").append(ticket.ticketId)
                    .append("  [").append(ticket.status).append("]")
                    .append("  优先级=").append(ticket.priority)
                    .append("  版本=").append(orNone(ticket.version))
                    .append("  负责人=").append(orNone(ticket.assignee))
                    .append("  rev=").append(ticket.revision)
                    .append("  ").append(TicketService.formatTime(ticket.updatedAt))
                    .append("  ").append(ticket.title)
                    .append('\n');
        }
        return sb.toString().trim();
    }

    private String doGet(JsonObject o) {
        TicketService.Ticket ticket = service.get(require(o, "ticket_id"));
        boolean includeBody = !JsonConfig.has(o, "include_body") || JsonConfig.getBool(o, "include_body", true);
        return formatTicket(ticket, includeBody);
    }

    private String doUpdate(JsonObject o) {
        String ticketId = require(o, "ticket_id");
        Long baseRevision = JsonConfig.getLongOrNull(o, "base_revision");
        TicketService.Ticket ticket = service.update(ticketId,
                optional(o, "status"),
                optional(o, "priority"),
                optional(o, "version"),
                optional(o, "assignee"),
                optional(o, "reporter"),
                optional(o, "labels"),
                optional(o, "title"),
                optional(o, "author"),
                optional(o, "message"),
                baseRevision);
        return "[TICKET_OK] 已更新工单 " + ticket.ticketId + "（revision " + ticket.revision + "）。\n"
                + formatTicket(ticket, false);
    }

    private String doWrite(JsonObject o) {
        String ticketId = require(o, "ticket_id");
        String mode = JsonConfig.getString(o, "mode", "overwrite");
        String content = JsonConfig.getString(o, "content", "");
        String author = optional(o, "author");
        String message = optional(o, "message");
        Long baseRevision = JsonConfig.getLongOrNull(o, "base_revision");
        TicketService.Ticket ticket = service.write(ticketId, mode, content, author, message,
                baseRevision);
        return "[TICKET_OK] 已更新工单 " + ticket.ticketId + " 正文（" + mode.trim().toLowerCase()
                + "，revision " + ticket.revision + "）。";
    }

    private static String formatTicket(TicketService.Ticket ticket, boolean includeBody) {
        StringBuilder sb = new StringBuilder();
        sb.append("工单 ").append(ticket.ticketId).append("  ").append(ticket.title).append('\n');
        sb.append("doc_id: ").append(ticket.docId).append("  revision: ").append(ticket.revision).append('\n');
        sb.append("状态: ").append(ticket.status)
                .append("  优先级: ").append(ticket.priority)
                .append("  版本: ").append(orNone(ticket.version)).append('\n');
        sb.append("负责人: ").append(orNone(ticket.assignee))
                .append("  报告人: ").append(orNone(ticket.reporter)).append('\n');
        if (!ticket.labels.isEmpty()) {
            sb.append("标签: ").append(String.join(", ", ticket.labels)).append('\n');
        }
        sb.append("创建: ").append(TicketService.formatTime(ticket.createdAt))
                .append("  更新: ").append(TicketService.formatTime(ticket.updatedAt));
        if (ticket.closedAt > 0) {
            sb.append("  关闭: ").append(TicketService.formatTime(ticket.closedAt));
        }
        sb.append('\n');
        if (includeBody) {
            sb.append("---\n").append(ticket.body);
        }
        return sb.toString().trim();
    }

    private static String optional(JsonObject o, String key) {
        return JsonConfig.has(o, key) ? JsonConfig.getString(o, key, "") : null;
    }

    private static String orNone(String value) {
        return value == null || value.isEmpty() ? "(未设置)" : value;
    }

    private static String safe(Supplier<String> action) {
        try {
            return action.get();
        } catch (DocException ex) {
            return "[" + ex.code + "] " + ex.getMessage();
        } catch (Throwable t) {
            t.printStackTrace();
            return "[TICKET_INTERNAL] "
                    + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    private static JsonObject json(String params) {
        if (params == null || params.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(params);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException ex) {
            throw new DocException("TICKET_BAD_REQUEST", "工具参数不是合法 JSON：" + ex.getMessage());
        }
    }

    private static String require(JsonObject o, String key) {
        String value = JsonConfig.getString(o, key, "");
        if (value.trim().isEmpty()) {
            throw new DocException("TICKET_BAD_REQUEST", key + " 不能为空");
        }
        return value.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
