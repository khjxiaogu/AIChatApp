package com.khjxiaogu.aiwuxia;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import com.khjxiaogu.aiwuxia.apps.AIArticleMain;
import com.khjxiaogu.aiwuxia.apps.AICharaTalkMain;
import com.khjxiaogu.aiwuxia.apps.AIGalgameMain;
import com.khjxiaogu.aiwuxia.apps.AITRPGSceneMain;
import com.khjxiaogu.aiwuxia.apps.AIWuxiaMain;
import com.khjxiaogu.aiwuxia.llm.LLMConnector;
import com.khjxiaogu.aiwuxia.state.history.MemoryHistory;
import com.khjxiaogu.aiwuxia.state.session.AISession;
import com.khjxiaogu.aiwuxia.state.session.WebSocketAISession;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.aiwuxia.voice.LocalVoiceModel;
import com.khjxiaogu.aiwuxia.voice.VoiceModelHandler;
import com.khjxiaogu.aiwuxia.voice.VolcanoVoiceApi;
import com.khjxiaogu.webserver.annotations.Adapter;
import com.khjxiaogu.webserver.annotations.GetBy;
import com.khjxiaogu.webserver.annotations.Header;
import com.khjxiaogu.webserver.annotations.HttpMethod;
import com.khjxiaogu.webserver.annotations.HttpPath;
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
 * AI对话核心服务，负责管理AI对话会话、用户权限、历史记录以及与前端WebSocket通信。
 * 该类实现了 {@link ServiceClass} 和 {@link CommandHandler} 接口，提供HTTP端点供前端调用，
 * 并管理多个AI应用实例（如武侠、文章、Galgame等）。
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

	/** 创建对话记录表的SQL语句 */
	private final static String createMsg = "CREATE TABLE IF NOT EXISTS chats (" +
			"uid	 TEXT(40)   NOT NULL, " + // 用户ID
			"brief TEXT, " +				   // 对话简述
			"app TEXT NOT NULL, " +			 // 智能体名称
			"chatid TEXT NOT NULL, " +		  // 对话ID
			"time	BIGINT(64), " +			// 时间戳（毫秒）
			"status TEXT, " +					// 状态
			"attribute TEXT DEFAULT ''" +		// 附加信息（如隐藏标记）
			");";

	/** 创建权限表的SQL语句 */
	private final static String createPerm = "CREATE TABLE IF NOT EXISTS permission (" +
			"uid	 TEXT(40)   NOT NULL, " +   // 用户ID
			"app TEXT NOT NULL, " +			   // 智能体
			"state TEXT NOT NULL" +				// 许可状态（如'1'表示允许）
			");";

	/** 创建费用记录表的SQL语句 */
	private final static String createPrice = "CREATE TABLE IF NOT EXISTS price (" +
			"chatid TEXT NOT NULL, " +		   // 对话ID
			"price TEXT, " +					   // 价格字符串
			"time	BIGINT(32) " +				// 时间戳（毫秒）
			");";

	/** 当前活跃的WebSocket会话映射，键为对话ID，值为对应的WebSocket会话对象 */
	protected Map<String, WebSocketAISession> uidsockets = new ConcurrentHashMap<>();

	/** 可用的AI应用映射，键为应用ID（如"wuxia"），值为对应的AI应用实例 */
	private Map<String, AIApplication> apps = new HashMap<>();

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

	/**
	 * 构造AI对话服务，初始化数据库连接、文件目录和AI应用。
	 *
	 * @param path 服务数据根目录
	 * @throws SQLException 如果数据库初始化失败
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
			database.createStatement().execute(AIChatService.createMsg);
			database.createStatement().execute(AIChatService.createPerm);
			database.createStatement().execute(AIChatService.createPrice);
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
	 * 重新加载所有AI应用配置。
	 * 扫描根目录下的所有子目录，根据meta.json元数据动态加载AI应用（如talk类型、trpg类型），
	 * 并更新apps映射和trial集合。
	 */
	public void reload() {
		apps.clear();
		trial.clear();
		//apps.put("fengyitalk", new AICharaTalkMain(parent,"fengyitalk","姚枫怡"));
		for (File fn : new File(parent,"apps").listFiles(File::isDirectory)) {
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
		//trial.clear();
		//trial.add("fengyitalk");
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
		try (PreparedStatement ps = database.prepareStatement("SELECT chatid,brief,time,app FROM chats WHERE uid = ? and app = ?")) {
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
	 * HTTP GET端点：删除（隐藏）指定对话。
	 * 将对话的attribute字段设置为"hide"，使其在列表中不可见。
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
	 * WebSocket端点：用于本地语音模型的部署（私有化部署）。
	 * 需要提供Bearer Token进行认证，Token通过系统属性"localVoiceToken"配置。
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
	 * HTTP POST端点：接收本地语音模型产出的数据（如音频字节数组）。
	 * 需要Bearer Token认证。
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
						try (PreparedStatement ps2 = database.prepareStatement("SELECT chatid FROM chats WHERE app = ? and uid = ?")) {
							ps2.setString(1, app);
							ps2.setString(2, uid);
							try (ResultSet rs2 = ps2.executeQuery()) {
								if (!rs2.next()) {
									isCreate = true;
									attribute = "hide";
								}
							}
						}
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
	 * 释放指定的WebSocket会话，从活跃会话映射中移除。
	 * 当WebSocket连接关闭时调用。
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
	 * 处理控制台命令。
	 * 支持命令：
	 * <ul>
	 *   <li>reload - 重新加载AI配置</li>
	 * </ul>
	 *
	 * @param msg	命令字符串
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