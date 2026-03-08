package com.khjxiaogu.aiwuxia;

import java.io.File;
import java.io.IOException;
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
import com.khjxiaogu.aiwuxia.apps.AIArticleMain;
import com.khjxiaogu.aiwuxia.apps.AICharaTalkMain;
import com.khjxiaogu.aiwuxia.apps.AIGalgameMain;
import com.khjxiaogu.aiwuxia.apps.AITRPGSceneMain;
import com.khjxiaogu.aiwuxia.apps.AIWuxiaMain;
import com.khjxiaogu.aiwuxia.state.MemoryHistory;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.aiwuxia.utils.JsonBuilder;
import com.khjxiaogu.webserver.annotations.Adapter;
import com.khjxiaogu.webserver.annotations.GetBy;
import com.khjxiaogu.webserver.annotations.HttpMethod;
import com.khjxiaogu.webserver.annotations.HttpPath;
import com.khjxiaogu.webserver.annotations.Query;
import com.khjxiaogu.webserver.loging.SimpleLogger;
import com.khjxiaogu.webserver.web.FilePageService;
import com.khjxiaogu.webserver.web.ServiceClass;
import com.khjxiaogu.webserver.web.lowlayer.Request;
import com.khjxiaogu.webserver.web.lowlayer.Response;
import com.khjxiaogu.webserver.wrappers.ResultDTO;
import com.khjxiaogu.webserver.wrappers.inadapters.DataIn;
import com.khjxiaogu.webserver.wrappers.inadapters.FullPathIn;

public class AIChatService implements ServiceClass {
	public Connection getDatabase() {
		return database;
	}

