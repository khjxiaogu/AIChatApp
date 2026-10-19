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
package com.khjxiaogu.aiwuxia;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.AIApplicationRegistry;
import com.khjxiaogu.aiwuxia.apps.ApplicationAttributes;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp;
import com.khjxiaogu.aiwuxia.mcp.client.McpClientManager;
import com.khjxiaogu.aiwuxia.mcp.server.McpAccessTokenStore;
import com.khjxiaogu.aiwuxia.mcp.server.McpBuiltinTools;
import com.khjxiaogu.aiwuxia.mcp.server.McpServerEndpoint;
import com.khjxiaogu.aiwuxia.mcp.server.McpToolRegistry;
import com.khjxiaogu.aiwuxia.mcp.server.McpToolSource;
import com.khjxiaogu.aiwuxia.mcp.SDXLMcp.LoraConfigurations;
import com.khjxiaogu.aiwuxia.objectstorage.DelegatingStorage;
import com.khjxiaogu.aiwuxia.objectstorage.LocalStorage;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;
import com.khjxiaogu.aiwuxia.objectstorage.TOStorage;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.session.AISession.ExtraData;
import com.khjxiaogu.aiwuxia.state.session.ChatIdentity;
import com.khjxiaogu.aiwuxia.state.session.PainterSession;
import com.khjxiaogu.aiwuxia.state.session.WebSocketAISession;
import com.khjxiaogu.aiwuxia.tools.NameTranslator;
import com.khjxiaogu.aiwuxia.utils.ConfigPaths;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;
import com.khjxiaogu.aiwuxia.utils.MCPTools;
import com.khjxiaogu.aiwuxia.utils.SiteConfig;
import com.khjxiaogu.aiwuxia.voice.LocalModel;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;
import com.khjxiaogu.webserver.annotations.Adapter;
import com.khjxiaogu.webserver.annotations.GetBy;
import com.khjxiaogu.webserver.annotations.Header;
import com.khjxiaogu.webserver.annotations.HttpMethod;
import com.khjxiaogu.webserver.annotations.HttpPath;
import com.khjxiaogu.webserver.annotations.PostQuery;
import com.khjxiaogu.webserver.annotations.Query;
import com.khjxiaogu.webserver.command.CommandHandler;
import com.khjxiaogu.webserver.command.CommandSender;
import com.khjxiaogu.webserver.loging.SimpleLogger;
import com.khjxiaogu.webserver.web.FilePageService;
import com.khjxiaogu.webserver.web.ServiceClass;
import com.khjxiaogu.webserver.web.lowlayer.Request;
import com.khjxiaogu.webserver.web.lowlayer.Response;
import com.khjxiaogu.webserver.web.lowlayer.WebsocketEvents;
import com.khjxiaogu.webserver.wrappers.ResultDTO;
import com.khjxiaogu.webserver.wrappers.inadapters.DataIn;
import com.khjxiaogu.webserver.wrappers.inadapters.FullPathIn;
import com.khjxiaogu.webserver.wrappers.inadapters.JsonIn;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.multipart.HttpData;

/**
 * AI对话核心服务，负责管理AI对话会话、用户权限、历史记录以及与前端WebSocket通信。 该类实现了 {@link ServiceClass} 和
 * {@link CommandHandler} 接口，提供HTTP端点供前端调用， 并管理多个AI应用实例（如武侠、文章、Galgame等）。
 * <p>
 * 注意：该服务本身不包含登录验证，需要调用方自行添加代理进行验证。
 * </p>
 */
public class AIChatService implements ServiceClass, CommandHandler {

	/**
	 * 获取数据库连接对象。
	 *
	 * @return SQLite数据库连接
	 */
	public Connection getDatabase() {
		return database;
	}

	/** SQLite数据库连接，用于存储对话列表、权限、费用等信息 */
	protected Connection database;

	/** 日志记录器，标签为"聊天" */
	public final SimpleLogger logger = new SimpleLogger("聊天");


	/** 当前活跃的WebSocket会话映射，键为对话ID，值为对应的WebSocket会话对象 */
	protected Map<String, AISession> uidsockets = new ConcurrentHashMap<>();
	/** 绘画师图片存储，使用DelegatingStorage（本地+TOS）持久化存储 */
	protected ObjectStorageProvider painterStorage;

	/** 可用的AI应用映射，键为应用ID（如"wuxia"），值为对应的AI应用实例 */
	private Map<String, ApplicationAttributes> apps = new HashMap<>();

	public Map<String,NameTranslator> modelTranslations=new HashMap<>();
	
	String defaultRule="";

	/** 服务数据根目录 */
	File parent;

	/** 对话数据保存目录（存放每个对话的JSON文件） */
	File saveData;

	/** 静态资源文件服务（用于提供图片、CSS等） */
	private FilePageService resource;

	/** 语音资源文件服务（用于提供音频文件） */
	private FilePageService voice;

	/** MCP 服务端端点（挂在 /aichat/mcp 上），初始化失败时为 null */
	private McpServerEndpoint mcpEndpoint;
	/** MCP 客户端管理器（读取 save/mcp-client.json），未启用时为 null */
	private McpClientManager mcpClients;
	/** MCP 访问令牌与计费存储 */
	private McpAccessTokenStore mcpTokenStore;
	// 默认每个用户每天的免费token额度
	private static final int DEFAULT_FREE_DAILY_LIMIT = 500_000;
	/** 每日签到一次可获得的补充额度（token） */
	public static final int DAILY_CHECKIN_REWARD = 50_000;
	/** 新用户首次获取今日配额时赠送的补充额度（token），赠送不做任何提示 */
	public static final int NEW_USER_GIFT = 100_000;
	/** 配额增加来源：系统充值 */
	public static final String INCREASE_SOURCE_RECHARGE = "充值";
	/** 配额增加来源：每日签到获取 */
	public static final String INCREASE_SOURCE_CHECKIN = "签到获取";
	/** 配额增加来源：兑换码获取 */
	public static final String INCREASE_SOURCE_CDK = "兑换码获取";
	/** 配额增加来源：新用户赠送 */
	public static final String INCREASE_SOURCE_NEW_USER = "新用户赠送";

	public void initModelNames() {
		modelTranslations.put("deepseek",new NameTranslator().add("deepseek", "Deepseek")
				.add("pro", "专家").add("flash", "对话")
				.add("reasoning", "推理型").add("non-reasoning", "非推理型")
				);
		modelTranslations.put("grok",new NameTranslator().add("grok", "Grok")
				.add("reasoning", "推理型").add("non-reasoning", "非推理型")
				);
		modelTranslations.put("silicon",new NameTranslator().add("silicon", "硅基流动")
			.add("deepseek3", "Deepseek V3")
			.add("deepseek3.2", "Deepseek V3.2").add("reasoning", "推理型").add("non-reasoning", "非推理型")
			);
		modelTranslations.put("volces",new NameTranslator().add("volces", "火山").add("seed2.0", "豆包Seed2.0")
				.add("reasoning", "推理型").add("non-reasoning", "对话型")
				.add("pro", "专业").add("lite", "普通").add("code", "代码").add("mini", "轻量")
				.add("none-strength", "（最简推理）").add("weak-strength", "（轻度推理）").add("medium-strength", "（中度推理）").add("strong-strength", "（重度推理）")
				);
	}
	public final Map<String, LoraConfigurations> lora = new LinkedHashMap<>();
	public final List<String> chara;
	public final String urlbase;
	/**
	 * 构造AI对话服务，初始化数据库连接、文件目录和AI应用。
	 *
	 * @param path 服务数据根目录
	 * @throws SQLException           如果数据库初始化失败
	 * @throws ClassNotFoundException 如果找不到SQLite JDBC驱动
	 * @throws IOException 
	 */
	public AIChatService(String urlbase,File path) throws SQLException, ClassNotFoundException, IOException {
		try {
			Class.forName("org.sqlite.JDBC");
		} catch (ClassNotFoundException e) {
			logger.severe("SQLITE链接失败！");
			throw e;
		}
		this.urlbase=urlbase;
		initModelNames();
		defaultRule=FileUtil.readString(new File(path,"apps/custom/prompt.txt"));
		
		File lfn=ConfigPaths.sdxlLoras(path);
		if(lfn.exists()) {
			JsonObject refimg=JsonParser.parseString(FileUtil.readString(lfn)).getAsJsonObject();
			for(String key:refimg.keySet()) {
				JsonObject jo=refimg.get(key).getAsJsonObject();
				lora.put(key, new LoraConfigurations(jo.get("key").getAsString(),jo.get("weight").getAsFloat()));
			}
		}
		chara=SDXLMcp.readLinesFromFile(ConfigPaths.sdxlCharacterTags(path));
		logger.info("正在链接SQLITE信息数据库...");
		try {
			database = DriverManager.getConnection("jdbc:sqlite:" + new File(path, "messages.db"));
			try (Statement stmt = database.createStatement()) {

				/** 创建对话记录表的SQL语句（price 直接并入主表，status 为存档状态） */
				String createMsg = "CREATE TABLE IF NOT EXISTS chats (" +
					"uid	 TEXT(40)   NOT NULL, " + // 用户ID
					"brief TEXT, " + // 对话简述（对话名称）
					"app TEXT NOT NULL, " + // 智能体名称
					"chatid TEXT NOT NULL, " + // 对话ID
					"time	BIGINT(64), " + // 时间戳（毫秒）
					"price TEXT DEFAULT '0.00', " + // 累计消耗（原 price 表字段）
					"status TEXT DEFAULT '" + STATUS_NORMAL + "', " + // 存档状态：normal/archived/removed
					"name_fixed INTEGER DEFAULT 0, " + // 名称是否被用户手动重命名（1 表示不再由AI自动改写）
					"attribute TEXT DEFAULT ''" + // 历史遗留附加信息（已由 status 取代，仅保留兼容）
					");";

				/** 创建权限表的SQL语句 */
				String createPerm = "CREATE TABLE IF NOT EXISTS permission (" +
					"uid	 TEXT(40)   NOT NULL, " + // 用户ID
					"app TEXT NOT NULL, " + // 智能体
					"state TEXT NOT NULL" + // 许可状态（如'1'表示允许）
					");";

				/** 创建备注/选中表的SQL语句（存档状态中的"当前存档"与备注仍保存在此表） */
				String createRemark = "CREATE TABLE IF NOT EXISTS chatdata (" +
					"chatid TEXT PRIMARY KEY, " +		   // 对话ID
					"remark TEXT, " +					   // 备注字符串
					"selected TEXT " +					   // 是否选中字符串
					");";
				String createPaidBalanceTable = "CREATE TABLE IF NOT EXISTS user_paid_balance ("
					+ "user_id TEXT PRIMARY KEY,"
					+ "balance INTEGER NOT NULL DEFAULT 0)";
				String createDailyUsageTable = "CREATE TABLE IF NOT EXISTS daily_usage ("
					+ "user_id TEXT,"
					+ "date TEXT,"
					+ "free_used INTEGER NOT NULL DEFAULT 0,"
					+ "paid_used INTEGER NOT NULL DEFAULT 0,"
					+ "PRIMARY KEY (user_id, date))";
				String createFreeLimitOverrideTable = "CREATE TABLE IF NOT EXISTS user_free_limit_override ("
					+ "user_id TEXT PRIMARY KEY,"
					+ "free_limit INTEGER NOT NULL)";
		        String createCDKTable = "CREATE TABLE IF NOT EXISTS cdk ("
		        		+ "code TEXT PRIMARY KEY,"
		        		+ "cost INTEGER NOT NULL,"
		        		+ "remaining_uses INTEGER NOT NULL,"
		        		+ "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";

		        String createLogTable = "CREATE TABLE IF NOT EXISTS redemption_log ("
		        		+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
		        		+ "cdk_code TEXT NOT NULL,"
		        		+ "user_id TEXT NOT NULL,"
		        		+ "cost INTEGER NOT NULL,"
		        		+ "redeemed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
		        		+ "UNIQUE(cdk_code, user_id))";

		        /** 每日签到记录表：主键保证同一用户同一天只能签到一次 */
		        String createCheckinTable = "CREATE TABLE IF NOT EXISTS daily_checkin ("
		        		+ "user_id TEXT NOT NULL,"
		        		+ "date TEXT NOT NULL,"
		        		+ "amount INTEGER NOT NULL DEFAULT 0,"
		        		+ "checked_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,"
		        		+ "PRIMARY KEY (user_id, date))";

		        /** 补充配额消费明细表：记录使用量类型、日期（到天）、聊天ID、用户ID与消耗量 */
		        String createPaidUsageTable = "CREATE TABLE IF NOT EXISTS paid_usage_record ("
		        		+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
		        		+ "user_id TEXT NOT NULL,"
		        		+ "chat_id TEXT,"
		        		+ "usage_type TEXT NOT NULL DEFAULT '其他',"
		        		+ "date TEXT NOT NULL,"
		        		+ "tokens INTEGER NOT NULL DEFAULT 0,"
		        		+ "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";
		        /** 按用户+日期查询的索引 */
		        String createPaidUsageIndex = "CREATE INDEX IF NOT EXISTS idx_paid_usage_user_date"
		        		+ " ON paid_usage_record(user_id, date)";

		        /** 配额增加记录表：记录补充配额的每一次增加及其来源（充值/签到获取/兑换码获取/新用户赠送） */
		        String createQuotaIncreaseTable = "CREATE TABLE IF NOT EXISTS quota_increase_record ("
		        		+ "id INTEGER PRIMARY KEY AUTOINCREMENT,"
		        		+ "user_id TEXT NOT NULL,"
		        		+ "source TEXT NOT NULL,"
		        		+ "amount INTEGER NOT NULL DEFAULT 0,"
		        		+ "detail TEXT,"
		        		+ "date TEXT NOT NULL,"
		        		+ "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";
		        /** 配额增加记录按用户+日期查询的索引 */
		        String createQuotaIncreaseIndex = "CREATE INDEX IF NOT EXISTS idx_quota_increase_user_date"
		        		+ " ON quota_increase_record(user_id, date)";
				stmt.execute(createMsg);
				stmt.execute(createPerm);
				stmt.execute(createRemark);
				stmt.execute(createPaidBalanceTable);
				stmt.execute(createDailyUsageTable);
				stmt.execute(createFreeLimitOverrideTable);
				stmt.execute(createCDKTable);
				stmt.execute(createLogTable);
				stmt.execute(createCheckinTable);
				stmt.execute(createPaidUsageTable);
				stmt.execute(createPaidUsageIndex);
				stmt.execute(createQuotaIncreaseTable);
				stmt.execute(createQuotaIncreaseIndex);
			}
			migrateChatsSchema();
		} catch (SQLException e) {
			logger.severe("信息数据库初始化失败！");
			throw e;
		}
		LLMConnector.initDefault();
		VoiceModelHandler.init(path);
		parent = path;
		// MCP 服务端（把本地 ToolData 暴露给外部）与客户端（把外部 ToolData 接入本地）初始化
		initMcp(path);
		saveData = new File(path, "saveData");
		saveData.mkdirs();
		resource = new FilePageService(new File(parent, "resource"));
		voice = new FilePageService(new File(parent, "voice"));
		try {
			File tosConfigFile = ConfigPaths.objectStorage(parent);
			if (tosConfigFile.exists()) {
				JsonObject tosCfg = JsonParser.parseString(FileUtil.readString(tosConfigFile)).getAsJsonObject();
				painterStorage = new DelegatingStorage(new File(parent, "painter_images"), new TOStorage(tosCfg));
				logger.info("绘画图片存储: 本地+TOS");
			} else {
				painterStorage = new LocalStorage(new File(parent, "painter_images"));
				logger.info("绘画图片存储: 仅本地");
			}
		} catch (Exception e) {
			painterStorage = new LocalStorage(new File(parent, "painter_images"));
			logger.info("绘画图片存储: 仅本地 (TOS初始化失败)");
		}
		reload();
	}

