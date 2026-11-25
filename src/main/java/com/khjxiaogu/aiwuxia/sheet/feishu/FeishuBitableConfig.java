package com.khjxiaogu.aiwuxia.sheet.feishu;

import java.io.File;
import java.util.Objects;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;

/**
 * 飞书开放平台凭据与连接级多维表格标识。
 * <p>
 * 保存鉴权信息，以及“连接哪张多维表格”的 {@code app_token}；具体数据表 {@code table_id} 由
 * {@code SheetApi} 的调用参数传入。字段行为按固定默认值处理：写表头时自动创建缺失字段、不自动
 * 删除旧字段、按飞书记录默认顺序读取。
 * </p>
 * <p>
 * 默认读取 {@code save/config/feishu-bitable.json}（路径见
 * {@link com.khjxiaogu.aiwuxia.utils.ConfigPaths#feishuBitable(File)}），文件不存在时不会报错：
 * 所有字段都可以改用启动参数 {@code -D} 覆盖。
 * </p>
 * <table border="1">
 * <caption>字段与系统属性对应关系</caption>
 * <tr><th>JSON 字段</th><th>系统属性</th><th>说明</th></tr>
 * <tr><td>app_id</td><td>feishu_app_id</td><td>自建应用 App ID</td></tr>
 * <tr><td>app_secret</td><td>feishu_app_secret</td><td>自建应用 App Secret</td></tr>
 * <tr><td>access_token</td><td>feishu_token / feishu_access_token</td><td>用户令牌；与自建应用凭据二选一，配置后不再自动换取 tenant_access_token</td></tr>
 * <tr><td>app_token</td><td>feishu_app_token</td><td>连接级多维表格标识（base 链接中的 app_token）；具体数据表由调用方传 table_id</td></tr>
 * <tr><td>base_url</td><td>feishu_base_url</td><td>开放平台域名，默认 https://open.feishu.cn</td></tr>
 * </table>
 * <p>
 * 示例：
 * </p>
 * <pre>
 * {
 *   "app_id": "cli_xxx",
 *   "app_secret": "xxx",
 *   "access_token": "",
 *   "app_token": "bascnxxx",
 *   "base_url": "https://open.feishu.cn"
 * }
 * </pre>
 * <p>
 * 默认使用自建应用凭据换取 {@code tenant_access_token}（有效期 2 小时，进程内自动缓存与续期）；
 * 若配置了 {@code access_token}（用户令牌）则直接使用，不参与自动刷新。
 * </p>
 */
public final class FeishuBitableConfig {
	private final File sourceFile;
	private String appId;
	private String appSecret;
	private String accessToken;
	private String appToken;
	private String baseUrl = "https://open.feishu.cn";

	private FeishuBitableConfig(File sourceFile) {
		this.sourceFile = sourceFile;
	}

	/**
	 * 从配置文件读取凭据，并用系统属性覆盖。文件不存在或内容损坏时返回空凭据，不抛异常。
	 *
	 * @param file 配置文件，可为 null
	 * @return 凭据
	 */
	public static FeishuBitableConfig load(File file) {
		FeishuBitableConfig config = new FeishuBitableConfig(file);
		if (file != null && file.isFile()) {
			try {
				JsonObject json = JsonParser.parseString(FileUtil.readString(file)).getAsJsonObject();
				config.appId = pick(json, "app_id", "appId");
				config.appSecret = pick(json, "app_secret", "appSecret");
				config.accessToken = pick(json, "access_token", "accessToken");
				config.appToken = pick(json, "app_token", "appToken");
				config.baseUrl = pick(json, "base_url", "baseUrl");
			} catch (Throwable e) {
				// 配置写坏时仍允许用 -D 参数启动
				System.err.println("[飞书多维表格] 凭据读取失败 " + file.getPath() + "：" + e);
			}
		}
		config.applyProperties();
		if (isBlank(config.baseUrl)) {
			config.baseUrl = "https://open.feishu.cn";
		}
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
		if ((value = property("feishu_app_id")) != null) {
			appId = value;
		}
		if ((value = property("feishu_app_secret")) != null) {
			appSecret = value;
		}
		if ((value = property("feishu_token")) != null) {
			accessToken = value;
		}
		if ((value = property("feishu_access_token")) != null) {
			accessToken = value;
		}
		if ((value = property("feishu_app_token")) != null) {
			appToken = value;
		}
		if ((value = property("feishu_base_url")) != null) {
			baseUrl = value;
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

	public String getAppId() {
		return appId;
	}

	public String getAppSecret() {
		return appSecret;
	}

	/**
	 * @return 用户令牌（user_access_token），未配置时为 {@code null}
	 */
	public String getAccessToken() {
		return accessToken;
	}

	/**
	 * @return 连接级的多维表格 app_token（base 标识）；具体数据表由调用方传入 table_id
	 */
	public String getAppToken() {
		return appToken;
	}

	/**
	 * @return 开放平台域名，例如 {@code https://open.feishu.cn}
	 */
	public String getBaseUrl() {
		return baseUrl;
	}

	/**
	 * 是否具备用自建应用凭据换取 tenant_access_token 的条件。
	 *
	 * @return 未配置用户令牌且配置了 app_id + app_secret 时返回 true
	 */
	public boolean canRefresh() {
		return isBlank(accessToken) && !isBlank(appId) && !isBlank(appSecret);
	}

	/**
	 * 凭据是否完整：配置了用户令牌，或配置了 app_id + app_secret。
	 *
	 * @return 凭据可用时返回 true
	 */
	public boolean isConfigured() {
		return !isBlank(accessToken) || (!isBlank(appId) && !isBlank(appSecret));
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}

	@Override
	public String toString() {
		return "FeishuBitableConfig[appId=" + mask(appId) + ", accessToken=" + mask(accessToken)
				+ ", appToken=" + appToken + ", baseUrl=" + baseUrl + "]";
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
		if (!(o instanceof FeishuBitableConfig)) {
			return false;
		}
		FeishuBitableConfig other = (FeishuBitableConfig) o;
		return Objects.equals(sourceFile, other.sourceFile) && Objects.equals(appId, other.appId)
				&& Objects.equals(appSecret, other.appSecret) && Objects.equals(accessToken, other.accessToken)
				&& Objects.equals(appToken, other.appToken) && Objects.equals(baseUrl, other.baseUrl);
	}

	@Override
	public int hashCode() {
		return Objects.hash(sourceFile, appId, appSecret, accessToken, appToken, baseUrl);
	}
}