	protected Connection database;
	public final SimpleLogger logger = new SimpleLogger("聊天");
	private final static String createMsg = "CREATE TABLE IF NOT EXISTS chats (" + "uid     TEXT(40)   NOT NULL, " + // 用户ID
		"brief TEXT, " + // 对话简述
		"app TEXT NOT NULL, " + // 智能体名称
		"chatid TEXT NOT NULL, " + // 对话ID
		"time    BIGINT(64), " + // 时间ms
		"status TEXT, " + // 状态
		"attribute TEXT DEFAULT ''" + //附加信息
		");";// 创建信息记录表
	private final static String createPerm = "CREATE TABLE IF NOT EXISTS permission (" + "uid     TEXT(40)   NOT NULL, " + // 用户ID
		"app TEXT NOT NULL, " + // 智能体
		"state TEXT NOT NULL"+ //许可
		");";// 创建权限表
	private final static String createPrice = "CREATE TABLE IF NOT EXISTS price ("+
		"chatid TEXT NOT NULL, " + // 对话ID
		"price TEXT, " + // 价格字符串
		"time    BIGINT(32) " + // 时间ms
		");";// 创建费用表
	protected Map<String, WebSocketAISession> uidsockets = new ConcurrentHashMap<>();//用户链接
	private Map<String, AIApplication> apps = new HashMap<>();//智能体列表
	private Set<String> trial=new HashSet<>();//公测智能体，只允许创建一个实例
	File parent;
	File saveData;
	private FilePageService resource;
	private FilePageService voice;
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
		parent = path;
		saveData = new File(path, "saveData");
		saveData.mkdirs();
		resource=new FilePageService(new File(parent,"resource"));
		voice=new FilePageService(new File(parent,"voice"));
		reload();
	}
	public void reload() {
		apps.clear();
		trial.clear();
		apps.put("wuxia", new AIWuxiaMain(parent));
		apps.put("article", new AIArticleMain(parent));
		apps.put("fengyi", new AIGalgameMain(parent,"promptfengyi.txt","枫怡DLC"));
		//apps.put("fengyitalk", new AICharaTalkMain(parent,"fengyitalk","姚枫怡"));
		for(File fn:parent.listFiles(File::isDirectory)) {
			try {
				String name=fn.getName();
				File metaFile=new File(fn,"meta.json");
				boolean isSucceed=false;
				if(metaFile.exists()) {
					JsonObject meta=JsonParser.parseString(FileUtil.readString(metaFile)).getAsJsonObject();
					if(meta.has("name")&&(!meta.has("enabled")||meta.get("enabled").getAsBoolean())) {
						getLogger().info("正在加载AI："+name);
						if(fn.getName().endsWith("talk")) {
							apps.put(name, new AICharaTalkMain(parent,name,meta.get("name").getAsString()));
							isSucceed=true;
						}else if(fn.getName().endsWith("trpg")) {
							apps.put(name, new AITRPGSceneMain(parent,name,meta.get("name").getAsString()));
							isSucceed=true;
						}
						if(isSucceed) {
							if(meta.has("trial")&&meta.get("trial").getAsBoolean())
								trial.add(name);
							getLogger().info("AI加载成功："+name);
						}else {
							getLogger().info("AI加载失败："+name);
						}
					}else {
						getLogger().info("忽视AI："+name+" 出于配置原因");
					}
				}else {
					getLogger().info("忽视AI："+name+" 由于找不到元数据");
				}
			} catch (Exception e) {
				getLogger().error(e);
			}
		}
		//trial.clear();
		//trial.add("fengyitalk");
	}
	public JsonArray getChatApps(String uid) {
		JsonArray ja = new JsonArray();
		try (PreparedStatement ps = database.prepareStatement("SELECT app FROM permission WHERE uid = ? and state='1'")) {

			ps.setString(1, uid);
			ResultSet rs = ps.executeQuery();
			while(rs.next()) {
				String appName=rs.getString(1);
				AIApplication ent=apps.get(appName);
				if(ent!=null)
				ja.add(JsonBuilder.object().add("name", ent.getName()).add("appid",appName).end());
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return ja;
	}
	public boolean isChatAllow(String uid,String appid) {
		try (PreparedStatement ps = database.prepareStatement("SELECT count(1) FROM permission WHERE uid = ? and state='1' and app = ?")) {

			ps.setString(1, uid);
			ps.setString(2, appid);
			ResultSet rs = ps.executeQuery();
			while(rs.next()) {
				if(rs.getInt(1)>0)
					return true;
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return false;
	}

	public JsonArray getChatListUser(String uid) {
		try (PreparedStatement ps = database.prepareStatement("SELECT chatid,brief,time,app FROM chats WHERE uid = ? and attribute!='hide'")) {

			ps.setString(1, uid);
			ResultSet rs = ps.executeQuery();
			JsonArray ja = new JsonArray();
			while (rs.next()) {
				String appid=rs.getString(4);
				AIApplication ent=apps.get(appid);
				if(ent!=null)
				ja.add(JsonBuilder.object().add("chatid", rs.getString(1)).add("appid", appid).add("time", rs.getString(3)).add("app", ent.getName()).add("name", rs.getString(2)==null?"新对话":rs.getString(2)).end());
			}
			return ja;
		} catch (SQLException e) {
			// TODO Auto-generated catch block
			getLogger().printStackTrace(e);
		}
		return new JsonArray();
	}
	public JsonArray findChat(String uid,String app) {
		try (PreparedStatement ps = database.prepareStatement("SELECT chatid,brief,time,app FROM chats WHERE uid = ? and app = ?")) {

			ps.setString(1, uid);
			ps.setString(2, app);
			ResultSet rs = ps.executeQuery();
			JsonArray ja = new JsonArray();
			while (rs.next()) {
				String appid=rs.getString(4);
				AIApplication ent=apps.get(appid);
				if(ent!=null)
					ja.add(JsonBuilder.object().add("chatid", rs.getString(1)).add("appid", appid).add("app", ent.getName()).add("time", rs.getString(3)).add("name", rs.getString(2)==null?"新对话":rs.getString(2)).end());
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
	@HttpPath("/querychat")
	@Adapter
	public ResultDTO findChats(@Query("uid") String uid,@Query("appid")String app) {
		return new ResultDTO(200, findChat(uid,app));
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
	public ResultDTO apps(@Query("uid") String uid) {
		return new ResultDTO(200, getChatApps(uid));
	}
	@HttpMethod("GET")
	@HttpPath("/resource")
	public void resource(Request req,Response rep) {
		resource.call(req, rep);
	}
	@HttpMethod("GET")
	@HttpPath("/voice")
	public void voice(Request req,Response rep) {
		voice.call(req, rep);
	}
	@HttpMethod("GET")
	@HttpPath("/chat.js")
	@Adapter
	public ResultDTO chatjs() throws IOException {
		return new ResultDTO(200, new File(parent, "chat.js"));
	}
	@HttpMethod("GET")
	@HttpPath("/doadminreload")
	@Adapter
	public ResultDTO reld() throws IOException {
		reload();
		return new ResultDTO(200, "reload succeed!");
	}
	@HttpMethod("GET")
	@HttpPath("/robots.txt")
	@Adapter
	public ResultDTO robots() throws IOException {
		return new ResultDTO(200, new File(parent, "robots.txt"));
	}

	@HttpMethod("GET")
	@HttpPath("/feather.min.js")
	@Adapter
	public ResultDTO featherjs() throws IOException {
		return new ResultDTO(200, new File(parent, "feather.min.js"));
	}

	@HttpMethod("GET")
	@HttpPath("/index")
	@Adapter
	public ResultDTO indexHtm() throws IOException {
		return new ResultDTO(200, new File(parent, "aiindex.html"));
	}
	@HttpMethod("GET")
	@HttpPath("/aichat")
	@Adapter
	public ResultDTO chat() throws IOException {
		return new ResultDTO(200, new File(parent, "chat.html"));
	}
	@HttpMethod("GET")
	@HttpPath("/aigal")
	@Adapter
	public ResultDTO aigal() throws IOException {
		return new ResultDTO(200, new File(parent, "galgame.html"));
	}
	@HttpMethod("GET")
	@HttpPath("/aitrpg")
	@Adapter
	public ResultDTO aitrpg() throws IOException {
		return new ResultDTO(200, new File(parent, "trpg.html"));
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
	public void setPrice(String chatid,String price) {
		try (PreparedStatement ps2 = database.prepareStatement("UPDATE price SET price=? WHERE chatid=?")) {
			ps2.setString(1, price);
			ps2.setString(2, chatid);
			ps2.executeUpdate();
		} catch (SQLException e) {
			e.printStackTrace();
		}
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
	@HttpPath("/kh$localModelDeploy")
	public void voiceWebSocket(Request req, Response res) {
		res.suscribeWebsocketEvents(LocalVoiceModel.lhs);
		
	}
	@HttpPath("/kh$localModelDeployData")
	@Adapter
	@HttpMethod("POST")
	public ResultDTO voicePost(@Query("reqid")String reqid,@Query("type")String type,@GetBy(DataIn.class)byte[] data) {
		LocalVoiceModel.lhs.onMessage(reqid, data);
		return new ResultDTO(200);
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
		String attribute="";
		try (PreparedStatement ps = database.prepareStatement("SELECT uid,app FROM chats WHERE chatid = ?")) {

			// ps.setString(1, uid);
			ps.setString(1, cid);
			try(ResultSet rs = ps.executeQuery()){
				if (rs.next()) {
					//System.out.println(1);
					if (!rs.getString(1).equals(uid)) {
						res.write(401, "Unauthorized");
						return;
					}
					app=rs.getString(2);
				} else {
					//System.out.println(0);
					
					if(isChatAllow(uid,app)){
						isCreate=true;
					}
					if(!isCreate&&trial.contains(app)) {//公测，允许创建一个
						try (PreparedStatement ps2 = database.prepareStatement("SELECT chatid FROM chats WHERE app = ? and uid = ?")) {
							ps2.setString(1, app);
							ps2.setString(2, uid);
							try(ResultSet rs2 = ps2.executeQuery()){
								if(!rs2.next()) {
									isCreate=true;
									attribute="hide";
								}
							}
						}
					}
					if(!isCreate) {
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
					state = new WebSocketAISession(this, uid,cid, appx, data, AIWuxiaMain.historyFromJson(data), AIWuxiaMain.dataFromJson(data));
					logger.info("AI " + cid + " Loaded");
				} catch (JsonSyntaxException | IOException e) {
					e.printStackTrace();
					logger.info("AI " + cid + " Load Error");
				}
			if (state == null) {
				if(isCreate) {
					long time=new Date().getTime();
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
				state = new WebSocketAISession(this,uid,cid, appx, data, new MemoryHistory(), new AISession.AIData());
				logger.info("AI " + cid + " Created");
			}
		}
		uidsockets.put(cid, state);
		res.suscribeWebsocketEvents(state);
		
	}

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

}