	/**
	 * 初始化 MCP 服务端与客户端。
	 * <p>
	 * 服务端读取 {@code save/mcp.json}（可选）：默认单次调用价格、是否暴露在线会话工具、写入用量统计的类型；
	 * 然后创建访问令牌存储（与主库共用 {@code messages.db}）、工具注册表并注册内置自检工具。
	 * 客户端读取 {@code save/mcp-client.json}，在后台线程连接配置中的 MCP 服务端，
	 * 连接成功后把远程工具注册到在线会话。
	 * </p>
	 * <p>
	 * 任何一步失败都只记录日志，不影响主服务启动（MCP 功能不可用）。
	 * </p>
	 *
	 * @param path 服务数据根目录
	 */
	private void initMcp(File path) {
		JsonObject config = new JsonObject();
		File configFile = new File(path, "mcp.json");
		if (configFile.isFile()) {
			try {
				config = JsonParser.parseString(FileUtil.readString(configFile)).getAsJsonObject();
			} catch (Exception ex) {
				logger.warning("MCP 服务端配置解析失败，使用默认配置：" + ex.getMessage());
			}
		}
		if (config.has("enabled") && !config.get("enabled").getAsBoolean()) {
			logger.info("MCP 服务端已在 mcp.json 中禁用");
		} else {
			try {
				initMcpServer(config);
			} catch (Throwable ex) {
				logger.printStackTrace(ex);
				logger.warning("MCP 服务端初始化失败：" + ex.getMessage());
				mcpEndpoint = null;
			}
		}
		// 客户端与服务端互相独立：即使服务端禁用，也允许连接外部 MCP 服务端
		try {
			mcpClients = new McpClientManager(new File(path, "mcp-client.json"));
			mcpClients.addChangeListener(this::applyMcpClientTools);
			mcpClients.startAsync();
		} catch (Throwable ex) {
			logger.printStackTrace(ex);
			logger.warning("MCP 客户端初始化失败：" + ex.getMessage());
			mcpClients = null;
		}
	}

	/**
	 * 初始化 MCP 服务端：令牌存储、工具注册表与端点。
	 *
	 * @param config {@code save/mcp.json} 解析结果
	 * @throws Exception 初始化失败（调用方记录日志并降级为不启用）
	 */
	private void initMcpServer(JsonObject config) throws Exception {
		int defaultPrice = config.has("defaultPrice") ? Math.max(0, config.get("defaultPrice").getAsInt()) : 1000;
		String usageType = config.has("usageType") ? config.get("usageType").getAsString()
				: McpAccessTokenStore.DEFAULT_USAGE_TYPE;
		boolean exposeSessionTools = config.has("exposeSessionTools")
				&& config.get("exposeSessionTools").getAsBoolean();

		mcpTokenStore = new McpAccessTokenStore(database, usageType);
		McpToolRegistry registry = new McpToolRegistry();
		McpBuiltinTools.register(registry);
		if (exposeSessionTools) {
			// 可选：让令牌所属用户调用自己在线会话中的工具（默认关闭，避免远程触发聊天副作用）
			registry.addSource(new McpToolSource() {
				@Override
				public String getName() {
					return "active-sessions";
				}

				@Override
				public Collection<ToolData> getTools(String uid) {
					if (uid == null || uid.isEmpty()) {
						return Collections.emptyList();
					}
					Map<String, ToolData> result = new LinkedHashMap<>();
					for (AISession session : uidsockets.values()) {
						if (session == null || !uid.equals(session.user)) {
							continue;
						}
						for (ToolData tool : session.getToolCallManager().getTools().values()) {
							if (!result.containsKey(tool.name)) {
								result.put(tool.name, tool);
							}
						}
					}
					return result.values();
				}
			});
		}
		mcpEndpoint = new McpServerEndpoint(registry, mcpTokenStore, defaultPrice, "AIWuxia-MCP", "0.0.1");
		logger.info("MCP 服务端已启用：/aichat/mcp，默认单次价格 " + defaultPrice + " 额外配额"
				+ (exposeSessionTools ? "，并暴露在线会话工具" : ""));
	}

	/** MCP 服务端端点（由 {@code /aichat/mcp} 转发），未启用时为 null。 */
	public McpServerEndpoint getMcpEndpoint() {
		return mcpEndpoint;
	}

	/** MCP 客户端管理器，未启用时为 null。 */
	public McpClientManager getMcpClientManager() {
		return mcpClients;
	}

	/** MCP 访问令牌与消费记录存储，未启用时为 null。 */
	public McpAccessTokenStore getMcpTokenStore() {
		return mcpTokenStore;
	}

	/**
	 * 把 MCP 客户端当前提供的工具注册到所有在线会话。
	 * <p>由 {@link McpClientManager} 在后台连接完成时回调；同名工具覆盖。</p>
	 */
	private void applyMcpClientTools() {
		if (mcpClients == null) {
			return;
		}
		MCPTools tools = mcpClients.getTools();
		if (tools.getTools().isEmpty() || uidsockets.isEmpty()) {
			return;
		}
		for (AISession session : uidsockets.values()) {
			try {
				session.registerTools(tools);
			} catch (Throwable ex) {
				logger.printStackTrace(ex);
			}
		}
		logger.info("已向在线会话注册 " + tools.getTools().size() + " 个 MCP 客户端工具");
	}

	/** 存档状态：正常，在聊天页与存档管理页均可见 */
	public static final String STATUS_NORMAL="normal";
	/** 存档状态：已归档，聊天页不再显示，仅在存档管理页的"已归档"中可见并可恢复 */
	public static final String STATUS_ARCHIVED="archived";
	/** 存档状态：已移除，任何界面都不再显示（软删除） */
	public static final String STATUS_REMOVED="removed";

	/** 对话名称（chats.brief）的最大长度，超过部分在重命名时截断 */
	public static final int MAX_CHAT_NAME_LENGTH=40;

