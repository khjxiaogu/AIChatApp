package com.khjxiaogu.aiwuxia.sheet.tencent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.sheet.A1Range;
import com.khjxiaogu.aiwuxia.sheet.CellValue;
import com.khjxiaogu.aiwuxia.sheet.SheetApi;
import com.khjxiaogu.aiwuxia.sheet.SheetData;
import com.khjxiaogu.aiwuxia.sheet.SheetException;
import com.khjxiaogu.aiwuxia.sheet.SheetMeta;
import com.khjxiaogu.aiwuxia.utils.ConfigPaths;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.HttpRequestBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.webserver.loging.SimpleLogger;

/**
 * 腾讯文档在线表格 API 接入。
 * <p>
 * 按腾讯文档开放平台 <b>V3</b> 接口实现（V2 已废弃）：
 * </p>
 * <ul>
 * <li>{@code GET /openapi/spreadsheet/v3/files/{fileId}}：查询工作表信息；</li>
 * <li>{@code GET /openapi/spreadsheet/v3/files/{fileId}/{sheetId}/{range}}：读取 A1 范围；</li>
 * <li>{@code POST /openapi/spreadsheet/v3/files/{fileId}/batchUpdate}：写入、增删工作表。</li>
 * </ul>
 * <p>
 * 鉴权使用三个请求头：{@code Access-Token}、{@code Client-Id}、{@code Open-Id}，凭据从
 * {@link TencentSheetConfig} 读取（默认 {@code save/config/tencent-docs.json}）。配置了
 * {@code client_secret + refresh_token} 时，Token 过期或接口返回鉴权失败会自动刷新并重试一次。
 * </p>
 * <p>
 * 官方限制（本类在发请求前校验，超过时抛 {@link IllegalArgumentException}）：
 * </p>
 * <ul>
 * <li>单次读取范围：行数 ≤ 1000、列数 ≤ 200、单元格总数 ≤ 10000；</li>
 * <li>单次写入范围：行数 ≤ 1000、列数 ≤ 200、单元格总数 ≤ 10000；</li>
 * <li>新增工作表：行数 ≤ 10000、列数 ≤ 200、单元格总数 ≤ 10000，名称 ≤ 31 个字符；</li>
 * <li>写入单元格只保证支持 {@code text/number/link}，因此 {@link CellValue.Kind#TIME} 会以
 * {@code yyyy-MM-dd HH:mm:ss} 文本提交；地理位置、单选/多选读取时降级为文本。</li>
 * </ul>
 * <p>
 * 频率控制：官方限制写入 50 次/分钟、读取 100 次/分钟。本类遇到 HTTP 429 会做两次短退避重试，
 * 之后仍失败则抛出带中文提示的 {@link SheetException}。实例方法本身无共享可变状态（除 Token 刷新，
 * 已加锁），可并发调用，但请遵守上述频率限制。
 * </p>
 */
public class TencentSheetApi implements SheetApi {
	/** 实现名称。 */
	public static final String PROVIDER_NAME = "腾讯文档";

	private static final String HOST = "docs.qq.com";
	private static final String JSON = "application/json";
	private static final int HTTP_TIMEOUT_MS = 30000;

	/** 官方单次读取/写入范围上限。 */
	private static final int MAX_RANGE_ROWS = 1000;
	private static final int MAX_RANGE_COLUMNS = 200;
	private static final long MAX_RANGE_CELLS = 10000L;

	/** 新增工作表上限。 */
	private static final int MAX_SHEET_ROWS = 10000;
	private static final int MAX_SHEET_TITLE_LENGTH = 31;

	private static final int DEFAULT_SHEET_ROWS = 200;
	private static final int DEFAULT_SHEET_COLUMNS = 20;

	/** 错误正文在提示信息里的最大长度。 */
	private static final int MAX_ERROR_TEXT = 400;

	private final SimpleLogger logger = new SimpleLogger("腾讯文档");
	private final TencentSheetConfig config;
	/** 构造时显式指定的表格 ID；为 null 时使用配置里的 file_id。 */
	private final String explicitFileId;
	private final Object tokenLock = new Object();

	public TencentSheetApi(TencentSheetConfig config) {
		this(config, null);
	}

