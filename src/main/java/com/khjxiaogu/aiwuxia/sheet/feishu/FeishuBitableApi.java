package com.khjxiaogu.aiwuxia.sheet.feishu;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.khjxiaogu.aiwuxia.sheet.A1Range;
import com.khjxiaogu.aiwuxia.sheet.CellValue;
import com.khjxiaogu.aiwuxia.sheet.SheetApi;
import com.khjxiaogu.aiwuxia.sheet.SheetData;
import com.khjxiaogu.aiwuxia.sheet.SheetException;
import com.khjxiaogu.aiwuxia.sheet.SheetMeta;
import com.khjxiaogu.aiwuxia.utils.ConfigPaths;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.webserver.loging.SimpleLogger;

/**
 * 飞书多维表格（Bitable）API 接入。
 * <p>
 * 实现通用 {@link SheetApi}，把「数据表」当作「工作表」、「字段」当作「列」。由于多维表格是
 * 记录（record）模型而不是单元格网格，行列映射约定如下：
 * </p>
 * <ul>
 * <li><b>工作表 ID</b> 即数据表 {@code table_id}；</li>
 * <li><b>列</b>默认按字段列表顺序对应 A、B、C……；写一次 A1 表头后，列顺序以<b>逻辑表头</b>为准，
 * 读写都会按“逻辑列名 → 实际字段”的映射重排，不需要调整字段物理顺序；</li>
 * <li><b>第 1 行</b>固定是字段名（表头），由读取时动态生成，不占用记录；<b>第 2 行起</b>对应记录，
 * 行号是记录在当前顺序中的位置，不是稳定的记录 ID；</li>
 * <li>{@link #readRange(String, String)} 从 A1 开始时返回「表头 + 记录」，从 A2 开始时只返回记录；</li>
 * <li>{@link #writeRange(String, String, List)} 从 A1 开始时把第一行当<b>逻辑表头</b>：表头每列必须
 * 有非空且唯一的字段名；按名称匹配现有字段，缺失字段追加创建。表头顺序可以随意修改（重排、
 * 增删、改名），本类记录「逻辑列名 → 实际字段」映射，之后的读写按映射重排，不调整字段物理顺序。
 * 旧字段默认保留（数据不丢），需要删除列时显式调用 {@link #deleteField(String, String)}；</li>
 * <li>从 A2 或更后开始时按已记录的逻辑表头映射字段；本实例还没有记录过表头（例如刚重启）时退回
 * 物理字段顺序，所以建议启动时先写一次 A1 表头建立映射；</li>
 * <li>{@link #appendRows(String, List)} 直接批量新增记录，同样按已记录的逻辑表头映射；</li>
 * <li>公式、查找引用、创建/修改时间、创建/修改人、自动编号等只读字段读取时正常展示，写入时跳过；
 * 人员、附件、关联、群组、地理位置等字段读取时降级为文本，写入时抛
 * {@link IllegalArgumentException}（需要飞书内部 ID，通用模型无法表达）；</li>
 * <li>日期字段按服务器本地时区（{@link ZoneId#systemDefault()}）与毫秒时间戳互转；多选字段读写都以
 * 中文顿号 {@code 、} 分隔选项；</li>
 * <li>记录顺序按飞书默认顺序；并发编辑会改变行位置，本类不做跨进程的并发保护。</li>
 * </ul>
 * <p>
 * 官方限制（本类在发请求前/分批时处理）：单次读取最多 5000 行、20000 单元格；单次写入最多 5000 行、
 * 20000 单元格；批量新增/更新单次最多 1000 条记录；单表最多 20000 条记录。写入频率约 50 次/秒、
 * 读取约 20 次/秒，遇到 HTTP 429、业务码 99991400/1254290 会短退避重试两次。
 * </p>
 * <p>
 * 鉴权：配置 {@code access_token} 时直接作为 {@code Bearer} 用户令牌；否则用 {@code app_id} +
 * {@code app_secret} 换取 {@code tenant_access_token}（进程内缓存，过期前 120 秒自动续期），
 * 接口返回凭证失效时自动刷新并重试一次。凭据配置见 {@link FeishuBitableConfig}。
 * </p>
 * <p>
 * 同一张数据表不支持并发写（飞书返回 1254291），本类在每个实例内串行化写操作；多实例部署时需由
 * 调用方自行串行。
 * </p>
 */
public class FeishuBitableApi implements SheetApi {
	/** 实现名称。 */
	public static final String PROVIDER_NAME = "飞书多维表格";

	private static final String API_PREFIX = "/open-apis";
	private static final String JSON = "application/json";
	private static final int HTTP_TIMEOUT_MS = 30000;

	/** 分页大小与上限。 */
	private static final int PAGE_SIZE = 500;
	private static final int TABLE_PAGE_SIZE = 100;
	private static final int FIELD_PAGE_SIZE = 100;
	private static final int MAX_TABLE_PAGES = 10;
	private static final int MAX_FIELD_PAGES = 10;
	private static final int MAX_RECORD_PAGES = 40;

	/** 批量与范围上限。 */
	private static final int MAX_BATCH_RECORDS = 1000;
	private static final int MAX_READ_RECORDS = 5000;
	private static final long MAX_READ_CELLS = 20000L;
	private static final int MAX_WRITE_ROWS = 5000;
	private static final long MAX_WRITE_CELLS = 20000L;
	private static final int MAX_SHEET_COLUMNS = 300;
	private static final int MAX_SHEET_TITLE_LENGTH = 100;

	private static final int MAX_ERROR_TEXT = 400;

