package com.khjxiaogu.aiwuxia.ticket;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.sheet.SheetApi;
import com.khjxiaogu.aiwuxia.sheet.SheetApiFactory;
import com.khjxiaogu.aiwuxia.sheet.SheetException;
import com.khjxiaogu.aiwuxia.sheet.SheetProvider;
import com.khjxiaogu.aiwuxia.utils.ConfigPaths;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;

/**
 * 工单看板的在线表格配置，默认读取 {@code save/config/ticket-sheet.json}。
 * <p>
 * 文件不存在时看板不同步。示例：
 * </p>
 * <pre>
 * {
 *   "enabled": true,
 *   "provider": "feishu",         // feishu（默认）或 tencent
 *   "tableId": "",                // 数据表/子表 ID；留空时按 sheetTitle 查找
 *   "sheetTitle": "工单看板",
 *   "syncOnStart": true,
 *   "syncDelayMs": 1500,          // 变更后延迟合并刷新
 *   "minSyncIntervalMs": 5000,    // 两次真实写入的最小间隔
 *   "columns": ["ticket_id", "title", "summary", "version", "status", "priority", "assignee", "reporter", "labels", "revision", "created_at", "updated_at"]
 * }
 * </pre>
 * <p>
 * {@code provider} 选择供应商：{@code tencent} 用 {@code tencent-docs.json} 的凭据，
 * {@code feishu} 用 {@code feishu-bitable.json} 的凭据（含连接级 {@code app_token}）。
 * 表格只作为看板：工单系统只覆盖写入，不读取/合并单元格内容，也不监听表格编辑。
 * </p>
 */
public final class TicketSheetConfig {

    /** 默认展示列（顺序即表格列顺序）。 */
    public static final List<String> DEFAULT_COLUMNS = Collections.unmodifiableList(Arrays.asList(
            "ticket_id", "title", "summary", "version", "status", "priority", "assignee", "reporter",
            "labels", "revision", "created_at", "updated_at"));

    private final File sourceFile;
    private final File dataFolder;
    private final boolean enabled;
    private final SheetProvider provider;
    /** 业务表格 ID：腾讯文档子表 ID / 飞书数据表 table_id。 */
    private final String tableId;
    private final String sheetTitle;
    private final List<String> columns;
    private final boolean syncOnStart;
    private final int syncDelayMs;
    private final int minSyncIntervalMs;

    private TicketSheetConfig(File sourceFile, File dataFolder, boolean enabled, SheetProvider provider,
            String tableId, String sheetTitle, List<String> columns, boolean syncOnStart, int syncDelayMs,
            int minSyncIntervalMs) {
        this.sourceFile = sourceFile;
        this.dataFolder = dataFolder;
        this.enabled = enabled;
        this.provider = provider == null ? SheetProvider.FEISHU : provider;
        this.tableId = tableId == null ? "" : tableId.trim();
        this.sheetTitle = sheetTitle == null || sheetTitle.trim().isEmpty() ? "工单看板" : sheetTitle.trim();
        this.columns = columns == null || columns.isEmpty()
                ? new ArrayList<>(DEFAULT_COLUMNS) : new ArrayList<>(columns);
        this.syncOnStart = syncOnStart;
        this.syncDelayMs = Math.max(0, syncDelayMs);
        this.minSyncIntervalMs = Math.max(1000, minSyncIntervalMs);
    }

    /**
     * 读取配置。文件不存在或损坏时返回禁用配置，不抛异常。
     *
     * @param dataFolder 数据目录（save/）
     * @return 配置
     */
    public static TicketSheetConfig load(File dataFolder) {
        File file = ConfigPaths.ticketSheet(dataFolder);
        if (file == null || !file.isFile()) {
            return new TicketSheetConfig(file, dataFolder, false, SheetProvider.FEISHU, "", "", null, false, 0,
                    1000);
        }
        boolean enabled = true;
        SheetProvider provider = SheetProvider.FEISHU;
        String tableId = "";
        String sheetTitle = "工单看板";
        List<String> columns = null;
        boolean syncOnStart = true;
        int syncDelayMs = 1500;
        int minSyncIntervalMs = 5000;
        try {
            JsonObject json = JsonParser.parseString(FileUtil.readString(file)).getAsJsonObject();
            enabled = JsonConfig.getBool(json, "enabled", true);
            provider = SheetProvider.parse(JsonConfig.getString(json, "provider", "feishu"));
            tableId = JsonConfig.getString(json, "tableId", JsonConfig.getString(json, "sheetId", ""));
            sheetTitle = JsonConfig.getString(json, "sheetTitle", "工单看板");
            columns = JsonConfig.getStringList(json, "columns");
            syncOnStart = JsonConfig.getBool(json, "syncOnStart", true);
            syncDelayMs = JsonConfig.getInt(json, "syncDelayMs", 1500);
            minSyncIntervalMs = JsonConfig.getInt(json, "minSyncIntervalMs", 5000);
        } catch (Throwable t) {
            System.out.println("[TicketSheet] 配置读取失败 " + file.getPath() + "：" + t.getMessage());
            enabled = false;
        }
        return new TicketSheetConfig(file, dataFolder, enabled, provider, tableId, sheetTitle, columns,
                syncOnStart, syncDelayMs, minSyncIntervalMs);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return 当前选择的电子表格供应商
     */
    public SheetProvider getProvider() {
        return provider;
    }

    public File getSourceFile() {
        return sourceFile;
    }

    /**
     * @return 业务表格 ID：腾讯文档子表 ID / 飞书数据表 table_id
     */
    public String getTableId() {
        return tableId;
    }

    /**
     * 兼容旧名，等同 {@link #getTableId()}。
     *
     * @return 业务表格 ID
     */
    public String getSheetId() {
        return tableId;
    }

    public String getSheetTitle() {
        return sheetTitle;
    }

    public List<String> getColumns() {
        return columns;
    }

    public boolean isSyncOnStart() {
        return syncOnStart;
    }

    public int getSyncDelayMs() {
        return syncDelayMs;
    }

    public int getMinSyncIntervalMs() {
        return minSyncIntervalMs;
    }

    /**
     * 按 {@code provider} 创建表格 API：腾讯文档读取 {@code tencent-docs.json}，飞书读取
     * {@code feishu-bitable.json}（凭据 + 连接级 app_token）。业务只需要提供 {@code tableId}。
     *
     * @return 表格 API
     * @throws SheetException 凭据或连接配置不完整
     */
    public SheetApi createApi() throws SheetException {
        return SheetApiFactory.create(dataFolder, provider, null);
    }
}