	/**
	 * @param config   凭据配置
	 * @param fileId   在线表格 ID；为 null 时使用配置里的 file_id
	 */
	public TencentSheetApi(TencentSheetConfig config, String fileId) {
		if (config == null) {
			throw new IllegalArgumentException("config 不能为 null");
		}
		this.config = config;
		String id = fileId == null ? null : fileId.trim();
		this.explicitFileId = id == null || id.isEmpty() ? null : id;
	}

	/**
	 * 从数据目录读取 {@code config/tencent-docs.json} 创建实例。
	 *
	 * @param dataFolder 数据目录（通常是 {@code save}）
	 * @return 腾讯文档表格 API
	 * @throws IOException 配置不完整
	 */
	public static TencentSheetApi fromDataFolder(File dataFolder) throws IOException {
		if (dataFolder == null) {
			throw new IllegalArgumentException("dataFolder 不能为 null");
		}
		return fromFile(ConfigPaths.tencentDocs(dataFolder));
	}

	/**
	 * 从指定配置文件创建实例。
	 *
	 * @param configFile 配置文件
	 * @return 腾讯文档表格 API
	 * @throws IOException 配置不完整
	 */
	public static TencentSheetApi fromFile(File configFile) throws IOException {
		TencentSheetConfig cfg = TencentSheetConfig.load(configFile);
		TencentSheetApi api = new TencentSheetApi(cfg);
		if (!cfg.isConfigured()) {
			throw new SheetException("未配置腾讯文档表格：请创建 " + cfg.pathDescription()
					+ "，填写 file_id、client_id，以及 access_token（或 client_secret + refresh_token 用于自动刷新）；"
					+ "open_id 可留空，配置刷新凭据后会自动补全");
		}
		return api;
	}

	/**
	 * @return 凭据配置
	 */
	public TencentSheetConfig getConfig() {
		return config;
	}

	/**
	 * @return 当前使用的在线表格 ID，未配置时为 {@code null}
	 */
	public String getFileId() {
		return explicitFileId != null ? explicitFileId : config.getFileId();
	}

	/**
	 * @return 配置是否完整（允许用构造函数显式传入 fileId，此时不要求配置文件里有 file_id）
	 */
	public boolean isConfigured() {
		String id = getFileId();
		return !isBlank(id) && !isBlank(config.getClientId())
				&& (!isBlank(config.getAccessToken()) || config.canRefresh());
	}

	@Override
	public String getProviderName() {
		return PROVIDER_NAME;
	}

	@Override
	public List<SheetMeta> listSheets() throws IOException {
		// concise=1：不计算已使用行列数，接口更快（官方建议不需要该数据时使用）
		JsonObject response = apiRequest("GET", basePath() + "?concise=1", null);
		return parseSheets(response);
	}

	@Override
	public SheetMeta getSheet(String sheetId) throws IOException {
		String id = resolveSheetId(sheetId);
		// 不传 concise：返回包含已使用行列数的完整元数据，appendRows 依赖它
		JsonObject response = apiRequest("GET", basePath(), null);
		for (SheetMeta meta : parseSheets(response)) {
			if (id.equals(meta.getSheetId())) {
				return meta;
			}
		}
		throw new SheetException("工作表不存在：" + id);
	}

	@Override
	public SheetData readRange(String sheetId, String a1Range) throws IOException {
		String id = resolveSheetId(sheetId);
		A1Range range = A1Range.parse(a1Range);
		checkRangeLimit(range.getRowCount(), range.getColumnCount(), "读取");
		JsonObject response = apiRequest("GET", basePath() + "/" + id + "/" + range.toA1(), null);
		JsonObject grid = gridDataOf(response);
		if (grid == null) {
			return new SheetData(range.getStartRow(), range.getStartColumn(), Collections.emptyList());
		}
		int startRow = JsonConfig.getInt(grid, "startRow", range.getStartRow());
		int startColumn = JsonConfig.getInt(grid, "startColumn", range.getStartColumn());
		List<List<CellValue>> rows = new ArrayList<>();
		JsonArray rowArray = JsonConfig.getArray(grid, "rows");
		if (rowArray != null) {
			for (JsonElement rowElement : rowArray) {
				if (rowElement == null || !rowElement.isJsonObject()) {
					rows.add(Collections.emptyList());
					continue;
				}
				JsonArray valueArray = JsonConfig.getArray(rowElement.getAsJsonObject(), "values");
				List<CellValue> values = new ArrayList<>();
				if (valueArray != null) {
					for (JsonElement cell : valueArray) {
						values.add(cell != null && cell.isJsonObject() ? toCellValue(cell.getAsJsonObject())
								: CellValue.empty());
					}
				}
				rows.add(values);
			}
		}
		return new SheetData(startRow, startColumn, rows);
	}

