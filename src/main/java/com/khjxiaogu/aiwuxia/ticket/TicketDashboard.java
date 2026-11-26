package com.khjxiaogu.aiwuxia.ticket;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.khjxiaogu.aiwuxia.sheet.SheetApi;
import com.khjxiaogu.aiwuxia.sheet.SheetMeta;
import com.khjxiaogu.aiwuxia.sheet.SheetProvider;

/**
 * 工单看板：把工单关键字段覆盖写入在线表格（sheet 系统）。
 * <p>
 * 表格只作为只读看板，工单系统是唯一写入方：不读取单元格内容、不监听表格编辑、不做合并。
 * 启动时做一次数量核对（分配行编码、刷新表头）：数量一致时只写表头、不覆盖已有数据行；
 * 数量不一致才整表初始化。之后每次工单变更只按 meta 里的 {@code sheet_row} 更新对应行。
 * 表格不写正文；正文/标题变化时调用 {@link TicketSummaryAgent} 生成 {@code sheet_summary} 简述列，写回该行。
 * </p>
 * <p>
 * 同一工单库只创建一个实例（静态注册表按库 id 共享），后台单线程合并刷新：
 * 工单变更后延迟 {@code syncDelayMs} 触发一次，两次真实写入至少间隔 {@code minSyncIntervalMs}，
 * 避免腾讯文档 50 次/分钟的写入限制。同步失败只记日志，不影响工单操作。
 * </p>
 * <p>
 * <b>重要约束</b>：看板同步只读取工单列表、只写在线表格，绝不调用
 * {@code writeExternal} 或修改工单 meta，因此不会产生 outbox 事件，
 * 也不会触发文档监听器。看板自己的同步状态（如果需要）必须放在独立配置/状态文件里，
 * 不要写进工单 meta。
 * </p>
 */
public final class TicketDashboard {

    /** 单次写入行数上限（腾讯文档单次范围上限 1000 行）。 */
    private static final int MAX_ROWS = 1000;
    /** 每个库共享一个看板实例，避免多个 agent 同时覆盖同一张表。 */
    private static final Map<String, TicketDashboard> INSTANCES = new ConcurrentHashMap<>();

    private static final Map<String, String> HEADERS = new LinkedHashMap<>();

    static {
        HEADERS.put("ticket_id", "工单号");
        HEADERS.put("doc_id", "文档id");
        HEADERS.put("title", "标题");
        HEADERS.put("summary", "简述");
        HEADERS.put("version", "版本");
        HEADERS.put("status", "状态");
        HEADERS.put("priority", "优先级");
        HEADERS.put("assignee", "负责人");
        HEADERS.put("reporter", "报告人");
        HEADERS.put("labels", "标签");
        HEADERS.put("revision", "修订");
        HEADERS.put("created_at", "创建时间");
        HEADERS.put("updated_at", "更新时间");
        HEADERS.put("closed_at", "关闭时间");
    }

    private final TicketService service;
    private final TicketSheetConfig config;
    private final SheetApi sheet;
    private final List<String> columns;
    private final List<String> headers;
    private final ScheduledExecutorService scheduler;
    private final Object scheduleLock = new Object();
    private final Object syncLock = new Object();
    /** 待更新的工单文档 id → 是否需要重新生成正文简述。 */
    private final java.util.Map<String, Boolean> pendingTickets = new java.util.LinkedHashMap<>();
    /** 是否已经完成启动时的整表初始化（表头只在这里覆盖）。 */
    private volatile boolean startupDone;
    private volatile boolean scheduled;
    private volatile boolean closed;
    private volatile long lastSyncAt;
    private volatile String resolvedSheetId;

