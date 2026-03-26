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
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.apps.AIApplication;
import com.khjxiaogu.aiwuxia.apps.AIApplicationRegistry;
import com.khjxiaogu.aiwuxia.apps.AIWuxiaMain;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.state.history.MemoryHistory;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.session.WebSocketAISession;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder.JsonObjectBuilder;
import com.khjxiaogu.aiwuxia.voice.LocalVoiceModel;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;
import com.khjxiaogu.aiwuxia.voice.VolcanoVoiceApi;
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
import com.khjxiaogu.webserver.wrappers.ResultDTO;
import com.khjxiaogu.webserver.wrappers.inadapters.DataIn;
import com.khjxiaogu.webserver.wrappers.inadapters.FullPathIn;

import io.netty.handler.codec.http.HttpHeaderNames;

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
	protected Map<String, WebSocketAISession> uidsockets = new ConcurrentHashMap<>();

	/** 可用的AI应用映射，键为应用ID（如"wuxia"），值为对应的AI应用实例 */
	private Map<String, AIApplication> apps = new HashMap<>();
	
	private Map<String, String> urls = new HashMap<>();

	/** 公测中的AI应用ID集合，对于这些应用，每个用户只能创建一个对话实例 */
	private Set<String> trial = new HashSet<>();

	/** 服务数据根目录 */
	File parent;

	/** 对话数据保存目录（存放每个对话的JSON文件） */
	File saveData;

	/** 静态资源文件服务（用于提供图片、CSS等） */
	private FilePageService resource;

	/** 语音资源文件服务（用于提供音频文件） */
	private FilePageService voice;
	// 默认每个用户每天的免费token额度
	private static final int DEFAULT_FREE_DAILY_LIMIT = 500_000;
	/**
	 * 构造AI对话服务，初始化数据库连接、文件目录和AI应用。
	 *
	 * @param path 服务数据根目录
	 * @throws SQLException           如果数据库初始化失败
	 * @throws ClassNotFoundException 如果找不到SQLite JDBC驱动
	 */
	public AIChatService(File path) throws SQLException, ClassNotFoundException {
		try {
			Class.forName("org.sqlite.JDBC");
		} catch (ClassNotFoundException e) {
			logger.severe("SQLITE链接失败！");
			throw e;
		}
		logger.info("正在链接SQLITE信息数据库...");
		try {
			database = DriverManager.getConnection("jdbc:sqlite:" + new File(path, "messages.db"));
			try (Statement stmt = database.createStatement()) {

				/** 创建对话记录表的SQL语句 */
				String createMsg = "CREATE TABLE IF NOT EXISTS chats (" +
					"uid	 TEXT(40)   NOT NULL, " + // 用户ID
					"brief TEXT, " + // 对话简述
					"app TEXT NOT NULL, " + // 智能体名称
					"chatid TEXT NOT NULL, " + // 对话ID
					"time	BIGINT(64), " + // 时间戳（毫秒）
					"status TEXT, " + // 状态
					"attribute TEXT DEFAULT ''" + // 附加信息（如隐藏标记）
					");";

				/** 创建权限表的SQL语句 */
				String createPerm = "CREATE TABLE IF NOT EXISTS permission (" +
					"uid	 TEXT(40)   NOT NULL, " + // 用户ID
					"app TEXT NOT NULL, " + // 智能体
					"state TEXT NOT NULL" + // 许可状态（如'1'表示允许）
					");";

				/** 创建费用记录表的SQL语句 */
				String createPrice = "CREATE TABLE IF NOT EXISTS price (" +
					"chatid TEXT NOT NULL, " + // 对话ID
					"price TEXT, " + // 价格字符串
					"time	BIGINT(32) " + // 时间戳（毫秒）
					");";
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
				stmt.execute(createMsg);
				stmt.execute(createPerm);
				stmt.execute(createPrice);
				stmt.execute(createRemark);
				stmt.execute(createPaidBalanceTable);
				stmt.execute(createDailyUsageTable);
				stmt.execute(createFreeLimitOverrideTable);
				stmt.execute(createCDKTable);
				stmt.execute(createLogTable);
			}
		} catch (SQLException e) {
			logger.severe("信息数据库初始化失败！");
			throw e;
		}
		LLMConnector.initDefault();
		VoiceModelHandler.model = new VolcanoVoiceApi();
		parent = path;
		saveData = new File(path, "saveData");
		saveData.mkdirs();
		resource = new FilePageService(new File(parent, "resource"));
		voice = new FilePageService(new File(parent, "voice"));
		reload();
	}

	/**
	 * 重新加载所有AI应用配置。 扫描根目录下的所有子目录，根据meta.json元数据动态加载AI应用（如talk类型、trpg类型），
	 * 并更新apps映射和trial集合。
	 */
	public void reload() {
		apps.clear();
		trial.clear();
		// apps.put("fengyitalk", new AICharaTalkMain(parent,"fengyitalk","姚枫怡"));
		for (File fn : new File(parent, "apps").listFiles(File::isDirectory)) {
			try {
				String name = fn.getName();
				File metaFile = new File(fn, "meta.json");
				boolean isSucceed = false;
				if (metaFile.exists()) {
					JsonObject meta = JsonParser.parseString(FileUtil.readString(metaFile)).getAsJsonObject();
					if (meta.has("name") && (!meta.has("enabled") || meta.get("enabled").getAsBoolean())) {
						getLogger().info("正在加载AI：" + name);

						try {
							apps.put(name, AIApplicationRegistry.createInstance(parent, fn, meta));
							if (meta.has("trial") && meta.get("trial").getAsBoolean())
								trial.add(name);
							if(meta.has("url"))
								urls.put(name, meta.get("url").getAsString());
							getLogger().info("AI加载成功：" + name);
						} catch (Throwable e) {
							e.printStackTrace();
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
		// trial.clear();
		// trial.add("fengyitalk");
	}

	/**
	 * 获取指定用户有权访问的AI应用列表（权限表中state='1'的应用）。
	 *
	 * @param uid 用户ID
	 * @return JSON数组，每个元素包含应用名称和ID
	 */
	public JsonArray getChatApps(String uid) {
		JsonArray ja = new JsonArray();
		try (PreparedStatement ps = database.prepareStatement("SELECT app FROM permission WHERE uid = ? and state='1'")) {
			ps.setString(1, uid);
			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				String appName = rs.getString(1);
				AIApplication ent = apps.get(appName);
				if (ent != null)
					ja.add(JsonBuilder.object().add("name", ent.getName()).add("appid", appName).end());
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return ja;
	}

	/**
	 * 检查指定用户是否有权使用指定的AI应用。
	 *
	 * @param uid   用户ID
	 * @param appid 应用ID
	 * @return 如果权限表中存在记录且state='1'，返回true；否则返回false
	 */
	public boolean isChatAllow(String uid, String appid) {
		try (PreparedStatement ps = database.prepareStatement("SELECT count(1) FROM permission WHERE uid = ? and state='1' and app = ?")) {
			ps.setString(1, uid);
			ps.setString(2, appid);
			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				if (rs.getInt(1) > 0)
					return true;
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return false;
	}

	/**
	 * 获取指定用户的所有对话列表（排除attribute='hide'的记录）。
	 *
	 * @param uid 用户ID
	 * @return JSON数组，每个元素包含对话ID、应用ID、时间、应用名称和简述
	 */
	public JsonArray getChatListUser(String uid) {
		try (PreparedStatement ps = database.prepareStatement("SELECT chatid,brief,time,app FROM chats WHERE uid = ? and attribute!='hide'")) {
			ps.setString(1, uid);
			ResultSet rs = ps.executeQuery();
			JsonArray ja = new JsonArray();
			while (rs.next()) {
				String appid = rs.getString(4);
				AIApplication ent = apps.get(appid);
				if (ent != null)
					ja.add(JsonBuilder.object()
						.add("chatid", rs.getString(1))
						.add("appid", appid)
						.add("time", rs.getString(3))
						.add("app", ent.getName())
						.add("name", rs.getString(2) == null ? "新对话" : rs.getString(2))
						.end());
			}
			return ja;
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return new JsonArray();
	}

	/**
	 * 查找指定用户在指定应用下的所有对话（不隐藏）。
	 *
	 * @param uid 用户ID
	 * @param app 应用ID
	 * @return JSON数组，包含匹配的对话信息
	 */
	public JsonArray findChat(String uid, String app) {
		try (PreparedStatement ps = database.prepareStatement("SELECT c.chatid,c.brief,c.time,c.app FROM chats c LEFT JOIN chatdata cd ON c.chatid=cd.chatid WHERE c.uid = ? and c.app = ? and c.attribute!='deleted' and (cd.selected is null or cd.selected = '1')")) {
			ps.setString(1, uid);
			ps.setString(2, app);
			ResultSet rs = ps.executeQuery();
			JsonArray ja = new JsonArray();
			while (rs.next()) {
				String appid = rs.getString(4);
				AIApplication ent = apps.get(appid);
				if (ent != null)
					ja.add(JsonBuilder.object()
						.add("chatid", rs.getString(1))
						.add("appid", appid)
						.add("app", ent.getName())
						.add("time", rs.getString(3))
						.add("name", rs.getString(2) == null ? "新对话" : rs.getString(2))
						.end());
			}
			return ja;
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return new JsonArray();
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
	 * HTTP GET端点：查询指定用户在指定应用下的对话。
	 *
	 * @param uid 用户ID
	 * @param app 应用ID
	 * @return JSON格式的对话列表
	 */
	@HttpMethod("GET")
	@HttpPath("/querychat")
	@Adapter
	public ResultDTO findChats(@Query("uid") String uid, @Query("appid") String app) {
		return new ResultDTO(200, findChat(uid, app));
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
	@HttpMethod("GET")
	@HttpPath("/archiveApplist")
	@Adapter
	public ResultDTO archiveApps(@Query("uid") String uid) {
		Set<String> curApps=new HashSet<>();
		JsonArray ja = new JsonArray();
		try (PreparedStatement ps = database.prepareStatement("SELECT app FROM permission WHERE uid = ? and state='1'")) {
			ps.setString(1, uid);
			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				
				String appName = rs.getString(1);
				curApps.add(appName);
				AIApplication ent = apps.get(appName);
				String url=urls.get(appName);
				if (ent != null) {
					JsonObjectBuilder<JsonObject> jb=JsonBuilder.object().add("name", ent.getName()).add("appid", appName);
					if(url!=null)
						jb.add("url", url);
					ja.add(jb.end());
				}
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		for(String s:trial) {
			if(curApps.add(s)) {
				AIApplication ent = apps.get(s);
				String url=urls.get(s);
				if (ent != null) {
					JsonObjectBuilder<JsonObject> jb=JsonBuilder.object().add("name", ent.getName()).add("appid", s);
					if(url!=null)
						jb.add("url", url);
					ja.add(jb.end());
				}
			}
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
	@HttpMethod("GET")
	@HttpPath("/echarts.min.js")
	@Adapter
	public ResultDTO echartsjs() throws IOException {
		return new ResultDTO(200, new File(parent, "echarts.min.js"));
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

	/**
	 * HTTP GET端点：删除（隐藏）指定对话。 将对话的attribute字段设置为"hide"，使其在列表中不可见。
	 *
	 * @param userid 用户ID
	 * @param chatid 对话ID
	 * @return 成功返回"true"
	 */
	@HttpMethod("GET")
	@HttpPath("/remove")
	@Adapter
	public ResultDTO delDialog(@Query("uid") String userid, @Query("cid") String chatid) {
		try (PreparedStatement ps2 = database.prepareStatement("UPDATE chats SET attribute=? WHERE uid=? and chatid=?")) {
			ps2.setString(1, "hide");
			ps2.setString(2, userid);
			ps2.setString(3, chatid);
			ps2.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return new ResultDTO(200, "true");
	}

	/**
	 * 更新指定对话的费用记录。
	 *
	 * @param chatid 对话ID
	 * @param price  费用字符串
	 */
	public void setPrice(String chatid, String price) {
		try (PreparedStatement ps2 = database.prepareStatement("UPDATE price SET price=? WHERE chatid=?")) {
			ps2.setString(1, price);
			ps2.setString(2, chatid);
			ps2.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 更新指定对话的简述（brief字段）。
	 *
	 * @param id   对话ID
	 * @param name 新的简述内容
	 */
	public void updateBrief(String id, String name) {
		if (name == null) return;
		try (PreparedStatement ps2 = database.prepareStatement("UPDATE chats SET brief=? WHERE chatid=?")) {
			ps2.setString(1, name);
			ps2.setString(2, id);
			ps2.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
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
			res.suscribeWebsocketEvents(LocalVoiceModel.lhs);
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
			LocalVoiceModel.lhs.onMessage(reqid, data);
			return new ResultDTO(200);
		}
		return new ResultDTO(404);
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
		try (PreparedStatement ps = database.prepareStatement("SELECT uid,app FROM chats WHERE chatid = ?")) {
			ps.setString(1, cid);
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.next() && rs.getString(1).equals(uid)) {
					WebSocketAISession state = uidsockets.get(cid);
					String app = rs.getString(2);
					AIApplication appx = apps.get(app);
					if (state == null) {
						if (appx == null) {
							return new ResultDTO(400, "App does not exist");
						}
						File data = new File(saveData, cid + ".json");
						if (data.exists()) {
							try {
								state = new WebSocketAISession(this, uid, cid, appx, data,
									AIWuxiaMain.historyFromJson(data),
									AIWuxiaMain.dataFromJson(data));
							} catch (JsonSyntaxException | IOException e) {
								e.printStackTrace();
								logger.info("AI " + cid + " Load Error");
							}
						} else
							return new ResultDTO(404, "Chat does not exist");
					}
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					try {
						baos.write(0xEF);
						baos.write(0xBB);
						baos.write(0xBF); // UTF-8 BOM
						baos.write(appx.constructBackLog(state).getBytes(StandardCharsets.UTF_8));
					} catch (IOException e) {
						e.printStackTrace();
						return new ResultDTO(500, "内部服务器错误");
					}
					ResultDTO res = new ResultDTO(200, baos.toByteArray());
					res.addHeader(HttpHeaderNames.CONTENT_DISPOSITION,
						"attachment; filename=\"" + new String(("故事导出-" + appx.getBrief(state) + ".txt")
							.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1) + "\"");
					res.addHeader(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8");
					res.addHeader(HttpHeaderNames.CACHE_CONTROL, "private, no-store, no-cache, must-revalidate");
					res.addHeader(HttpHeaderNames.PRAGMA, "no-cache");
					res.addHeader(HttpHeaderNames.EXPIRES, "0");
					return res;
				}
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		return new ResultDTO(401, "Unauthorized");
	}

	/**
	 * WebSocket端点：建立与前端对话页面的WebSocket连接。
	 * 根据提供的chatid、app和userId进行验证，如果对话不存在且满足创建条件（权限或公测限制），则创建新对话。
	 * 成功后将WebSocket会话与{@link WebSocketAISession}绑定。
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
		String attribute = "";
		try (PreparedStatement ps = database.prepareStatement("SELECT uid,app FROM chats WHERE chatid = ? and attribute!='removed'")) {
			ps.setString(1, cid);
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
					if (!isCreate && trial.contains(app)) { // 公测应用，允许每个用户创建一个
						isCreate = true;
						attribute = "hide";
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
		WebSocketAISession state = uidsockets.get(cid);
		if (state == null) {
			AIApplication appx = apps.get(app);
			if (appx == null) {
				res.write(400, "App does not exist");
				return;
			}
			File data = new File(saveData, cid + ".json");

			if (data.exists())
				try {
					state = new WebSocketAISession(this, uid, cid, appx, data,
							AIWuxiaMain.historyFromJson(data),
							AIWuxiaMain.dataFromJson(data));
					state.onLoad();
					logger.info("AI " + cid + " Loaded");
				} catch (JsonSyntaxException | IOException e) {
					e.printStackTrace();
					logger.info("AI " + cid + " Load Error");
				}
			if (state == null) {
				if (isCreate) {
					long time = new Date().getTime();
					try (PreparedStatement ps2 = database.prepareStatement("INSERT INTO chats(uid,chatid,app,time,attribute) VALUES(?,?,?,?,?)")) {
						ps2.setString(1, uid);
						ps2.setString(2, cid);
						ps2.setString(3, app);
						ps2.setLong(4, time);
						ps2.setString(5, attribute);
						ps2.executeUpdate();
					} catch (SQLException e) {
						e.printStackTrace();
					}
					try (PreparedStatement ps2 = database.prepareStatement("INSERT INTO price(chatid,price,time) VALUES(?,?,?)")) {
						ps2.setString(1, cid);
						ps2.setString(2, "0.00");
						ps2.setLong(3, time);
						ps2.executeUpdate();
					} catch (SQLException e) {
						e.printStackTrace();
					}
					try (PreparedStatement ps2 = database.prepareStatement("INSERT INTO chatdata(chatid,remark,selected) VALUES(?,?,?)")) {
						ps2.setString(1, cid);
						ps2.setString(2, "");
						ps2.setString(3, "1");
						ps2.executeUpdate();
					} catch (SQLException e) {
						e.printStackTrace();
					}
					
				}
				state = new WebSocketAISession(this, uid, cid, appx, data,
					new MemoryHistory(), new AISession.ExtraData());
				logger.info("AI " + cid + " Created");
			}
		}
		uidsockets.put(cid, state);
		res.suscribeWebsocketEvents(state);
	}
	/**
	 * 根据用户 ID、对话 ID 修改备注。
	 * 若对应的 chats 记录存在且未被删除，且 chatdata 中已有记录则更新，否则插入新记录。
	 */
	@HttpPath("/updateRemark")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO updateRemark(@Query("uid") String userId, @PostQuery("chatid") String chatId,@PostQuery("remark") String newRemark) {
		  // 1. 校验对话是否存在且未被移除
	    String checkChatSql = "SELECT 1 FROM chats WHERE uid = ? AND chatid = ? AND attribute != 'removed'";
	    try (PreparedStatement ps = database.prepareStatement(checkChatSql)) {
	        ps.setString(1, userId);
	        ps.setString(2, chatId);
	        try (ResultSet rs = ps.executeQuery()) {
	            if (!rs.next()) {
	                return new ResultDTO(404,"对话不存在或已被移除");
	            }
	        }
	    } catch (SQLException e) {
			e.printStackTrace();
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
			e.printStackTrace();

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
				e.printStackTrace();

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
				e.printStackTrace();
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
			e.printStackTrace();
			return new ResultDTO(500,"内部错误");
	    }
	    return new ResultDTO(404,"对话不存在或已被移除");
	}

	/**
	 * 根据用户 ID、对话 ID、智能体名称，将该用户该智能体下所有对话的 selected 设为 0，
	 * 并将指定对话的 selected 设为 1。若 chatdata 中缺少记录则自动插入。
	 */
	@HttpPath("/updateSelection")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO updateSelected(@Query("uid") String userId,@PostQuery("chatid") String chatId,@PostQuery("appid") String appName) {
		 // 开启事务（保证原子性）
	    try {
			database.setAutoCommit(false);
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	    try {
	        // 1. 获取该用户该智能体所有未移除的对话ID
	        String selectChatsSql = "SELECT chatid FROM chats WHERE uid = ? AND app = ? AND attribute != 'removed'";
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
	        // 3. 验证目标对话是否属于上述集合（即未被移除且属于该用户、该智能体）
	        if (!chatids.contains(chatId)) {
	        	throw new IllegalStateException("目标对话不存在、已被移除或不属于指定智能体");
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
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
	        return new ResultDTO(500,"内部错误");
	    }catch(IllegalStateException ex) {
	    	 try {
					database.rollback();
				} catch (SQLException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}
		        return new ResultDTO(400,ex.getMessage());
	    	
	    } finally {
	        try {
				database.setAutoCommit(true);
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
	    }
	    return new ResultDTO(200,chatId);
	}

	/**
	 * 连表查询对话详情，包括智能体名称、简述、价格、备注、是否选中。
	 * 若 price 或 chatdata 无记录，对应字段返回空字符串。
	 */
	@HttpPath("/queryArchives")
	@Adapter
	@HttpMethod("GET")
	public ResultDTO queryChats(@Query("uid") String uid,@Query("appid") String appName) throws SQLException {
	    String sql = "SELECT c.chatid, c.brief, c.time, p.price, d.remark, d.selected " +
	                 "FROM chats c " +
	                 "LEFT JOIN price p ON c.chatid = p.chatid " +
	                 "LEFT JOIN chatdata d ON c.chatid = d.chatid " +
	                 "WHERE c.uid = ? AND c.app = ? AND c.attribute != 'removed'";

	    JsonArray ja = new JsonArray();

		AIApplication ent = apps.get(appName);
		if(ent==null)
			return new ResultDTO(400,"应用不存在");
	    try (PreparedStatement ps = database.prepareStatement(sql)) {
	        ps.setString(1, uid);
	        ps.setString(2, appName);
	        try (ResultSet rs = ps.executeQuery()) {
	            while (rs.next()) {
	                JsonObject obj = new JsonObject();
	                obj.addProperty("chatid", rs.getString("chatid"));
	                obj.addProperty("appname", appName);
	                obj.addProperty("app", appName);
	                obj.addProperty("time", rs.getString("time")); // 时间戳转为字符串
	                String brief = rs.getString("brief");
	                obj.addProperty("name", brief == null ? "新对话" : brief);
	                obj.addProperty("price", rs.getString("price") == null ? "" : rs.getString("price"));
	                obj.addProperty("remark", rs.getString("remark") == null ? "" : rs.getString("remark"));
	                obj.addProperty("selected", rs.getString("selected") == null ? "" : rs.getString("selected"));
	                ja.add(obj);
	            }
	        }
	    }
	    return new ResultDTO(200,ja.toString());
	}

	/**
	 * 释放指定的WebSocket会话，从活跃会话映射中移除。 当WebSocket连接关闭时调用。
	 *
	 * @param state 要释放的会话对象
	 */
	public void markRelease(WebSocketAISession state) {
		logger.info("AI " + state.getChatId() + " UnLoaded");
		uidsockets.remove(state.getChatId());
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
				e.printStackTrace();
			}
		} else {
			String sql = "DELETE FROM user_free_limit_override WHERE user_id = ?";
			try (PreparedStatement pstmt = database.prepareStatement(sql)) {
				pstmt.setString(1, userId);
				pstmt.executeUpdate();
			} catch (SQLException e) {
				e.printStackTrace();
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
			e.printStackTrace();
		}
		return DEFAULT_FREE_DAILY_LIMIT;
	}

	/**
	 * 增加付费token（系统充值）
	 * 
	 * @param userId 用户ID
	 * @param amount 增加数量（必须为正数）
	 * @throws SQLException 数据库操作异常
	 */
	public synchronized void increasePaidTokens(String userId, int amount){
		if (amount <= 0) {
			throw new IllegalArgumentException("增加数量必须为正数");
		}
		String sql = "INSERT INTO user_paid_balance (user_id, balance) VALUES (?, ?) "
			+ "ON CONFLICT(user_id) DO UPDATE SET balance = balance + ?";
		try (PreparedStatement pstmt = database.prepareStatement(sql)) {
			pstmt.setString(1, userId);
			pstmt.setInt(2, amount);
			pstmt.setInt(3, amount);
			pstmt.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 获取用户剩余token（免费+付费）
	 * 
	 * @param userId 用户ID
	 * @return TokenRemaining对象，包含免费总额度、付费剩余和今日免费已使用量
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

		return new ResultDTO(200,JsonBuilder.object().add("freeTotal", freeLimit).add("paidRemaining", paidRemaining).add("freeCost", freeUsed).add("paidCost", paidUsed));
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

	/**
	 * 消耗token（优先消耗免费，不足时消耗付费）
	 * 
	 * @param userId 用户ID
	 * @param tokens 需要消耗的token数量（必须为正数）
	 * @return true表示消耗成功，false表示总余额不足
	 * @throws SQLException 数据库操作异常
	 */
	public synchronized void consumeTokens(String userId, int tokens) {
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
					e.printStackTrace();
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
					e.printStackTrace();
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
		} catch (SQLException e) {
			e.printStackTrace();
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
			e.printStackTrace();
			return new ResultDTO(500,"内部错误");
		}
		return new ResultDTO(200,ja);
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
			e.printStackTrace();
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
			e.printStackTrace();
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
			e.printStackTrace();
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
				// TODO Auto-generated catch block
				e.printStackTrace();
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
				// TODO Auto-generated catch block
				e3.printStackTrace();
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
				e2.printStackTrace();
				return new ResultDTO(500,"CDK兑换失败，系统错误，请稍后再试");
			}

            // 3. 扣减次数（更新remaining_uses）
            String updateCDK = "UPDATE cdk SET remaining_uses = remaining_uses - 1 WHERE code = ? and remaining_uses>0";
            int rowsUpdated=0;
            try (PreparedStatement pstmt = database.prepareStatement(updateCDK)) {
                pstmt.setString(1, code);
                rowsUpdated = pstmt.executeUpdate();
            } catch (SQLException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
            if (rowsUpdated != 1) {
            	return new ResultDTO(400,"CDK已失效");
            }
            if(cost>0) {
            	this.increasePaidTokens(userId, cost);
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
                e.printStackTrace();
                
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