	private static final DateTimeFormatter[] DATE_TIME_FORMATS = {
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
			DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
			DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm") };
	private static final DateTimeFormatter[] DATE_FORMATS = {
			DateTimeFormatter.ofPattern("yyyy-MM-dd"),
			DateTimeFormatter.ofPattern("yyyy/MM/dd") };

	private final SimpleLogger logger = new SimpleLogger("飞书多维表格");
	/** 凭据与连接级 app_token。 */
	private final FeishuBitableConfig config;
	private final Object tokenLock = new Object();
	private final Object writeLock = new Object();
	/**
	 * 每张数据表的逻辑表头顺序（列名顺序）。
	 * <p>
	 * 飞书字段的物理顺序不会因为写表头而改变；这里记录调用方通过 A1 写入的逻辑列顺序，后续读写
	 * 都按“逻辑列名 → 实际字段”重新排列。实例重启后为空，首次读写会退回物理字段顺序，直到再次
	 * 写入 A1 表头。
	 * </p>
	 */
	private final Map<String, List<String>> headerOrders = new ConcurrentHashMap<>();

	/** tenant_access_token 缓存。 */
	private String tenantToken;
	/** tenant_access_token 过期时间（秒级时间戳，已预留 120 秒余量）。 */
	private long tenantTokenExpiresAt;

	public FeishuBitableApi(FeishuBitableConfig config) {
		if (config == null) {
			throw new IllegalArgumentException("config 不能为 null");
		}
		this.config = config;
	}

	/**
	 * 从数据目录读取 {@code config/feishu-bitable.json} 创建实例。
	 *
	 * @param dataFolder 数据目录（通常是 {@code save}）
	 * @return 飞书多维表格 API
	 * @throws IOException 配置不完整
	 */
	public static FeishuBitableApi fromDataFolder(File dataFolder) throws IOException {
		if (dataFolder == null) {
			throw new IllegalArgumentException("dataFolder 不能为 null");
		}
		return fromFile(ConfigPaths.feishuBitable(dataFolder));
	}

	/**
	 * 从指定凭据文件创建实例。
	 *
	 * @param configFile 凭据文件
	 * @return 飞书多维表格 API
	 * @throws IOException 配置不完整
	 */
	public static FeishuBitableApi fromFile(File configFile) throws IOException {
		FeishuBitableConfig cfg = FeishuBitableConfig.load(configFile);
		FeishuBitableApi api = new FeishuBitableApi(cfg);
		if (!api.isConfigured()) {
			throw new SheetException("飞书配置不完整：请创建 " + cfg.pathDescription()
					+ "，配置 app_id + app_secret（或 access_token）以及 app_token");
		}
		return api;
	}

	/**
	 * @return 凭据与连接配置
	 */
	public FeishuBitableConfig getConfig() {
		return config;
	}

	/**
	 * @return 连接级多维表格 app_token，未配置时为 {@code null}
	 */
	public String getAppToken() {
		return isBlank(config.getAppToken()) ? null : config.getAppToken();
	}

	/**
	 * @return 凭据与连接级 app_token 是否完整
	 */
	public boolean isConfigured() {
		return !isBlank(getAppToken()) && config.isConfigured();
	}

	@Override
	public String getProviderName() {
		return PROVIDER_NAME;
	}

	// ---------------------------------------------------------------- 工作表（数据表）

	@Override
	public List<SheetMeta> listSheets() throws IOException {
		List<SheetMeta> tables = new ArrayList<>();
		String pageToken = null;
		int pages = 0;
		while (pages++ < MAX_TABLE_PAGES) {
			String path = tablesPath() + "?page_size=" + TABLE_PAGE_SIZE + pageTokenParam(pageToken);
			JsonObject response = apiRequest("GET", path, null);
			JsonObject data = JsonConfig.getObject(response, "data");
			JsonArray items = data == null ? null : JsonConfig.getArray(data, "items");
			if (items != null) {
				for (JsonElement element : items) {
					if (element == null || !element.isJsonObject()) {
						continue;
					}
					JsonObject item = element.getAsJsonObject();
					String tableId = JsonConfig.getNonBlankString(item, "table_id");
					if (tableId == null) {
						continue;
					}
					tables.add(new SheetMeta(tableId, JsonConfig.getString(item, "name", ""), -1, -1, -1, -1));
				}
			}
			pageToken = nextPageToken(data);
			if (pageToken == null) {
				break;
			}
		}
		return tables;
	}

	@Override
	public SheetMeta getSheet(String sheetId) throws IOException {
		String tableId = resolveTableId(sheetId);
		String name = null;
		for (SheetMeta meta : listSheets()) {
			if (tableId.equals(meta.getSheetId())) {
				name = meta.getTitle();
				break;
			}
		}
		if (name == null) {
			throw new SheetException("数据表不存在：" + tableId);
		}
		List<FeishuField> fields = listFields(tableId);
		int total = countRecords(tableId);
		List<String> order = headerOrders.get(tableId);
		int columns = order != null ? order.size() : fields.size();
		// 第 1 行是表头，后面 total 行记录
		return new SheetMeta(tableId, name, total + 1, columns, -1, columns);
	}

	/**
	 * 列出数据表的全部字段（列）。
	 *
	 * @param tableId 数据表 ID；为 null 时使用配置里的 table_id
	 * @return 字段列表，按飞书返回顺序
	 * @throws IOException 调用失败
	 */
	public List<FeishuField> listFields(String tableId) throws IOException {
		String id = resolveTableId(tableId);
		List<FeishuField> fields = new ArrayList<>();
		String pageToken = null;
		int pages = 0;
		while (pages++ < MAX_FIELD_PAGES) {
			String path = tablePath(id) + "/fields?page_size=" + FIELD_PAGE_SIZE + pageTokenParam(pageToken);
			JsonObject response = apiRequest("GET", path, null);
			JsonObject data = JsonConfig.getObject(response, "data");
			JsonArray items = data == null ? null : JsonConfig.getArray(data, "items");
			if (items != null) {
				for (JsonElement element : items) {
					if (element != null && element.isJsonObject()) {
						fields.add(parseField(element.getAsJsonObject()));
					}
				}
			}
			pageToken = nextPageToken(data);
			if (pageToken == null) {
				break;
			}
		}
		return fields;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * 多维表格没有预分配行数，{@code rowCount} 被忽略；{@code columnCount} 大于 0 时创建对应数量的
	 * 初始文本字段（列1、列2……），第一列作为索引列。列数上限 {@value #MAX_SHEET_COLUMNS}。
	 * </p>
	 */
	@Override
	public SheetMeta addSheet(String title, int rowCount, int columnCount) throws IOException {
		String name = title == null ? "" : title.trim();
		if (name.isEmpty()) {
			throw new IllegalArgumentException("数据表名称不能为空");
		}
		if (name.length() > MAX_SHEET_TITLE_LENGTH) {
			throw new IllegalArgumentException(
					"数据表名称最长 " + MAX_SHEET_TITLE_LENGTH + " 个字符，当前 " + name.length() + " 个");
		}
		if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('?') >= 0 || name.indexOf('*') >= 0
				|| name.indexOf(':') >= 0 || name.indexOf('[') >= 0 || name.indexOf(']') >= 0) {
			throw new IllegalArgumentException("数据表名称不能包含 / \\ ? * : [ ] 等字符");
		}
		int columns = columnCount <= 0 ? 1 : columnCount;
		if (columns > MAX_SHEET_COLUMNS) {
			throw new IllegalArgumentException("初始字段数不能超过 " + MAX_SHEET_COLUMNS + "，当前 " + columns);
		}

		JsonBuilder.JsonObjectBuilder<?> root = JsonBuilder.object();
		JsonBuilder.JsonObjectBuilder<?> table = root.object("table");
		table.add("name", name);
		if (columnCount > 0) {
			JsonBuilder.JsonArrayBuilder<?> fields = table.array("fields");
			for (int i = 0; i < columns; i++) {
				JsonBuilder.JsonObjectBuilder<?> field = fields.object();
				field.add("field_name", "列" + (i + 1)).add("type", FeishuField.TYPE_TEXT);
			}
		}

		synchronized (writeLock) {
			JsonObject response = apiRequest("POST", tablesPath(), root.get());
			JsonObject data = JsonConfig.getObject(response, "data");
			String tableId = data == null ? null : JsonConfig.getNonBlankString(data, "table_id");
			if (tableId == null) {
				throw new SheetException("新增数据表成功，但飞书未返回 table_id");
			}
			// Bitable 没有预分配行数的概念，rowCount 会被忽略；columnCount 决定初始文本字段数量
			return new SheetMeta(tableId, name, 1, Math.max(1, columns), -1, Math.max(1, columns));
		}
	}

	@Override
	public void deleteSheet(String sheetId) throws IOException {
		String tableId = resolveTableId(sheetId);
		synchronized (writeLock) {
			apiRequest("DELETE", tablePath(tableId), null);
			headerOrders.remove(tableId);
		}
	}

	// ---------------------------------------------------------------- 读取

	@Override
	public SheetData readRange(String sheetId, String a1Range) throws IOException {
		String tableId = resolveTableId(sheetId);
		A1Range range = A1Range.parse(a1Range);
		checkReadLimit(range);
		List<FeishuField> fields = listFields(tableId);
		List<String> order = headerOrders.get(tableId);
		Map<String, FeishuField> byName = byName(fields);
		List<List<CellValue>> rows = new ArrayList<>();

		// 需要读取到第 endRow-1 行（0 开始），对应记录下标 0..endRow-2，即 endRow-1 条记录
		int neededRecords = Math.max(0, range.getEndRow() - 1);
		List<JsonObject> records = neededRecords > 0 ? fetchRecords(tableId, neededRecords)
				: Collections.emptyList();

		if (range.getStartRow() == 0) {
			rows.add(headerRow(fields, order, range));
		}
		int fromRecord = Math.max(0, range.getStartRow() - 1);
		int toRecordExclusive = Math.min(records.size(), range.getEndRow() - 1);
		for (int index = fromRecord; index < toRecordExclusive; index++) {
			rows.add(dataRow(records.get(index), fields, byName, order, range));
		}
		return new SheetData(range.getStartRow(), range.getStartColumn(), rows);
	}

	// ---------------------------------------------------------------- 写入

	/**
	 * {@inheritDoc}
	 * <p>
	 * 从 A1 开始时第一行是<b>逻辑表头</b>：按名称匹配现有字段，缺失字段追加创建；不会调整字段的
	 * 物理顺序，而是记录“逻辑列名 → 实际字段”的映射，之后的读写都按该映射重新排列。表头顺序
	 * 可以随意修改（重排、增删、改名）；旧字段默认不删除，只从逻辑表头中隐藏，数据继续保留，
	 * 需要删除列时显式调用 {@link #deleteField(String, String)}。
	 * 表头顺序或字段集合发生变化时，数据行中的空单元格视为“保持原值”；表头与上次一致时按普通
	 * 写入处理（空单元格清空）。从 A2 或更后开始时按上次记录的逻辑表头映射字段；本实例还没有
	 * 记录过表头时退回物理字段顺序。
	 * </p>
	 */
	@Override
	public void writeRange(String sheetId, String a1Range, List<List<CellValue>> values) throws IOException {
		String tableId = resolveTableId(sheetId);
		if (values == null || values.isEmpty()) {
			throw new IllegalArgumentException("写入内容不能为空");
		}
		A1Range range = A1Range.parse(a1Range);
		int width = widthOf(values);
		if (width == 0) {
			throw new IllegalArgumentException("写入内容不能为空");
		}
		checkWriteLimit(values.size(), width);

		synchronized (writeLock) {
			List<FeishuField> fields = new ArrayList<>(listFields(tableId));
			List<FeishuField> mapping;
			int firstDataIndex;
			boolean protectExistingData;
			if (range.getStartRow() == 0) {
				if (range.getStartColumn() != 0) {
					throw new IllegalArgumentException("逻辑表头必须从 A1 开始写入，不能从中间列开始");
				}
				// 第一行是逻辑表头：允许任意顺序/增删/改名，只按名称建立映射，不动物理列顺序
				List<String> headerNames = parseHeaderNames(values.get(0));
				List<String> previousOrder = headerOrders.get(tableId);
				boolean schemaChanged = syncHeader(tableId, fields, headerNames);
				protectExistingData = previousOrder == null || !headerNames.equals(previousOrder) || schemaChanged;
				rememberHeaderOrder(tableId, headerNames);
				mapping = mappingByName(fields, headerNames);
				firstDataIndex = 1;
				if (values.size() <= firstDataIndex) {
					return;
				}
			} else {
				// 从第 2 行起：按已记录的逻辑表头顺序映射到实际字段；没有记录时退回物理顺序
				List<String> order = headerOrders.get(tableId);
				protectExistingData = false;
				mapping = order == null ? positionMapping(fields, range.getStartColumn(), width)
						: mappingByOrder(fields, order, range.getStartColumn(), width);
				firstDataIndex = 0;
			}

			int maxGridRow = range.getStartRow() + values.size() - 1;
			int neededRecords = Math.max(0, maxGridRow);
			List<JsonObject> existing = neededRecords > 0 ? fetchRecords(tableId, neededRecords)
					: Collections.emptyList();
			List<JsonObject> updates = new ArrayList<>();
			List<JsonObject> creates = new ArrayList<>();
			for (int i = firstDataIndex; i < values.size(); i++) {
				int gridRow = range.getStartRow() + i;
				int recordIndex = gridRow - 1;
				if (recordIndex < 0) {
					continue;
				}
				boolean update = recordIndex < existing.size();
				JsonObject fieldObject = buildFieldsJson(mapping, values.get(i), update, protectExistingData);
				if (fieldObject.size() == 0) {
					continue;
				}
				if (update) {
					String recordId = JsonConfig.getNonBlankString(existing.get(recordIndex), "record_id");
					if (recordId == null) {
						continue;
					}
					JsonObject item = new JsonObject();
					item.addProperty("record_id", recordId);
					item.add("fields", fieldObject);
					updates.add(item);
				} else {
					JsonObject item = new JsonObject();
					item.add("fields", fieldObject);
					creates.add(item);
				}
			}
			flushRecords(tableId, updates, true);
			flushRecords(tableId, creates, false);
		}
	}

	@Override
	public void appendRows(String sheetId, List<List<CellValue>> values) throws IOException {
		String tableId = resolveTableId(sheetId);
		if (values == null || values.isEmpty()) {
			throw new IllegalArgumentException("追加内容不能为空");
		}
		int width = widthOf(values);
		if (width == 0) {
			throw new IllegalArgumentException("追加内容不能为空");
		}
		checkWriteLimit(values.size(), width);

		synchronized (writeLock) {
			List<FeishuField> fields = listFields(tableId);
			if (fields.isEmpty()) {
				throw new IllegalArgumentException("数据表没有字段，请先在 A1 写入表头（字段名）");
			}
			List<String> order = headerOrders.get(tableId);
			List<FeishuField> mapping;
			if (order != null) {
				if (width > order.size()) {
					throw new IllegalArgumentException(
							"列数超出已记录的表头范围：最多 " + order.size() + " 列，当前 " + width + " 列");
				}
				mapping = mappingByOrder(fields, order, 0, width);
			} else {
				if (width > fields.size()) {
					throw new IllegalArgumentException(
							"列数超出数据表字段数：最多 " + fields.size() + " 列，当前 " + width + " 列");
				}
				mapping = fields;
			}
			List<JsonObject> creates = new ArrayList<>();
			for (List<CellValue> row : values) {
				JsonObject fieldObject = buildFieldsJson(mapping, row, false, false);
				if (fieldObject.size() == 0) {
					continue;
				}
				JsonObject item = new JsonObject();
				item.add("fields", fieldObject);
				creates.add(item);
			}
			flushRecords(tableId, creates, false);
		}
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * 第 1 行（字段名表头）不能清空；数据行的可写字段会写成 {@code null}。索引列是否允许清空由飞书
	 * 配置决定，清空索引列可能被服务端拒绝。
	 * </p>
	 */
	@Override
	public void clearAllData(String sheetId, int columns) throws IOException {
		String tableId = resolveTableId(sheetId);
		synchronized (writeLock) {
			List<String> recordIds = listAllRecordIds(tableId);
			for (int from = 0; from < recordIds.size(); from += MAX_BATCH_RECORDS) {
				JsonArray batch = new JsonArray();
				for (int index = from; index < Math.min(recordIds.size(), from + MAX_BATCH_RECORDS); index++) {
					batch.add(recordIds.get(index));
				}
				JsonObject payload = new JsonObject();
				payload.add("records", batch);
				apiRequest("POST", tablePath(tableId) + "/records/batch_delete?user_id_type=open_id", payload);
			}
		}
	}

	/** 分页列出全部记录 ID，用于整表重建前删除旧记录。 */
	private List<String> listAllRecordIds(String tableId) throws IOException {
		List<String> ids = new ArrayList<>();
		String pageToken = null;
		int pages = 0;
		while (pages++ < MAX_RECORD_PAGES) {
			String path = recordsSearchPath(tableId) + "?page_size=" + PAGE_SIZE + pageTokenParam(pageToken);
			JsonObject response = apiRequest("POST", path, buildSearchBody());
			JsonObject data = JsonConfig.getObject(response, "data");
			JsonArray items = data == null ? null : JsonConfig.getArray(data, "items");
			if (items != null) {
				for (JsonElement element : items) {
					if (element == null || !element.isJsonObject()) {
						continue;
					}
					String id = JsonConfig.getNonBlankString(element.getAsJsonObject(), "record_id");
					if (id != null) {
						ids.add(id);
					}
				}
			}
			pageToken = nextPageToken(data);
			if (pageToken == null) {
				break;
			}
		}
		return ids;
	}

	@Override
	public void clearRange(String sheetId, String a1Range) throws IOException {
		A1Range range = A1Range.parse(a1Range);
		if (range.getStartRow() == 0) {
			throw new IllegalArgumentException("第 1 行是字段名（表头），不能清空；请从第 2 行开始清空数据");
		}
		checkWriteLimit(range.getRowCount(), range.getColumnCount());
		List<List<CellValue>> values = new ArrayList<>(range.getRowCount());
		for (int row = 0; row < range.getRowCount(); row++) {
			values.add(Collections.nCopies(range.getColumnCount(), CellValue.empty()));
		}
		writeRange(sheetId, range.toA1(), values);
	}

	// ---------------------------------------------------------------- 字段解析

	private static FeishuField parseField(JsonObject field) {
		return new FeishuField(
				JsonConfig.getNonBlankString(field, "field_id"),
				JsonConfig.getString(field, "field_name", ""),
				JsonConfig.getInt(field, "type", FeishuField.TYPE_TEXT),
				JsonConfig.getString(field, "ui_type", ""),
				JsonConfig.getBool(field, "is_primary"),
				JsonConfig.getBool(field, "is_hidden"),
				JsonConfig.getObject(field, "property"));
	}

	/**
	 * 解析逻辑表头：要求每列都有非空字段名，且不重复。
	 *
	 * @param header 第一行
	 * @return 列名列表
	 */
	private static List<String> parseHeaderNames(List<CellValue> header) {
		if (header == null || header.isEmpty()) {
			throw new IllegalArgumentException("写入表头行时第一行不能为空");
		}
		List<String> names = new ArrayList<>(header.size());
		Set<String> seen = new HashSet<>();
		for (int index = 0; index < header.size(); index++) {
			CellValue cell = header.get(index);
			String name = cell == null ? "" : cell.asText().trim();
			if (name.isEmpty()) {
				throw new IllegalArgumentException("第 " + (index + 1) + " 列表头为空；表头写入要求每列都有字段名");
			}
			if (!seen.add(name)) {
				throw new IllegalArgumentException("表头存在重复字段名：" + name);
			}
			names.add(name);
		}
		return names;
	}

	/**
	 * 把逻辑表头与现有字段对齐：缺失字段追加创建；不会自动删除旧字段（保护数据）。
	 * <p>
	 * 不调整字段物理顺序——表头顺序由 {@link #headerOrders} 记录，读写时按映射重排。
	 * </p>
	 *
	 * @return 字段集合是否发生变化
	 */
	private boolean syncHeader(String tableId, List<FeishuField> fields, List<String> headerNames)
			throws IOException {
		boolean changed = false;
		Map<String, FeishuField> byName = byName(fields);
		for (String name : headerNames) {
			if (byName.containsKey(name)) {
				continue;
			}
			FeishuField created = createTextField(tableId, name);
			fields.add(created);
			byName.put(name, created);
			changed = true;
		}
		return changed;
	}

	private static Map<String, FeishuField> byName(List<FeishuField> fields) {
		Map<String, FeishuField> byName = new HashMap<>();
		for (FeishuField field : fields) {
			byName.put(field.getFieldName(), field);
		}
		return byName;
	}

	private static List<FeishuField> mappingByName(List<FeishuField> fields, List<String> names) {
		Map<String, FeishuField> byName = byName(fields);
		List<FeishuField> mapping = new ArrayList<>(names.size());
		for (String name : names) {
			FeishuField field = byName.get(name);
			if (field == null) {
				throw new IllegalArgumentException("字段不存在：" + name);
			}
			mapping.add(field);
		}
		return mapping;
	}

	private static List<FeishuField> mappingByOrder(List<FeishuField> fields, List<String> order, int startColumn,
			int width) {
		Map<String, FeishuField> byName = byName(fields);
		List<FeishuField> mapping = new ArrayList<>(width);
		for (int index = 0; index < width; index++) {
			int column = startColumn + index;
			if (column < 0 || column >= order.size()) {
				throw new IllegalArgumentException(
						"列超出已记录的表头范围：共 " + order.size() + " 列，请求写到第 " + (column + 1) + " 列");
			}
			String name = order.get(column);
			FeishuField field = byName.get(name);
			if (field == null) {
				throw new IllegalArgumentException("表头映射失效：字段「" + name + "」不存在；请重新写入 A1 表头");
			}
			mapping.add(field);
		}
		return mapping;
	}

	private void rememberHeaderOrder(String tableId, List<String> names) {
		headerOrders.put(tableId, Collections.unmodifiableList(new ArrayList<>(names)));
	}

	private FeishuField createTextField(String tableId, String name) throws IOException {
		JsonObject payload = JsonBuilder.object()
				.add("field_name", name)
				.add("type", FeishuField.TYPE_TEXT)
				.get();
		JsonObject response = apiRequest("POST", tablePath(tableId) + "/fields", payload);
		JsonObject data = JsonConfig.getObject(response, "data");
		JsonObject fieldObject = data == null ? null : JsonConfig.getObject(data, "field");
		if (fieldObject != null) {
			return parseField(fieldObject);
		}
		// 部分版本不回传 field 对象，回查字段列表兜底
		for (FeishuField field : listFields(tableId)) {
			if (name.equals(field.getFieldName())) {
				return field;
			}
		}
		throw new SheetException("创建字段「" + name + "」成功，但飞书未返回字段信息");
	}

	/**
	 * 显式删除字段。
	 *
	 * @param tableId   数据表 ID；为 null 时使用默认数据表
	 * @param fieldName 字段名
	 * @throws IOException 调用失败
	 */
	private void deleteFieldInternal(String tableId, FeishuField field) throws IOException {
		if (field.isPrimary()) {
			throw new IllegalArgumentException("索引列不能删除：" + field.getFieldName());
		}
		if (isBlank(field.getFieldId())) {
			throw new IllegalArgumentException("字段缺少 field_id，无法删除：" + field.getFieldName());
		}
		apiRequest("DELETE", tablePath(tableId) + "/fields/" + field.getFieldId(), null);
	}

	/**
	 * 显式新增一个文本字段。
	 * <p>
	 * 新字段追加在物理末尾，并<b>不会</b>自动加入已记录的逻辑表头；需要它参与读写时，请再写一次
	 * A1 表头把它包含进去。
	 * </p>
	 *
	 * @param tableId   数据表 ID；为 null 时使用默认数据表
	 * @param fieldName 字段名
	 * @return 新字段
	 * @throws IOException 调用失败
	 */
	public FeishuField addField(String tableId, String fieldName) throws IOException {
		String id = resolveTableId(tableId);
		String name = fieldName == null ? "" : fieldName.trim();
		if (name.isEmpty()) {
			throw new IllegalArgumentException("字段名不能为空");
		}
		synchronized (writeLock) {
			for (FeishuField field : listFields(id)) {
				if (name.equals(field.getFieldName())) {
					throw new IllegalArgumentException("字段已存在：" + name);
				}
			}
			return createTextField(id, name);
		}
	}

	/**
	 * 显式重命名字段，保留字段类型、属性和全部数据。
	 *
	 * @param tableId 数据表 ID；为 null 时使用默认数据表
	 * @param oldName 原字段名
	 * @param newName 新字段名
	 * @return 重命名后的字段
	 * @throws IOException 调用失败
	 */
	public FeishuField renameField(String tableId, String oldName, String newName) throws IOException {
		String id = resolveTableId(tableId);
		String source = oldName == null ? "" : oldName.trim();
		String target = newName == null ? "" : newName.trim();
		if (source.isEmpty() || target.isEmpty()) {
			throw new IllegalArgumentException("字段名不能为空");
		}
		synchronized (writeLock) {
			List<FeishuField> fields = listFields(id);
			FeishuField field = null;
			for (FeishuField candidate : fields) {
				if (source.equals(candidate.getFieldName())) {
					field = candidate;
					break;
				}
			}
			if (field == null) {
				throw new IllegalArgumentException("字段不存在：" + source);
			}
			if (isBlank(field.getFieldId())) {
				throw new IllegalArgumentException("字段缺少 field_id，无法重命名：" + source);
			}
			if (target.equals(field.getFieldName())) {
				return field;
			}
			for (FeishuField candidate : fields) {
				if (target.equals(candidate.getFieldName())) {
					throw new IllegalArgumentException("字段名已存在：" + target);
				}
			}
			JsonBuilder.JsonObjectBuilder<?> payload = JsonBuilder.object()
					.add("field_name", target)
					.add("type", field.getType());
			if (field.getProperty() != null) {
				payload.add("property", field.getProperty());
			}
			JsonObject response = apiRequest("PUT", tablePath(id) + "/fields/" + field.getFieldId(), payload.get());
			JsonObject data = JsonConfig.getObject(response, "data");
			JsonObject fieldObject = data == null ? null : JsonConfig.getObject(data, "field");
			FeishuField renamed = fieldObject == null
					? new FeishuField(field.getFieldId(), target, field.getType(), field.getUiType(),
							field.isPrimary(), field.isHidden(), field.getProperty())
					: parseField(fieldObject);
			List<String> order = headerOrders.get(id);
			if (order != null && order.contains(source)) {
				List<String> updated = new ArrayList<>(order);
				for (int index = 0; index < updated.size(); index++) {
					if (source.equals(updated.get(index))) {
						updated.set(index, target);
					}
				}
				rememberHeaderOrder(id, updated);
			}
			return renamed;
		}
	}

	/**
	 * 显式删除字段（会同时删除该列数据）；如果该字段在已记录的逻辑表头中，会从表头映射里移除。
	 * 索引列不能删除。
	 *
	 * @param tableId   数据表 ID；为 null 时使用默认数据表
	 * @param fieldName 字段名
	 * @throws IOException 调用失败
	 */
	public void deleteField(String tableId, String fieldName) throws IOException {
		String id = resolveTableId(tableId);
		String name = fieldName == null ? "" : fieldName.trim();
		if (name.isEmpty()) {
			throw new IllegalArgumentException("字段名不能为空");
		}
		synchronized (writeLock) {
			FeishuField field = null;
			for (FeishuField candidate : listFields(id)) {
				if (name.equals(candidate.getFieldName())) {
					field = candidate;
					break;
				}
			}
			if (field == null) {
				throw new IllegalArgumentException("字段不存在：" + name);
			}
			deleteFieldInternal(id, field);
			List<String> order = headerOrders.get(id);
			if (order != null && order.contains(name)) {
				List<String> updated = new ArrayList<>(order);
				updated.remove(name);
				rememberHeaderOrder(id, updated);
			}
		}
	}

	private static List<FeishuField> positionMapping(List<FeishuField> fields, int startColumn, int width) {
		if (startColumn + width > fields.size()) {
			throw new IllegalArgumentException("列超出数据表字段范围：当前共 " + fields.size() + " 个字段，请求写到第 "
					+ (startColumn + width) + " 列；请先在 A1 写入表头以创建字段");
		}
		return new ArrayList<>(fields.subList(startColumn, startColumn + width));
	}

	// ---------------------------------------------------------------- 记录读取

	private List<JsonObject> fetchRecords(String tableId, int needed) throws IOException {
		List<JsonObject> records = new ArrayList<>();
		if (needed <= 0) {
			return records;
		}
		String pageToken = null;
		int pages = 0;
		while (records.size() < needed && pages++ < MAX_RECORD_PAGES) {
			int pageSize = Math.min(PAGE_SIZE, needed - records.size());
			if (pageSize <= 0) {
				break;
			}
			String path = recordsSearchPath(tableId) + "?page_size=" + pageSize + pageTokenParam(pageToken);
			JsonObject response = apiRequest("POST", path, buildSearchBody());
			JsonObject data = JsonConfig.getObject(response, "data");
			JsonArray items = data == null ? null : JsonConfig.getArray(data, "items");
			if (items != null) {
				for (JsonElement element : items) {
					if (element != null && element.isJsonObject()) {
						records.add(element.getAsJsonObject());
					}
				}
			}
			pageToken = nextPageToken(data);
			if (pageToken == null) {
				break;
			}
		}
		return records;
	}

	private int countRecords(String tableId) throws IOException {
		JsonObject response = apiRequest("POST", recordsSearchPath(tableId) + "?page_size=1", buildSearchBody());
		JsonObject data = JsonConfig.getObject(response, "data");
		return data == null ? 0 : JsonConfig.getInt(data, "total", 0);
	}

	private static JsonObject buildSearchBody() {
		return JsonBuilder.object().add("automatic_fields", false).get();
	}

	private static List<CellValue> headerRow(List<FeishuField> fields, List<String> order, A1Range range) {
		List<CellValue> row = new ArrayList<>(range.getColumnCount());
		for (int column = range.getStartColumn(); column < range.getEndColumn(); column++) {
			String name = null;
			if (order != null) {
				if (column >= 0 && column < order.size()) {
					name = order.get(column);
				}
			} else if (column >= 0 && column < fields.size()) {
				name = fields.get(column).getFieldName();
			}
			row.add(name == null || name.isEmpty() ? CellValue.empty() : CellValue.of(name));
		}
		return row;
	}

	private static List<CellValue> dataRow(JsonObject record, List<FeishuField> fields,
			Map<String, FeishuField> byName, List<String> order, A1Range range) {
		JsonObject values = JsonConfig.getObject(record, "fields");
		List<CellValue> row = new ArrayList<>(range.getColumnCount());
		for (int column = range.getStartColumn(); column < range.getEndColumn(); column++) {
			FeishuField field;
			if (order != null) {
				String name = column >= 0 && column < order.size() ? order.get(column) : null;
				field = name == null ? null : byName.get(name);
			} else {
				field = column >= 0 && column < fields.size() ? fields.get(column) : null;
			}
			JsonElement value = field == null || values == null ? null : values.get(field.getFieldName());
			row.add(field == null ? CellValue.empty() : toCellValue(field, value));
		}
		return row;
	}

	// ---------------------------------------------------------------- 记录写入

	private static JsonObject buildFieldsJson(List<FeishuField> mapping, List<CellValue> row, boolean update,
			boolean protectExistingData) {
		JsonObject fields = new JsonObject();
		if (row == null || row.isEmpty()) {
			return fields;
		}
		for (int column = 0; column < row.size(); column++) {
			CellValue cell = row.get(column) == null ? CellValue.empty() : row.get(column);
			FeishuField field;
			if (column < mapping.size()) {
				field = mapping.get(column);
			} else {
				if (!cell.isBlank()) {
					throw new IllegalArgumentException(
							"第 " + (column + 1) + " 列超出数据表字段范围，无法确定字段");
				}
				continue;
			}
			if (field == null) {
				if (!cell.isBlank()) {
					throw new IllegalArgumentException("第 " + (column + 1) + " 列没有对应字段（表头为空）");
				}
				continue;
			}
			if (field.isReadOnly()) {
				// 公式、创建时间等由飞书维护，写入时跳过
				continue;
			}
			if (!field.isSupportedForWrite()) {
				if (cell.isBlank()) {
					continue;
				}
				throw new IllegalArgumentException("暂不支持写入字段「" + field.getFieldName() + "」（类型 "
						+ field.getType() + "），该字段需要飞书内部 ID 或专用结构");
			}
			if (cell.isBlank()) {
				if (update && !protectExistingData) {
					// 普通更新：写 null 表示清空单元格；
					// 表头变更保护：空值表示“保持原值”，不能清掉旧数据
					fields.add(field.getFieldName(), JsonNull.INSTANCE);
				}
				continue;
			}
			fields.add(field.getFieldName(), toJsonValue(field, cell));
		}
		return fields;
	}

	private static JsonElement toJsonValue(FeishuField field, CellValue cell) {
		switch (field.getType()) {
		case FeishuField.TYPE_TEXT:
		case FeishuField.TYPE_PHONE:
		case FeishuField.TYPE_SINGLE_SELECT:
			return new JsonPrimitive(cell.asText());
		case FeishuField.TYPE_NUMBER:
			return new JsonPrimitive(parseNumber(field, cell));
		case FeishuField.TYPE_MULTI_SELECT: {
			JsonArray options = new JsonArray();
			for (String part : cell.asText().split("、")) {
				String option = part.trim();
				if (!option.isEmpty()) {
					options.add(option);
				}
			}
			return options;
		}
		case FeishuField.TYPE_DATE:
			return new JsonPrimitive(epochMillis(field, cell));
		case FeishuField.TYPE_CHECKBOX:
			return new JsonPrimitive(parseBoolean(cell));
		case FeishuField.TYPE_URL:
			return urlValue(field, cell);
		default:
			throw new IllegalArgumentException("暂不支持写入字段「" + field.getFieldName() + "」（类型 "
					+ field.getType() + "）");
		}
	}

	private static double parseNumber(FeishuField field, CellValue cell) {
		if (cell.getKind() == CellValue.Kind.NUMBER) {
			return cell.getNumber();
		}
		String text = cell.asText().trim();
		try {
			return Double.parseDouble(text);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("字段「" + field.getFieldName() + "」需要数字，实际值：" + text);
		}
	}

	private static long epochMillis(FeishuField field, CellValue cell) {
		if (cell.getKind() == CellValue.Kind.NUMBER) {
			return (long) cell.getNumber();
		}
		if (cell.getKind() == CellValue.Kind.TIME) {
			return cell.getTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		}
		String text = cell.asText().trim();
		for (DateTimeFormatter format : DATE_TIME_FORMATS) {
			try {
				return LocalDateTime.parse(text, format).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
			} catch (RuntimeException ignored) {
				// 尝试下一种格式
			}
		}
		for (DateTimeFormatter format : DATE_FORMATS) {
			try {
				return LocalDate.parse(text, format).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
			} catch (RuntimeException ignored) {
				// 尝试下一种格式
			}
		}
		throw new IllegalArgumentException(
				"字段「" + field.getFieldName() + "」需要日期，支持 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss，实际值：" + text);
	}

	private static boolean parseBoolean(CellValue cell) {
		if (cell.getKind() == CellValue.Kind.NUMBER) {
			return cell.getNumber() != 0;
		}
		String text = cell.asText().trim().toLowerCase(Locale.ROOT);
		return "true".equals(text) || "1".equals(text) || "是".equals(text) || "yes".equals(text)
				|| "y".equals(text) || "on".equals(text);
	}

	private static JsonObject urlValue(FeishuField field, CellValue cell) {
		JsonObject link = new JsonObject();
		if (cell.getKind() == CellValue.Kind.LINK) {
			link.addProperty("link", cell.getLinkUrl());
			link.addProperty("text", cell.getLinkText());
			return link;
		}
		String text = cell.asText().trim();
		if (!text.startsWith("http://") && !text.startsWith("https://")) {
			throw new IllegalArgumentException(
					"超链接字段「" + field.getFieldName() + "」需要 http(s) 地址，实际值：" + text);
		}
		link.addProperty("link", text);
		link.addProperty("text", text);
		return link;
	}

	private void flushRecords(String tableId, List<JsonObject> records, boolean update) throws IOException {
		if (records.isEmpty()) {
			return;
		}
		String path = update ? tablePath(tableId) + "/records/batch_update"
				: tablePath(tableId) + "/records/batch_create";
		String suffix = "?user_id_type=open_id";
		for (int from = 0; from < records.size(); from += MAX_BATCH_RECORDS) {
			JsonArray batch = new JsonArray();
			for (int index = from; index < Math.min(records.size(), from + MAX_BATCH_RECORDS); index++) {
				batch.add(records.get(index));
			}
			JsonObject payload = new JsonObject();
			payload.add("records", batch);
			apiRequest("POST", path + suffix, payload);
		}
	}

	// ---------------------------------------------------------------- 单元格值

	private static CellValue toCellValue(FeishuField field, JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return CellValue.empty();
		}
		switch (field.getType()) {
		case FeishuField.TYPE_NUMBER:
			return numberCellValue(value);
		case FeishuField.TYPE_SINGLE_SELECT:
			return CellValue.of(textValue(value));
		case FeishuField.TYPE_MULTI_SELECT:
			return CellValue.of(joinStrings(value));
		case FeishuField.TYPE_DATE:
		case FeishuField.TYPE_CREATED_TIME:
		case FeishuField.TYPE_MODIFIED_TIME:
			return timeCellValue(value);
		case FeishuField.TYPE_CHECKBOX:
			return CellValue.of(booleanText(value));
		case FeishuField.TYPE_USER:
		case FeishuField.TYPE_GROUP:
		case FeishuField.TYPE_ATTACHMENT:
			return CellValue.of(joinObjectNames(value));
		case FeishuField.TYPE_URL:
			return linkCellValue(value);
		case FeishuField.TYPE_LINK:
		case FeishuField.TYPE_DUPLEX_LINK:
			return CellValue.of(joinLinkIds(value));
		case FeishuField.TYPE_LOCATION:
			return CellValue.of(locationText(value));
		default:
			return CellValue.of(textValue(value));
		}
	}

	private static CellValue numberCellValue(JsonElement value) {
		if (value.isJsonPrimitive()) {
			try {
				return CellValue.of(value.getAsDouble());
			} catch (RuntimeException e) {
				return CellValue.of(value.getAsString());
			}
		}
		String text = textValue(value);
		try {
			return CellValue.of(Double.parseDouble(text.trim()));
		} catch (RuntimeException e) {
			return CellValue.of(text);
		}
	}

	private static CellValue timeCellValue(JsonElement value) {
		Long millis = null;
		if (value.isJsonPrimitive()) {
			try {
				millis = value.getAsLong();
			} catch (RuntimeException ignored) {
				// 尝试按文本解析
			}
		}
		if (millis == null) {
			String text = textValue(value);
			try {
				millis = Long.parseLong(text.trim());
			} catch (RuntimeException ignored) {
				return CellValue.of(text);
			}
		}
		try {
			return CellValue.time(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
		} catch (RuntimeException e) {
			return CellValue.of(String.valueOf(millis));
		}
	}

	private static CellValue linkCellValue(JsonElement value) {
		if (value.isJsonObject()) {
			JsonObject link = value.getAsJsonObject();
			String url = JsonConfig.getString(link, "link");
			if (url == null) {
				url = JsonConfig.getString(link, "url");
			}
			return CellValue.link(url, JsonConfig.getString(link, "text"));
		}
		return CellValue.of(textValue(value));
	}

	private static String booleanText(JsonElement value) {
		if (value.isJsonPrimitive()) {
			try {
				return String.valueOf(value.getAsBoolean());
			} catch (RuntimeException ignored) {
				// 退回文本
			}
		}
		return textValue(value);
	}

	/**
	 * 把飞书的字段值转成文本：兼容字符串、富文本片段数组（{@code [{"type":"text","text":"..."}]}）与对象。
	 */
	private static String textValue(JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return "";
		}
		if (value.isJsonPrimitive()) {
			return value.getAsString();
		}
		if (value.isJsonArray()) {
			StringBuilder sb = new StringBuilder();
			for (JsonElement element : value.getAsJsonArray()) {
				if (element == null || element.isJsonNull()) {
					continue;
				}
				if (element.isJsonObject()) {
					JsonObject object = element.getAsJsonObject();
					String text = JsonConfig.getString(object, "text");
					if (text == null) {
						text = JsonConfig.getString(object, "name");
					}
					if (text == null) {
						text = JsonConfig.getString(object, "link");
					}
					if (text != null) {
						sb.append(text);
					}
				} else {
					sb.append(element.getAsString());
				}
			}
			return sb.toString();
		}
		if (value.isJsonObject()) {
			JsonObject object = value.getAsJsonObject();
			String text = JsonConfig.getString(object, "text");
			if (text == null) {
				text = JsonConfig.getString(object, "name");
			}
			if (text == null) {
				text = JsonConfig.getString(object, "link");
			}
			return text == null ? "" : text;
		}
		return "";
	}

	private static String joinStrings(JsonElement value) {
		if (value.isJsonArray()) {
			List<String> parts = new ArrayList<>();
			for (JsonElement element : value.getAsJsonArray()) {
				parts.add(textValue(element));
			}
			return String.join("、", parts);
		}
		return textValue(value);
	}

	private static String joinObjectNames(JsonElement value) {
		if (value.isJsonArray()) {
			List<String> parts = new ArrayList<>();
			for (JsonElement element : value.getAsJsonArray()) {
				if (element != null && element.isJsonObject()) {
					JsonObject object = element.getAsJsonObject();
					String name = JsonConfig.getNonBlankString(object, "name");
					if (name == null) {
						name = JsonConfig.getNonBlankString(object, "id");
					}
					if (name == null) {
						name = JsonConfig.getNonBlankString(object, "file_token");
					}
					parts.add(name == null ? "" : name);
				} else {
					parts.add(textValue(element));
				}
			}
			return String.join("、", parts);
		}
		if (value.isJsonObject()) {
			JsonObject object = value.getAsJsonObject();
			String name = JsonConfig.getNonBlankString(object, "name");
			if (name == null) {
				name = JsonConfig.getNonBlankString(object, "id");
			}
			return name == null ? textValue(value) : name;
		}
		return textValue(value);
	}

	private static String joinLinkIds(JsonElement value) {
		if (value.isJsonObject()) {
			JsonArray ids = JsonConfig.getArray(value.getAsJsonObject(), "link_record_ids");
			if (ids != null) {
				List<String> parts = new ArrayList<>();
				for (JsonElement id : ids) {
					parts.add(textValue(id));
				}
				return String.join("、", parts);
			}
		}
		return joinStrings(value);
	}

	private static String locationText(JsonElement value) {
		if (value.isJsonObject()) {
			JsonObject location = value.getAsJsonObject();
			String text = JsonConfig.getNonBlankString(location, "name");
			if (text == null) {
				text = JsonConfig.getNonBlankString(location, "full_address");
			}
			if (text == null) {
				text = JsonConfig.getNonBlankString(location, "location");
			}
			if (text != null) {
				return text;
			}
		}
		return textValue(value);
	}

	// ---------------------------------------------------------------- HTTP 与路径

	private String tablesPath() throws SheetException {
		return API_PREFIX + "/bitable/v1/apps/" + appToken() + "/tables";
	}

	private String tablePath(String tableId) throws SheetException {
		return tablesPath() + "/" + tableId;
	}

	private String recordsSearchPath(String tableId) throws SheetException {
		return tablePath(tableId) + "/records/search";
	}

	private String appToken() throws SheetException {
		String token = getAppToken();
		if (isBlank(token)) {
			throw new SheetException("缺少 app_token（多维表格标识）：请在飞书连接配置中填写 app_token，"
					+ "或在调用方参数中传入");
		}
		return token.trim();
	}

	private String resolveTableId(String tableId) throws SheetException {
		String id = tableId == null ? null : tableId.trim();
		if (isBlank(id)) {
			throw new SheetException("缺少 tableId：请由调用方传入数据表 ID");
		}
		return id;
	}

	private JsonObject apiRequest(String method, String path, JsonObject body) throws IOException {
		boolean refreshed = false;
		int rateLimitRetries = 0;
		while (true) {
			try {
				return apiRequestOnce(method, path, body);
			} catch (SheetException e) {
				if (isRateLimit(e) && rateLimitRetries < 2) {
					rateLimitRetries++;
					sleepBeforeRetry(1000L * rateLimitRetries);
					continue;
				}
				if (!refreshed && isAuthError(e) && config.canRefresh()) {
					refreshed = true;
					logger.warning("飞书访问凭证失效，重新获取 tenant_access_token 后重试：" + e.getMessage());
					try {
						refreshTenantToken();
					} catch (IOException refreshError) {
						refreshError.addSuppressed(e);
						throw refreshError;
					}
					continue;
				}
				throw e;
			}
		}
	}

	private JsonObject apiRequestOnce(String method, String path, JsonObject body) throws IOException {
		String token = ensureToken();
		HttpRequestBuilder builder = HttpRequestBuilder.create(host())
				.url(path)
				.header("Authorization", "Bearer " + token)
				.contenttype(JSON)
				.header("Accept", JSON)
				.readTimeout(HTTP_TIMEOUT_MS);
		HttpRequestBuilder.OpenedConnectionBuilder connection;
		try {
			if ("GET".equals(method)) {
				connection = builder.get();
			} else if ("DELETE".equals(method)) {
				connection = builder.open("DELETE", true, false);
			} else if ("PUT".equals(method)) {
				connection = builder.open("PUT", true, true);
			} else {
				connection = builder.post();
			}
		} catch (IOException e) {
			throw new SheetException("连接飞书开放平台失败：" + e.getMessage(), 0, -1, e);
		}
		try {
			if (body != null) {
				connection.send(body.toString());
			}
			String text = connection.readString();
			int status = connection.getResponseCode();
			JsonObject response = parseObject(text, status);
			checkSuccess(response, status);
			return response;
		} catch (IOException e) {
			if (e instanceof SheetException) {
				throw e;
			}
			int status = -1;
			try {
				status = connection.getResponseCode();
			} catch (RuntimeException ignored) {
				// 拿不到状态码时保留 -1
			}
			throw error(status, e.getMessage(), e);
		}
	}

	/**
	 * 用自建应用凭据换取 tenant_access_token。
	 * <p>
	 * 接口：{@code POST /open-apis/auth/v3/tenant_access_token/internal}，请求体只有 app_id 与
	 * app_secret，URL 不含密钥，可以安全地使用会打印 URL 的 {@link HttpRequestBuilder}。
	 * </p>
	 */
	private void refreshTenantToken() throws IOException {
		synchronized (tokenLock) {
			tenantToken = null;
			tenantTokenExpiresAt = 0;
			String appId = config.getAppId();
			String appSecret = config.getAppSecret();
			if (isBlank(appId) || isBlank(appSecret)) {
				throw new SheetException(
						"缺少 app_id 或 app_secret：请在 " + config.pathDescription() + " 中配置，或设置 -Dfeishu_app_id=... / -Dfeishu_app_secret=...");
			}
			JsonObject payload = JsonBuilder.object()
					.add("app_id", appId.trim())
					.add("app_secret", appSecret.trim())
					.get();
			JsonObject response = tokenRequest(payload);
			checkSuccess(response, 200);
			String token = JsonConfig.getNonBlankString(response, "tenant_access_token");
			if (token == null) {
				throw new SheetException("飞书未返回 tenant_access_token：" + abbreviate(response.toString()));
			}
			long expire = JsonConfig.getLong(response, "expire", 7200);
			tenantToken = token;
			tenantTokenExpiresAt = System.currentTimeMillis() / 1000 + Math.max(60, expire - 120);
			logger.info("tenant_access_token 已获取，有效期 " + expire + " 秒");
		}
	}

	private JsonObject tokenRequest(JsonObject payload) throws IOException {
		HttpRequestBuilder builder = HttpRequestBuilder.create(host())
				.url(API_PREFIX + "/auth/v3/tenant_access_token/internal")
				.contenttype(JSON)
				.header("Accept", JSON)
				.readTimeout(HTTP_TIMEOUT_MS);
		HttpRequestBuilder.OpenedConnectionBuilder connection;
		try {
			connection = builder.post();
		} catch (IOException e) {
			throw new SheetException("连接飞书开放平台失败：" + e.getMessage(), 0, -1, e);
		}
		try {
			connection.send(payload.toString());
			String text = connection.readString();
			int status = connection.getResponseCode();
			return parseObject(text, status);
		} catch (IOException e) {
			if (e instanceof SheetException) {
				throw e;
			}
			int status = -1;
			try {
				status = connection.getResponseCode();
			} catch (RuntimeException ignored) {
				// 拿不到状态码时保留 -1
			}
			throw error(status, e.getMessage(), e);
		}
	}

	private String ensureToken() throws IOException {
		String fixed = config.getAccessToken();
		if (!isBlank(fixed)) {
			return fixed.trim();
		}
		synchronized (tokenLock) {
			if (tenantToken != null && System.currentTimeMillis() / 1000 < tenantTokenExpiresAt) {
				return tenantToken;
			}
			refreshTenantToken();
			return tenantToken;
		}
	}

	private String host() {
		String baseUrl = config.getBaseUrl();
		if (isBlank(baseUrl)) {
			return "open.feishu.cn";
		}
		try {
			String authority = URI.create(baseUrl.trim()).getAuthority();
			if (!isBlank(authority)) {
				return authority;
			}
		} catch (RuntimeException ignored) {
			// 退回默认域名
		}
		return "open.feishu.cn";
	}

	// ---------------------------------------------------------------- 解析与校验

	private static String nextPageToken(JsonObject data) {
		if (data == null || !JsonConfig.getBool(data, "has_more")) {
			return null;
		}
		return JsonConfig.getNonBlankString(data, "page_token");
	}

	private static String pageTokenParam(String pageToken) {
		return pageToken == null || pageToken.isEmpty() ? "" : "&page_token=" + encode(pageToken);
	}

	private static int widthOf(List<List<CellValue>> values) {
		int width = 0;
		for (List<CellValue> row : values) {
			if (row != null) {
				width = Math.max(width, row.size());
			}
		}
		return width;
	}

	private static void checkReadLimit(A1Range range) {
		if (range.getRowCount() > MAX_READ_RECORDS) {
			throw new IllegalArgumentException(
					"读取范围行数超过上限：单次最多 " + MAX_READ_RECORDS + " 行，当前 " + range.getRowCount() + " 行");
		}
		if (range.getCellCount() > MAX_READ_CELLS) {
			throw new IllegalArgumentException(
					"读取范围单元格超过上限：单次最多 " + MAX_READ_CELLS + " 个，当前 " + range.getCellCount() + " 个");
		}
	}

	private static void checkWriteLimit(int rows, int columns) {
		if (rows > MAX_WRITE_ROWS) {
			throw new IllegalArgumentException(
					"写入行数超过上限：单次最多 " + MAX_WRITE_ROWS + " 行，当前 " + rows + " 行，请分批写入");
		}
		long cells = (long) rows * columns;
		if (cells > MAX_WRITE_CELLS) {
			throw new IllegalArgumentException(
					"写入单元格超过上限：单次最多 " + MAX_WRITE_CELLS + " 个，当前 " + cells + " 个，请拆分范围");
		}
	}

	// ---------------------------------------------------------------- 错误处理

	private static JsonObject parseObject(String text, int status) throws SheetException {
		String body = text == null ? "" : text;
		try {
			JsonElement element = JsonParser.parseString(body);
			if (!element.isJsonObject()) {
				throw new SheetException("飞书返回的不是 JSON 对象（HTTP " + status + "）：" + abbreviate(body));
			}
			return element.getAsJsonObject();
		} catch (SheetException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new SheetException("飞书返回了无法解析的响应（HTTP " + status + "）：" + abbreviate(body), e);
		}
	}

	private static void checkSuccess(JsonObject response, int status) throws SheetException {
		int code = JsonConfig.getInt(response, "code", 0);
		if (code != 0) {
			throw error(status, response.toString(), null);
		}
	}

	private static SheetException error(int status, String body, Throwable cause) {
		int code = 0;
		String serverMessage = "";
		String text = body == null ? "" : body.trim();
		if (!text.isEmpty()) {
			try {
				JsonElement element = JsonParser.parseString(text);
				if (element.isJsonObject()) {
					JsonObject object = element.getAsJsonObject();
					code = JsonConfig.getInt(object, "code", 0);
					serverMessage = JsonConfig.getNonBlankString(object, "msg",
							JsonConfig.getNonBlankString(object, "message", ""));
					JsonObject error = JsonConfig.getObject(object, "error");
					if (error != null) {
						String message = JsonConfig.getNonBlankString(error, "message");
						if (message != null) {
							serverMessage = message;
						}
					}
				}
			} catch (RuntimeException ignored) {
				// 非 JSON 响应，直接使用截断后的正文
			}
		}
		if (serverMessage.isEmpty()) {
			serverMessage = abbreviate(text);
		}
		return new SheetException(describe(status, code, serverMessage), code, status, cause);
	}

	private static String describe(int status, int code, String serverMessage) {
		String hint = businessHint(code);
		if (hint == null) {
			if (status == 401) {
				hint = "访问凭证无效或已过期";
			} else if (status == 403) {
				hint = "没有访问该多维表格的权限";
			} else if (status == 404) {
				hint = "数据表或接口不存在";
			} else if (status == 429) {
				hint = "调用过于频繁，已触发飞书限频";
			} else if (status >= 500) {
				hint = "飞书服务暂时不可用";
			} else if (status > 0) {
				hint = "飞书请求失败";
			} else {
				hint = "飞书请求失败（网络错误）";
			}
		}
		StringBuilder sb = new StringBuilder(hint);
		if (status > 0 || code != 0) {
			sb.append('（');
			if (status > 0) {
				sb.append("HTTP ").append(status);
			}
			if (code != 0) {
				if (status > 0) {
					sb.append("，");
				}
				sb.append("code ").append(code);
			}
			sb.append('）');
		}
		if (!serverMessage.isEmpty() && !serverMessage.equals(hint)) {
			sb.append('：').append(serverMessage);
		}
		return sb.toString();
	}

	private static String businessHint(int code) {
		switch (code) {
		case 21001:
			return "飞书平台策略拒绝访问：请确认自建应用已开通 bitable:app 权限并发布版本、"
					+ "已被添加为多维表格协作者，且应用与多维表格属于同一租户；跨租户请改用 user_access_token";
		case 10003:
		case 10004:
			return "应用凭据无效，请检查 app_id / app_secret";
		case 1254003:
			return "app_token 错误，请检查多维表格链接中的 base 标识";
		case 1254004:
			return "table_id 错误，请检查数据表标识";
		case 1254005:
			return "view_id 错误";
		case 1254006:
			return "record_id 错误";
		case 1254009:
			return "field_id 错误";
		case 1254013:
			return "数据表名称重复";
		case 1254014:
			return "字段名称重复";
		case 1254015:
			return "字段类型与写入值不匹配";
		case 1254040:
			return "app_token 不存在或应用无权访问";
		case 1254041:
			return "table_id 不存在";
		case 1254042:
			return "view_id 不存在";
		case 1254043:
			return "record_id 不存在";
		case 1254045:
			return "字段名不存在，请检查字段名是否完全一致";
		case 1254034:
			return "不能删除最后一张数据表";
		case 1254100:
			return "数据表数量超限";
		case 1254103:
			return "记录数量超过上限（20000 条）";
		case 1254104:
			return "单次写入记录数超过上限（1000 条），请分批写入";
		case 1254290:
			return "请求过于频繁，已触发飞书限频";
		case 1254291:
			return "数据表写冲突：同一张表不支持并发写，请串行调用";
		case 1254302:
		case 1254304:
			return "没有访问该多维表格的权限（高级权限需把应用设为可管理）";
		case 1254607:
			return "数据尚未就绪，请稍后重试";
		case 1255040:
			return "飞书请求超时，请稍后重试";
		case 99991400:
			return "请求过于频繁，已触发飞书限频";
		case 99991661:
		case 99991663:
		case 99991668:
			return "访问凭证无效或已过期";
		default:
			if (code >= 99991661 && code <= 99991680) {
				return "访问凭证无效或已过期";
			}
			return null;
		}
	}

	private static boolean isAuthError(SheetException e) {
		int code = e.getCode();
		return e.getHttpStatus() == 401 || (code >= 99991661 && code <= 99991680);
	}

	private static boolean isRateLimit(SheetException e) {
		int code = e.getCode();
		return e.getHttpStatus() == 429 || code == 99991400 || code == 1254290;
	}

	private static String abbreviate(String text) {
		if (text == null) {
			return "";
		}
		String value = text.trim().replaceAll("\\s*[\r\n]+\\s*", " ");
		return value.length() > MAX_ERROR_TEXT ? value.substring(0, MAX_ERROR_TEXT) + "……" : value;
	}

	private static void sleepBeforeRetry(long millis) throws SheetException {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SheetException("等待重试时被中断", e);
		}
	}

	private static String encode(String value) {
		try {
			return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException("UTF-8 不受支持", e);
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