    private TicketDashboard(TicketSheetConfig config, TicketService service, SheetApi sheet) {
        this.config = config;
        this.service = service;
        this.sheet = sheet;
        List<String> keys = new ArrayList<>();
        for (String raw : config.getColumns()) {
            String key = normalizeColumn(raw);
            if (key != null && !keys.contains(key)) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            keys.addAll(TicketSheetConfig.DEFAULT_COLUMNS);
        }
        this.columns = Collections.unmodifiableList(keys);
        List<String> headerList = new ArrayList<>();
        for (String key : keys) {
            headerList.add(HEADERS.get(key));
        }
        this.headers = Collections.unmodifiableList(headerList);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ticket-dashboard-" + (service == null ? "" : service.libraryId()));
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 为工单库创建/获取共享看板。配置未启用、没有表格凭据或创建失败时返回 null。
     *
     * @param dataFolder 数据目录（save/）
     * @param libraryId  工单库 id
     * @param service    工单服务（用于读取工单列表）
     * @return 看板实例或 null
     */
    public static TicketDashboard forLibrary(File dataFolder, String libraryId, TicketService service) {
        TicketSheetConfig config = TicketSheetConfig.load(dataFolder);
        if (!config.isEnabled()) {
            return null;
        }
        TicketDashboard existing = INSTANCES.get(libraryId);
        if (existing != null) {
            return existing;
        }
        try {
            SheetApi sheet = config.createApi();
            TicketDashboard created = new TicketDashboard(config, service, sheet);
            TicketDashboard raced = INSTANCES.putIfAbsent(libraryId, created);
            if (raced != null) {
                created.close();
                return raced;
            }
            System.out.println("[TicketSheet] 工单看板已启用（" + config.getProvider().getDisplayName()
                    + "）：表格 "
                    + (config.getTableId().isEmpty() ? config.getSheetTitle() : config.getTableId()));
            if (config.isSyncOnStart()) {
                created.requestStartupSync();
            }
            return created;
        } catch (Throwable t) {
            System.out.println("[TicketSheet] 工单看板初始化失败：" + t.getMessage());
            return null;
        }
    }

    /**
     * 请求启动时的整表初始化：分配缺失行编码、写表头、刷新全部工单行。
     */
    public void requestStartupSync() {
        schedule(null, false);
    }

    /**
     * 请求只更新某张工单对应的表格行。
     *
     * @param docId       工单文档 id
     * @param bodyChanged 正文（含标题）是否变化；为 true 时重新生成表格简述
     */
    public void requestTicket(String docId, boolean bodyChanged) {
        if (docId == null || docId.isEmpty()) {
            return;
        }
        schedule(docId, bodyChanged);
    }

    private void schedule(String docId, boolean bodyChanged) {
        if (closed) {
            return;
        }
        synchronized (scheduleLock) {
            if (docId != null) {
                pendingTickets.put(docId, bodyChanged || Boolean.TRUE.equals(pendingTickets.get(docId)));
            }
            if (scheduled) {
                return;
            }
            scheduled = true;
            long delay = config.getSyncDelayMs();
            long sinceLast = System.currentTimeMillis() - lastSyncAt;
            if (lastSyncAt > 0 && sinceLast < config.getMinSyncIntervalMs()) {
                delay = Math.max(delay, config.getMinSyncIntervalMs() - sinceLast);
            }
            try {
                scheduler.schedule(this::runScheduled, delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ex) {
                scheduled = false;
            }
        }
    }

    private void runScheduled() {
        java.util.Map<String, Boolean> tickets;
        synchronized (scheduleLock) {
            scheduled = false;
            tickets = new java.util.LinkedHashMap<>(pendingTickets);
            pendingTickets.clear();
        }
        try {
            synchronized (syncLock) {
                if (closed) {
                    return;
                }
                lastSyncAt = System.currentTimeMillis();
                String sheetId = resolveSheetId();
                List<TicketService.Ticket> startupTickets = null;
                if (!startupDone) {
                    startupTickets = startupSync(sheetId);
                    startupDone = true;
                    // 启动整表已包含当前数据，但正文刚变化的工单仍需补生成简述
                    tickets.entrySet().removeIf(entry -> !entry.getValue());
                }
                if (startupTickets != null) {
                    // 历史工单没有 sheet_summary：启动后补生成简述并只更新这些行
                    for (TicketService.Ticket ticket : startupTickets) {
                        if (ticket.summary.isEmpty() && !ticket.body.trim().isEmpty()) {
                            tickets.put(ticket.docId, true);
                        }
                    }
                }
                if (!tickets.isEmpty()) {
                    flushTickets(sheetId, tickets);
                }
            }
        } catch (Throwable t) {
            System.out.println("[TicketSheet] 工单看板同步失败：" + t.getMessage());
        }
    }

    /** 启动核对：数量一致只刷新表头；数量不一致才整表初始化。 */
    private List<TicketService.Ticket> startupSync(String sheetId) throws Exception {
        // 先补齐缺失的行编码；写 meta 不会触发文档监听器
        service.ensureSheetRows();
        List<TicketService.Ticket> tickets = service.list(null, null, null, null, null, MAX_ROWS - 1, 0);
        int maxRow = 1;
        for (TicketService.Ticket ticket : tickets) {
            if (ticket.sheetRow > maxRow) {
                maxRow = ticket.sheetRow;
            }
        }
        int existingRows = -1;
        try {
            SheetMeta meta = sheet.getSheet(sheetId);
            existingRows = meta == null ? -1 : meta.getRowCount();
        } catch (Throwable t) {
            System.out.println("[TicketSheet] 读取工作表行数失败：" + t.getMessage());
        }
        int dataRows = existingRows > 0 ? existingRows - 1 : 0;
        if (existingRows >= 0 && dataRows == tickets.size() && maxRow <= existingRows) {
            // 数量一致：再核对一次表头；表头也一致时完全不写
            if (headerMatches(sheetId)) {
                System.out.println("[TicketSheet] 启动核对：工单 " + tickets.size() + " 条，表格数据行 "
                        + dataRows + " 行，表头一致，跳过覆盖");
                return tickets;
            }
            sheet.writeText(sheetId, "A1", Collections.singletonList(new ArrayList<>(headers)));
            System.out.println("[TicketSheet] 启动核对：工单 " + tickets.size() + " 条，表格数据行 "
                    + dataRows + " 行，数量一致但表头不一致，仅刷新表头");
            return tickets;
        }
        if (existingRows > 1 || existingRows < 0) {
            // 数量不一致（或无法确认数量）：先删除全部旧数据行（飞书直接删记录），再整表重建
            sheet.clearAllData(sheetId, columns.size());
            System.out.println("[TicketSheet] 启动核对：数量不一致，已删除表格全部数据行，准备重建");
            existingRows = 0;
        }
        int total = Math.max(maxRow, existingRows > 0 ? existingRows : 0);
        total = Math.min(Math.max(total, 1), MAX_ROWS);
        int width = headers.size();
        List<List<String>> rows = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            rows.add(Collections.nCopies(width, ""));
        }
        rows.set(0, new ArrayList<>(headers));
        for (TicketService.Ticket ticket : tickets) {
            int row = ticket.sheetRow;
            if (row >= 2 && row <= total) {
                rows.set(row - 1, rowOf(ticket));
            }
        }
        sheet.writeText(sheetId, "A1", rows);
        System.out.println("[TicketSheet] 启动核对：工单 " + tickets.size() + " 条，表格数据行 "
                + dataRows + " 行，数量不一致，已整表初始化 " + tickets.size() + " 张工单");
        return tickets;
    }

    /** 读取第 1 行并与配置的表头比较；读取失败按不一致处理。 */
    private boolean headerMatches(String sheetId) {
        try {
            String range = "A1:" + com.khjxiaogu.aiwuxia.sheet.A1Range.columnName(columns.size() - 1) + "1";
            List<List<String>> rows = sheet.readText(sheetId, range);
            if (rows.isEmpty() || rows.get(0).size() < headers.size()) {
                return false;
            }
            List<String> actual = rows.get(0);
            for (int i = 0; i < headers.size(); i++) {
                String value = actual.get(i) == null ? "" : actual.get(i).trim();
                if (!headers.get(i).equals(value)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            System.out.println("[TicketSheet] 读取表头失败：" + t.getMessage());
            return false;
        }
    }

    /** 只更新指定工单对应的行，不碰表头和其它行。 */
    private void flushTickets(String sheetId, java.util.Map<String, Boolean> tickets) {
        for (java.util.Map.Entry<String, Boolean> entry : tickets.entrySet()) {
            String docId = entry.getKey();
            try {
                TicketService.Ticket ticket = service.get(docId);
                if (entry.getValue() || ticket.summary.isEmpty()) {
                    // 正文变化才调用一次非推理摘要器；结果写回 meta。
                    // 这次 meta 变化会产生 doclib 事件，TicketMcp 只挑工单字段，忽略 sheet_summary。
                    String summary = TicketSummaryAgent.summarize(ticket.title, ticket.body, ticket.summary);
                    if (!summary.isEmpty()) {
                        ticket = service.setSheetSummary(ticket.docId, summary);
                    }
                }
                int row = ticket.sheetRow;
                if (row < 2 || row > MAX_ROWS) {
                    continue;
                }
                String range = "A" + row + ":"
                        + com.khjxiaogu.aiwuxia.sheet.A1Range.columnName(columns.size() - 1) + row;
                sheet.writeText(sheetId, range, Collections.singletonList(rowOf(ticket)));
            } catch (Throwable t) {
                System.out.println("[TicketSheet] 更新工单行失败 " + docId + "：" + t.getMessage());
            }
        }
    }

    private String resolveSheetId() throws Exception {
        String current = resolvedSheetId;
        if (current != null && !current.isEmpty()) {
            return current;
        }
        synchronized (syncLock) {
            current = resolvedSheetId;
            if (current != null && !current.isEmpty()) {
                return current;
            }
            if (!config.getTableId().isEmpty()) {
                resolvedSheetId = config.getTableId();
                return resolvedSheetId;
            }
            String title = config.getSheetTitle();
            for (SheetMeta meta : sheet.listSheets()) {
                if (title.equals(meta.getTitle())) {
                    resolvedSheetId = meta.getSheetId();
                    return resolvedSheetId;
                }
            }
            SheetMeta created = sheet.addSheet(title, MAX_ROWS,
                    config.getProvider() == SheetProvider.FEISHU ? 0 : Math.max(columns.size(), 10));
            if (created == null || created.getSheetId() == null || created.getSheetId().isEmpty()) {
                throw new IllegalStateException("新建工作表「" + title + "」后没有拿到 sheetId");
            }
            resolvedSheetId = created.getSheetId();
            return resolvedSheetId;
        }
    }

    private List<String> rowOf(TicketService.Ticket ticket) {
        List<String> row = new ArrayList<>(columns.size());
        for (String column : columns) {
            row.add(valueOf(ticket, column));
        }
        return row;
    }

    private static String valueOf(TicketService.Ticket ticket, String column) {
        switch (column) {
        case "ticket_id":
            return ticket.ticketId;
        case "doc_id":
            return ticket.docId;
        case "title":
            return ticket.title;
        case "summary":
            return ticket.summary;
        case "version":
            return ticket.version;
        case "status":
            return ticket.status;
        case "priority":
            return ticket.priority;
        case "assignee":
            return ticket.assignee;
        case "reporter":
            return ticket.reporter;
        case "labels":
            return String.join(", ", ticket.labels);
        case "revision":
            return Long.toString(ticket.revision);
        case "created_at":
            return TicketService.formatTime(ticket.createdAt);
        case "updated_at":
            return TicketService.formatTime(ticket.updatedAt);
        case "closed_at":
            return ticket.closedAt > 0 ? TicketService.formatTime(ticket.closedAt) : "";
        default:
            return "";
        }
    }

    /** 列名规范化；未知列返回 null（忽略）。 */
    private static String normalizeColumn(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase().replace('-', '_').replace(' ', '_');
        if ("id".equals(key)) {
            key = "ticket_id";
        } else if ("created".equals(key)) {
            key = "created_at";
        } else if ("updated".equals(key)) {
            key = "updated_at";
        } else if ("closed".equals(key)) {
            key = "closed_at";
        }
        return HEADERS.containsKey(key) ? key : null;
    }

    public String libraryId() {
        return service == null ? "" : service.libraryId();
    }

    public List<String> getColumns() {
        return columns;
    }

    /** 关闭后台线程（共享实例通常随进程结束）。 */
    public void close() {
        closed = true;
        scheduler.shutdownNow();
    }
}
