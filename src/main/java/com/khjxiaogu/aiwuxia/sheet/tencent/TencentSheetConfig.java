package com.khjxiaogu.aiwuxia.sheet.tencent;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;

/**
 * 腾讯文档开放平台凭据与默认表格配置。
 * <p>
 * 默认读取 {@code save/config/tencent-docs.json}（路径见
 * {@link com.khjxiaogu.aiwuxia.utils.ConfigPaths#tencentDocs(File)}），文件不存在时不会报错：
 * 所有字段都可以改用启动参数 {@code -D} 覆盖，便于容器化部署：
 * </p>
 * <table border="1">
 * <caption>字段与系统属性对应关系</caption>
 * <tr><th>JSON 字段</th><th>系统属性</th><th>说明</th></tr>
 * <tr><td>client_id</td><td>tencent_docs_client_id</td><td>应用 ID，必填</td></tr>
 * <tr><td>client_secret</td><td>tencent_docs_client_secret</td><td>应用密钥，自动刷新 Token 时必填</td></tr>
 * <tr><td>access_token</td><td>tencent_docs_token / tencent_docs_access_token</td><td>用户访问令牌</td></tr>
 * <tr><td>refresh_token</td><td>tencent_docs_refresh_token</td><td>刷新令牌，与 client_secret 配合自动续期</td></tr>
 * <tr><td>open_id</td><td>tencent_docs_open_id</td><td>开放平台用户 ID（请求头 Open-Id）</td></tr>
 * <tr><td>file_id</td><td>tencent_docs_file_id</td><td>在线表格唯一标识（也接受 book_id）</td></tr>
 * <tr><td>sheet_id</td><td>tencent_docs_sheet_id</td><td>默认工作表，调用时省略 sheetId 时使用</td></tr>
 * <tr><td>expires_at</td><td>tencent_docs_expires_at</td><td>Access Token 过期时间（秒级时间戳），刷新后自动回写</td></tr>
 * </table>
 * <p>
 * JSON 还接受驼峰写法（{@code clientId}、{@code accessToken}、{@code fileId} 等）。示例：
 * </p>
 * <pre>
 * {
 *   "client_id": "…",
 *   "client_secret": "…",
 *   "access_token": "…",
 *   "refresh_token": "…",
 *   "open_id": "…",
 *   "expires_at": 0,
 *   "file_id": "300000000$XXXXXXXX",
 *   "sheet_id": "BB08J2"
 * }
 * </pre>
 * <p>
 * {@code access_token} 缺失但配置了 {@code client_secret + refresh_token} 时，
 * {@link TencentSheetApi} 会在首次调用前自动换取新 Token，并把新 Token 与过期时间写回配置文件；
 * 配置文件不可写时只记日志，不影响本次进程使用。
 * </p>
 */
