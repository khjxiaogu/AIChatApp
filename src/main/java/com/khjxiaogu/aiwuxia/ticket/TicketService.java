package com.khjxiaogu.aiwuxia.ticket;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.doclib.DocAgent;
import com.khjxiaogu.aiwuxia.doclib.DocEventListener;
import com.khjxiaogu.aiwuxia.doclib.DocException;
import com.khjxiaogu.aiwuxia.doclib.DocLibraryConfig;
import com.khjxiaogu.aiwuxia.doclib.DocLibraryRegistry;
import com.khjxiaogu.aiwuxia.doclib.DocLibraryRuntime;
import com.khjxiaogu.aiwuxia.doclib.DocRecord;
import com.khjxiaogu.aiwuxia.doclib.DocStore;
import com.khjxiaogu.aiwuxia.doclib.PatchApplier;
import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;

/**
 * 基于共享文档库的工单系统。
 * <p>
 * 每张工单是一篇文档：
 * </p>
 * <ul>
 *   <li>文档 id：{@code ticket/<工单号>}；</li>
 *   <li>正文：第一行是标题，空行后是描述；正文编辑与文档库的 {@code doc_write} 一致；</li>
 *   <li>文件开头的 JSON meta：保存 {@code kind=ticket}、状态、优先级、负责人、标签、时间等结构化字段。
 *       文档查询工具看不到 meta，工单 MCP 通过 {@link DocRecord#meta} 读取。</li>
 * </ul>
 * <p>
 * 工单工具通过 {@code agents.json} 的 {@code tools} 启用，注册到 agent 会话（见 {@link TicketMcp}），
 * 不暴露在 {@code /aichat/mcp} 在线端点上。所有写操作走
 * {@link DocLibraryRuntime#writeExternal}，不经过 AI 轮次，但仍受文档库修订号、
 * 写锁和通知机制约束。读-改-写序列在同一个文档库实例上串行化，避免同一工单的并发更新互相覆盖。
 * </p>
 */
public final class TicketService {

    /** meta 中标识工单的类型值。 */
    public static final String KIND = "ticket";
    /** 工单文档 id 前缀。 */
    public static final String DOC_PREFIX = "ticket/";

    /** 工单状态：待创建。 */
    public static final String STATUS_PENDING_CREATE = "待创建";
    /** 工单状态：无效。 */
    public static final String STATUS_INVALID = "无效";
    /** 工单状态：处理中。 */
    public static final String STATUS_IN_PROGRESS = "处理中";
    /** 工单状态：已修复。 */
    public static final String STATUS_FIXED = "已修复";
    /** 工单状态：暂不修复。 */
    public static final String STATUS_WONT_FIX = "暂不修复";
    /** 工单状态：已解答。 */
    public static final String STATUS_ANSWERED = "已解答";
    /** 工单状态：设计如此。 */
    public static final String STATUS_BY_DESIGN = "设计如此";
    /** 工单状态：重复。 */
    public static final String STATUS_DUPLICATE = "重复";
    /** 工单状态：未复现。 */
    public static final String STATUS_CANNOT_REPRODUCE = "未复现";
    /** 工单状态：待反馈。 */
    public static final String STATUS_WAITING_FEEDBACK = "待反馈";
    /** 工单状态：待补充（需要更多信息）。 */
    public static final String STATUS_NEED_INFO = "待补充";
    /** 工单状态：已拒绝。 */
    public static final String STATUS_REJECTED = "已拒绝";

