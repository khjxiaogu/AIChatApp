package com.khjxiaogu.aiwuxia;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.webserver.annotations.Adapter;
import com.khjxiaogu.webserver.annotations.GetBy;
import com.khjxiaogu.webserver.annotations.HttpMethod;
import com.khjxiaogu.webserver.annotations.HttpPath;
import com.khjxiaogu.webserver.annotations.Query;
import com.khjxiaogu.webserver.loging.SimpleLogger;
import com.khjxiaogu.webserver.web.ServiceClass;
import com.khjxiaogu.webserver.web.lowlayer.Request;
import com.khjxiaogu.webserver.web.lowlayer.Response;
import com.khjxiaogu.webserver.wrappers.ResultDTO;
import com.khjxiaogu.webserver.wrappers.inadapters.FullPathIn;

public class ChatServerService implements ServiceClass {
	public Connection getDatabase() {
		return database;
	}

	protected Connection database;
	public final SimpleLogger logger = new SimpleLogger("聊天");
	private final static int timeout = 1000 * 60 * 2;
	private final static String createMsg = "CREATE TABLE IF NOT EXISTS chats (" + "uid     TEXT(40)   NOT NULL, " + // 用户ID
		"brief TEXT, " + // 信息
		"app TEXT NOT NULL, " + // 信息
		"chatid TEXT NOT NULL, " + // 信息
		"time    BIGINT(32), " + // 时间ms
		"attribute TEXT DEFAULT ''" + ");";// 创建信息记录表
	protected Map<String, WebSocketAIState> uidsockets = new ConcurrentHashMap<>();
	private Map<String, AIApplication> apps = new HashMap<>();
	{
		apps.put("wuxia", new AIWuxiaMain());
		apps.put("article", new AIArticleMain());
		apps.put("fengyi", new AIGalgameMain("promptfengyi.txt"));
	}
	File parent;
	File saveData;

	public ChatServerService(File path) throws SQLException, ClassNotFoundException {
		try {
			Class.forName("org.sqlite.JDBC");
		} catch (ClassNotFoundException e) {
			logger.severe("SQLITE链接失败！");
			throw e;
		}
		logger.info("正在链接SQLITE信息数据库...");
		try {
			database = DriverManager.getConnection("jdbc:sqlite:" + new File(path, "messages.db"));
			database.createStatement().execute(ChatServerService.createMsg);
		} catch (SQLException e) {
			logger.severe("信息数据库初始化失败！");
			throw e;
		}
		parent = path;
		saveData = new File(path, "saveData");
		saveData.mkdirs();
	}

	public JsonArray getChatApps() {
		JsonArray ja = new JsonArray();
		for (Entry<String, AIApplication> ent : apps.entrySet()) {
			ja.add(JsonBuilder.object().add("name", ent.getValue().getName()).add("appid", ent.getKey()).end());
		}
		return ja;
	}

	public JsonArray getChatListUser(String uid) {
		try (PreparedStatement ps = database.prepareStatement("SELECT chatid,brief FROM chats WHERE uid = ? and attribute!='hide'")) {

			ps.setString(1, uid);
			ResultSet rs = ps.executeQuery();
			JsonArray ja = new JsonArray();
			while (rs.next()) {
				ja.add(JsonBuilder.object().add("chatid", rs.getString(1)).add("name", rs.getString(2)==null?"新对话":rs.getString(2)).end());
			}
			return ja;
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			getLogger().printStackTrace(e);
		}
		return new JsonArray();
	}

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

	@HttpMethod("GET")
	@HttpPath("/")
	@Adapter
	public ResultDTO red(@GetBy(FullPathIn.class) String path) {
		return ResultDTO.redirect("/aichat/index");
	}

	@HttpMethod("GET")
	@HttpPath("/chatlist")
	@Adapter
	public ResultDTO chats(@Query("uid") String uid) {
		return new ResultDTO(200, getChatListUser(uid));
	}
	@HttpMethod("GET")
	@HttpPath("/createid")
	@Adapter
	public ResultDTO createid() {
		return new ResultDTO(200, getAvailableId());
	}
	@HttpMethod("GET")
	@HttpPath("/applist")
	@Adapter
	public ResultDTO apps() {
		return new ResultDTO(200, getChatApps());
	}