	@Override
	public void writeRange(String sheetId, String a1Range, List<List<CellValue>> values) throws IOException {
		String id = resolveSheetId(sheetId);
		if (values == null || values.isEmpty()) {
			throw new IllegalArgumentException("写入内容不能为空");
		}
		A1Range range = A1Range.parse(a1Range);
		int width = widthOf(values);
		if (width == 0) {
			throw new IllegalArgumentException("写入内容不能为空");
		}
		checkRangeLimit(values.size(), width, "写入");
		JsonObject payload = buildUpdateRangeRequest(id, range.getStartRow(), range.getStartColumn(), values);
		apiRequest("POST", basePath() + "/batchUpdate", payload);
	}

	@Override
	public void clearRange(String sheetId, String a1Range) throws IOException {
		String id = resolveSheetId(sheetId);
		A1Range range = A1Range.parse(a1Range);
		checkRangeLimit(range.getRowCount(), range.getColumnCount(), "清空");
		List<List<CellValue>> values = new ArrayList<>(range.getRowCount());
		for (int row = 0; row < range.getRowCount(); row++) {
			values.add(Collections.nCopies(range.getColumnCount(), CellValue.empty()));
		}
		// V3 没有独立的清除接口，写入空文本即可清空内容并保留样式
		writeRange(id, range.toA1(), values);
	}

	@Override
	public SheetMeta addSheet(String title, int rowCount, int columnCount) throws IOException {
		String name = title == null ? "" : title.trim();
		if (name.isEmpty()) {
			throw new IllegalArgumentException("工作表名称不能为空");
		}
		if (name.length() > MAX_SHEET_TITLE_LENGTH) {
			throw new IllegalArgumentException(
					"工作表名称最长 " + MAX_SHEET_TITLE_LENGTH + " 个字符，当前 " + name.length() + " 个");
		}
		int rows = rowCount <= 0 ? DEFAULT_SHEET_ROWS : rowCount;
		int columns = columnCount <= 0 ? DEFAULT_SHEET_COLUMNS : columnCount;
		checkNewSheetLimit(rows, columns);

		JsonBuilder.JsonObjectBuilder<?> root = JsonBuilder.object();
		JsonBuilder.JsonObjectBuilder<?> request = root.array("requests").object();
		JsonBuilder.JsonObjectBuilder<?> add = request.object("addSheetRequest");
		add.add("title", name).add("rowCount", rows).add("columnCount", columns);

		JsonObject response = apiRequest("POST", basePath() + "/batchUpdate", root.get());
		JsonObject added = firstResponse(response, "addSheetResponse");
		JsonObject properties = added == null ? null : JsonConfig.getObject(added, "properties");
		if (properties != null) {
			SheetMeta meta = parseSheetMeta(properties);
			if (meta.getSheetId() != null) {
				return meta;
			}
		}
		// 服务端没有回传 properties 时按名称回查（返回的是轻量信息，但至少能拿到 sheetId）
		for (SheetMeta meta : listSheets()) {
			if (name.equals(meta.getTitle())) {
				return meta;
			}
		}
		throw new SheetException("新增工作表「" + name + "」成功，但未能获取其 sheetId，请用 listSheets() 确认");
	}

	@Override
	public void deleteSheet(String sheetId) throws IOException {
		String id = resolveSheetId(sheetId);
		JsonBuilder.JsonObjectBuilder<?> root = JsonBuilder.object();
		JsonBuilder.JsonObjectBuilder<?> request = root.array("requests").object();
		request.object("deleteSheetRequest").add("sheetId", id);
		apiRequest("POST", basePath() + "/batchUpdate", root.get());
	}

	// ---------------------------------------------------------------- HTTP