public final class TencentSheetConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private final File sourceFile;
	private String clientId;
	private String clientSecret;
	private String accessToken;
	private String refreshToken;
	private String openId;
	private String fileId;
	private String sheetId;
	/** Access Token 过期时间，秒级时间戳；0 表示未知。 */
	private long expiresAt;

	private TencentSheetConfig(File sourceFile) {
		this.sourceFile = sourceFile;
	}

	/**
	 * 从配置文件读取配置，并用系统属性覆盖。文件不存在或内容损坏时返回空配置，不抛异常。
	 *
	 * @param file 配置文件，可为 null
	 * @return 配置
	 */
	public static TencentSheetConfig load(File file) {
		TencentSheetConfig config = new TencentSheetConfig(file);
		if (file != null && file.isFile()) {
			try {
				JsonObject json = JsonParser.parseString(FileUtil.readString(file)).getAsJsonObject();
				config.clientId = pick(json, "client_id", "clientId");
				config.clientSecret = pick(json, "client_secret", "clientSecret");
				config.accessToken = pick(json, "access_token", "accessToken");
				config.refreshToken = pick(json, "refresh_token", "refreshToken");
				config.openId = pick(json, "open_id", "openId");
				config.fileId = pick(json, "file_id", "fileId", "book_id", "bookId");
				config.sheetId = pick(json, "sheet_id", "sheetId", "default_sheet_id", "defaultSheetId");
				config.expiresAt = JsonConfig.getLong(json, "expires_at", JsonConfig.getLong(json, "expiresAt", 0L));
			} catch (Throwable e) {
				// 配置写坏时仍允许用 -D 参数启动，不能因为一个坏文件让整个功能不可用
				System.err.println("[腾讯文档] 配置读取失败 " + file.getPath() + "：" + e);
			}
		}
		config.applyProperties();
		return config;
	}

	private static String pick(JsonObject json, String... keys) {
		for (String key : keys) {
			String value = JsonConfig.getNonBlankString(json, key);
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	private void applyProperties() {
		String value;
		if ((value = property("tencent_docs_client_id")) != null) {
			clientId = value;
		}
		if ((value = property("tencent_docs_client_secret")) != null) {
			clientSecret = value;
		}
		if ((value = property("tencent_docs_token")) != null) {
			accessToken = value;
		}
		if ((value = property("tencent_docs_access_token")) != null) {
			accessToken = value;
		}
		if ((value = property("tencent_docs_refresh_token")) != null) {
			refreshToken = value;
		}
		if ((value = property("tencent_docs_open_id")) != null) {
			openId = value;
		}
		if ((value = property("tencent_docs_file_id")) != null) {
			fileId = value;
		}
		if ((value = property("tencent_docs_sheet_id")) != null) {
			sheetId = value;
		}
		if ((value = property("tencent_docs_expires_at")) != null) {
			try {
				long parsed = Long.parseLong(value);
				if (parsed > 0) {
					expiresAt = parsed;
				}
			} catch (NumberFormatException ignored) {
				// 非法值按未配置处理
			}
		}
	}

	private static String property(String key) {
		String value = System.getProperty(key);
		if (value == null) {
			return null;
		}
		value = value.trim();
		return value.isEmpty() ? null : value;
	}

	/**
	 * @return 配置文件路径；用构造函数直接创建时可能为 {@code null}
	 */
	public File getSourceFile() {
		return sourceFile;
	}

	/**
	 * @return 配置文件路径描述，用于错误提示
	 */
	public String pathDescription() {
		return sourceFile == null ? "（未指定配置文件）" : sourceFile.getPath();
	}

	public String getClientId() {
		return clientId;
	}

	public String getClientSecret() {
		return clientSecret;
	}

	public String getAccessToken() {
		return accessToken;
	}

	public String getRefreshToken() {
		return refreshToken;
	}

	public String getOpenId() {
		return openId;
	}

	public String getFileId() {
		return fileId;
	}

	/**
	 * @return 默认工作表 ID，未配置时为 {@code null}
	 */
	public String getSheetId() {
		return sheetId;
	}

	/**
	 * @return Access Token 过期时间（秒级时间戳），0 表示未知
	 */
	public long getExpiresAt() {
		return expiresAt;
	}

	/**
	 * @return 是否配置了在线表格 ID
	 */
	public boolean hasFileId() {
		return !isBlank(fileId);
	}

	/**
	 * 是否具备自动刷新 Access Token 的条件。
	 *
	 * @return 配置了 client_id、client_secret 与 refresh_token 时返回 true
	 */
	public boolean canRefresh() {
		return !isBlank(clientId) && !isBlank(clientSecret) && !isBlank(refreshToken);
	}

	/**
	 * 是否为一次可用调用所需的最小配置（fileId + clientId + Token 或刷新凭据）。
	 * <p>
	 * {@code open_id} 不在此处校验：配置了刷新凭据时，刷新 Token 的响应会带回 {@code user_id}
	 * （即 Open-Id），由 {@link TencentSheetApi} 自动补全。
	 * </p>
	 *
	 * @return 配置完整时返回 true
	 */
	public boolean isConfigured() {
		return hasFileId() && !isBlank(clientId)
				&& (!isBlank(accessToken) || canRefresh());
	}

	/**
	 * Access Token 是否已过期或即将过期（提前 120 秒视为过期，避免请求途中失效）。
	 *
	 * @return 需要刷新时返回 true
	 */
	public boolean isTokenExpired() {
		return expiresAt > 0 && System.currentTimeMillis() / 1000 >= expiresAt - 120;
	}

	void setAccessToken(String accessToken) {
		this.accessToken = accessToken;
	}

	void setRefreshToken(String refreshToken) {
		this.refreshToken = refreshToken;
	}

	void setOpenId(String openId) {
		this.openId = openId;
	}

	void setExpiresAt(long expiresAt) {
		this.expiresAt = expiresAt;
	}

	/**
	 * 把当前 Token 与过期时间写回配置文件（保留文件中已有的其他字段）。
	 * <p>
	 * 配置文件不存在时按当前字段新建；没有绑定配置文件时直接返回。
	 * </p>
	 *
	 * @throws IOException 写入失败
	 */
	public synchronized void save() throws IOException {
		if (sourceFile == null) {
			return;
		}
		JsonObject json;
		if (sourceFile.isFile()) {
			try {
				json = JsonParser.parseString(FileUtil.readString(sourceFile)).getAsJsonObject();
			} catch (RuntimeException e) {
				json = new JsonObject();
			}
		} else {
			json = new JsonObject();
		}
		if (accessToken != null) {
			json.addProperty("access_token", accessToken);
		}
		if (refreshToken != null) {
			json.addProperty("refresh_token", refreshToken);
		}
		if (openId != null) {
			json.addProperty("open_id", openId);
		}
		if (expiresAt > 0) {
			json.addProperty("expires_at", expiresAt);
		}
		File parent = sourceFile.getParentFile();
		if (parent != null && !parent.isDirectory()) {
			parent.mkdirs();
		}
		FileUtil.transfer(GSON.toJson(json), sourceFile);
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	@Override
	public String toString() {
		return "TencentSheetConfig[fileId=" + fileId + ", sheetId=" + sheetId + ", clientId=" + mask(clientId)
				+ ", accessToken=" + mask(accessToken) + ", refreshToken=" + mask(refreshToken) + ", expiresAt="
				+ expiresAt + "]";
	}

	private static String mask(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		if (value.length() <= 8) {
			return "****";
		}
		return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof TencentSheetConfig)) {
			return false;
		}
		TencentSheetConfig other = (TencentSheetConfig) o;
		return expiresAt == other.expiresAt && Objects.equals(sourceFile, other.sourceFile)
				&& Objects.equals(clientId, other.clientId) && Objects.equals(clientSecret, other.clientSecret)
				&& Objects.equals(accessToken, other.accessToken) && Objects.equals(refreshToken, other.refreshToken)
				&& Objects.equals(openId, other.openId) && Objects.equals(fileId, other.fileId)
				&& Objects.equals(sheetId, other.sheetId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(sourceFile, clientId, clientSecret, accessToken, refreshToken, openId, fileId, sheetId,
				expiresAt);
	}
}