    /** 工单库允许的状态（顺序即展示顺序）。 */
    public static final List<String> STATUS_VALUES = Collections.unmodifiableList(Arrays.asList(
            STATUS_PENDING_CREATE, STATUS_INVALID, STATUS_IN_PROGRESS, STATUS_FIXED,
            STATUS_WONT_FIX, STATUS_ANSWERED, STATUS_BY_DESIGN, STATUS_DUPLICATE,
            STATUS_CANNOT_REPRODUCE, STATUS_WAITING_FEEDBACK, STATUS_NEED_INFO, STATUS_REJECTED));
    private static final Set<String> STATUSES = Collections.unmodifiableSet(
            new HashSet<>(STATUS_VALUES));
    /** 视为已结束的状态：设置时记录 closed_at，离开时清除。 */
    private static final Set<String> CLOSED_STATUSES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(STATUS_INVALID, STATUS_FIXED, STATUS_WONT_FIX,
                    STATUS_ANSWERED, STATUS_BY_DESIGN, STATUS_DUPLICATE,
                    STATUS_CANNOT_REPRODUCE, STATUS_REJECTED)));
    private static final Set<String> PRIORITIES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("low", "normal", "high", "urgent")));

    /** 状态允许值，例如 {@code 待创建 / 无效 / 处理中 / 已修复 / ...}。 */
    public static String statusText() {
        return String.join(" / ", STATUS_VALUES);
    }

    /** 状态是否合法；兼容旧数据的 open/in_progress/pending/closed。 */
    public static boolean isValidStatus(String status) {
        return STATUSES.contains(normalizeStatus(status));
    }

    /** 是否已结束状态。 */
    public static boolean isClosedStatus(String status) {
        return CLOSED_STATUSES.contains(normalizeStatus(status));
    }

    /**
     * 状态规范化：保留中文状态；把旧的英文状态映射到新状态，便于旧工单平滑读取。
     *
     * @param status 原始状态
     * @return 规范化后的状态；null/空白返回空串
     */
    public static String normalizeStatus(String status) {
        if (status == null) {
            return "";
        }
        String value = status.trim();
        if (value.isEmpty()) {
            return "";
        }
        switch (value.toLowerCase()) {
        case "open":
        case "pending":
            return STATUS_PENDING_CREATE;
        case "in_progress":
            return STATUS_IN_PROGRESS;
        case "closed":
            return STATUS_FIXED;
        default:
            return value;
        }
    }

    /** 一张工单的只读视图。 */
    public static final class Ticket {
        public final String ticketId;
        public final String docId;
        public final String title;
        public final String body;
        public final String status;
        public final String priority;
        /** 工单对应的软件版本。 */
        public final String version;
        public final String assignee;
        public final String reporter;
        public final List<String> labels;
        public final long revision;
        public final long createdAt;
        public final long updatedAt;
        public final long closedAt;
        /** 工单在在线表格中的行号（1 是表头，数据从 2 开始）；0 表示尚未分配。 */
        public final int sheetRow;
        /** 看板简述（只用于表格展示，不写正文）。 */
        public final String summary;

        Ticket(String ticketId, String docId, String title, String body, String status, String priority,
                String version, String assignee, String reporter, List<String> labels, long revision,
                long createdAt, long updatedAt, long closedAt, int sheetRow, String summary) {
            this.ticketId = ticketId;
            this.docId = docId;
            this.title = title;
            this.body = body;
            this.status = status;
            this.priority = priority;
            this.version = version == null ? "" : version;
            this.assignee = assignee;
            this.reporter = reporter;
            this.labels = labels == null ? Collections.<String>emptyList() : labels;
            this.revision = revision;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.closedAt = closedAt;
            this.sheetRow = sheetRow;
            this.summary = summary == null ? "" : summary;
        }

        public boolean isClosed() {
            return isClosedStatus(status);
        }
    }

    private final DocLibraryRuntime runtime;
    /** 工单库的监听配置（监听 ticket/*，供 TicketMcp 接收原始事件）。 */
    private final DocLibraryConfig config;
    /** 在线表格看板；未启用时为 null。 */
    private final TicketDashboard dashboard;

    /**
     * 打开工单库。库参数来自 {@code save/config/doclibs.json}；没有配置时使用默认目录
     * {@code save/doclibs/<libraryId>}。
     *
     * @param dataFolder 数据目录（save/）
     * @param libraryId  工单库 id，默认 {@code tickets}
     */
    public TicketService(File dataFolder, String libraryId) {
        this(dataFolder, libraryId, false);
    }

    /**
     * 打开工单库，并指定工单监听通知是否主动唤醒会话。
     * <p>每个持有 {@code ticket} 工具的会话都会创建一个实例；{@code wakeOnNotify} 是该会话
     * 自己的设置（{@code multi.json} / {@code agents.json} 的 {@code ticket.wakeOnNotify}），
     * 不影响同库其它会话。默认 false 只入队，等下一轮再处理。</p>
     *
     * @param dataFolder   数据目录（save/）
     * @param libraryId    工单库 id，默认 {@code tickets}
     * @param wakeOnNotify 收到工单变更通知后是否主动唤醒会话
     */
    public TicketService(File dataFolder, String libraryId, boolean wakeOnNotify) {
        this.config = DocLibraryConfig.forLibrary(dataFolder, libraryId,
                Collections.singletonList(DOC_PREFIX + "*"), wakeOnNotify);
        this.runtime = DocLibraryRegistry.open(dataFolder, config);
        this.dashboard = TicketDashboard.forLibrary(dataFolder, runtime.id(), this);
    }

    /**
     * 为 agent 注册工单事件监听者。TicketMcp 收到原始事件后自行决定是否通知与通知文本。
     *
     * @param session  会话
     * @param listener 监听者
     * @return agent
     */
    public DocAgent registerListener(AIGroupSession session, DocEventListener listener) {
        return runtime.register(session, config, listener);
    }

    public DocLibraryRuntime runtime() {
        return runtime;
    }

    /**
     * 新建工单。
     *
     * @param title       标题，必填
     * @param description 描述，可空
     * @param version     工单对应的软件版本，可空
     * @param reporter    报告人，可空
     * @param assignee    负责人，可空
     * @param priority    优先级：low / normal / high / urgent，默认 normal
     * @param labels      标签：逗号或 | 分隔
     * @return 新建的工单
     */
    public Ticket create(String title, String description, String version, String reporter, String assignee,
            String priority, String labels) {
        String cleanTitle = requireTitle(title);
        String desc = description == null ? "" : description.trim();
        String body = desc.isEmpty() ? cleanTitle : cleanTitle + "\n\n" + desc;
        long now = System.currentTimeMillis();
        JsonObject meta = new JsonObject();
        meta.addProperty("schema", 1);
        meta.addProperty("kind", KIND);
        meta.addProperty("status", STATUS_PENDING_CREATE);
        meta.addProperty("priority", normalizePriority(priority));
        meta.addProperty("version", normalizeVersion(version));
        meta.addProperty("assignee", assignee == null ? "" : assignee.trim());
        meta.addProperty("reporter", reporter == null ? "" : reporter.trim());
        setLabels(meta, parseLabels(labels));
        meta.addProperty("created_at", now);
        meta.addProperty("updated_at", now);
        synchronized (runtime) {
            String ticketId = nextTicketId();
            meta.addProperty("ticket_id", ticketId);
            meta.addProperty("sheet_row", nextSheetRow());
            DocLibraryRuntime.ExternalWriteResult result = runtime.writeExternal("ticket-mcp",
                    DOC_PREFIX + ticketId, body, meta, cleanTitle, "工单创建", null);
            Ticket ticket = toTicket(result.record);
            requestDashboardSync(ticket.docId, true);
            return ticket;
        }
    }

    /**
     * 列出工单。
     *
     * @param status   状态筛选，可空；支持逗号/| 分隔多个，{@code all} 表示全部
     * @param assignee 负责人精确筛选，可空
     * @param reporter 报告人精确筛选，可空
     * @param label    标签筛选，可空；支持多个，命中任意一个即可
     * @param keyword  关键词（标题或正文，忽略大小写），可空
     * @param limit    最多返回条数
     * @param offset   分页偏移
     * @return 工单列表
     */
    public List<Ticket> list(String status, String assignee, String reporter, String label,
            String keyword, int limit, int offset) {
        if (status != null && !status.trim().isEmpty() && !"all".equalsIgnoreCase(status.trim())) {
            for (String part : status.split("[|,，]")) {
                String value = part.trim();
                if (!value.isEmpty() && !isValidStatus(value)) {
                    throw new DocException("TICKET_BAD_REQUEST",
                            "status 只能是 " + statusText() + " 之一");
                }
            }
        }
        List<DocRecord> docs = runtime.store().list(DOC_PREFIX, false, 1000, 0);
        List<Ticket> out = new ArrayList<>();
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        for (DocRecord summary : docs) {
            DocRecord record;
            try {
                record = runtime.store().current(summary.docId);
            } catch (RuntimeException ex) {
                continue;
            }
            if (record == null || record.deleted) {
                continue;
            }
            Ticket ticket;
            try {
                ticket = toTicket(record);
            } catch (DocException ex) {
                continue;
            }
            if (!matchesAny(ticket.status, status)) {
                continue;
            }
            if (!matchesText(ticket.assignee, assignee) || !matchesText(ticket.reporter, reporter)) {
                continue;
            }
            if (!matchesLabels(ticket.labels, label)) {
                continue;
            }
            if (!kw.isEmpty() && !ticket.title.toLowerCase().contains(kw)
                    && !ticket.body.toLowerCase().contains(kw)) {
                continue;
            }
            out.add(ticket);
        }
        int from = Math.min(Math.max(0, offset), out.size());
        int to = Math.min(out.size(), from + Math.max(1, limit));
        return new ArrayList<>(out.subList(from, to));
    }

    /**
     * 读取一张工单。
     *
     * @param ticketId 工单号或文档 id（{@code T-...} / {@code ticket/T-...}）
     * @return 工单
     */
    public Ticket get(String ticketId) {
        return toTicket(current(ticketId));
    }

    /**
     * 更新工单的结构化字段。参数为 null 表示“不修改”，空字符串表示“清空”
     * （标题、状态、优先级除外）。
     *
     * @param ticketId     工单号
     * @param status       新状态
     * @param priority     新优先级
     * @param version      新软件版本；空串表示清空
     * @param assignee     新负责人
     * @param reporter     新报告人
     * @param labels       新标签；空串表示清空
     * @param title        新标题；替换正文第一行
     * @param author       操作者
     * @param message      提交说明
     * @param baseRevision 期望的当前修订；null 表示不显式校验
     * @return 更新后的工单
     */
    public Ticket update(String ticketId, String status, String priority, String version, String assignee,
            String reporter, String labels, String title, String author, String message, Long baseRevision) {
        if (status == null && priority == null && version == null && assignee == null && reporter == null
                && labels == null && title == null) {
            throw new DocException("TICKET_BAD_REQUEST", "没有需要更新的字段");
        }
        synchronized (runtime) {
            DocRecord record = current(ticketId);
            Ticket old = toTicket(record);
            JsonObject meta = record.meta.deepCopy();
            long now = System.currentTimeMillis();
            if (status != null) {
                String value = normalizeStatus(status);
                if (!isValidStatus(value)) {
                    throw new DocException("TICKET_BAD_REQUEST",
                            "status 只能是 " + statusText() + " 之一");
                }
                meta.addProperty("status", value);
                if (isClosedStatus(value)) {
                    if (!meta.has("closed_at")) {
                        meta.addProperty("closed_at", now);
                    }
                } else {
                    meta.remove("closed_at");
                }
            }
            if (priority != null) {
                meta.addProperty("priority", normalizePriority(priority));
            }
            if (version != null) {
                meta.addProperty("version", normalizeVersion(version));
            }
            if (assignee != null) {
                meta.addProperty("assignee", assignee.trim());
            }
            if (reporter != null) {
                meta.addProperty("reporter", reporter.trim());
            }
            if (labels != null) {
                setLabels(meta, parseLabels(labels));
            }
            String body = old.body;
            String newTitle = old.title;
            if (title != null) {
                newTitle = requireTitle(title);
                body = replaceTitle(body, newTitle);
            }
            meta.addProperty("updated_at", now);
            String actor = author == null || author.trim().isEmpty() ? "ticket-mcp" : author.trim();
            // meta 的变化明细由 doclib 统一比较生成；TicketMcp 只挑工单字段通知
            DocLibraryRuntime.ExternalWriteResult result = runtime.writeExternal(actor,
                    old.docId, body, meta, newTitle,
                    message == null || message.trim().isEmpty() ? "工单更新" : message.trim(), baseRevision);
            Ticket ticket = toTicket(result.record);
            requestDashboardSync(ticket.docId, title != null);
            return ticket;
        }
    }

    /**
     * 编辑工单正文，语义与文档库的 {@code doc_write} 一致。
     * <p>meta 与工单状态字段原样保留；只替换/追加/补丁可见正文。</p>
     *
     * @param ticketId     工单号
     * @param mode         {@code overwrite} / {@code append} / {@code patch}
     * @param content      新正文；patch 时放 diff
     * @param author       操作者
     * @param message      提交说明
     * @param baseRevision 期望的当前修订；null 表示不显式校验
     * @return 更新后的工单
     */
    public Ticket write(String ticketId, String mode, String content, String author,
            String message, Long baseRevision) {
        String op = mode == null ? "overwrite" : mode.trim().toLowerCase();
        if (!"overwrite".equals(op) && !"append".equals(op) && !"patch".equals(op)) {
            throw new DocException("TICKET_BAD_REQUEST", "mode 只能是 overwrite / append / patch");
        }
        String value = content == null ? "" : content;
        synchronized (runtime) {
            DocRecord record = current(ticketId);
            Ticket old = toTicket(record);
            String newBody;
            try {
                if ("overwrite".equals(op)) {
                    newBody = value;
                } else if ("append".equals(op)) {
                    newBody = appendText(old.body, value);
                } else {
                    newBody = PatchApplier.apply(old.body, value);
                }
            } catch (IllegalArgumentException ex) {
                throw new DocException("TICKET_PATCH_FAILED", ex.getMessage());
            }
            if (newBody.trim().isEmpty()) {
                throw new DocException("TICKET_BAD_REQUEST", "正文不能为空");
            }
            // 标题始终跟随正文第一行，避免 patch 改到首行后标题与正文不一致
            String newTitle = DocStore.deriveTitle(newBody);
            JsonObject meta = record.meta.deepCopy();
            meta.addProperty("updated_at", System.currentTimeMillis());
            String actor = author == null || author.trim().isEmpty() ? "ticket-mcp" : author.trim();
            DocLibraryRuntime.ExternalWriteResult result = runtime.writeExternal(actor,
                    old.docId, newBody, meta, newTitle,
                    message == null || message.trim().isEmpty() ? "工单正文更新" : message.trim(), baseRevision);
            Ticket ticket = toTicket(result.record);
            requestDashboardSync(ticket.docId, true);
            return ticket;
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 工单变更后请求刷新在线表格对应行；未启用时无操作。
     * <p>看板自己的 meta（行编码/简述）会正常产生结构化 meta 事件，但 TicketMcp 只挑工单字段，
     * 这些字段被忽略，不会给 agent 发通知。</p>
     *
     * @param docId       工单文档 id
     * @param bodyChanged 正文（含标题）是否变化；为 true 时看板会重新生成简述
     */
    private void requestDashboardSync(String docId, boolean bodyChanged) {
        TicketDashboard current = dashboard;
        if (current != null) {
            current.requestTicket(docId, bodyChanged);
        }
    }

    /**
     * 把看板简述写进工单 meta。事件仍会正常进入 doclib，TicketMcp 只挑工单字段，不会通知 agent。
     *
     * @param ticketId 工单号
     * @param summary  简述
     * @return 更新后的工单
     */
    public Ticket setSheetSummary(String ticketId, String summary) {
        synchronized (runtime) {
            for (int attempt = 0; attempt < 2; attempt++) {
                DocRecord record = current(ticketId);
                JsonObject meta = record.meta.deepCopy();
                meta.addProperty("sheet_summary", summary == null ? "" : summary);
                try {
                    DocLibraryRuntime.ExternalWriteResult result = runtime.writeExternal("ticket-dashboard",
                            record.docId, record.body, meta, record.title, "工单看板简述", record.revision);
                    System.out.println("[TicketSheet] 简述已写入工单 meta：" + record.docId
                            + " rev=" + result.revision + " chars=" + (summary == null ? 0 : summary.length()));
                    return toTicket(result.record);
                } catch (DocException ex) {
                    // 只更新了 meta（例如别处刚改了状态）时用最新 revision 重试；
                    // 正文已变说明简述基于旧正文，直接放弃，等下一次正文变化重新生成。
                    if (!"DOC_STALE".equals(ex.code) || attempt > 0) {
                        throw ex;
                    }
                    DocRecord latest = current(ticketId);
                    if (!latest.body.equals(record.body) || !latest.title.equals(record.title)) {
                        throw ex;
                    }
                }
            }
            throw new DocException("DOC_INTERNAL", "保存看板简述失败：" + ticketId);
        }
    }

    /**
     * 给还没有行编码的工单分配表格行号；由看板启动时调用。
     * <p>行号写进工单 meta，会产生正常的结构化 meta 事件；TicketMcp 只挑工单字段，忽略该字段。</p>
     */
    public void ensureSheetRows() {
        synchronized (runtime) {
            List<Ticket> tickets = list(null, null, null, null, null, 1000, 0);
            int max = 1;
            for (Ticket ticket : tickets) {
                max = Math.max(max, ticket.sheetRow);
            }
            for (Ticket ticket : tickets) {
                if (ticket.sheetRow <= 0) {
                    max++;
                    try {
                        assignSheetRow(ticket.docId, max);
                    } catch (DocException ex) {
                        System.out.println("[TicketSheet] 分配工单行失败 " + ticket.docId + "：" + ex.getMessage());
                    }
                }
            }
        }
    }

    private int nextSheetRow() {
        int max = 1;
        for (Ticket ticket : list(null, null, null, null, null, 1000, 0)) {
            max = Math.max(max, ticket.sheetRow);
        }
        return max + 1;
    }

    private void assignSheetRow(String docId, int row) {
        DocRecord record = runtime.store().current(docId);
        if (record == null || record.deleted || record.meta == null) {
            return;
        }
        JsonObject meta = record.meta.deepCopy();
        meta.addProperty("sheet_row", row);
        runtime.writeExternal("ticket-dashboard", docId, record.body, meta, record.title,
                "工单看板行分配", record.revision);
    }

    private DocRecord current(String ticketId) {
        String docId = normalizeDocId(ticketId);
        DocRecord record = runtime.store().current(docId);
        if (record == null || record.deleted) {
            throw new DocException("TICKET_NOT_FOUND", "工单不存在：" + ticketId);
        }
        return record;
    }

    private static Ticket toTicket(DocRecord record) {
        JsonObject meta = record.meta;
        if (meta == null || !KIND.equals(str(meta, "kind"))) {
            throw new DocException("TICKET_NOT_FOUND", "文档不是工单：" + record.docId);
        }
        String ticketId = str(meta, "ticket_id");
        if (ticketId.isEmpty()) {
            ticketId = record.docId.startsWith(DOC_PREFIX)
                    ? record.docId.substring(DOC_PREFIX.length()) : record.docId;
        }
        String status = normalizeStatus(str(meta, "status"));
        if (status.isEmpty()) {
            status = STATUS_PENDING_CREATE;
        }
        if (!isValidStatus(status)) {
            throw new DocException("TICKET_BAD_REQUEST",
                    "工单 " + record.docId + " 的状态非法：" + str(meta, "status")
                            + "；允许值：" + statusText());
        }
        return new Ticket(ticketId, record.docId, record.title, record.body,
                status, str(meta, "priority"), str(meta, "version"),
                str(meta, "assignee"), str(meta, "reporter"),
                readLabels(meta), record.revision,
                longOf(meta, "created_at", record.createdAt),
                longOf(meta, "updated_at", record.updatedAt),
                longOf(meta, "closed_at", 0L),
                intOf(meta, "sheet_row"),
                str(meta, "sheet_summary"));
    }

    private static String normalizeDocId(String ticketId) {
        String id = ticketId == null ? "" : ticketId.trim();
        if (id.startsWith(DOC_PREFIX)) {
            id = id.substring(DOC_PREFIX.length());
        }
        if (id.isEmpty()) {
            throw new DocException("TICKET_BAD_REQUEST", "ticket_id 不能为空");
        }
        if (id.contains("..") || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new DocException("TICKET_BAD_REQUEST",
                    "ticket_id 只能使用字母、数字、._:-，且以字母/数字开头");
        }
        return DOC_PREFIX + id;
    }

    private String nextTicketId() {
        String stamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        for (int i = 0; i < 8; i++) {
            String suffix = String.format("%04X", ThreadLocalRandom.current().nextInt(0x10000));
            String id = "T-" + stamp + "-" + suffix;
            if (runtime.store().current(DOC_PREFIX + id) == null) {
                return id;
            }
        }
        return "T-" + stamp + "-" + System.nanoTime();
    }

    private static String requireTitle(String title) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) {
            throw new DocException("TICKET_BAD_REQUEST", "title 不能为空");
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new DocException("TICKET_BAD_REQUEST", "title 不能包含换行");
        }
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static String normalizePriority(String priority) {
        String value = priority == null ? "normal" : priority.trim().toLowerCase();
        if (value.isEmpty()) {
            value = "normal";
        }
        if (!PRIORITIES.contains(value)) {
            throw new DocException("TICKET_BAD_REQUEST",
                    "priority 只能是 " + PRIORITIES + " 之一");
        }
        return value;
    }

    /** 软件版本文本：去首尾空白，允许为空。 */
    private static String normalizeVersion(String version) {
        return version == null ? "" : version.trim();
    }

    private static void setLabels(JsonObject meta, List<String> labels) {
        JsonArray array = new JsonArray();
        for (String label : labels) {
            array.add(label);
        }
        meta.add("labels", array);
    }

    private static List<String> readLabels(JsonObject meta) {
        JsonElement element = meta.get("labels");
        List<String> out = new ArrayList<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) {
                    String value = item.getAsString().trim();
                    if (!value.isEmpty()) {
                        out.add(value);
                    }
                }
            }
        }
        return out;
    }

    private static List<String> parseLabels(String labels) {
        List<String> out = new ArrayList<>();
        if (labels == null) {
            return out;
        }
        for (String part : labels.split("[|,，]")) {
            String value = part.trim();
            if (!value.isEmpty() && !out.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    private static String replaceTitle(String body, String title) {
        String[] lines = (body == null ? "" : body).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].trim().isEmpty()) {
                lines[i] = title;
                return String.join("\n", lines);
            }
        }
        return title;
    }

    private static String appendText(String oldText, String add) {
        if (oldText.isEmpty()) {
            return add;
        }
        if (oldText.endsWith("\n") || add.startsWith("\n")) {
            return oldText + add;
        }
        return oldText + "\n" + add;
    }

    private static boolean matchesText(String value, String filter) {
        return filter == null || filter.trim().isEmpty() || value.equalsIgnoreCase(filter.trim());
    }

    private static boolean matchesAny(String value, String filter) {
        if (filter == null || filter.trim().isEmpty() || "all".equalsIgnoreCase(filter.trim())) {
            return true;
        }
        String normalized = normalizeStatus(value);
        for (String part : filter.split("[|,，]")) {
            if (normalized.equals(normalizeStatus(part))) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesLabels(List<String> labels, String filter) {
        if (filter == null || filter.trim().isEmpty()) {
            return true;
        }
        for (String part : filter.split("[|,，]")) {
            String value = part.trim();
            for (String label : labels) {
                if (label.equalsIgnoreCase(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String str(JsonObject json, String key) {
        JsonElement element = json == null ? null : json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        try {
            return element.getAsString();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static long longOf(JsonObject json, String key, long def) {
        JsonElement element = json == null ? null : json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return def;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static int intOf(JsonObject json, String key) {
        JsonElement element = json == null ? null : json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /** 工单时间格式。 */
    public static String formatTime(long epoch) {
        if (epoch <= 0) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(epoch));
    }

    /** 文档库 id（日志用）。 */
    public String libraryId() {
        return runtime.id();
    }

    /** 文档库根目录（日志用）。 */
    public File root() {
        return runtime.root();
    }
}