	@HttpMethod("GET")
	@HttpPath("/chat.js")
	@Adapter
	public ResultDTO chatjs() throws IOException {
		return new ResultDTO(200, FileUtil.readString(new File(parent, "chat.js")));
	}

	@HttpMethod("GET")
	@HttpPath("/robots.txt")
	@Adapter
	public ResultDTO robots() throws IOException {
		return new ResultDTO(200, FileUtil.readString(new File(parent, "robots.txt")));
	}

	@HttpMethod("GET")
	@HttpPath("/feather.min.js")
	@Adapter
	public ResultDTO featherjs() throws IOException {
		return new ResultDTO(200, FileUtil.readString(new File(parent, "feather.min.js")));
	}

	@HttpMethod("GET")
	@HttpPath("/index")
	@Adapter
	public ResultDTO indexHtm() throws IOException {
		return new ResultDTO(200, FileUtil.readString(new File(parent, "index.html")));
	}
	@HttpMethod("GET")
	@HttpPath("/remove")
	@Adapter
	public ResultDTO delDialog(@Query("uid")String userid,@Query("cid")String chatid) {
		try (PreparedStatement ps2 = database.prepareStatement("UPDATE chats SET attribute=? WHERE uid=? and chatid=?")) {
			ps2.setString(1, "hide");
			ps2.setString(2, userid);
			ps2.setString(3, chatid);
			ps2.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return new ResultDTO(200,"true");
	}
	public void updateBrief(String id,String name) {
		if(name==null)return;
		try (PreparedStatement ps2 = database.prepareStatement("UPDATE chats SET brief=? WHERE chatid=?")) {

			ps2.setString(1, name);
			ps2.setString(2, id);
			ps2.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}
	/**
	 * @param req
	 */
	@HttpPath("/chatsocket")
	public void webSocket(Request req, Response res) {
		String cid = req.getQuery().get("chatid");
		String app = req.getQuery().get("app");
		String uid = req.getQuery().get("userId");
		boolean isCreate=false;
		try (PreparedStatement ps = database.prepareStatement("SELECT uid,app FROM chats WHERE chatid = ?")) {

			// ps.setString(1, uid);
			ps.setString(1, cid);
			ResultSet rs = ps.executeQuery();
			if (rs.next()) {
				System.out.println(1);
				if (!rs.getString(1).equals(uid)) {
					res.write(401, "Unauthorized");
					return;
				}
				app=rs.getString(2);
			} else {
				System.out.println(0);
				isCreate=true;
				
			}
		} catch (SQLException e) {
			getLogger().printStackTrace(e);
		}
		WebSocketAIState state = uidsockets.get(cid);
		if (state == null) {
			AIApplication appx = apps.get(app);
			if (appx == null) {
				res.write(400, "App does not exist");
				return;
			}
			File data = new File(saveData, cid + ".json");

			if (data.exists())
				try {
					state = new WebSocketAIState(this, cid, appx, data, AIWuxiaMain.historyFromJson(data), AIWuxiaMain.dataFromJson(data));
					logger.info("AI " + cid + " Loaded");
				} catch (JsonSyntaxException | IOException e) {
					e.printStackTrace();
					logger.info("AI " + cid + " Load Error");
				}
			if (state == null) {
				if(isCreate)
					try (PreparedStatement ps2 = database.prepareStatement("INSERT INTO chats(uid,chatid,app) VALUES(?,?,?)")) {

						ps2.setString(1, uid);
						ps2.setString(2, cid);
						ps2.setString(3, app);
						ps2.executeUpdate();
					} catch (SQLException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				state = new WebSocketAIState(this, cid, appx, data, new History(), new AIState.AIData());
				logger.info("AI " + cid + " Created");
			}
		}
		uidsockets.put(cid, state);
		res.suscribeWebsocketEvents(state);
		
	}

	public void markRelease(WebSocketAIState state) {
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

}