	/**
	 * 发送一次 API 请求，处理限频退避与 Token 刷新重试。
	 *
	 * @param method HTTP 方法，GET 或 POST
	 * @param path   以 / 开头的路径
	 * @param body   POST 请求体，GET 时为 null
	 * @return 解析后的响应对象
	 * @throws IOException 调用失败
	 */
	private JsonObject apiRequest(String method, String path, JsonObject body) throws IOException {
		boolean refreshed = false;
		int rateLimitRetries = 0;
		while (true) {
			try {
				return apiRequestOnce(method, path, body);
			} catch (SheetException e) {
				// 429 表示请求被拒绝，没有产生副作用，退避后重试是安全的；
				// 少数情况下限频以 HTTP 200 + 业务码 400007 返回，一并处理
				if ((e.getHttpStatus() == 429 || e.getCode() == 400007) && rateLimitRetries < 2) {
					rateLimitRetries++;
					sleepBeforeRetry(1000L * rateLimitRetries);
					continue;
				}
				if (!refreshed && isAuthError(e) && config.canRefresh()) {
					refreshed = true;
					logger.warning("腾讯文档鉴权失败，刷新 Access Token 后重试：" + e.getMessage());
					try {
						refreshToken();
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
		String clientId = config.getClientId();
		if (isBlank(clientId)) {
			throw new SheetException(
					"缺少 client_id：请在 " + config.pathDescription() + " 中配置，或设置 -Dtencent_docs_client_id=...");
		}
		String openId = config.getOpenId();
		if (isBlank(openId) && config.canRefresh()) {
			// 刷新令牌的响应会带回 user_id（即 Open-Id），用于未配置 open_id 的部署
			refreshToken();
			openId = config.getOpenId();
		}
		if (isBlank(openId)) {
			throw new SheetException("缺少 open_id：请在 " + config.pathDescription()
					+ " 中配置，或设置 -Dtencent_docs_open_id=...；配置 refresh_token 时也可由刷新结果自动补全");
		}

		HttpRequestBuilder builder = HttpRequestBuilder.create(HOST)
				.url(path)
				.header("Access-Token", token)
				.header("Client-Id", clientId.trim())
				.header("Open-Id", openId.trim())
				.contenttype(JSON)
				.header("Accept", JSON)
				.readTimeout(HTTP_TIMEOUT_MS);

		HttpRequestBuilder.OpenedConnectionBuilder connection;
		try {
			connection = "GET".equals(method) ? builder.get() : builder.post();
		} catch (IOException e) {
			throw new SheetException("连接腾讯文档失败：" + e.getMessage(), 0, -1, e);
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
	 * 用刷新令牌换取新的 Access Token，并写回配置文件。
	 * <p>
	 * 接口：{@code GET /oauth/v2/token?grant_type=refresh_token&client_id=...&client_secret=...&refresh_token=...}
	 * </p>
	 */
	private void refreshToken() throws IOException {
		synchronized (tokenLock) {
			String clientId = config.getClientId();
			String clientSecret = config.getClientSecret();
			String refreshToken = config.getRefreshToken();
			if (isBlank(clientId) || isBlank(clientSecret) || isBlank(refreshToken)) {
				throw new SheetException("无法刷新 Access Token：缺少 client_secret 或 refresh_token（配置 "
						+ config.pathDescription() + "）");
			}
			String path = "/oauth/v2/token?client_id=" + encode(clientId) + "&client_secret=" + encode(clientSecret)
					+ "&grant_type=refresh_token&refresh_token=" + encode(refreshToken);
			JsonObject response = publicRequest(path);
			String token = JsonConfig.getNonBlankString(response, "access_token");
			if (token == null) {
				throw error(200, response.toString(), null);
			}
			long expiresIn = JsonConfig.getLong(response, "expires_in", 0);
			String newRefreshToken = JsonConfig.getNonBlankString(response, "refresh_token");
			String userId = JsonConfig.getNonBlankString(response, "user_id");
			config.setAccessToken(token);
			config.setExpiresAt(expiresIn > 0
					? System.currentTimeMillis() / 1000 + Math.max(30L, expiresIn - 120L)
					: 0);
			if (newRefreshToken != null) {
				config.setRefreshToken(newRefreshToken);
			}
			if (userId != null && isBlank(config.getOpenId())) {
				config.setOpenId(userId);
			}
			try {
				config.save();
			} catch (IOException e) {
				logger.warning("Access Token 已刷新，但写回配置失败：" + e.getMessage());
			}
			logger.info("Access Token 已刷新" + (expiresIn > 0 ? "，有效期 " + expiresIn + " 秒" : ""));
		}
	}

	private JsonObject publicRequest(String path) throws IOException {
		// 这里刻意不用 HttpRequestBuilder：它会把完整 URL 打到标准输出，
		// 而换取 Token 的 URL 查询串里带着 client_secret 与 refresh_token，不能进日志。
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL("https://" + HOST + path).openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(10000);
			connection.setReadTimeout(HTTP_TIMEOUT_MS);
			connection.setRequestProperty("Accept", JSON);
			int status = connection.getResponseCode();
			InputStream stream = status >= 200 && status < 300 ? connection.getInputStream()
					: connection.getErrorStream();
			String text;
			if (stream == null) {
				text = "";
			} else {
				try (InputStream in = stream) {
					text = FileUtil.readString(in);
				}
			}
			if (status < 200 || status >= 300) {
				throw error(status, text, null);
			}
			return parseObject(text, status);
		} catch (IOException e) {
			if (e instanceof SheetException) {
				throw e;
			}
			throw new SheetException("连接腾讯文档失败：" + e.getMessage(), 0, -1, e);
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/**
	 * 取可用的 Access Token：未过期直接返回；已过期且能刷新时先刷新。
	 */
	private String ensureToken() throws IOException {
		synchronized (tokenLock) {
			String token = config.getAccessToken();
			if (!isBlank(token) && !config.isTokenExpired()) {
				return token;
			}
			if (config.canRefresh()) {
				refreshToken();
				token = config.getAccessToken();
				if (!isBlank(token)) {
					return token;
				}
			}
			if (!isBlank(token)) {
				// 过期时间未知：交给服务端判断，401 时会走刷新重试
				return token;
			}
			throw new SheetException("缺少 access_token：请在 " + config.pathDescription()
					+ " 中配置，或配置 client_secret + refresh_token 以便自动刷新，也可设置 -Dtencent_docs_token=...");
		}
	}

	// ---------------------------------------------------------------- 解析与校验

	private String basePath() throws SheetException {
		return "/openapi/spreadsheet/v3/files/" + fileId();
	}

	private String fileId() throws SheetException {
		String id = getFileId();
		if (isBlank(id)) {
			throw new SheetException("缺少 file_id（在线表格唯一标识）：请在 " + config.pathDescription()
					+ " 中配置，或设置 -Dtencent_docs_file_id=...");
		}
		return id.trim();
	}

	private String resolveSheetId(String sheetId) throws SheetException {
		String id = sheetId == null ? null : sheetId.trim();
		if (isBlank(id)) {
			id = config.getSheetId();
		}
		if (isBlank(id)) {
			throw new SheetException("缺少 sheetId：请传入工作表 ID，或在配置中设置 sheet_id 作为默认工作表");
		}
		return id.trim();
	}

	private static JsonObject gridDataOf(JsonObject response) {
		JsonObject grid = JsonConfig.getObject(response, "gridData");
		if (grid == null) {
			// 兼容 {data:{gridData:...}} 的封装形式
			JsonObject data = JsonConfig.getObject(response, "data");
			grid = data == null ? null : JsonConfig.getObject(data, "gridData");
		}
		return grid;
	}

	private static List<SheetMeta> parseSheets(JsonObject response) {
		JsonArray properties = JsonConfig.getArray(response, "properties");
		if (properties == null) {
			JsonObject data = JsonConfig.getObject(response, "data");
			properties = data == null ? null : JsonConfig.getArray(data, "properties");
		}
		List<SheetMeta> sheets = new ArrayList<>();
		if (properties != null) {
			for (JsonElement element : properties) {
				if (element != null && element.isJsonObject()) {
					sheets.add(parseSheetMeta(element.getAsJsonObject()));
				}
			}
		}
		return sheets;
	}

	private static SheetMeta parseSheetMeta(JsonObject properties) {
		return new SheetMeta(
				JsonConfig.getNonBlankString(properties, "sheetId"),
				JsonConfig.getString(properties, "title", ""),
				JsonConfig.getInt(properties, "rowCount", -1),
				JsonConfig.getInt(properties, "columnCount", -1),
				JsonConfig.getInt(properties, "rowTotal", -1),
				JsonConfig.getInt(properties, "columnTotal", -1));
	}

	private static JsonObject firstResponse(JsonObject response, String key) {
		JsonArray responses = JsonConfig.getArray(response, "responses");
		if (responses == null) {
			JsonObject data = JsonConfig.getObject(response, "data");
			responses = data == null ? null : JsonConfig.getArray(data, "responses");
		}
		if (responses == null || responses.size() == 0) {
			return null;
		}
		JsonElement first = responses.get(0);
		if (first == null || !first.isJsonObject()) {
			return null;
		}
		return JsonConfig.getObject(first.getAsJsonObject(), key);
	}

	private static CellValue toCellValue(JsonObject cell) {
		JsonObject value = JsonConfig.getObject(cell, "cellValue");
		if (value == null) {
			return CellValue.empty();
		}
		if (JsonConfig.has(value, "number")) {
			return CellValue.of(JsonConfig.getDouble(value, "number", 0));
		}
		JsonObject link = JsonConfig.getObject(value, "link");
		if (link != null) {
			return CellValue.link(JsonConfig.getString(link, "url"), JsonConfig.getString(link, "text"));
		}
		JsonObject time = JsonConfig.getObject(value, "time");
		if (time != null) {
			return toTimeValue(time);
		}
		JsonObject location = JsonConfig.getObject(value, "location");
		if (location != null) {
			// 地理位置没有对应的通用类型，降级为地点名称文本
			return CellValue.of(JsonConfig.getString(location, "name"));
		}
		JsonObject select = JsonConfig.getObject(value, "select");
		if (select != null) {
			// 单选/多选降级为选项文本
			return CellValue.of(selectText(select));
		}
		return CellValue.of(JsonConfig.getString(value, "text"));
	}

	private static CellValue toTimeValue(JsonObject time) {
		int year = JsonConfig.getInt(time, "year", 0);
		int month = JsonConfig.getInt(time, "month", 0);
		int day = JsonConfig.getInt(time, "day", 0);
		int hour = JsonConfig.getInt(time, "hour", 0);
		int minute = JsonConfig.getInt(time, "minute", 0);
		int second = JsonConfig.getInt(time, "second", 0);
		if (year <= 0) {
			return CellValue.empty();
		}
		try {
			return CellValue.time(LocalDateTime.of(year, clamp(month, 1, 12), clamp(day, 1, 31),
					clamp(hour, 0, 23), clamp(minute, 0, 59), clamp(second, 0, 59)));
		} catch (DateTimeException e) {
			return CellValue.empty();
		}
	}

	private static String selectText(JsonObject select) {
		List<String> ids = JsonConfig.getStringList(select, "value");
		JsonArray options = JsonConfig.getArray(select, "options");
		Map<String, String> texts = new LinkedHashMap<>();
		if (options != null) {
			for (JsonElement element : options) {
				if (element == null || !element.isJsonObject()) {
					continue;
				}
				JsonObject option = element.getAsJsonObject();
				String id = JsonConfig.getNonBlankString(option, "id");
				String text = JsonConfig.getString(option, "text");
				if (id != null) {
					texts.put(id, text == null ? id : text);
				}
			}
		}
		List<String> out = new ArrayList<>();
		for (String id : ids) {
			String text = texts.get(id);
			out.add(text == null ? id : text);
		}
		return String.join("、", out);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static JsonObject buildUpdateRangeRequest(String sheetId, int startRow, int startColumn,
			List<List<CellValue>> values) {
		JsonBuilder.JsonObjectBuilder<?> root = JsonBuilder.object();
		JsonBuilder.JsonObjectBuilder<?> request = root.array("requests").object();
		JsonBuilder.JsonObjectBuilder<?> update = request.object("updateRangeRequest");
		update.add("sheetId", sheetId);
		JsonBuilder.JsonObjectBuilder<?> grid = update.object("gridData");
		grid.add("startRow", startRow).add("startColumn", startColumn);
		JsonBuilder.JsonArrayBuilder<?> rows = grid.array("rows");
		for (List<CellValue> row : values) {
			JsonBuilder.JsonObjectBuilder<?> rowObject = rows.object();
			JsonBuilder.JsonArrayBuilder<?> cells = rowObject.array("values");
			if (row == null || row.isEmpty()) {
				// 空行也要占位，避免行序错位；写一个空文本单元格保持结构合法
				JsonBuilder.JsonObjectBuilder<?> cell = cells.object();
				addCellValue(cell.object("cellValue"), CellValue.empty());
				continue;
			}
			for (CellValue value : row) {
				JsonBuilder.JsonObjectBuilder<?> cell = cells.object();
				addCellValue(cell.object("cellValue"), value == null ? CellValue.empty() : value);
			}
		}
		return root.get();
	}

	private static void addCellValue(JsonBuilder.JsonObjectBuilder<?> cellValue, CellValue value) {
		switch (value.getKind()) {
		case NUMBER:
			cellValue.add("number", value.getNumber());
			break;
		case LINK:
			JsonBuilder.JsonObjectBuilder<?> link = cellValue.object("link");
			link.add("url", value.getLinkUrl() == null ? "" : value.getLinkUrl());
			link.add("text", value.getLinkText() == null ? "" : value.getLinkText());
			break;
		case TIME:
			// V3 写入只保证 text/number/link，时间以文本提交
			cellValue.add("text", value.asText());
			break;
		case TEXT:
			cellValue.add("text", value.getText());
			break;
		default:
			cellValue.add("text", "");
		}
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

	private static void checkRangeLimit(int rows, int columns, String action) {
		if (rows > MAX_RANGE_ROWS) {
			throw new IllegalArgumentException(
					action + "范围行数超过上限：单次最多 " + MAX_RANGE_ROWS + " 行，当前 " + rows + " 行，请分批处理");
		}
		if (columns > MAX_RANGE_COLUMNS) {
			throw new IllegalArgumentException(
					action + "范围列数超过上限：单次最多 " + MAX_RANGE_COLUMNS + " 列，当前 " + columns + " 列，请分批处理");
		}
		long cells = (long) rows * columns;
		if (cells > MAX_RANGE_CELLS) {
			throw new IllegalArgumentException(
					action + "范围单元格超过上限：单次最多 " + MAX_RANGE_CELLS + " 个，当前 " + cells + " 个，请拆分范围");
		}
	}

	private static void checkNewSheetLimit(int rows, int columns) {
		if (rows > MAX_SHEET_ROWS) {
			throw new IllegalArgumentException("新增工作表行数不能超过 " + MAX_SHEET_ROWS + "，当前 " + rows);
		}
		if (columns > MAX_RANGE_COLUMNS) {
			throw new IllegalArgumentException("新增工作表列数不能超过 " + MAX_RANGE_COLUMNS + "，当前 " + columns);
		}
		long cells = (long) rows * columns;
		if (cells > MAX_RANGE_CELLS) {
			throw new IllegalArgumentException("新增工作表单元格总数不能超过 " + MAX_RANGE_CELLS + "，当前 " + cells);
		}
	}

	// ---------------------------------------------------------------- 错误处理

	private static JsonObject parseObject(String text, int status) throws SheetException {
		String body = text == null ? "" : text;
		try {
			JsonElement element = JsonParser.parseString(body);
			if (!element.isJsonObject()) {
				throw new SheetException("腾讯文档返回的不是 JSON 对象（HTTP " + status + "）：" + abbreviate(body));
			}
			return element.getAsJsonObject();
		} catch (SheetException e) {
			throw e;
		} catch (RuntimeException e) {
			throw new SheetException("腾讯文档返回了无法解析的响应（HTTP " + status + "）：" + abbreviate(body), e);
		}
	}

	private static void checkSuccess(JsonObject response, int status) throws SheetException {
		int code = JsonConfig.getInt(response, "code", JsonConfig.getInt(response, "ret", 0));
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
					code = JsonConfig.getInt(object, "code", JsonConfig.getInt(object, "ret", 0));
					serverMessage = JsonConfig.getNonBlankString(object, "message",
							JsonConfig.getNonBlankString(object, "msg", ""));
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
				hint = "Access Token 无效或已过期";
			} else if (status == 403) {
				hint = "没有访问该表格的权限";
			} else if (status == 404) {
				hint = "表格或工作表不存在";
			} else if (status == 429) {
				hint = "调用过于频繁，已触发腾讯文档限频";
			} else if (status >= 500) {
				hint = "腾讯文档服务暂时不可用";
			} else if (status > 0) {
				hint = "腾讯文档请求失败";
			} else {
				hint = "腾讯文档请求失败（网络错误）";
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
		case 400001:
			return "请求参数不合法";
		case 400006:
			return "没有操作权限或 Access Token 已失效";
		case 400007:
			return "调用过于频繁，已触发腾讯文档限频";
		case 400010:
			return "腾讯文档服务内部错误";
		case 37019:
			return "Access Token 无效或已过期";
		case 10302:
			return "Client-Id 错误或缺失";
		case 10303:
			return "Open-Id 错误或缺失";
		case 10313:
			return "Access-Token 为空";
		default:
			return null;
		}
	}

	private static boolean isAuthError(SheetException e) {
		int code = e.getCode();
		return e.getHttpStatus() == 401 || code == 400006 || code == 37019 || code == 10302 || code == 10303
				|| code == 10313;
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