	/**
	 * 判断列是否存在于指定表中。
	 *
	 * @param table  表名
	 * @param column 列名
	 * @return 存在返回true
	 * @throws SQLException 查询失败
	 */
	private boolean hasColumn(String table, String column) throws SQLException {
		try (PreparedStatement ps = database.prepareStatement("PRAGMA table_info(" + table + ")")) {
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					if (column.equalsIgnoreCase(rs.getString("name")))
						return true;
				}
			}
		}
		return false;
	}

	/**
	 * 判断表是否存在。
	 *
	 * @param table 表名
	 * @return 存在返回true
	 * @throws SQLException 查询失败
	 */
	private boolean hasTable(String table) throws SQLException {
		try (PreparedStatement ps = database.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
			ps.setString(1, table);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}

	/**
	 * 升级历史数据库结构，使 chats 表成为唯一的对话主表。
	 * <p>
	 * 处理三件事：
	 * <ol>
	 * <li>把原 {@code price} 表的费用并入 {@code chats.price}（费用归位到主表）；</li>
	 * <li>补齐 {@code chats.price} / {@code chats.status} 列；</li>
	 * <li>把历史 {@code attribute} 标记迁移为明确的 {@code status}：hide → archived，
	 * removed/deleted → removed，其余 → normal。</li>
	 * </ol>
	 * 迁移是幂等的，可以重复执行。
	 * </p>
	 *
	 * @throws SQLException 迁移失败
	 */
	private void migrateChatsSchema() throws SQLException {
		boolean addedPrice=false;
		boolean addedStatus=false;
		try (Statement stmt = database.createStatement()) {
			if (!hasColumn("chats", "price")) {
				stmt.execute("ALTER TABLE chats ADD COLUMN price TEXT DEFAULT '0.00'");
				addedPrice=true;
			}
			if (!hasColumn("chats", "status")) {
				stmt.execute("ALTER TABLE chats ADD COLUMN status TEXT DEFAULT '" + STATUS_NORMAL + "'");
				addedStatus=true;
			}
			if (!hasColumn("chats", "name_fixed")) {
				stmt.execute("ALTER TABLE chats ADD COLUMN name_fixed INTEGER DEFAULT 0");
			}
			if (!hasTable("chatdata")) {
				stmt.execute("CREATE TABLE IF NOT EXISTS chatdata (chatid TEXT PRIMARY KEY, remark TEXT, selected TEXT)");
			}
		}
		// 1. 费用迁移：原 price 表的数据并入 chats.price
		if (hasTable("price")) {
			try (Statement stmt = database.createStatement()) {
				int moved=stmt.executeUpdate("UPDATE chats SET price = COALESCE((SELECT p.price FROM price p WHERE p.chatid=chats.chatid), price)"
					+ " WHERE EXISTS(SELECT 1 FROM price p WHERE p.chatid=chats.chatid AND p.price IS NOT NULL)");
				if (moved>0)
					logger.info("存档费用迁移完成：" + moved + " 条记录并入 chats.price");
			}
		}
		if (addedPrice) {
			try (Statement stmt = database.createStatement()) {
				stmt.executeUpdate("UPDATE chats SET price='0.00' WHERE price IS NULL");
			}
		}
		// 2. 状态迁移：历史 attribute 标记 → status
		boolean hasAttribute=hasColumn("chats", "attribute");
		try (Statement stmt = database.createStatement()) {
			long archived=0;
			long removed=0;
			if (addedStatus&&hasAttribute) {
				// 首次补齐 status 列：按 attribute 语义整体迁移
				removed=stmt.executeUpdate("UPDATE chats SET status='" + STATUS_REMOVED
					+ "' WHERE attribute IN ('removed','deleted')");
				archived=stmt.executeUpdate("UPDATE chats SET status='" + STATUS_ARCHIVED
					+ "' WHERE attribute='hide' AND status='" + STATUS_NORMAL + "'");
			} else if (hasAttribute) {
				// 已存在 status 的库：仅补齐空值，避免覆盖用户已经设置的归档状态
				archived=stmt.executeUpdate("UPDATE chats SET status='" + STATUS_ARCHIVED
					+ "' WHERE (status IS NULL OR status='') AND attribute='hide'");
				removed=stmt.executeUpdate("UPDATE chats SET status='" + STATUS_REMOVED
					+ "' WHERE (status IS NULL OR status='') AND attribute IN ('removed','deleted')");
			}
			stmt.executeUpdate("UPDATE chats SET status='" + STATUS_NORMAL + "' WHERE status IS NULL OR status=''");
			if (archived>0||removed>0)
				logger.info("存档状态迁移完成：归档 " + archived + " 条，移除 " + removed + " 条");
		}
	}

	/**
	 * 重新加载所有AI应用配置。 扫描根目录下的所有子目录，根据meta.json元数据动态加载AI应用（如talk类型、trpg类型），
	 * 并更新apps映射与各应用的可见性（access）。
	 */
	@SuppressWarnings("unchecked")
	public void reload() {
		apps.clear();
		Gson gson=new Gson();
		// apps.put("fengyitalk", new AICharaTalkMain(parent,"fengyitalk","姚枫怡"));
		for (File fn : new File(parent, "apps").listFiles(File::isDirectory)) {
			try {
				String name = fn.getName();
				File metaFile = new File(fn, "meta.json");
				if (metaFile.exists()) {
					JsonObject meta = JsonParser.parseString(FileUtil.readString(metaFile)).getAsJsonObject();
					if (meta.has("name") && (!meta.has("enabled") || meta.get("enabled").getAsBoolean())) {
						getLogger().info("正在加载AI：" + name);

						try {
							ApplicationAttributes attr;
							apps.put(name, attr=new ApplicationAttributes(AIApplicationRegistry.createInstance(parent, fn, meta),name));
							// 可见性：优先读取新的 access 字段；为兼容旧配置回退到 trial 标记
							if (meta.has("access")) {
								attr.access=ApplicationAttributes.normalizeAccess(meta.get("access").getAsString());
							} else if (meta.has("trial")) {
								attr.access=meta.get("trial").getAsBoolean()
									? ApplicationAttributes.ACCESS_PUBLIC
									: ApplicationAttributes.ACCESS_PERMISSION;
								getLogger().info("AI " + name + " 使用已弃用的 trial 标记，等价 visibility=" + attr.access
									+ "（建议改为 access 字段）");
							}
							
							if(meta.has("paidOnly"))
								attr.paidOnly=meta.get("paidOnly").getAsBoolean();
							if(meta.has("models"))
								attr.models=gson.fromJson(meta.get("models"), List.class);
							if(meta.has("defaultModel"))
								attr.defaultModel=meta.get("defaultModel").getAsString();
							if(meta.has("url"))
								attr.url=meta.get("url").getAsString();
							if(meta.has("freeNow"))
								attr.freeNow=meta.get("freeNow").getAsBoolean();
							if(meta.has("ui"))
								attr.ui=meta.get("ui").getAsString();
							getLogger().info("AI加载成功：" + name);
						} catch (Throwable e) {
							getLogger().printStackTrace(e);
							getLogger().info("AI加载失败：" + name);
						}
					} else {
						getLogger().info("忽视AI：" + name + " 出于配置原因");
					}
				} else {
					getLogger().info("忽视AI：" + name + " 由于找不到元数据");
				}
			} catch (Exception e) {
				getLogger().error(e);
			}
		}
	}

	/**
	 * 获取指定用户在权限表中已授权的应用ID集合（state='1'）。
	 *
	 * @param uid 用户ID
	 * @return 已授权的应用ID集合
	 */
	private Set<String> getPermittedApps(String uid) {
		Set<String> result = new HashSet<>();
		try (PreparedStatement ps = database.prepareStatement("SELECT app FROM permission WHERE uid = ? and state='1'")) {
			ps.setString(1, uid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next())
					result.add(rs.getString(1));
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return result;
	}

	/**
	 * 获取指定用户可见的AI应用。
	 * <p>
	 * 可见性由应用的 access 配置决定：公开可见的应用对所有用户可见，仅权限可见的应用
	 * 需要权限表授权，不可见（hidden）的应用不出现在任何列表中。
	 * </p>
	 *
	 * @param uid 用户ID
	 * @return 可见的应用ID集合
	 */
	public Set<String> getVisibleApps(String uid) {
		Set<String> permitted = getPermittedApps(uid);
		Set<String> result = new HashSet<>();
		for (Map.Entry<String, ApplicationAttributes> e : apps.entrySet()) {
			ApplicationAttributes attr = e.getValue();
			if (attr == null || attr.isHidden())
				continue;
			if (attr.isPublic() || permitted.contains(e.getKey()))
				result.add(e.getKey());
		}
		return result;
	}

	/**
	 * 获取指定用户有权访问的AI应用列表。
	 * <p>
	 * 包含公开可见的应用，以及权限表中 state='1' 的应用；不可见应用被排除。
	 * </p>
	 *
	 * @param uid 用户ID
	 * @return JSON数组，每个元素包含应用名称和ID
	 */
	public JsonArray getChatApps(String uid) {
		JsonArray ja = new JsonArray();
		for (String appName : getVisibleApps(uid)) {
			ApplicationAttributes ent = apps.get(appName);
			if (ent != null)
				ja.add(JsonBuilder.object().add("name", ent.getName()).add("appid", appName).end());
		}
		return ja;
	}

	/**
	 * 检查指定用户是否有权使用指定的AI应用。
	 * <p>
	 * 公开可见的应用对所有用户开放；仅权限可见的应用需要权限表授权；不可见应用一律拒绝。
	 * </p>
	 *
	 * @param uid   用户ID
	 * @param appid 应用ID
	 * @return 允许使用返回true
	 */
	public boolean isChatAllow(String uid, String appid) {
		if (appid == null)
			return false;
		ApplicationAttributes attr = apps.get(appid);
		if (attr == null || attr.isHidden())
			return false;
		if (attr.isPublic())
			return true;
		return hasPermission(uid, appid);
	}

	/**
	 * 查询权限表中是否存在授权记录。
	 *
	 * @param uid   用户ID
	 * @param appid 应用ID
	 * @return 存在 state='1' 的记录返回true
	 */
	private boolean hasPermission(String uid, String appid) {
		if (uid == null)
			return false;
		try (PreparedStatement ps = database.prepareStatement("SELECT count(1) FROM permission WHERE uid = ? and state='1' and app = ?")) {
			ps.setString(1, uid);
			ps.setString(2, appid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next())
					return rs.getInt(1) > 0;
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return false;
	}

	/**
	 * 获取指定用户的所有对话列表（仅包含未归档、未移除的对话）。
	 *
	 * @param uid 用户ID
	 * @return JSON数组，每个元素包含对话ID、应用ID、时间、应用名称和简述
	 */
	public JsonArray getChatListUser(String uid) {
		return queryChatList(uid, null, STATUS_NORMAL);
	}

	/**
	 * 按应用分类查询对话列表。
	 * <p>
	 * 这是各聊天页面拉取列表的统一入口：必须传入应用ID，保证每个页面的列表里只会出现
	 * 该应用自己的对话；隐藏应用与不可见应用下的对话不会被返回。
	 * </p>
	 *
	 * @param uid    用户ID
	 * @param app    应用ID，为空时返回该用户全部应用的对话
	 * @param status 存档状态过滤（{@link #STATUS_NORMAL} / {@link #STATUS_ARCHIVED}），
	 *               为空时默认查询未归档
	 * @return JSON数组，包含匹配的对话信息
	 */
	public JsonArray queryChatList(String uid, String app, String status) {
		if (status == null || status.isEmpty())
			status = STATUS_NORMAL;
		StringBuilder sql = new StringBuilder("SELECT c.chatid,c.brief,c.time,c.app FROM chats c"
			+ " WHERE c.uid = ? AND c.status = ?");
		if (app != null && !app.isEmpty())
			sql.append(" AND c.app = ?");
		sql.append(" ORDER BY c.time DESC");
		try (PreparedStatement ps = database.prepareStatement(sql.toString())) {
			ps.setString(1, uid);
			ps.setString(2, status);
			if (app != null && !app.isEmpty())
				ps.setString(3, app);
			try (ResultSet rs = ps.executeQuery()) {
				JsonArray ja = new JsonArray();
				while (rs.next()) {
					String appid = rs.getString(4);
					ApplicationAttributes ent = apps.get(appid);
					// 只返回仍可见的应用下的对话，避免聊天页面串到其它/已下线应用
					if (ent == null || ent.isHidden())
						continue;
					ja.add(JsonBuilder.object()
						.add("chatid", rs.getString(1))
						.add("appid", appid)
						.add("app", ent.getName())
						.add("time", rs.getString(3))
						.add("name", rs.getString(2) == null ? "新对话" : rs.getString(2))
						.end());
				}
				return ja;
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return new JsonArray();
	}

	/**
	 * 查找指定用户在指定应用下的对话（兼容旧接口，等价于查询该应用下未归档的对话）。
	 *
	 * @param uid 用户ID
	 * @param app 应用ID
	 * @return JSON数组，包含匹配的对话信息
	 */
	public JsonArray findChat(String uid, String app) {
		return queryChatList(uid, app, STATUS_NORMAL);
	}

	/**
	 * 查询指定用户在指定应用下的"当前存档"。
	 * <p>
	 * 存档管理页选中的对话（chatdata.selected='1'）优先；没有选中标记时回退到最近一次的
	 * 未归档对话。各聊天页面用它决定进入时打开哪一个存档，而不是靠列表顺序猜。
	 * </p>
	 *
	 * @param uid 用户ID
	 * @param app 应用ID
	 * @return 当前存档信息，没有可用存档时返回null
	 */
	public JsonObject findCurrentChat(String uid, String app) {
		String sql = "SELECT c.chatid,c.brief,c.time,c.app FROM chats c LEFT JOIN chatdata cd ON c.chatid=cd.chatid"
			+ " WHERE c.uid = ? AND c.app = ? AND c.status = ?"
			+ " ORDER BY (CASE WHEN cd.selected='1' THEN 0 ELSE 1 END), c.time DESC LIMIT 1";
		try (PreparedStatement ps = database.prepareStatement(sql)) {
			ps.setString(1, uid);
			ps.setString(2, app);
			ps.setString(3, STATUS_NORMAL);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					String appid = rs.getString(4);
					ApplicationAttributes ent = apps.get(appid);
					if (ent == null || ent.isHidden())
						return null;
					return JsonBuilder.object()
						.add("chatid", rs.getString(1))
						.add("appid", appid)
						.add("app", ent.getName())
						.add("time", rs.getString(3))
						.add("name", rs.getString(2) == null ? "新对话" : rs.getString(2))
						.end();
				}
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return null;
	}

	/**
	 * 生成一个全局唯一的对话ID（UUID去除横线），并确保在数据库中不存在。
	 *
	 * @return 可用的对话ID
	 */
	public String getAvailableId() {
		while (true) {
			String id = UUID.randomUUID().toString().replaceAll("-", "");
			try (PreparedStatement ps = database.prepareStatement("SELECT * FROM chats WHERE chatid = ?")) {
				ps.setString(1, id);
				ResultSet rs = ps.executeQuery();
				if (rs.next()) {
					continue;
				}
				return id;
			} catch (SQLException e) {
				getLogger().printStackTrace(e);
			}
		}
	}

	/**
	 * HTTP GET端点：根路径"/"重定向到"/aichat/index"。
	 *
	 * @param path 完整路径（由框架注入）
	 * @return 重定向结果
	 */
	@HttpMethod("GET")
	@HttpPath("/")
	@Adapter
	public ResultDTO red(@GetBy(FullPathIn.class) String path) {
		return ResultDTO.redirect("/aichat/index");
	}

	/**
	 * HTTP GET端点：获取指定用户的对话列表。
	 *
	 * @param uid 用户ID
	 * @return JSON格式的对话列表
	 */
	@HttpMethod("GET")
	@HttpPath("/chatlist")
	@Adapter
	public ResultDTO chats(@Query("uid") String uid) {
		return new ResultDTO(200, getChatListUser(uid));
	}
	@HttpMethod("GET")
	@HttpPath("/archive")
	@Adapter
	public ResultDTO archive(@Query("uid") String uid) {
		return new ResultDTO(200, new File(parent, "saveman.html"));
	}
	/**
	 * HTTP GET端点：查询指定用户在指定应用下的对话（按应用分类，只返回该应用的对话）。
	 *
	 * @param uid    用户ID
	 * @param app    应用ID
	 * @param status 存档状态过滤，缺省为进行中（normal）
	 * @return JSON格式的对话列表
	 */
	@HttpMethod("GET")
	@HttpPath("/querychat")
	@Adapter
	public ResultDTO findChats(@Query("uid") String uid, @Query("appid") String app, @Query("status") String status) {
		if (uid == null || uid.isEmpty())
			return new ResultDTO(400, "缺少用户ID");
		if (app == null || app.isEmpty())
			return new ResultDTO(400, "缺少应用ID");
		ApplicationAttributes attr = apps.get(app);
		if (attr == null || attr.isHidden())
			return new ResultDTO(400, "应用不存在");
		return new ResultDTO(200, queryChatList(uid, app, normalizeStatus(status)));
	}

	/**
	 * HTTP GET端点：查询指定用户在指定应用下的当前存档。
	 * <p>
	 * 存档管理页选中的存档优先，没有选中标记时回退到最近一次的存档。各聊天页面进入时
	 * 用它决定打开哪一个存档，避免依赖列表顺序。
	 * </p>
	 *
	 * @param uid 用户ID
	 * @param app 应用ID
	 * @return JSON格式的单个对话信息，没有存档时返回空对象
	 */
	@HttpMethod("GET")
	@HttpPath("/currentChat")
	@Adapter
	public ResultDTO currentChat(@Query("uid") String uid, @Query("appid") String app) {
		if (uid == null || uid.isEmpty())
			return new ResultDTO(400, "缺少用户ID");
		if (app == null || app.isEmpty())
			return new ResultDTO(400, "缺少应用ID");
		ApplicationAttributes attr = apps.get(app);
		if (attr == null || attr.isHidden())
			return new ResultDTO(400, "应用不存在");
		JsonObject current = findCurrentChat(uid, app);
		return new ResultDTO(200, current == null ? new JsonObject() : current);
	}

	/**
	 * 规范化请求中的存档状态过滤值。
	 *
	 * @param status 请求参数
	 * @return {@link #STATUS_ARCHIVED} 或 {@link #STATUS_NORMAL}
	 */
	private static String normalizeStatus(String status) {
		if (status != null && STATUS_ARCHIVED.equalsIgnoreCase(status.trim()))
			return STATUS_ARCHIVED;
		return STATUS_NORMAL;
	}

	/**
	 * HTTP GET端点：生成一个可用的对话ID。
	 *
	 * @return 包含新ID的JSON结果
	 */
	@HttpMethod("GET")
	@HttpPath("/createid")
	@Adapter
	public ResultDTO createid() {
		return new ResultDTO(200, getAvailableId());
	}

	/**
	 * HTTP GET端点：获取指定用户可用的AI应用列表。
	 *
	 * @param uid 用户ID
	 * @return 应用列表JSON
	 */
	@HttpMethod("GET")
	@HttpPath("/applist")
	@Adapter
	public ResultDTO apps(@Query("uid") String uid) {
		return new ResultDTO(200, getChatApps(uid));
	}
	/**
	 * HTTP GET端点：获取存档管理页的应用列表。
	 * <p>
	 * 列表 = 该用户可见的应用（公开可见 + 已授权）∪ 该用户已经有存档的应用，
	 * 后者保证即使用户后来失去权限，也不会看不到自己已有的存档。不可见应用始终被排除。
	 * </p>
	 *
	 * @param uid 用户ID
	 * @return 应用列表JSON，元素含 name/appid/url 以及存档数量统计
	 */
	@HttpMethod("GET")
	@HttpPath("/archiveApplist")
	@Adapter
	public ResultDTO archiveApps(@Query("uid") String uid) {
		Set<String> curApps=getVisibleApps(uid);
		if (uid != null && !uid.isEmpty()) {
			// 聊天分类：把用户已有对话的应用一并纳入存档管理范围
			try (PreparedStatement ps = database.prepareStatement("SELECT DISTINCT app FROM chats WHERE uid = ?")) {
				ps.setString(1, uid);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next())
						curApps.add(rs.getString(1));
				}
			} catch (SQLException e) {
				getLogger().printStackTrace(e);
			}
		}
		// 统计各应用的进行中/已归档存档数量，供前端展示
		Map<String,int[]> counts=new HashMap<>();
		if (uid != null && !uid.isEmpty()) {
			try (PreparedStatement ps = database.prepareStatement(
				"SELECT app, status, count(1) FROM chats WHERE uid = ? GROUP BY app, status")) {
				ps.setString(1, uid);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						String appid = rs.getString(1);
						int[] c = counts.get(appid);
						if (c == null)
							counts.put(appid, c = new int[2]);
						if (STATUS_ARCHIVED.equals(rs.getString(2)))
							c[1] += rs.getInt(3);
						else if (STATUS_NORMAL.equals(rs.getString(2)))
							c[0] += rs.getInt(3);
					}
				}
			} catch (SQLException e) {
				getLogger().printStackTrace(e);
			}
		}
		JsonArray ja = new JsonArray();
		for (String appName : curApps) {
			ApplicationAttributes attr = apps.get(appName);
			if (attr == null || attr.isHidden())
				continue;
			String url=attr.url;
			JsonObjectBuilder<JsonObject> jb=JsonBuilder.object().add("name", attr.getName()).add("appid", appName);
			if(url!=null)
				jb.add("url", url);
			int[] c = counts.get(appName);
			jb.add("active", c == null ? 0 : c[0]);
			jb.add("archived", c == null ? 0 : c[1]);
			ja.add(jb.end());
		}
		return new ResultDTO(200, ja);
	}
	/**
	 * HTTP GET端点：提供静态资源文件（如图片、CSS等）。
	 *
	 * @param req 请求对象
	 * @param rep 响应对象
	 */
	@HttpMethod("GET")
	@HttpPath("/resource")
	public void resource(Request req, Response rep) {
		resource.call(req, rep);
	}

	/**
	 * HTTP GET端点：提供语音资源文件。
	 *
	 * @param req 请求对象
	 * @param rep 响应对象
	 */
	@HttpMethod("GET")
	@HttpPath("/voice")
	public void voice(Request req, Response rep) {
		voice.call(req, rep);
	}

	/**
	 * HTTP GET端点：返回chat.js文件。
	 *
	 * @return 包含文件内容的ResultDTO
	 * @throws IOException 如果文件读取失败
	 */
	@HttpMethod("GET")
	@HttpPath("/chat.js")
	@Adapter
	public ResultDTO chatjs() throws IOException {
		return new ResultDTO(200, new File(parent, "chat.js"));
	}
	/**
	 * HTTP GET端点：返回 MCP 访问令牌管理页面。
	 * <p>
	 * 页面通过 {@code /aichat/mcp/token}、{@code /aichat/mcp/usage} 等管理接口完成令牌的创建、停用与消费记录查看。
	 * 用户身份由外部框架通过 URL 查询参数注入，页面不展示，接口也不从请求体读取。
	 * </p>
	 *
	 * @return 包含页面文件的ResultDTO
	 * @throws IOException 文件读取失败
	 */
	@HttpMethod("GET")
	@HttpPath("/mcptoken")
	@Adapter
	public ResultDTO mcpTokenPage() throws IOException {
		return new ResultDTO(200, new File(parent, "mcptoken.html"));
	}

	/**
	 * HTTP端点：把 {@code /aichat/mcp} 及其子路径转发给 MCP 服务端端点。
	 * <p>
	 * MCP 的 JSON-RPC 端点（{@code /aichat/mcp}）与令牌管理接口（{@code /aichat/mcp/token} 等）
	 * 都由 {@link McpServerEndpoint} 按剩余路径分派，因此不需要在 {@code AIServerMain} 单独挂载。
	 * 未启用 MCP 服务端（{@code save/mcp.json} 中 {@code enabled=false}）时返回 404。
	 * </p>
	 *
	 * @param req 请求对象
	 * @param res 响应对象
	 */
	@HttpPath("/mcp")
	public void mcp(Request req, Response res) {
		if (mcpEndpoint == null) {
			res.write(404, "Not Found");
			return;
		}
		mcpEndpoint.call(req, res);
	}
	@HttpMethod("GET")
	@HttpPath("/ptrpg")
	@Adapter
	public ResultDTO ptrpg() throws IOException {
		return new ResultDTO(200, new File(parent, "ptrpg.html"));
	}
	/**
	 * HTTP GET端点：返回vue.js文件。
	 *
	 * @return 包含文件内容的ResultDTO
	 * @throws IOException 如果文件读取失败
	 */
	@HttpMethod("GET")
	@HttpPath("/vue.js")
	@Adapter
	public ResultDTO vuejs() throws IOException {
		return new ResultDTO(200, new File(parent, "vue.js"));
	}

	/**
	 * HTTP GET端点：返回robots.txt文件。
	 *
	 * @return 包含文件内容的ResultDTO
	 * @throws IOException 如果文件读取失败
	 */
	@HttpMethod("GET")
	@HttpPath("/robots.txt")
	@Adapter
	public ResultDTO robots() throws IOException {
		return new ResultDTO(200, new File(parent, "robots.txt"));
	}

	/**
	 * HTTP GET端点：返回feather.min.js文件。
	 *
	 * @return 包含文件内容的ResultDTO
	 * @throws IOException 如果文件读取失败
	 */
	@HttpMethod("GET")
	@HttpPath("/feather.min.js")
	@Adapter
	public ResultDTO featherjs() throws IOException {
		return new ResultDTO(200, new File(parent, "feather.min.js"));
	}

	/**
	 * HTTP GET端点：返回galgame、trpg、ptrpg、custom四个页面共用的样式表。
	 *
	 * @return 包含文件内容的ResultDTO
	 * @throws IOException 如果文件读取失败
	 */
	@HttpMethod("GET")
	@HttpPath("/chat-common.css")
	@Adapter
	public ResultDTO chatCommonCss() throws IOException {
		return new ResultDTO(200, new File(parent, "chat-common.css"));
	}
	@HttpMethod("GET")
	@HttpPath("/echarts.min.js")
	@Adapter
	public ResultDTO echartsjs() throws IOException {
		return new ResultDTO(200, new File(parent, "echarts.min.js"));
	}
	/**
	 * HTTP GET端点：返回各前端页面共用的模型选择组件脚本（目录树形式）。
	 *
	 * @return 包含文件内容的ResultDTO
	 * @throws IOException 如果文件读取失败
	 */
	@HttpMethod("GET")
	@HttpPath("/modeltree.js")
	@Adapter
	public ResultDTO modelTreeJs() throws IOException {
		return new ResultDTO(200, new File(parent, "modeltree.js"));
	}
	/**
	 * HTTP GET端点：返回各前端页面共用的白天/黑夜模式脚本。
	 *
	 * @return 包含文件内容的ResultDTO
	 * @throws IOException 如果文件读取失败
	 */
	@HttpMethod("GET")
	@HttpPath("/theme.js")
	@Adapter
	public ResultDTO themeJs() throws IOException {
		return new ResultDTO(200, new File(parent, "theme.js"));
	}
	@HttpMethod("GET")
	@HttpPath("/heartbeat.js")
	@Adapter
	public ResultDTO heartbeatJs() throws IOException {
		return new ResultDTO(200, new File(parent, "heartbeat.js"));
	}
	@HttpMethod("GET")
	@HttpPath("/ai-protocol.js")
	@Adapter
	public ResultDTO aiProtocolJs() throws IOException {
		return new ResultDTO(200, new File(parent, "ai-protocol.js"));
	}
	/**
	 * HTTP GET端点：返回前端站点配置 site-config.js。
	 * <p>
	 * 站点域名默认读取数据目录下的 {@code site.json}（{@code "host"} 字段），
	 * 也可以用启动参数 {@code --site-host=example.com}（或 {@code -Dsite_host=example.com}）临时覆盖，
	 * 见 {@link SiteConfig}。未配置时下发空域名，前端各页面会自动跟随当前访问域名，
	 * 因此自建部署无需修改任何 HTML。
	 * </p>
	 *
	 * @return 包含配置脚本的ResultDTO
	 */
	@HttpMethod("GET")
	@HttpPath("/site-config.js")
	@Adapter
	public ResultDTO siteConfigJs() {
		ResultDTO res = new ResultDTO(200, SiteConfig.resolve(parent).toScript());
		// 脚本内容保持纯ASCII，Content-Length按字符数计算才不会截断
		res.addHeader(HttpHeaderNames.CONTENT_TYPE, "application/javascript; charset=UTF-8");
		res.addHeader(HttpHeaderNames.CACHE_CONTROL, "no-cache");
		return res;
	}
	@HttpMethod("GET")
	@HttpPath("/ai-ui.css")
	@Adapter
	public ResultDTO aiUiCss() throws IOException {
		return new ResultDTO(200, new File(parent, "ai-ui.css"));
	}
	@HttpMethod("GET")
	@HttpPath("/sidebar.css")
	@Adapter
	public ResultDTO sidebarCss() throws IOException {
		return new ResultDTO(200, new File(parent, "sidebar.css"));
	}
	@HttpMethod("GET")
	@HttpPath("/usage")
	@Adapter
	public ResultDTO usage() throws IOException {
		return new ResultDTO(200, new File(parent, "usage.html"));
	}
	/**
	 * HTTP GET端点：返回aiindex.html文件（主页）。
	 *
	 * @return 包含文件内容的ResultDTO
	 * @throws IOException 如果文件读取失败
	 */
	@HttpMethod("GET")
	@HttpPath("/index")
	@Adapter
	public ResultDTO indexHtm() throws IOException {
		return new ResultDTO(200, new File(parent, "aiindex.html"));
	}
	@HttpMethod("GET")
	@HttpPath("/aipaintindex")
	@Adapter
	public ResultDTO aiPaintIndex() throws IOException {
		return new ResultDTO(200, new File(parent, "paintindex.html"));
	}

	/**
	 * HTTP GET端点：返回chat.html文件（对话页面）。
	 *
	 * @return 包含文件内容的ResultDTO
	 * @throws IOException 如果文件读取失败
	 */
	@HttpMethod("GET")
	@HttpPath("/aichat")
	@Adapter
	public ResultDTO chat() throws IOException {
		return new ResultDTO(200, new File(parent, "chat.html"));
	}
	@HttpMethod("GET")
	@HttpPath("/pricing")
	@Adapter
	public ResultDTO pricing() throws IOException {
		return new ResultDTO(200, new File(parent, "pricing.html"));
	}
	/**
	 * HTTP GET端点：返回galgame.html文件（Galgame风格页面）。
	 *
	 * @return 包含文件内容的ResultDTO
	 * @throws IOException 如果文件读取失败
	 */
	@HttpMethod("GET")
	@HttpPath("/aigal")
	@Adapter
	public ResultDTO aigal() throws IOException {
		return new ResultDTO(200, new File(parent, "galgame.html"));
	}

	/**
	 * HTTP GET端点：返回trpg.html文件（TRPG风格页面）。
	 *
	 * @return 包含文件内容的ResultDTO
	 * @throws IOException 如果文件读取失败
	 */
	@HttpMethod("GET")
	@HttpPath("/aitrpg")
	@Adapter
	public ResultDTO aitrpg() throws IOException {
		return new ResultDTO(200, new File(parent, "trpg.html"));
	}
	@HttpMethod("GET")
	@HttpPath("/agentedit")
	@Adapter
	public ResultDTO agentedit() throws IOException {
		return new ResultDTO(200, new File(parent, "agentedit.html"));
	}
	@HttpMethod("GET")
	@HttpPath("/custom")
	@Adapter
	public ResultDTO custom() throws IOException {
		return new ResultDTO(200, new File(parent, "custom.html"));
	}

	/**
	 * HTTP GET端点：返回painter.html文件（AI绘画页面）。
	 *
	 * @return 包含文件内容的ResultDTO
	 * @throws IOException 如果文件读取失败
	 */
	@HttpMethod("GET")
	@HttpPath("/painter")
	@Adapter
	public ResultDTO painter() throws IOException {
		return new ResultDTO(200, new File(parent, "painter.html"));
	}

	@HttpMethod("GET")
	@HttpPath("/painter-local")
	@Adapter
	public ResultDTO painterLocal() throws IOException {
		return new ResultDTO(200, new File(parent, "painter-local.html"));
	}

	@HttpPath("/painter/localResponse")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO painterLocalResponse(@Query("conversation_id") String cid,
		@Query("request_id") String requestId,
		@GetBy(JsonIn.class) JsonObject result) {
		try {
			if (cid == null || cid.isEmpty() || requestId == null || requestId.isEmpty()) {
				return new ResultDTO(400, JsonBuilder.object().add("code", -1).add("message", "missing parameters").end());
			}
			AISession session = uidsockets.get(cid);
			if (session != null&&session instanceof PainterSession) {
				((PainterSession) session).completeLocalRequest(requestId, result);
				return new ResultDTO(200, JsonBuilder.object().add("code", 0).end());
			}
			return new ResultDTO(404, JsonBuilder.object().add("code", -1).add("message", "session not found").end());
		} catch (Exception e) {
			getLogger().printStackTrace(e);
			return new ResultDTO(500, JsonBuilder.object().add("code", -1).add("message", "internal error").end());
		}
	}

	public ObjectStorageProvider getPainterStorage() {
		return painterStorage;
	}
	/**
	 * HTTP POST端点：接收绘画师前端上传的图片（multipart/form-data）。
	 * 使用DelegatingStorage持久化存储（本地+TOS），返回内容寻址的图片ID。
	 *
	 * @param cid 对话ID（conversation_id）
	 * @param imageData 上传的图片文件数据
	 * @return 包含图片ID的JSON结果
	 */
	@HttpPath("/painter/uploadImage")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO painterUploadImage(@PostQuery("conversation_id") String cid,
		@PostQuery("image") HttpData imageData) {
		try {
			if (cid == null || cid.isEmpty()) {
				return new ResultDTO(400, JsonBuilder.object().add("code", -1).add("message", "缺少会话ID（conversation_id）").end());
			}
			if (imageData == null) {
				return new ResultDTO(400, JsonBuilder.object().add("code", -1).add("message", "没有收到图片数据（image 字段为空）").end());
			}
			AISession session = uidsockets.get(cid);
			if (session != null&&session instanceof PainterSession) {

				byte[] data = imageData.get();
				String imageId = painterStorage.uploadIfNotExists(data,session::addUsage);
				
				// 图片作为待生成的用户输入记录到会话历史：写入动作会排到会话命令线程，
				// 与正在进行的生成串行，避免与生成线程并发写历史（落盘也在一并在线程内完成）
				((PainterSession) session).addPendingUserMessage(new ImageContent(imageId));
				return new ResultDTO(200, JsonBuilder.object().add("code", 0).add("message", "success").end());
			}
			if (session == null) {
				return new ResultDTO(409, JsonBuilder.object().add("code", -1)
						.add("message", "对话连接不存在或已释放，请刷新页面后重试").end());
			}
			return new ResultDTO(409, JsonBuilder.object().add("code", -1)
					.add("message", "当前对话不是绘画会话，无法上传图片").end());
		} catch (Exception e) {
			getLogger().printStackTrace(e);
			String detail = e.getClass().getSimpleName();
			if (e.getMessage() != null && !e.getMessage().isEmpty()) {
				detail = detail + "：" + e.getMessage();
			}
			return new ResultDTO(500, JsonBuilder.object().add("code", -1)
					.add("message", "服务器上传失败（" + detail + "）").end());
		}
	}

	/**
	 * HTTP GET端点：根据图片ID获取已存储的图片数据。
	 * 优先从本地存储读取，若不存在则从TOS下载。
	 *
	 * @param imageId 图片ID（内容寻址的SHA-256哈希）
	 * @return 包含图片字节数据的ResultDTO
	 */
	@HttpMethod("GET")
	@HttpPath("/painter/image")
	@Adapter
	public ResultDTO painterGetImage(@Query("image_id") String imageId) {
		try {
			if (!painterStorage.exists(imageId,null)) {
				return new ResultDTO(404, "image not found");
			}
			byte[] data = painterStorage.download(imageId,null);
			String mime = "image/png";
			if (imageId.endsWith(".jpg") || imageId.endsWith(".jpeg")) mime = "image/jpeg";
			else if (imageId.endsWith(".gif")) mime = "image/gif";
			else if (imageId.endsWith(".webp")) mime = "image/webp";
			ResultDTO result = new ResultDTO(200, data);
			result.addHeader(HttpHeaderNames.CONTENT_TYPE, mime);
			result.setHeader(HttpHeaderNames.CACHE_CONTROL, "public,max-age=2592000");
			return result;
		} catch (Exception e) {
			getLogger().printStackTrace(e);
			return new ResultDTO(500, "failed to load image");
		}
	}
	/**
	 * HTTP GET端点：移除指定对话（软删除）。
	 * <p>
	 * 把对话的 status 设置为 removed，任何界面都不再显示。若只是想从列表中隐藏，
	 * 应使用 {@code /archiveChat} 归档（归档后可在存档管理页恢复）。
	 * </p>
	 *
	 * @param userid 用户ID
	 * @param chatid 对话ID
	 * @return 成功返回"true"
	 */
	@HttpMethod("GET")
	@HttpPath("/remove")
	@Adapter
	public ResultDTO delDialog(@Query("uid") String userid, @Query("cid") String chatid) {
		return updateChatStatus(userid, chatid, STATUS_REMOVED, "true");
	}

	/**
	 * HTTP POST端点：归档指定对话。归档后聊天页面不再显示，仅在存档管理页的"已归档"中可见。
	 * <p>
	 * 被归档的对话同时取消"当前存档"标记，使该应用自动回退到其它进行中的存档。
	 * </p>
	 *
	 * @param uid    用户ID
	 * @param chatid 对话ID
	 * @return 成功返回"true"
	 */
	@HttpPath("/archiveChat")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO archiveChat(@Query("uid") String uid,@PostQuery("chatid") String chatid) {
		ResultDTO result = updateChatStatus(uid, chatid, STATUS_ARCHIVED, "true");
		if (result.code == 200) {
			// 归档的存档不再作为当前存档，避免聊天页面打开一个已归档的对话
			try (PreparedStatement ps = database.prepareStatement("UPDATE chatdata SET selected='0' WHERE chatid=?")) {
				ps.setString(1, chatid);
				ps.executeUpdate();
			} catch (SQLException e) {
				getLogger().printStackTrace(e);
			}
		}
		return result;
	}

	/**
	 * HTTP POST端点：取消归档，把对话恢复为正常状态。
	 *
	 * @param uid    用户ID
	 * @param chatid 对话ID
	 * @return 成功返回"true"
	 */
	@HttpPath("/unarchiveChat")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO unarchiveChat(@Query("uid") String uid,@PostQuery("chatid") String chatid) {
		return updateChatStatus(uid, chatid, STATUS_NORMAL, "true");
	}

	/**
	 * 更新指定对话的存档状态。
	 *
	 * @param uid    用户ID
	 * @param chatid 对话ID
	 * @param status 目标状态
	 * @param okBody 成功时返回的响应体
	 * @return 更新结果
	 */
	private ResultDTO updateChatStatus(String uid, String chatid, String status, String okBody) {
		if (uid == null || uid.isEmpty() || chatid == null || chatid.isEmpty())
			return new ResultDTO(400, "缺少用户ID或对话ID");
		try (PreparedStatement ps2 = database.prepareStatement("UPDATE chats SET status=? WHERE uid=? and chatid=?")) {
			ps2.setString(1, status);
			ps2.setString(2, uid);
			ps2.setString(3, chatid);
			if (ps2.executeUpdate() <= 0)
				return new ResultDTO(404, "对话不存在");
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
			return new ResultDTO(500, "内部错误");
		}
		return new ResultDTO(200, okBody);
	}

	/**
	 * HTTP POST端点：重命名对话（修改 chats.brief，即各页面列表中显示的名称）。
	 * <p>
	 * 绘画（图片生成）等页面用它给自己的对话起名，名称会写入主表并同步到所有列表。
	 * 名称长度限制 40 字符，超长会被截断；传入空名称时回退为"新对话"。
	 * </p>
	 *
	 * @param uid    用户ID
	 * @param chatid 对话ID
	 * @param name   新名称
	 * @return 保存后的名称
	 */
	@HttpPath("/renameChat")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO renameChat(@Query("uid") String uid,@PostQuery("chatid") String chatid,@PostQuery("name") String name) {
		if (uid == null || uid.isEmpty() || chatid == null || chatid.isEmpty())
			return new ResultDTO(400, "缺少用户ID或对话ID");
		String newName = name == null ? "" : name.trim();
		if (newName.length() > MAX_CHAT_NAME_LENGTH)
			newName = newName.substring(0, MAX_CHAT_NAME_LENGTH);
		if (newName.isEmpty())
			newName = "新对话";
		try (PreparedStatement ps = database.prepareStatement("UPDATE chats SET brief=?, name_fixed=1 WHERE uid=? and chatid=?")) {
			ps.setString(1, newName);
			ps.setString(2, uid);
			ps.setString(3, chatid);
			if (ps.executeUpdate() <= 0)
				return new ResultDTO(404, "对话不存在");
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
			return new ResultDTO(500, "内部错误");
		}
		return new ResultDTO(200, newName);
	}

	/**
	 * 更新指定对话的消耗记录（price 已并入 chats 主表）。
	 *
	 * @param chatid 对话ID
	 * @param price  费用字符串
	 */
	public void setPrice(String chatid, String price) {
		try (PreparedStatement ps2 = database.prepareStatement("UPDATE chats SET price=? WHERE chatid=?")) {
			ps2.setString(1, price);
			ps2.setString(2, chatid);
			ps2.executeUpdate();
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
	}

	/**
	 * 更新指定对话的简述（brief字段），由AI在生成结束时自动设置。
	 * <p>
	 * 已被用户手动重命名（name_fixed=1）的对话不会被自动名称覆盖，保证
	 * {@code /renameChat} 的结果稳定；名称超长时按 {@link #MAX_CHAT_NAME_LENGTH} 截断。
	 * </p>
	 *
	 * @param id   对话ID
	 * @param name 新的简述内容
	 */
	public void updateBrief(String id, String name) {
		if (name == null) return;
		if (name.length() > MAX_CHAT_NAME_LENGTH)
			name = name.substring(0, MAX_CHAT_NAME_LENGTH);
		try (PreparedStatement ps2 = database.prepareStatement("UPDATE chats SET brief=? WHERE chatid=? AND COALESCE(name_fixed,0)=0")) {
			ps2.setString(1, name);
			ps2.setString(2, id);
			ps2.executeUpdate();
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
	}

	/**
	 * WebSocket端点：用于本地语音模型的部署（私有化部署）。 需要提供Bearer
	 * Token进行认证，Token通过系统属性"localVoiceToken"配置。
	 *
	 * @param req 请求对象
	 * @param res 响应对象
	 */
	@HttpPath("/kh$localModelDeploy")
	public void voiceWebSocket(Request req, Response res) {
		String auth = req.getHeaders().get(HttpHeaderNames.AUTHORIZATION);
		if (auth != null && auth.startsWith("Bearer ") && System.getProperty("localVoiceToken", "").equals(auth.split(" ")[1]))
			res.suscribeWebsocketEvents(LocalModel.lhs);
		else
			res.write(404);
	}

	/**
	 * HTTP POST端点：接收本地语音模型产出的数据（如音频字节数组）。 需要Bearer Token认证。
	 *
	 * @param reqid 请求ID
	 * @param type  数据类型
	 * @param data  字节数据
	 * @param auth  Authorization头
	 * @return 成功返回200，失败返回404
	 */
	@HttpPath("/kh$localModelDeployData")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO voicePost(@Query("reqid") String reqid,
		@Query("type") String type,
		@GetBy(DataIn.class) byte[] data,
		@Header("Authorization") String auth) {
		if (auth != null && auth.startsWith("Bearer ") && System.getProperty("localVoiceToken", "").equals(auth.split(" ")[1])) {
			if(LocalModel.lhs.onMessage(reqid, data, type))
			return new ResultDTO(200);
			return new ResultDTO(500);
		}
		return new ResultDTO(404);
	}
	/**
	 * 按对话ID与用户ID加载会话（内存中已有则直接复用）。
	 * <p>
	 * 已移除（status=removed）的对话视为不存在；已归档的对话仍可加载，用于导出或按ID直连。
	 * </p>
	 *
	 * @param cid 对话ID
	 * @param uid 用户ID
	 * @return 会话对象，找不到或不属于该用户时返回null
	 * @throws SQLException 查询失败
	 */
	public AISession getOrLoadSession(String cid,String uid) throws SQLException{
		try (PreparedStatement ps = database.prepareStatement("SELECT uid,app FROM chats WHERE chatid = ? and status != ?")) {
			ps.setString(1, cid);
			ps.setString(2, STATUS_REMOVED);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next() && rs.getString(1).equals(uid)) {
					AISession state = uidsockets.get(cid);
					String app = rs.getString(2);
					ApplicationAttributes attr = apps.get(app);
					if (state == null) {
						if (attr == null) {
							return null;
						}
						File data = new File(saveData, cid + ".json");
						if (data.exists()) {
							try {
								state=loadSession(uid, cid,attr, data);
							} catch (JsonSyntaxException | IOException e) {
								getLogger().printStackTrace(e);
								logger.info("AI " + cid + " Load Error");
							}
						} else
							return null;
					}

					return state;
				}
			}
		}
		return null;
	}
	/**
	 * HTTP POST端点：导出指定对话的历史记录为UTF-8文本文件（带BOM头）。
	 * 文件内容由AI应用提供的{@link AIApplication#constructBackLog(AISession)}生成。
	 * 返回的文件会添加适当的下载响应头。
	 *
	 * @param uid 用户ID
	 * @param cid 对话ID
	 * @return 包含文本文件内容的ResultDTO，如果验证失败返回错误码
	 */
	@HttpPath("/exportChat")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO export(@Query("uid") String uid, @Query("chatid") String cid) {
		try {
			AISession state = this.getOrLoadSession(cid, uid);
			if(state!=null) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				try {
					baos.write(0xEF);
					baos.write(0xBB);
					baos.write(0xBF); // UTF-8 BOM
					baos.write(state.constructBackLog().getBytes(StandardCharsets.UTF_8));
				} catch (IOException e) {
					getLogger().printStackTrace(e);
					return new ResultDTO(500, "内部服务器错误");
				}
				ResultDTO res = new ResultDTO(200, baos.toByteArray());
				res.addHeader(HttpHeaderNames.CONTENT_DISPOSITION,
					"attachment; filename=\"" + new String(("故事导出-" + state.getBrief() + ".txt")
						.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1) + "\"");
				res.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
				res.addHeader(HttpHeaderNames.CACHE_CONTROL, "private, no-store, no-cache, must-revalidate");
				res.addHeader(HttpHeaderNames.PRAGMA, "no-cache");
				res.addHeader(HttpHeaderNames.EXPIRES, "0");
				return res;
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
			return new ResultDTO(500, "Internal Server Error");
		}
		return new ResultDTO(401, "Unauthorized");
	}
	@HttpPath("/exportMemory")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO exportMemory(@Query("uid") String uid, @Query("chatid") String cid) {
		try {
			AISession state = this.getOrLoadSession(cid, uid);
			if(state!=null) {
				String memory=state.getMemory();
				if(memory==null) {
					ResultDTO res = new ResultDTO(200, "<html><head></head><body><script>alert('暂无记忆')</script></body>");
					res.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
					res.addHeader(HttpHeaderNames.CACHE_CONTROL, "private, no-store, no-cache, must-revalidate");
					res.addHeader(HttpHeaderNames.PRAGMA, "no-cache");
					res.addHeader(HttpHeaderNames.EXPIRES, "0");
					return res;
				}
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				try {
					baos.write(0xEF);
					baos.write(0xBB);
					baos.write(0xBF); // UTF-8 BOM
					baos.write(memory.getBytes(StandardCharsets.UTF_8));
				} catch (IOException e) {
					getLogger().printStackTrace(e);
					return new ResultDTO(500, "内部服务器错误");
				}
				ResultDTO res = new ResultDTO(200, baos.toByteArray());
				res.addHeader(HttpHeaderNames.CONTENT_DISPOSITION,
					"attachment; filename=\"" + new String(("记忆导出-" + state.getBrief() + ".txt")
						.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1) + "\"");
				res.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
				res.addHeader(HttpHeaderNames.CACHE_CONTROL, "private, no-store, no-cache, must-revalidate");
				res.addHeader(HttpHeaderNames.PRAGMA, "no-cache");
				res.addHeader(HttpHeaderNames.EXPIRES, "0");
				return res;
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
			return new ResultDTO(500, "Internal Server Error");
		}
		return new ResultDTO(401, "Unauthorized");
	}
	@HttpPath("/chatconfig")
	@Adapter
	@HttpMethod("GET")
	public ResultDTO getChatConfig(@Query("uid") String uid, @Query("chatid") String cid) {
		if(cid==null) {
			return new ResultDTO(200,JsonBuilder.object().add("gameSetting", "").add("brief", "新建智能体").add("coreRule", defaultRule).add("contextLimit",50000).end());		
		}
		try (PreparedStatement ps = database.prepareStatement("SELECT uid,app FROM chats WHERE chatid = ? AND app='custom'")) {
			ps.setString(1, cid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next() && rs.getString(1).equals(uid)) {
					AISession state = uidsockets.get(cid);
					String app = rs.getString(2);
					ApplicationAttributes attr = apps.get(app);
					ExtraData exd;
					if (state == null) {
						if (attr == null) {
							return new ResultDTO(400, "App does not exist");
						}
						File data = new File(saveData, cid + ".json");
						if (data.exists()) {
							try {
								exd = AIApplication.dataFromJson(data);
							} catch (JsonSyntaxException | IOException e) {
								getLogger().printStackTrace(e);
								logger.info("AI " + cid + " Load Error");
								return new ResultDTO(500, "Chat Load Error");
							}
						} else
							return new ResultDTO(404, "Chat does not exist");
					}else
						exd=state.getExtraData();
					return new ResultDTO(200,JsonBuilder.object().add("brief", exd.extraData.get("brief")).add("gameSetting", exd.extraData.get("charaset")).add("coreRule", exd.extraData.get("rules")).add("contextLimit",Integer.parseInt(exd.extraData.get("limit"))).add("url", "custom?chatid="+cid).end());		
					
				}
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return new ResultDTO(404);
	}
	public ApplicationAttributes getAttribute(String app) {
		return apps.get(app);
	}
	/**
	 * 新建一个对话（存档）。
	 * <p>
	 * 对话主表 chats 一次性写入用户、应用、时间、名称、状态与费用（price 已并入主表），
	 * 同时初始化 chatdata 中的备注与"当前存档"标记。
	 * </p>
	 *
	 * @param uid        用户ID
	 * @param rcid       对话ID
	 * @param attribute  应用属性
	 * @param data       对话数据文件
	 * @param visibility 初始存档状态，见 {@link #STATUS_NORMAL} 等
	 * @return 加载完成的会话对象
	 * @throws IOException 会话数据读取失败
	 */
	public AISession createSession(String uid, String rcid,ApplicationAttributes attribute, File data,String visibility) throws IOException{
		long time = new Date().getTime();
		String status = visibility == null || visibility.isEmpty() ? STATUS_NORMAL : visibility;
		try (PreparedStatement ps2 = database.prepareStatement("INSERT INTO chats(uid,chatid,app,time,price,status,attribute) VALUES(?,?,?,?,?,?,'')")) {
			ps2.setString(1, uid);
			ps2.setString(2, rcid);
			ps2.setString(3, attribute.appid);
			ps2.setLong(4, time);
			ps2.setString(5, "0.00");
			ps2.setString(6, status);
			ps2.executeUpdate();
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		try (PreparedStatement ps2 = database.prepareStatement("INSERT INTO chatdata(chatid,remark,selected) VALUES(?,?,?)")) {
			ps2.setString(1, rcid);
			ps2.setString(2, "");
			ps2.setString(3, "1");
			ps2.executeUpdate();
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		// 新存档即该应用的"当前存档"：清掉同一应用下其它存档的选中标记，保证唯一
		try (PreparedStatement ps2 = database.prepareStatement("UPDATE chatdata SET selected='0' WHERE chatid != ?"
			+ " AND chatid IN (SELECT chatid FROM chats WHERE uid = ? AND app = ?)")) {
			ps2.setString(1, rcid);
			ps2.setString(2, uid);
			ps2.setString(3, attribute.appid);
			ps2.executeUpdate();
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return loadSession(uid,rcid,attribute,data);
	}
	public AISession loadSession(String uid, String rcid,ApplicationAttributes attribute, File data) throws IOException{
		AISession session;
		if("painter".equals(attribute.ui))
			session = new PainterSession(this, uid, rcid, attribute.app, attribute, data,
					AIApplication.saveDataFromJson(data));
		else
			session = new WebSocketAISession(this, uid, rcid, attribute.app,attribute, data,
				AIApplication.saveDataFromJson(data));
		// 把 MCP 客户端当前提供的远程工具注册进会话，AI 可像本地工具一样调用
		if (mcpClients != null) {
			try {
				session.registerTools(mcpClients.getTools());
			} catch (Throwable ex) {
				logger.printStackTrace(ex);
			}
		}
		return session;
	}
	@HttpPath("/chatconfig")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO setChatConfig(@Query("uid") String uid, @GetBy(JsonIn.class) JsonObject obj) {
		String rcid = null;
		String app="custom";
		if(obj.has("chatid"))
			try (PreparedStatement ps = database.prepareStatement("SELECT uid,app FROM chats WHERE chatid = ? AND app='custom'")) {
				ps.setString(1, obj.get("chatid").getAsString());
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next() && rs.getString(1).equals(uid)) {
						rcid=obj.get("chatid").getAsString();
					}
				}
			} catch (SQLException e) {
				getLogger().printStackTrace(e);
			}
		if(rcid==null) {
			rcid=this.getAvailableId();	
		}
		AISession state =null;
		try {
			state = this.getOrLoadSession(rcid, uid);
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		ApplicationAttributes attr=apps.get(app);
		if(attr.paidOnly&&!this.hasAnyPaidTokenRemaining(uid)) {
			return new ResultDTO(403,"补充配额不足，无法创建存档");
		}
		File data = new File(saveData, rcid + ".json");
		if (state == null) {
			try {
				state=createSession(uid,rcid,attr,data,"");
			} catch (IOException e) {
				e.printStackTrace();
				return new ResultDTO(500,"加载失败");
			}
		}
		if(state.onModifyAttempt()) {
			try {
				state.getExtra().put("charaset",obj.get("gameSetting").getAsString());
				state.getExtra().put("rules",obj.get("coreRule").getAsString());
				state.getExtra().put("limit",obj.get("contextLimit").getAsString());
				state.getExtra().put("brief",obj.get("brief").getAsString());
				state.flush();
			}finally {
				state.onModifyComplete();
			}
		
			
			return new ResultDTO(200,JsonBuilder.object().add("brief", state.getExtra().get("brief")).add("gameSetting", state.getExtra().get("charaset")).add("coreRule", state.getExtra().get("rules")).add("contextLimit",Integer.parseInt(state.getExtra().get("limit"))).add("url", "custom?chatid="+rcid).add("chatid",rcid).end());		
		}
		return new ResultDTO(400,"该对话正在生成中，禁止修改");
	
	}
	/**
	 * WebSocket端点：建立与前端对话页面的WebSocket连接。
	 * <p>
	 * 根据提供的chatid、app和userId进行验证：已存在的对话必须是本人的且未被移除；
	 * 不存在的对话只有在应用对当前用户可见（公开可见或已授权）时才允许创建，
	 * 新建的对话一律是正常的存档状态（不再有"隐藏"的新建对话）。
	 * 成功后将WebSocket会话与{@link WebSocketAISession}绑定。
	 * </p>
	 *
	 * @param req 请求对象
	 * @param res 响应对象
	 */
	@HttpPath("/chatsocket")
	public void webSocket(Request req, Response res) {
		String cid = req.getQuery().get("chatid");
		String app = req.getQuery().get("app");
		String uid = req.getQuery().get("userId");

		boolean isCreate = false;
		try (PreparedStatement ps = database.prepareStatement("SELECT uid,app FROM chats WHERE chatid = ? and status != ?")) {
			ps.setString(1, cid);
			ps.setString(2, STATUS_REMOVED);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next()) {
					if (!rs.getString(1).equals(uid)) {
						res.write(401, "Unauthorized");
						return;
					}
					app = rs.getString(2);
				} else {
					if (isChatAllow(uid, app)) {
						isCreate = true;
					}
					if (!isCreate) {
						res.write(400, "App does not exist");
						return;
					}
				}
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		AISession state = uidsockets.get(cid);
		if (state == null) {
			ApplicationAttributes attr = apps.get(app);
			if (attr == null) {
				res.write(400, "App does not exist");
				return;
			}
			File data = new File(saveData, cid + ".json");
			if (data.exists())
				try {
					state = loadSession(uid, cid, attr, data);
					state.onLoad();
					logger.info("AI " + cid + " Loaded");
				} catch (JsonSyntaxException | IOException e) {
					getLogger().printStackTrace(e);
					logger.info("AI " + cid + " Load Error");
				}
			if (state == null) {
				try{
					if (isCreate) {
						state = createSession(uid, cid, attr, data,STATUS_NORMAL);
						state.flush();
						logger.info("AI " + cid + " Created");
					}else {
						state = loadSession(uid,cid,attr,data);
						state.flush();
						logger.info("AI " + cid + " Created");
					}
				}catch(IOException e) {
					e.printStackTrace();
					res.write(500,"加载失败");
					return;
				}
				
			}
		}
		uidsockets.put(cid, state);
		
		res.suscribeWebsocketEvents((WebsocketEvents) state);
	}
	/**
	 * 根据用户 ID、对话 ID 修改备注。
	 * 若对应的 chats 记录存在且未被移除，且 chatdata 中已有记录则更新，否则插入新记录。
	 */
	@HttpPath("/updateRemark")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO updateRemark(@Query("uid") String userId, @PostQuery("chatid") String chatId,@PostQuery("remark") String newRemark) {
		  // 1. 校验对话是否存在且未被移除
	    String checkChatSql = "SELECT 1 FROM chats WHERE uid = ? AND chatid = ? AND status != '" + STATUS_REMOVED + "'";
	    try (PreparedStatement ps = database.prepareStatement(checkChatSql)) {
	        ps.setString(1, userId);
	        ps.setString(2, chatId);
	        try (ResultSet rs = ps.executeQuery()) {
	            if (!rs.next()) {
	                return new ResultDTO(404,"对话不存在或已被移除");
	            }
	        }
	    } catch (SQLException e) {
			getLogger().printStackTrace(e);
			return new ResultDTO(500,"内部错误");
		}

	    // 2. 检查chatdata中是否已有记录
	    String checkDataSql = "SELECT 1 FROM chatdata WHERE chatid = ?";
	    boolean exists;
	    try (PreparedStatement ps = database.prepareStatement(checkDataSql)) {
	        ps.setString(1, chatId);
	        try (ResultSet rs = ps.executeQuery()) {
	            exists = rs.next();
	        }
	    } catch (SQLException e) {
			getLogger().printStackTrace(e);

			return new ResultDTO(500,"内部错误");
		}

	    // 3. 更新或插入
	    if (exists) {
	        String updateSql = "UPDATE chatdata SET remark = ? WHERE chatid = ?";
	        try (PreparedStatement ps = database.prepareStatement(updateSql)) {
	            ps.setString(1, newRemark);
	            ps.setString(2, chatId);
	            ps.executeUpdate();
	        } catch (SQLException e) {
				getLogger().printStackTrace(e);

				return new ResultDTO(500,"内部错误");
			}
	    } else {
	        String insertSql = "INSERT INTO chatdata (chatid, remark, selected) VALUES (?, ?, ?)";
	        try (PreparedStatement ps = database.prepareStatement(insertSql)) {
	            ps.setString(1, chatId);
	            ps.setString(2, newRemark);
	            ps.setString(3, "");   // selected默认为空字符串
	            ps.executeUpdate();
	        } catch (SQLException e) {
				getLogger().printStackTrace(e);
				return new ResultDTO(500,"内部错误");
			}
	    }
	    String remarkSql = "SELECT remark FROM chatdata WHERE chatid = ?";
	    try (PreparedStatement remarkStmt = database.prepareStatement(remarkSql)) {
	    	remarkStmt.setString(1, chatId);
	        try (ResultSet rs = remarkStmt.executeQuery()) {
	            if (rs.next()) {
	                return new ResultDTO(200,rs.getString(1));
	            }
	        }
	    } catch (SQLException e) {
			getLogger().printStackTrace(e);
			return new ResultDTO(500,"内部错误");
	    }
	    return new ResultDTO(404,"对话不存在或已被移除");
	}

	/**
	 * 根据用户 ID、对话 ID、智能体名称，将该用户该智能体下所有未归档、未移除对话的
	 * selected 设为 0，并将指定对话的 selected 设为 1。若 chatdata 中缺少记录则自动插入。
	 */
	@HttpPath("/updateSelection")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO updateSelected(@Query("uid") String userId,@PostQuery("chatid") String chatId,@PostQuery("appid") String appName) {
		 // 开启事务（保证原子性）
	    try {
			database.setAutoCommit(false);
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
	    try {
	        // 1. 获取该用户该智能体所有进行中的对话ID（归档与移除的存档不参与选中）
	        String selectChatsSql = "SELECT chatid FROM chats WHERE uid = ? AND app = ? AND status = '" + STATUS_NORMAL + "'";
	        java.util.List<String> chatids = new java.util.ArrayList<>();
	        try (PreparedStatement ps = database.prepareStatement(selectChatsSql)) {
	            ps.setString(1, userId);
	            ps.setString(2, appName);
	            try (ResultSet rs = ps.executeQuery()) {
	                while (rs.next()) {
	                    chatids.add(rs.getString("chatid"));
	                }
	            }
	        }

	        // 2. 确保每个对话在chatdata中都有记录，并设置selected=0
	        for (String chatid : chatids) {
	            // 检查是否存在
	            String checkSql = "SELECT 1 FROM chatdata WHERE chatid = ?";
	            boolean exists;
	            try (PreparedStatement ps = database.prepareStatement(checkSql)) {
	                ps.setString(1, chatid);
	                try (ResultSet rs = ps.executeQuery()) {
	                    exists = rs.next();
	                }
	            }
	            if (exists) {
	                String updateSql = "UPDATE chatdata SET selected = '0' WHERE chatid = ?";
	                try (PreparedStatement ps = database.prepareStatement(updateSql)) {
	                    ps.setString(1, chatid);
	                    ps.executeUpdate();
	                }
	            } else {
	                String insertSql = "INSERT INTO chatdata (chatid, remark, selected) VALUES (?, ?, ?)";
	                try (PreparedStatement ps = database.prepareStatement(insertSql)) {
	                    ps.setString(1, chatid);
	                    ps.setString(2, "");   // remark默认空字符串
	                    ps.setString(3, "0");
	                    ps.executeUpdate();
	                }
	            }
	        }
	        if(chatId.isEmpty()) {
	        	 database.commit();
	        	return new ResultDTO(200,"");
	        }
	        // 3. 验证目标对话是否属于上述集合（即进行中、属于该用户与该智能体）
	        if (!chatids.contains(chatId)) {
	        	throw new IllegalStateException("目标对话不存在、已归档或不属于指定智能体");
	        }

	        // 4. 将目标对话的selected设为1
	        String checkTargetSql = "SELECT 1 FROM chatdata WHERE chatid = ?";
	        boolean targetExists;
	        try (PreparedStatement ps = database.prepareStatement(checkTargetSql)) {
	            ps.setString(1, chatId);
	            try (ResultSet rs = ps.executeQuery()) {
	                targetExists = rs.next();
	            }
	        }
	        if (targetExists) {
	            String updateSql = "UPDATE chatdata SET selected = '1' WHERE chatid = ?";
	            try (PreparedStatement ps = database.prepareStatement(updateSql)) {
	                ps.setString(1, chatId);
	                ps.executeUpdate();
	            }
	        } else {
	            String insertSql = "INSERT INTO chatdata (chatid, remark, selected) VALUES (?, ?, ?)";
	            try (PreparedStatement ps = database.prepareStatement(insertSql)) {
	                ps.setString(1, chatId);
	                ps.setString(2, "");
	                ps.setString(3, "1");
	                ps.executeUpdate();
	            }
	        }

	        database.commit();
	    } catch (SQLException e) {
	        try {
				database.rollback();
			} catch (SQLException e1) {
				getLogger().printStackTrace(e1);
			}
	        return new ResultDTO(500,"内部错误");
	    }catch(IllegalStateException ex) {
	    	 try {
					database.rollback();
				} catch (SQLException e1) {
					getLogger().printStackTrace(e1);
				}
		        return new ResultDTO(400,ex.getMessage());
	    	
	    } finally {
	        try {
				database.setAutoCommit(true);
			} catch (SQLException e) {
				getLogger().printStackTrace(e);
			}
	    }
	    return new ResultDTO(200,chatId);
	}

	/**
	 * 查询存档管理页所需的对话详情：应用名称、对话名称、时间、消耗（chats.price 主表字段）、
	 * 备注与"当前存档"选中标记。
	 * <p>
	 * 通过 {@code archived} 参数切换查看进行中或已归档的存档；两种状态都排除已移除的存档。
	 * 结果按时间倒序，方便前端直接渲染。
	 * </p>
	 *
	 * @param uid      用户ID
	 * @param appName  应用ID
	 * @param archived 为 "1" 时返回已归档存档，否则返回进行中的存档
	 * @return JSON数组，每个元素为一个存档
	 * @throws SQLException 查询失败
	 */
	@HttpPath("/queryArchives")
	@Adapter
	@HttpMethod("GET")
	public ResultDTO queryChats(@Query("uid") String uid,@Query("appid") String appName,
		@Query("archived") String archived) throws SQLException {
	    if (appName == null || appName.isEmpty())
	    	return new ResultDTO(400,"缺少应用ID");
	    ApplicationAttributes attr = apps.get(appName);
		if(attr==null)
			return new ResultDTO(400,"应用不存在");
	    String status = "1".equals(archived) || "true".equalsIgnoreCase(archived)
	    	? STATUS_ARCHIVED : STATUS_NORMAL;
	    // price 直接读主表，不再连 price 表
	    String sql = "SELECT c.chatid, c.brief, c.time, c.price, c.status, d.remark, d.selected " +
	                 "FROM chats c " +
	                 "LEFT JOIN chatdata d ON c.chatid = d.chatid " +
	                 "WHERE c.uid = ? AND c.app = ? AND c.status = ? " +
	                 "ORDER BY c.time DESC";

	    JsonArray ja = new JsonArray();
	    try (PreparedStatement ps = database.prepareStatement(sql)) {
	        ps.setString(1, uid);
	        ps.setString(2, appName);
	        ps.setString(3, status);
	        try (ResultSet rs = ps.executeQuery()) {
	            while (rs.next()) {
	                JsonObject obj = new JsonObject();
	                obj.addProperty("chatid", rs.getString("chatid"));
	                obj.addProperty("appname", appName);
	                obj.addProperty("app", appName);
	                obj.addProperty("time", rs.getString("time")); // 时间戳转为字符串
	                String brief = rs.getString("brief");
	                obj.addProperty("name", brief == null ? "新对话" : brief);
	                String price = rs.getString("price");
	                obj.addProperty("price", price == null || price.isEmpty() ? "0.00" : price);
	                obj.addProperty("status", rs.getString("status") == null ? STATUS_NORMAL : rs.getString("status"));
	                obj.addProperty("remark", rs.getString("remark") == null ? "" : rs.getString("remark"));
	                obj.addProperty("selected", rs.getString("selected") == null ? "" : rs.getString("selected"));
	                ja.add(obj);
	            }
	        }
	    }
	    return new ResultDTO(200,ja);
	}

	/**
	 * 释放指定的WebSocket会话，从活跃会话映射中移除。 当WebSocket连接关闭时调用。
	 *
	 * @param painterSession 要释放的会话对象
	 */
	public void markRelease(ChatIdentity painterSession) {
		logger.info("AI " + painterSession.getChatId() + " UnLoaded");
		try {
			painterSession.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		uidsockets.remove(painterSession.getChatId());
		
	}

	@Override
	public SimpleLogger getLogger() {
		return logger;
	}

	@Override
	public String getName() {
		return "聊天";
	}



	/**
	 * 设置用户免费限额覆盖（若freeLimit <= 0则删除覆盖记录，恢复使用默认值）
	 * 
	 * @param userId    用户ID
	 * @param freeLimit 自定义免费限额（>0表示设置，<=0表示删除覆盖）
	 * @throws SQLException 数据库操作异常
	 */
	public synchronized void setFreeLimitOverride(String userId, int freeLimit){
		if (freeLimit > 0) {
			String sql = "INSERT INTO user_free_limit_override (user_id, free_limit) VALUES (?, ?) "
				+ "ON CONFLICT(user_id) DO UPDATE SET free_limit = ?";
			try (PreparedStatement pstmt = database.prepareStatement(sql)) {
				pstmt.setString(1, userId);
				pstmt.setInt(2, freeLimit);
				pstmt.setInt(3, freeLimit);
				pstmt.executeUpdate();
			} catch (SQLException e) {
				getLogger().printStackTrace(e);
			}
		} else {
			String sql = "DELETE FROM user_free_limit_override WHERE user_id = ?";
			try (PreparedStatement pstmt = database.prepareStatement(sql)) {
				pstmt.setString(1, userId);
				pstmt.executeUpdate();
			} catch (SQLException e) {
				getLogger().printStackTrace(e);
			}
		}
	}

	/**
	 * 获取用户的实际免费每日限额（优先使用覆盖表，否则返回全局默认值）
	 * 
	 * @param userId 用户ID
	 * @return 免费每日限额
	 * @throws SQLException 数据库操作异常
	 */
	private int getUserFreeLimit(String userId) {
		String sql = "SELECT free_limit FROM user_free_limit_override WHERE user_id = ?";
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			ResultSet rs = pstmt.executeQuery();
			if (rs.next()) {
				return rs.getInt("free_limit");
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return DEFAULT_FREE_DAILY_LIMIT;
	}

	/**
	 * 增加付费token（系统充值），并写入来源为"充值"的配额增加记录
	 * 
	 * @param userId 用户ID
	 * @param amount 增加数量（必须为正数）
	 * @throws SQLException 数据库操作异常
	 */
	public synchronized void increasePaidTokens(String userId, int amount){
		if (amount <= 0) {
			throw new IllegalArgumentException("增加数量必须为正数");
		}
		addPaidBalance(userId, amount, INCREASE_SOURCE_RECHARGE, null);
	}

	/**
	 * 直接为用户增加付费（补充）额度，并写入配额增加记录
	 * 
	 * @param userId 用户ID
	 * @param amount 增加数量
	 * @param source 增加来源（充值/签到获取/兑换码获取/新用户赠送）
	 * @param detail 增加详情（可为null，如兑换使用的兑换码）
	 * @return true表示写入成功
	 */
	private boolean addPaidBalance(String userId, int amount, String source, String detail) {
		String sql = "INSERT INTO user_paid_balance (user_id, balance) VALUES (?, ?) "
			+ "ON CONFLICT(user_id) DO UPDATE SET balance = balance + ?";
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.setInt(2, amount);
			pstmt.setInt(3, amount);
			pstmt.executeUpdate();
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
			return false;
		}
		// 增加成功后写入配额增加记录
		recordQuotaIncrease(userId, source, amount, detail);
		return true;
	}

	/**
	 * 写入一条配额增加记录
	 * 
	 * @param userId 用户ID
	 * @param source 增加来源（充值/签到获取/兑换码获取/新用户赠送）
	 * @param amount 增加数量（小于等于0时不记录）
	 * @param detail 增加详情（可为null）
	 */
	private void recordQuotaIncrease(String userId, String source, int amount, String detail) {
		if (amount <= 0 || userId == null || userId.trim().isEmpty() || source == null) {
			return;
		}
		String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
		String sql = "INSERT INTO quota_increase_record (user_id, source, amount, detail, date) VALUES (?, ?, ?, ?, ?)";
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.setString(2, source);
			pstmt.setInt(3, amount);
			pstmt.setString(4, detail == null ? "" : detail);
			pstmt.setString(5, date);
			pstmt.executeUpdate();
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
	}

	/**
	 * 判断用户是否已存在指定来源的配额增加记录
	 * 
	 * @param userId 用户ID
	 * @param source 增加来源
	 * @return true表示已存在
	 */
	private boolean hasQuotaIncrease(String userId, String source) {
		if (userId == null || source == null) {
			return false;
		}
		String sql = "SELECT 1 FROM quota_increase_record WHERE user_id = ? AND source = ? LIMIT 1";
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.setString(2, source);
			try (ResultSet rs = pstmt.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return false;
	}

	/**
	 * 获取用户剩余token（免费+付费）
	 * 
	 * @param userId 用户ID
	 * @return TokenRemaining对象，包含免费总额度、付费剩余和今日免费已使用量，
	 *         以及新用户福利是否已领取（未领取时前端显示"新用户领取"入口，领取后不再显示）
	 * @throws SQLException 数据库操作异常
	 */
	@HttpMethod("GET")
	@HttpPath("/useToday")
	@Adapter
	public ResultDTO getRemainingTokens(@Query("uid")String userId) {
		// 获取当天已使用的免费token和用户免费限额
		String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
		int freeUsed = getDailyFreeUsed(userId, today);
		int freeLimit = getUserFreeLimit(userId);
		int paidUsed = getDailyPaidUsed(userId);
		// 获取付费余额
		int paidRemaining = getPaidBalance(userId);
		// 今日是否已签到
		boolean checkedIn = hasCheckedIn(userId, today);
		// 新用户福利是否已被领取（领取过则前端不再显示领取入口）
		boolean newUserClaimed = hasQuotaIncrease(userId, INCREASE_SOURCE_NEW_USER);

		return new ResultDTO(200,JsonBuilder.object().add("freeTotal", freeLimit).add("paidRemaining", paidRemaining)
				.add("freeCost", freeUsed).add("paidCost", paidUsed)
				.add("checkedIn", checkedIn).add("checkinReward", DAILY_CHECKIN_REWARD)
				.add("newUserClaimed", newUserClaimed).add("newUserGift", NEW_USER_GIFT));
	}

	/**
	 * 领取新用户福利：每个用户只能领取一次，成功后获得固定数量的补充额度（{@link #NEW_USER_GIFT}）。
	 * <p>
	 * 以配额增加记录中是否已存在"新用户赠送"记录作为是否已领取的标记，领取后前端不再显示该入口。
	 * </p>
	 * 
	 * @param userId 用户ID
	 * @return 领取结果，成功时返回提示信息
	 */
	@HttpMethod("GET")
	@HttpPath("/claimNewUserGift")
	@Adapter
	public ResultDTO claimNewUserGift(@Query("uid")String userId) {
		if (userId == null || userId.trim().isEmpty()) {
			return new ResultDTO(400, "缺少用户ID");
		}
		synchronized (lock) {
			// 1. 校验是否已经领取过
			if (hasQuotaIncrease(userId, INCREASE_SOURCE_NEW_USER)) {
				return new ResultDTO(400, "新用户福利已领取");
			}
			// 2. 发放补充额度并写入来源为"新用户赠送"的配额增加记录
			if (!addPaidBalance(userId, NEW_USER_GIFT, INCREASE_SOURCE_NEW_USER, null)) {
				return new ResultDTO(500, "领取失败，系统错误，请稍后再试");
			}
			return new ResultDTO(200, "领取成功，获得 " + NEW_USER_GIFT + " 补充额度");
		}
	}

	/**
	 * 每日签到：每个用户每天只能签到一次，成功后获得固定数量的补充额度。
	 *
	 * @param userId 用户ID
	 * @return 签到结果，成功时返回提示信息
	 */
	@HttpMethod("GET")
	@HttpPath("/checkin")
	@Adapter
	public ResultDTO dailyCheckIn(@Query("uid")String userId) {
		if (userId == null || userId.trim().isEmpty()) {
			return new ResultDTO(400, "缺少用户ID");
		}
		String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
		synchronized (lock) {
			// 1. 校验今日是否已经签到
			if (hasCheckedIn(userId, today)) {
				return new ResultDTO(400, "今日已签到，明天再来吧");
			}
			// 2. 写入签到记录，主键冲突（并发下已被签到）则忽略
			String insertCheckin = "INSERT OR IGNORE INTO daily_checkin (user_id, date, amount) VALUES (?, ?, ?)";
			try (PreparedStatement pstmt = database.prepareStatement(insertCheckin)) {
				pstmt.setString(1, userId);
				pstmt.setString(2, today);
				pstmt.setInt(3, DAILY_CHECKIN_REWARD);
				if (pstmt.executeUpdate() != 1) {
					return new ResultDTO(400, "今日已签到，明天再来吧");
				}
			} catch (SQLException e) {
				getLogger().printStackTrace(e);
				return new ResultDTO(500, "签到失败，系统错误，请稍后再试");
			}
			// 3. 发放补充额度（记录来源为"签到获取"），发放失败则撤销签到记录，保证当天还能重试
			if (!addPaidBalance(userId, DAILY_CHECKIN_REWARD, INCREASE_SOURCE_CHECKIN, null)) {
				removeCheckinRecord(userId, today);
				return new ResultDTO(500, "签到失败，系统错误，请稍后再试");
			}
			return new ResultDTO(200, "签到成功，获得 " + DAILY_CHECKIN_REWARD + " 补充额度");
		}
	}

	/**
	 * 删除签到记录（用于发放额度失败时回滚）
	 *
	 * @param userId 用户ID
	 * @param date   日期（格式：yyyy-MM-dd）
	 */
	private void removeCheckinRecord(String userId, String date) {
		String sql = "DELETE FROM daily_checkin WHERE user_id = ? AND date = ?";
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.setString(2, date);
			pstmt.executeUpdate();
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
	}

	/**
	 * 检查用户指定日期是否已经签到
	 *
	 * @param userId 用户ID
	 * @param date   日期（格式：yyyy-MM-dd）
	 * @return true表示已签到
	 */
	private boolean hasCheckedIn(String userId, String date) {
		if (userId == null) {
			return false;
		}
		String sql = "SELECT 1 FROM daily_checkin WHERE user_id = ? AND date = ?";
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.setString(2, date);
			try (ResultSet rs = pstmt.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return false;
	}

	/**
	 * 检查用户是否还有任何token剩余（免费或付费）
	 * 
	 * @param userId 用户ID
	 * @return true表示还有剩余，false表示已无可用token
	 * @throws SQLException 数据库操作异常
	 */
	public synchronized boolean hasAnyTokenRemaining(String userId) {
		String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
		int freeUsed = getDailyFreeUsed(userId, today);
		int freeLimit = getUserFreeLimit(userId);
		int freeRemaining = freeLimit - freeUsed;
		int paidRemaining = getPaidBalance(userId);
		return freeRemaining + paidRemaining > 0;
	}
	public synchronized boolean hasAnyPaidTokenRemaining(String userId) {
		int paidRemaining = getPaidBalance(userId);
		return  paidRemaining > 2000;
	}
	/**
	 * 消耗token（优先消耗免费，不足时消耗付费）
	 * 
	 * @param userId 用户ID
	 * @param tokens 需要消耗的token数量（必须为正数）
	 * @return true表示消耗成功，false表示总余额不足
	 * @throws SQLException 数据库操作异常
	 */
	public synchronized void consumeTokens(String userId, int tokens) {
		consumeTokens(userId, tokens, null, null);
	}

	/**
	 * 消耗token（优先消耗免费，不足时消耗付费），并记录补充配额消费明细
	 * 
	 * @param userId    用户ID
	 * @param tokens    需要消耗的token数量（必须为正数）
	 * @param chatId    聊天ID（可为null）
	 * @param usageType 使用量类型（可为null）
	 * @throws SQLException 数据库操作异常
	 */
	public synchronized void consumeTokens(String userId, int tokens, String chatId, String usageType) {
		if (tokens <= 0) {
			throw new IllegalArgumentException("消耗数量必须为正数");
		}

		// 开始事务
		try {
			String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

			// 获取当天已使用免费token、用户免费限额和付费余额
			int freeUsed = getDailyFreeUsed(userId, today);
			int freeLimit = getUserFreeLimit(userId);
			int freeRemaining = Math.max(0, freeLimit - freeUsed);
			int paidBalance = getPaidBalance(userId);

			// 计算实际可消耗的免费和付费数量
			int freeConsume = Math.min(tokens, freeRemaining);
			int paidConsume = tokens - freeConsume;

			if(paidConsume>paidBalance) {
				int reminder=paidConsume-paidBalance;
				paidConsume=paidBalance;
				freeConsume+=reminder;
			}

			// 更新daily_usage表（免费消耗）
			if (freeConsume > 0) {
				String upsertFreeSql = "INSERT INTO daily_usage (user_id, date, free_used, paid_used) VALUES (?, ?, ?, 0)"
					+ " ON CONFLICT(user_id, date) DO UPDATE SET free_used = free_used + ?";
				try (PreparedStatement pstmt = database.prepareStatement(upsertFreeSql)) {
					pstmt.setString(1, userId);
					pstmt.setString(2, today);
					pstmt.setInt(3, freeConsume);
					pstmt.setInt(4, freeConsume);
					pstmt.executeUpdate();
				} catch (SQLException e) {
					getLogger().printStackTrace(e);
				}
			}

			// 更新daily_usage表（付费消耗）
			if (paidConsume > 0) {
				String upsertPaidSql = "INSERT INTO daily_usage (user_id, date, free_used, paid_used) VALUES (?, ?, 0, ?)"
					+ " ON CONFLICT(user_id, date) DO UPDATE SET paid_used = paid_used + ?";
				try (PreparedStatement pstmt = database.prepareStatement(upsertPaidSql)) {
					pstmt.setString(1, userId);
					pstmt.setString(2, today);
					pstmt.setInt(3, paidConsume);
					pstmt.setInt(4, paidConsume);
					pstmt.executeUpdate();
				} catch (SQLException e) {
					getLogger().printStackTrace(e);
				}
			}

			// 更新付费余额
			if (paidConsume > 0) {
				String updatePaidSql = "UPDATE user_paid_balance SET balance = balance - ? WHERE user_id = ?";
				try (PreparedStatement pstmt = database.prepareStatement(updatePaidSql)) {
					pstmt.setInt(1, paidConsume);
					pstmt.setString(2, userId);
					pstmt.executeUpdate();
				}
			}

			// 记录补充配额消费明细（仅记录实际消耗补充配额的部分）
			recordPaidUsage(userId, chatId, usageType, paidConsume);
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
	}
	public synchronized void consumePaidTokens(String userId, int tokens) {
		consumePaidTokens(userId, tokens, null, null);
	}

	/**
	 * 消耗补充（付费）配额，并记录补充配额消费明细
	 * 
	 * @param userId    用户ID
	 * @param tokens    需要消耗的token数量（必须为正数）
	 * @param chatId    聊天ID（可为null）
	 * @param usageType 使用量类型（可为null）
	 * @throws SQLException 数据库操作异常
	 */
	public synchronized void consumePaidTokens(String userId, int tokens, String chatId, String usageType) {
		if (tokens <= 0) {
			throw new IllegalArgumentException("消耗数量必须为正数");
		}

		// 开始事务
		try {
			String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
			// 更新daily_usage表（付费消耗）
			if (tokens > 0) {
				String upsertPaidSql = "INSERT INTO daily_usage (user_id, date, free_used, paid_used) VALUES (?, ?, 0, ?)"
					+ " ON CONFLICT(user_id, date) DO UPDATE SET paid_used = paid_used + ?";
				try (PreparedStatement pstmt = database.prepareStatement(upsertPaidSql)) {
					pstmt.setString(1, userId);
					pstmt.setString(2, today);
					pstmt.setInt(3, tokens);
					pstmt.setInt(4, tokens);
					pstmt.executeUpdate();
				} catch (SQLException e) {
					getLogger().printStackTrace(e);
				}
				String updatePaidSql = "UPDATE user_paid_balance SET balance = balance - ? WHERE user_id = ?";
				try (PreparedStatement pstmt = database.prepareStatement(updatePaidSql)) {
					pstmt.setInt(1, tokens);
					pstmt.setString(2, userId);
					pstmt.executeUpdate();
				}
				// 记录补充配额消费明细
				recordPaidUsage(userId, chatId, usageType, tokens);
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
	}

	/**
	 * 写入一条补充配额消费明细记录
	 * 
	 * @param userId    用户ID
	 * @param chatId    聊天ID
	 * @param usageType 使用量类型
	 * @param tokens    消耗的补充配额（小于等于0时不记录）
	 */
	private void recordPaidUsage(String userId, String chatId, String usageType, int tokens) {
		if (tokens <= 0 || userId == null) {
			return;
		}
		String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
		String sql = "INSERT INTO paid_usage_record (user_id, chat_id, usage_type, date, tokens) VALUES (?, ?, ?, ?, ?)";
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.setString(2, chatId);
			pstmt.setString(3, usageType == null || usageType.trim().isEmpty() ? UsageIntf.DEFAULT_USAGE_NAME : usageType);
			pstmt.setString(4, date);
			pstmt.setInt(5, tokens);
			pstmt.executeUpdate();
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
	}
	/**
	 * 查询指定日期范围内每天的免费和付费token用量
	 * 
	 * @param userId    用户ID
	 * @param startDate 起始日期（格式：yyyy-MM-dd）
	 * @param endDate   结束日期（格式：yyyy-MM-dd）
	 * @return 每日用量列表，包含日期、免费用量、付费用量
	 * @throws SQLException 数据库操作异常
	 */
	@HttpMethod("GET")
	@HttpPath("/useHistory")
	@Adapter
	public ResultDTO getDailyUsage(@Query("uid")String userId,@Query("start")String startDate,@Query("end")String endDate){
		LocalDate start = LocalDate.parse(startDate);
		LocalDate end = LocalDate.parse(endDate);
		if (start.isAfter(end)) {
			return new ResultDTO(500,"起始日期不能晚于结束日期");
		}

		// 查询数据库中已有的记录
		String sql = "SELECT date, free_used, paid_used FROM daily_usage WHERE user_id = ? AND date BETWEEN ? AND ?";
		JsonArray ja=new JsonArray();
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.setString(2, startDate);
			pstmt.setString(3, endDate);
			ResultSet rs = pstmt.executeQuery();
			// 将查询结果存入临时map
			while (rs.next()) {
				String date = rs.getString("date");
				int freeUsed = rs.getInt("free_used");
				int paidUsed = rs.getInt("paid_used");
				ja.add(JsonBuilder.object().add("date", date).add("freeUsed", freeUsed).add("paidUsed", paidUsed).end());
			}
			// 填充所有日期（没有记录的用量为0）
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
			return new ResultDTO(500,"内部错误");
		}
		return new ResultDTO(200,ja);
	}

	/**
	 * 按对话聚合查询补充配额消费记录，用于前端饼图展示。
	 * <p>
	 * 返回数组元素形如：
	 * {@code {"chatId":"...","name":"对话名称","tokens":1234,"count":3,"types":[{"type":"豆包 Seed2 专业","tokens":100}]}}
	 * </p>
	 * 
	 * @param userId    用户ID
	 * @param startDate 起始日期（格式：yyyy-MM-dd，缺省为当月1日）
	 * @param endDate   结束日期（格式：yyyy-MM-dd，缺省为当天）
	 * @return 按对话聚合的消费记录，按消耗量从高到低排序
	 */
	@HttpMethod("GET")
	@HttpPath("/paidUsageRecords")
	@Adapter
	public ResultDTO getPaidUsageRecords(@Query("uid")String userId,@Query("start")String startDate,@Query("end")String endDate){
		if (userId == null || userId.trim().isEmpty()) {
			return new ResultDTO(400,"缺少用户ID");
		}
		LocalDate today = LocalDate.now();
		LocalDate start = parseDateOrDefault(startDate, today.withDayOfMonth(1));
		LocalDate end = parseDateOrDefault(endDate, today);
		if (start == null || end == null) {
			return new ResultDTO(400,"日期格式错误，应为 yyyy-MM-dd");
		}
		if (start.isAfter(end)) {
			return new ResultDTO(400,"起始日期不能晚于结束日期");
		}
		String s = start.format(DateTimeFormatter.ISO_LOCAL_DATE);
		String e = end.format(DateTimeFormatter.ISO_LOCAL_DATE);

		// 对话名称映射：chatid -> brief
		Map<String,String> chatNames = new HashMap<>();
		String nameSql = "SELECT chatid, brief FROM chats WHERE uid = ?";
		try (PreparedStatement pstmt = database.prepareStatement(nameSql)) {
			pstmt.setString(1, userId);
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					chatNames.put(rs.getString("chatid"), rs.getString("brief"));
				}
			}
		} catch (SQLException ex) {
			getLogger().printStackTrace(ex);
			return new ResultDTO(500,"内部错误");
		}

		// 按对话 + 使用量类型聚合
		Map<String,JsonObject> byChat = new LinkedHashMap<>();
		String sql = "SELECT chat_id, usage_type, SUM(tokens) AS tokens, COUNT(*) AS cnt FROM paid_usage_record"
			+ " WHERE user_id = ? AND date BETWEEN ? AND ? GROUP BY chat_id, usage_type";
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.setString(2, s);
			pstmt.setString(3, e);
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					String chatId = rs.getString("chat_id");
					String type = rs.getString("usage_type");
					int tokens = rs.getInt("tokens");
					int count = rs.getInt("cnt");
					String key = chatId == null ? "" : chatId;
					JsonObject entry = byChat.get(key);
					if (entry == null) {
						entry = new JsonObject();
						entry.addProperty("chatId", chatId);
						entry.addProperty("name", resolveChatName(chatId, chatNames));
						entry.addProperty("tokens", 0);
						entry.addProperty("count", 0);
						entry.add("types", new JsonArray());
						byChat.put(key, entry);
					}
					entry.addProperty("tokens", entry.get("tokens").getAsInt() + tokens);
					entry.addProperty("count", entry.get("count").getAsInt() + count);
					JsonObject typeObj = new JsonObject();
					typeObj.addProperty("type", type == null || type.isEmpty() ? UsageIntf.DEFAULT_USAGE_NAME : type);
					typeObj.addProperty("tokens", tokens);
					entry.getAsJsonArray("types").add(typeObj);
				}
			}
		} catch (SQLException ex) {
			getLogger().printStackTrace(ex);
			return new ResultDTO(500,"内部错误");
		}

		// 按消耗量从高到低排序
		List<JsonObject> list = new ArrayList<>(byChat.values());
		list.sort((a,b) -> Integer.compare(b.get("tokens").getAsInt(), a.get("tokens").getAsInt()));
		JsonArray ja = new JsonArray();
		for (JsonObject o : list) {
			ja.add(o);
		}
		return new ResultDTO(200,ja);
	}

	/**
	 * 按来源聚合查询指定日期范围内的配额增加记录（充值/签到获取/兑换码获取/新用户赠送），用于前端展示。
	 * <p>
	 * 返回对象形如：
	 * {@code {"total":200000,"sources":[{"source":"签到获取","tokens":50000,"count":1}],
	 * "records":[{"date":"2026-01-01","source":"签到获取","tokens":50000,"detail":""}]}}
	 * </p>
	 * 
	 * @param userId    用户ID
	 * @param startDate 起始日期（格式：yyyy-MM-dd，缺省为当月1日）
	 * @param endDate   结束日期（格式：yyyy-MM-dd，缺省为当天）
	 * @return 配额增加记录，来源按增加量从高到低排序，明细按日期从新到旧排序
	 */
	@HttpMethod("GET")
	@HttpPath("/quotaIncreaseRecords")
	@Adapter
	public ResultDTO getQuotaIncreaseRecords(@Query("uid")String userId,@Query("start")String startDate,@Query("end")String endDate){
		if (userId == null || userId.trim().isEmpty()) {
			return new ResultDTO(400,"缺少用户ID");
		}
		LocalDate today = LocalDate.now();
		LocalDate start = parseDateOrDefault(startDate, today.withDayOfMonth(1));
		LocalDate end = parseDateOrDefault(endDate, today);
		if (start == null || end == null) {
			return new ResultDTO(400,"日期格式错误，应为 yyyy-MM-dd");
		}
		if (start.isAfter(end)) {
			return new ResultDTO(400,"起始日期不能晚于结束日期");
		}
		String s = start.format(DateTimeFormatter.ISO_LOCAL_DATE);
		String e = end.format(DateTimeFormatter.ISO_LOCAL_DATE);

		// 按来源聚合，同时收集明细记录
		Map<String,JsonObject> bySource = new LinkedHashMap<>();
		JsonArray records = new JsonArray();
		int total = 0;
		String sql = "SELECT source, amount, detail, date FROM quota_increase_record"
			+ " WHERE user_id = ? AND date BETWEEN ? AND ? ORDER BY date DESC, id DESC";
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.setString(2, s);
			pstmt.setString(3, e);
			try (ResultSet rs = pstmt.executeQuery()) {
				while (rs.next()) {
					String source = rs.getString("source");
					int amount = rs.getInt("amount");
					String detail = rs.getString("detail");
					String date = rs.getString("date");
					total += amount;

					JsonObject entry = bySource.get(source);
					if (entry == null) {
						entry = new JsonObject();
						entry.addProperty("source", source);
						entry.addProperty("tokens", 0);
						entry.addProperty("count", 0);
						bySource.put(source, entry);
					}
					entry.addProperty("tokens", entry.get("tokens").getAsInt() + amount);
					entry.addProperty("count", entry.get("count").getAsInt() + 1);

					JsonObject rec = new JsonObject();
					rec.addProperty("date", date);
					rec.addProperty("source", source);
					rec.addProperty("tokens", amount);
					rec.addProperty("detail", detail == null ? "" : detail);
					records.add(rec);
				}
			}
		} catch (SQLException ex) {
			getLogger().printStackTrace(ex);
			return new ResultDTO(500,"内部错误");
		}

		// 来源按增加量从高到低排序
		List<JsonObject> list = new ArrayList<>(bySource.values());
		list.sort((a,b) -> Integer.compare(b.get("tokens").getAsInt(), a.get("tokens").getAsInt()));
		JsonArray sources = new JsonArray();
		for (JsonObject o : list) {
			sources.add(o);
		}
		JsonObject result = new JsonObject();
		result.addProperty("total", total);
		result.add("sources", sources);
		result.add("records", records);
		return new ResultDTO(200,result);
	}

	/**
	 * 解析日期字符串，为空时返回默认日期
	 * 
	 * @param text    日期字符串（yyyy-MM-dd）
	 * @param defaultDate 默认日期
	 * @return 解析结果，格式错误返回null
	 */
	private static LocalDate parseDateOrDefault(String text, LocalDate defaultDate) {
		if (text == null || text.trim().isEmpty()) {
			return defaultDate;
		}
		try {
			return LocalDate.parse(text.trim());
		} catch (Exception ex) {
			return null;
		}
	}

	/**
	 * 解析对话展示名称：优先使用对话简述，其次按记录是否存在给出兜底名称
	 * 
	 * @param chatId    对话ID
	 * @param chatNames 对话名称映射
	 * @return 展示名称
	 */
	private static String resolveChatName(String chatId, Map<String,String> chatNames) {
		if (chatId == null || chatId.isEmpty()) {
			return "其他消费";
		}
		if (chatNames.containsKey(chatId)) {
			String brief = chatNames.get(chatId);
			if (brief != null && !brief.trim().isEmpty()) {
				return brief;
			}
			return "未命名对话";
		}
		return "已删除对话";
	}

	// ---------- 辅助方法 ----------

	/**
	 * 获取指定用户指定日期的免费已使用量
	 */
	private int getDailyFreeUsed(String userId, String date) {
		String sql = "SELECT free_used FROM daily_usage WHERE user_id = ? AND date = ?";
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.setString(2, date);
			ResultSet rs = pstmt.executeQuery();
			if (rs.next()) {
				return rs.getInt("free_used");
			}
			return 0;
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return 0;
	}
	private int getDailyPaidUsed(String userId) {
		String date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
		String sql = "SELECT paid_used FROM daily_usage WHERE user_id = ? AND date = ?";
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.setString(2, date);
			ResultSet rs = pstmt.executeQuery();
			if (rs.next()) {
				return rs.getInt("paid_used");
			}
			return 0;
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return 0;
	}
	/**
	 * 获取用户付费余额
	 */
	private int getPaidBalance(String userId){
		String sql = "SELECT balance FROM user_paid_balance WHERE user_id = ?";
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			ResultSet rs = pstmt.executeQuery();
			if (rs.next()) {
				return rs.getInt("balance");
			}
			return 0;
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return 0;
	}

    /**
     * 生成10位随机CDK码（大写字母和数字）
     */
    private static String generateCDKCode() {
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }
        return sb.toString();
    }

    /**
     * 确保CDK码唯一，如果冲突则重新生成
     */
    private String generateUniqueCDKCode() throws SQLException {
        String code;
        boolean exists;
        do {
            code = generateCDKCode();
            exists = checkCDKExists(code);
        } while (exists);
        return code;
    }

    /**
     * 检查CDK码是否已存在
     */
    private boolean checkCDKExists(String code) throws SQLException {
        String sql = "SELECT 1 FROM cdk WHERE code = ?";
        try (PreparedStatement pstmt = database.prepareStatement(sql)) {
            pstmt.setString(1, code);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        }
    }
    Object lock=new Object();
    /**
     * 创建CDK
     * @param cost 每次兑换的费用（整数）
     * @param uses 可使用次数
     * @return 生成的唯一CDK码
     * @throws SQLException 数据库操作异常
     */
    @HttpMethod("GET")
	@HttpPath("/gencdk")
	@Adapter
    public ResultDTO createCDK(@Query("cost")String cost,@Query("uses")String uses,@Query("token")String token) {
    	if(!System.getProperty("cdk_secret").equals(token))
    		return new ResultDTO(404);
        synchronized (lock) {
            // 生成唯一CDK码
            int ncost=Integer.parseInt(cost);
            int nuses=Integer.parseInt(uses);
        	
            String code = null;
            // 插入数据库
            String insertSQL = "INSERT INTO cdk (code, cost, remaining_uses) VALUES (?, ?, ?)";
            try (PreparedStatement pstmt = database.prepareStatement(insertSQL)) {
            	code=generateUniqueCDKCode();
                pstmt.setString(1, code);
                pstmt.setInt(2, ncost);
                pstmt.setInt(3, nuses);
                pstmt.executeUpdate();
            } catch (SQLException e) {
				getLogger().printStackTrace(e);
				 return new ResultDTO(500,e.getMessage());
			}

            return new ResultDTO(200,code);
        }
    }

    /**
     * 兑换CDK
     * @param code CDK码
     * @param userId 用户ID
     * @return 兑换成功返回的费用
     * @throws SQLException 数据库操作异常
     * @throws IllegalArgumentException 当CDK无效、已耗尽或用户已兑换过时抛出
     */
	@HttpMethod("GET")
	@HttpPath("/redeem")
	@Adapter
    public ResultDTO redeemCDK(@Query("cdk")String code,@Query("uid")String userId) {
        synchronized (lock) {
            // 1. 检查CDK是否存在且剩余次数>0
            String selectCDK = "SELECT cost, remaining_uses FROM cdk WHERE code = ?";
            int cost = -1;
            int remainingUses = -1;
            try (PreparedStatement pstmt = database.prepareStatement(selectCDK)) {
                pstmt.setString(1, code);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) {
                        return new ResultDTO(400,"CDK不存在: " + code);
                    }
                    cost = rs.getInt("cost");
                    remainingUses = rs.getInt("remaining_uses");
                    if (remainingUses <= 0) {
                        return new ResultDTO(400,"CDK已耗尽: " + code);
                    }
                }
            } catch (SQLException e3) {
				getLogger().printStackTrace(e3);
				return new ResultDTO(500,"CDK兑换失败，系统错误，请稍后再试");
			}

            // 2. 检查用户是否已经兑换过此CDK
            String checkLog = "SELECT 1 FROM redemption_log WHERE cdk_code = ? AND user_id = ?";
            try (PreparedStatement pstmt = database.prepareStatement(checkLog)) {
                pstmt.setString(1, code);
                pstmt.setString(2, userId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return new ResultDTO(400,"已兑换过该CDK");
                    }
                }
            } catch (SQLException e2) {
				getLogger().printStackTrace(e2);
				return new ResultDTO(500,"CDK兑换失败，系统错误，请稍后再试");
			}

            // 3. 扣减次数（更新remaining_uses）
            String updateCDK = "UPDATE cdk SET remaining_uses = remaining_uses - 1 WHERE code = ? and remaining_uses>0";
            int rowsUpdated=0;
            try (PreparedStatement pstmt = database.prepareStatement(updateCDK)) {
                pstmt.setString(1, code);
                rowsUpdated = pstmt.executeUpdate();
            } catch (SQLException e1) {
				getLogger().printStackTrace(e1);
			}
            if (rowsUpdated != 1) {
            	return new ResultDTO(400,"CDK已失效");
            }
            if(cost>0) {
            	// 记录来源为"兑换码获取"，详情写入兑换码本身
            	this.addPaidBalance(userId, cost, INCREASE_SOURCE_CDK, code);
            }else {
            	return new ResultDTO(400,"CDK已耗尽: " + code);
            }
            // 4. 插入兑换日志
            String insertLog = "INSERT INTO redemption_log (cdk_code, user_id, cost) VALUES (?, ?, ?)";
            try (PreparedStatement pstmt = database.prepareStatement(insertLog)) {
                pstmt.setString(1, code);
                pstmt.setString(2, userId);
                pstmt.setInt(3, cost);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                getLogger().printStackTrace(e);
                
            }

            return new ResultDTO(200,"CDK兑换成功");
        }
    }

	/**
	 * 处理控制台命令。 支持命令：
	 * <ul>
	 * <li>reload - 重新加载AI配置</li>
	 * </ul>
	 *
	 * @param msg    命令字符串
	 * @param sender 命令发送者
	 * @return 如果命令被处理返回true
	 */
	@Override
	public boolean dispatchCommand(String msg, CommandSender sender) {
		if ("reload".equals(msg)) {
			reload();
			return true;
		}
		return false;
	}

	@Override
	public String getHelp() {
		return "reload:重载AI配置";
	}

	@Override
	public String getCommandLabel() {
		return "AiChat";
	}
}