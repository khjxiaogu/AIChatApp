/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.khjxiaogu.aiwuxia.utils;

import java.io.File;
import java.util.Locale;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 站点地址配置。
 * <p>
 * 前端页面需要知道对外可访问的站点域名（WebSocket 地址、图片查看页链接等）。为了不再把域名写死在
 * HTML 里，这里提供统一来源，取值顺序为：
 * </p>
 * <ol>
 * <li>启动参数 {@code --site-host=example.com}（也支持 {@code --site-host example.com}
 * 与 {@code -Dsite_host=example.com}），由 {@link #applyArgs(String[])} 解析，用于临时覆盖；</li>
 * <li><b>默认</b>读取数据目录下的 {@code site.json}，见 {@link #resolve(File)}；</li>
 * <li>都没有配置时为空值：前端自动跟随当前页面域名（{@code location.host}），
 * Java 端则退回各调用点给出的默认域名。因此自建部署不改代码也能跑通。</li>
 * </ol>
 * <p>
 * {@code site.json} 形如：
 * </p>
 * <pre>
 * {
 *   "host": "example.com",
 *   "ws_host": "ws.example.com",
 *   "reader_host": "www.example.com"
 * }
 * </pre>
 * <p>
 * 配置值可写成裸域名 {@code example.com}、带端口 {@code example.com:8443}，
 * 或完整地址 {@code https://example.com}（协议只在 Java 端推断 ws/https 时使用；
 * 写成 {@code http://} 会得到 ws/http 地址）。{@code reader_host} 可选，缺省时与 {@code host} 相同。
 * </p>
 * <p>
 * {@code ws_host} 可选，<b>专门决定 WebSocket 的 host</b>，与网页/HTTP 使用的 {@code host} 分开：
 * 例如网页走 {@code example.com}，而 WebSocket 走 {@code ws.example.com} 或另一个端口。
 * 缺省（空值）时 WebSocket 与 {@code host} 相同；显式写 {@code ws://...} / {@code http://...}
 * 会得到非加密的 {@code ws://} 地址，写裸域名时沿用 {@code host} 的加密设置。
 * </p>
 */
public final class SiteConfig {

	/** 站点域名的系统属性名，也是推荐的启动参数名（{@code -Dsite_host=...}）。 */
	public static final String PROPERTY = "site_host";

	/** 启动参数前缀（{@code --site-host=example.com}）。 */
	private static final String ARG_PREFIX = "--site-host";

	/** 数据目录下的站点配置文件名，默认配置来源。 */
	public static final String CONFIG_FILE = "site.json";

	/**
	 * Java 端未配置站点域名时使用的默认域名。<br>
	 * 仅作兜底：正常部署请在 {@code save/site.json} 里配置自己的域名。
	 */
	public static final String DEFAULT_HOST = "127.0.0.1";

	/** 图片查看页所在的默认域名（未配置时的兜底）。 */
	public static final String DEFAULT_READER_HOST = "127.0.0.1";

	/** 站点域名，形如 {@code example.com} 或 {@code example.com:8443}；未配置时为空串。 */
	private final String host;

	/** WebSocket 专用的域名；为空表示与 {@link #host} 相同。 */
	private final String wsHost;

	/** 图片查看页域名；为空表示与 {@link #host} 相同。 */
	private final String readerHost;

	/** 该域名应使用加密协议（wss/https） */
	private final boolean secure;

	/** WebSocket 应使用加密协议（wss）；未单独配置 {@code ws_host} 时与 {@link #secure} 相同 */
	private final boolean wsSecure;

	/** 配置读取失败只提示一次，避免每次请求刷日志。 */
	private static volatile boolean warnIssued = false;

	private SiteConfig(String host, boolean secure, String readerHost) {
		this(host, secure, readerHost, "", secure);
	}

	private SiteConfig(String host, boolean secure, String readerHost, String wsHost, boolean wsSecure) {
		this.host = host;
		this.secure = secure;
		this.readerHost = readerHost == null ? "" : readerHost;
		this.wsHost = wsHost == null ? "" : wsHost;
		this.wsSecure = wsSecure;
	}

	/**
	 * 解析一个站点域名配置值。
	 *
	 * @param raw 原始配置，可以是裸域名、host:port 或完整 URL；null/空白表示未配置
	 * @return 站点配置
	 */
	public static SiteConfig of(String raw) {
		return of(raw, null);
	}

	/**
	 * 解析站点域名与 WebSocket 域名两个配置值。
	 *
	 * @param raw   站点域名（裸域名、host:port 或完整 URL）；null/空白表示未配置
	 * @param wsRaw WebSocket 域名；null/空白表示与 {@code raw} 相同
	 * @return 站点配置
	 */
	public static SiteConfig of(String raw, String wsRaw) {
		Parsed site = parse(raw, true);
		Parsed ws = parse(wsRaw, site.secure);
		return new SiteConfig(site.host, site.secure, "", ws.host, ws.secure);
	}

	/** 解析结果：域名 + 该域名是否使用加密协议。 */
	private static final class Parsed {
		private final String host;
		private final boolean secure;

		private Parsed(String host, boolean secure) {
			this.host = host;
			this.secure = secure;
		}
	}

	/**
	 * 解析单个配置值。
	 *
	 * @param raw           原始配置
	 * @param defaultSecure 未写协议时的加密设置
	 * @return 解析结果，未配置时 host 为空串
	 */
	private static Parsed parse(String raw, boolean defaultSecure) {
		String value = raw == null ? "" : raw.trim();
		if (value.isEmpty())
			return new Parsed("", defaultSecure);
		boolean secure = defaultSecure;
		int schemeEnd = value.indexOf("://");
		if (schemeEnd > 0) {
			String scheme = value.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
			secure = !"http".equals(scheme) && !"ws".equals(scheme);
			value = value.substring(schemeEnd + 3);
		}
		// 只保留 host[:port]，丢掉路径、查询串与结尾斜杠
		int pathStart = value.indexOf('/');
		if (pathStart >= 0)
			value = value.substring(0, pathStart);
		int queryStart = value.indexOf('?');
		if (queryStart >= 0)
			value = value.substring(0, queryStart);
		value = value.trim();
		return new Parsed(value, secure);
	}

	/**
	 * 读取当前生效的站点配置：启动参数/系统属性优先，否则读数据目录下的 {@code site.json}。
	 *
	 * @param dataFolder 数据目录（通常是 {@code save}），可为 null
	 * @return 站点配置，未配置时 {@link #isConfigured()} 为 false
	 */
	public static SiteConfig resolve(File dataFolder) {
		String override = System.getProperty(PROPERTY);
		if (override != null && !override.trim().isEmpty())
			return of(override);
		if (dataFolder == null)
			return of(null);
		return fromFile(new File(dataFolder, CONFIG_FILE));
	}

	/**
	 * 从 JSON 配置文件读取站点配置。
	 * <p>
	 * 文件不存在时返回未配置状态；内容不合法时提示一次并回退到未配置状态，
	 * 避免一个写坏的配置把整个站点（尤其是前端页面）卡死。
	 * </p>
	 *
	 * @param file 配置文件，可为 null
	 * @return 站点配置
	 */
	public static SiteConfig fromFile(File file) {
		if (file == null || !file.isFile())
			return of(null);
		try {
			JsonObject cfg = JsonParser.parseString(FileUtil.readString(file)).getAsJsonObject();
			String ws = getString(cfg, "ws_host");
			if (ws == null || ws.isEmpty())
				ws = getString(cfg, "wsHost");
			SiteConfig site = of(getString(cfg, "host"), ws);
			String reader = getString(cfg, "reader_host");
			if (reader != null && !reader.isEmpty())
				return new SiteConfig(site.host, site.secure, of(reader).host, site.wsHost, site.wsSecure);
			return site;
		} catch (Throwable e) {
			if (!warnIssued) {
				warnIssued = true;
				System.err.println("[SiteConfig] 站点配置 " + file.getPath()
					+ " 读取失败，将跟随访问域名: " + e);
			}
			return of(null);
		}
	}

	private static String getString(JsonObject cfg, String key) {
		JsonElement el = cfg.get(key);
		if (el == null || el.isJsonNull() || !el.isJsonPrimitive())
			return null;
		return el.getAsString().trim();
	}

	/**
	 * 只读取启动参数/系统属性得到的站点配置，不读 {@code site.json}。
	 * <p>
	 * 业务代码请用 {@link #resolve(File)}，这样才能拿到 {@code site.json} 里的配置。
	 * </p>
	 *
	 * @return 站点配置
	 */
	public static SiteConfig system() {
		return of(System.getProperty(PROPERTY));
	}

	/**
	 * 从 {@code main} 的启动参数中解析站点域名并写入系统属性，供后续 {@link #resolve(File)} 优先取用。
	 * 支持 {@code --site-host=example.com}、{@code --site-host example.com} 与
	 * {@code -Dsite_host=example.com}。<br>
	 * 未传入站点域名时不会覆盖已有的系统属性，因此 {@code site.json} 会继续生效。
	 *
	 * @param args 启动参数，可为 null
	 * @return 解析后的站点配置（仅启动参数/系统属性部分）
	 */
	public static SiteConfig applyArgs(String[] args) {
		if (args != null) {
			for (int i = 0; i < args.length; i++) {
				String arg = args[i];
				if (arg == null)
					continue;
				String value = null;
				if (arg.startsWith(ARG_PREFIX + "=")) {
					value = arg.substring(ARG_PREFIX.length() + 1);
				} else if (arg.equals(ARG_PREFIX) && i + 1 < args.length) {
					value = args[++i];
				} else if (arg.startsWith("-D" + PROPERTY + "=")) {
					value = arg.substring(("-D" + PROPERTY + "=").length());
				}
				if (value != null)
					System.setProperty(PROPERTY, value.trim());
			}
		}
		return system();
	}

	/**
	 * 是否显式配置了站点域名。
	 *
	 * @return 已配置返回 true
	 */
	public boolean isConfigured() {
		return !host.isEmpty();
	}

	/**
	 * @return 配置的站点域名，未配置时为空串
	 */
	public String getHost() {
		return host;
	}

	/**
	 * @return 配置的站点是否使用加密协议
	 */
	public boolean isSecure() {
		return secure;
	}

	/**
	 * @return WebSocket 专用域名；未单独配置时为空串（表示与 {@link #getHost()} 相同）
	 */
	public String getWebSocketHost() {
		return wsHost;
	}

	/**
	 * @return WebSocket 是否使用加密协议（wss）
	 */
	public boolean isWebSocketSecure() {
		return wsSecure;
	}

	/**
	 * 取站点域名，未配置时退回给定的默认域名。
	 *
	 * @param fallbackHost 默认域名
	 * @return 实际使用的域名
	 */
	public String hostOr(String fallbackHost) {
		return host.isEmpty() ? fallbackHost : host;
	}

	/**
	 * 取 WebSocket 域名：优先 {@code ws_host}，未配置时退回站点域名；两者都未配置时用默认域名。
	 *
	 * @param fallbackHost 默认域名
	 * @return 实际使用的 WebSocket 域名
	 */
	public String webSocketHostOr(String fallbackHost) {
		if (!wsHost.isEmpty())
			return wsHost;
		return hostOr(fallbackHost);
	}

	/**
	 * 拼出站点的 WebSocket 地址。
	 * <p>
	 * host 取 {@code ws_host}（未配置时与 {@code host} 相同），协议取 WebSocket 自己的加密设置，
	 * 因此 WebSocket 可以独立于网页走另一个域名或端口。
	 * </p>
	 *
	 * @param fallbackHost 未配置时使用的默认域名
	 * @param path         以 {@code /} 开头的路径
	 * @return 形如 {@code wss://example.com/aichat} 的地址
	 */
	public String webSocketUrl(String fallbackHost, String path) {
		return (wsSecure ? "wss:" : "ws:") + "//" + webSocketHostOr(fallbackHost) + (path == null ? "" : path);
	}

	/**
	 * 拼出站点的 HTTP 地址。
	 *
	 * @param fallbackHost 未配置时使用的默认域名
	 * @param path         以 {@code /} 开头的路径
	 * @return 形如 {@code https://example.com/aichat} 的地址
	 */
	public String httpUrl(String fallbackHost, String path) {
		return (secure ? "https:" : "http:") + "//" + hostOr(fallbackHost) + (path == null ? "" : path);
	}

	/**
	 * 拼出图片查看页的 HTTP 地址。
	 * <p>
	 * 优先用配置里的 {@code reader_host}；未配置该字段时与 {@link #httpUrl} 一致，
	 * 使用站点域名。
	 * </p>
	 *
	 * @param fallbackHost 未配置时使用的默认域名
	 * @param path         以 {@code /} 开头的路径
	 * @return 图片查看页地址
	 */
	public String readerUrl(String fallbackHost, String path) {
		String base = readerHost.isEmpty() ? hostOr(fallbackHost) : readerHost;
		return (secure ? "https:" : "http:") + "//" + base + (path == null ? "" : path);
	}

	/**
	 * 生成前端使用的 site-config.js 内容。
	 * <p>
	 * {@code host} 为空串表示未配置，前端应回退到当前页面域名。
	 * 内容保持纯 ASCII：服务端按字符数写 Content-Length，中文会截断响应。
	 * </p>
	 *
	 * @return JavaScript 源码
	 */
	public String toScript() {
		return "window.AI_SITE = {\n"
			+ "\thost: " + new Gson().toJson(host) + ",\n"
			+ "\tsecure: " + secure + ",\n"
			+ "\twsHost: " + new Gson().toJson(wsHost) + ",\n"
			+ "\twsSecure: " + wsSecure + "\n"
			+ "};\n";
	}

	@Override
	public String toString() {
		if (!isConfigured())
			return "未配置（跟随访问域名）";
		String base = (secure ? "wss" : "ws") + "://" + host;
		if (!wsHost.isEmpty() && !wsHost.equals(host))
			base += "（WebSocket: " + (wsSecure ? "wss" : "ws") + "://" + wsHost + "）";
		if (!readerHost.isEmpty() && !readerHost.equals(host))
			base += "（图片查看页: " + readerHost + "）";
		return base;
	}
}
