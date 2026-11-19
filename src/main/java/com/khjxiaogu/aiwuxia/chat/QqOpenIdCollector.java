package com.khjxiaogu.aiwuxia.chat;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * QQ 官方机器人 openid 采集器。
 * <p>
 * 官方 API 不提供“按 QQ 号查 openid”或“列出机器人所在群”的接口，openid 只能从事件里拿。
 * 本类既可以作为 {@link ChatEventHandler} 挂到 {@link QqOfficialAdapter} 上实时收集，也提供了
 * 命令行入口，适合第一次配置前把需要的 id 跑出来。
 * </p>
 * <p>
 * 收集内容：
 * </p>
 * <ul>
 *   <li>群 {@code group_openid}：收到群消息、机器人入群事件时记录；</li>
 *   <li>群成员 {@code member_openid}：群消息发送者，按群分组记录；</li>
 *   <li>单聊用户 {@code user_openid}：收到私聊消息、好友添加事件时记录。</li>
 * </ul>
 * <p>
 * 结果保存为 JSON，默认路径 {@code save/config/openid-directory.json}。命令行用法：
 * </p>
 * <pre>
 * java -Dqqbot_appid=102xxxxxx -Dqqbot_secret=xxxxx \
 *      -cp "target/AIWuxia-0.0.1-SNAPSHOT.jar;target/aiwx_lib/*" \
 *      com.khjxiaogu.aiwuxia.chat.QqOpenIdCollector
 * </pre>
 * <p>
 * 也可以显式传参：{@code --appid=... --secret=... --out=... --data=save}。
 * </p>
 */
public class QqOpenIdCollector implements ChatEventHandler {
	/** 群信息。 */
	public static final class GroupInfo {
		public final String groupOpenId;
		/** 事件里没有群名，留空；需要时可以在 JSON 里手工补。 */
		public String groupName = "";
		public long firstSeen;
		public long lastSeen;
		public int messages;
		public final Map<String, MemberInfo> members = new LinkedHashMap<>();

		GroupInfo(String groupOpenId) {
			this.groupOpenId = groupOpenId;
		}

		JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("group_openid", groupOpenId);
			json.addProperty("group_name", groupName);
			json.addProperty("first_seen", firstSeen);
			json.addProperty("last_seen", lastSeen);
			json.addProperty("messages", messages);
			JsonObject memberJson = new JsonObject();
			for (Map.Entry<String, MemberInfo> entry : members.entrySet()) {
				memberJson.add(entry.getKey(), entry.getValue().toJson());
			}
			json.add("members", memberJson);
			return json;
		}
	}

	/** 群成员信息。 */
	public static final class MemberInfo {
		public final String memberOpenId;
		public String name = "";
		public long firstSeen;
		public long lastSeen;
		public int messages;

		MemberInfo(String memberOpenId) {
			this.memberOpenId = memberOpenId;
		}

		JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("member_openid", memberOpenId);
			json.addProperty("name", name);
			json.addProperty("first_seen", firstSeen);
			json.addProperty("last_seen", lastSeen);
			json.addProperty("messages", messages);
			return json;
		}
	}

	/** 单聊用户信息。 */
	public static final class UserInfo {
		public final String userOpenId;
		public String name = "";
		public long firstSeen;
		public long lastSeen;
		public int messages;

		UserInfo(String userOpenId) {
			this.userOpenId = userOpenId;
		}

		JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("user_openid", userOpenId);
			json.addProperty("name", name);
			json.addProperty("first_seen", firstSeen);
			json.addProperty("last_seen", lastSeen);
			json.addProperty("messages", messages);
			return json;
		}
	}

	private final File outputFile;
	private final Map<String, GroupInfo> groups = new LinkedHashMap<>();
	private final Map<String, UserInfo> users = new LinkedHashMap<>();

	/**
	 * @param outputFile 结果 JSON 路径
	 */
	public QqOpenIdCollector(File outputFile) {
		this.outputFile = outputFile;
	}

	/** 读取已有结果；文件不存在/损坏时返回空采集器。 */
	public static QqOpenIdCollector load(File outputFile) {
		QqOpenIdCollector collector = new QqOpenIdCollector(outputFile);
		if (outputFile == null || !outputFile.isFile()) {
			return collector;
		}
		try {
			JsonObject root = JsonParser.parseString(FileUtil.readString(outputFile)).getAsJsonObject();
			collector.read(root);
		} catch (Throwable t) {
			System.err.println("[OpenID] 读取已有采集结果失败：" + t);
		}
		return collector;
	}

	/** 已发现的群（只读快照）。 */
	public synchronized Map<String, GroupInfo> getGroups() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(groups));
	}

	/** 已发现的单聊用户（只读快照）。 */
	public synchronized Map<String, UserInfo> getUsers() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(users));
	}

	public synchronized GroupInfo findGroup(String groupOpenId) {
		return groupOpenId == null ? null : groups.get(groupOpenId);
	}

	public synchronized UserInfo findUser(String userOpenId) {
		return userOpenId == null ? null : users.get(userOpenId);
	}

	public synchronized MemberInfo findMember(String groupOpenId, String memberOpenId) {
		GroupInfo group = findGroup(groupOpenId);
		return group == null || memberOpenId == null ? null : group.members.get(memberOpenId);
	}

	public File getOutputFile() {
		return outputFile;
	}

	// ==================== 事件收集 ====================

	@Override
	public synchronized void onGroupMessage(IncomingMessage message) {
		if (message == null || message.getChatId().isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		GroupInfo group = groups.get(message.getChatId());
		if (group == null) {
			group = new GroupInfo(message.getChatId());
			group.firstSeen = now;
			groups.put(group.groupOpenId, group);
			System.out.println("[OpenID] 发现新群：group_openid=" + group.groupOpenId);
		}
		group.lastSeen = now;
		group.messages++;

		String memberId = message.getSenderId();
		if (memberId != null && !memberId.isEmpty()) {
			MemberInfo member = group.members.get(memberId);
			if (member == null) {
				member = new MemberInfo(memberId);
				member.firstSeen = now;
				group.members.put(memberId, member);
				System.out.println("[OpenID] 发现新群成员：member_openid=" + memberId
						+ "（群 " + group.groupOpenId + "）");
			}
			member.lastSeen = now;
			member.messages++;
			if (message.getSenderName() != null && !message.getSenderName().isEmpty()) {
				member.name = message.getSenderName();
			}
			System.out.println("[OpenID] 群 " + group.groupOpenId + " / 成员 " + memberId
					+ (member.name.isEmpty() ? "" : "（" + member.name + "）")
					+ "：" + shorten(message.getPlainText()));
		}
	}

	@Override
	public synchronized void onPrivateMessage(IncomingMessage message) {
		if (message == null || message.getSenderId().isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		String userId = message.getSenderId();
		UserInfo user = users.get(userId);
		if (user == null) {
			user = new UserInfo(userId);
			user.firstSeen = now;
			users.put(userId, user);
			System.out.println("[OpenID] 发现新用户：user_openid=" + userId);
		}
		user.lastSeen = now;
		user.messages++;
		if (message.getSenderName() != null && !message.getSenderName().isEmpty()) {
			user.name = message.getSenderName();
		}
		System.out.println("[OpenID] 私聊 user_openid=" + userId
				+ (user.name.isEmpty() ? "" : "（" + user.name + "）")
				+ "：" + shorten(message.getPlainText()));
	}

	@Override
	public synchronized void onSystemEvent(String event, JsonObject data) {
		if ("GROUP_ADD_ROBOT".equals(event)) {
			String groupOpenId = JsonConfig.getStringOrEmpty(data, "group_openid");
			if (!groupOpenId.isEmpty() && !groups.containsKey(groupOpenId)) {
				GroupInfo group = new GroupInfo(groupOpenId);
				group.firstSeen = System.currentTimeMillis();
				group.lastSeen = group.firstSeen;
				groups.put(groupOpenId, group);
				System.out.println("[OpenID] 发现新群（机器人入群事件）：group_openid=" + groupOpenId);
			}
			return;
		}
		if ("FRIEND_ADD".equals(event)) {
			JsonObject author = data != null && data.has("author") && data.get("author").isJsonObject()
					? data.getAsJsonObject("author") : new JsonObject();
			String userId = JsonConfig.getStringOrEmpty(data, "user_openid");
			if (userId.isEmpty()) {
				userId = JsonConfig.getStringOrEmpty(data, "openid");
			}
			if (userId.isEmpty()) {
				userId = JsonConfig.getStringOrEmpty(author, "user_openid");
			}
			if (userId.isEmpty()) {
				userId = JsonConfig.getStringOrEmpty(author, "id");
			}
			if (!userId.isEmpty() && !users.containsKey(userId)) {
				UserInfo user = new UserInfo(userId);
				user.firstSeen = System.currentTimeMillis();
				user.lastSeen = user.firstSeen;
				user.name = JsonConfig.getStringOrEmpty(author, "username");
				users.put(userId, user);
				System.out.println("[OpenID] 发现新用户（好友添加事件）：user_openid=" + userId);
			}
		}
	}

	@Override
	public void onOpen() {
		System.out.println("[OpenID] QQ 官方网关已连接");
	}

	@Override
	public void onClose(int code, String reason, boolean remote) {
		System.out.println("[OpenID] QQ 官方网关关闭：" + code + " " + reason);
	}

	@Override
	public void onError(Throwable error) {
		System.err.println("[OpenID] QQ 官方网关异常：" + error);
	}

	// ==================== 保存 ====================

	/** 当前采集结果。 */
	public synchronized JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("generated_at", System.currentTimeMillis());
		JsonObject groupJson = new JsonObject();
		for (Map.Entry<String, GroupInfo> entry : groups.entrySet()) {
			groupJson.add(entry.getKey(), entry.getValue().toJson());
		}
		root.add("groups", groupJson);
		JsonObject userJson = new JsonObject();
		for (Map.Entry<String, UserInfo> entry : users.entrySet()) {
			userJson.add(entry.getKey(), entry.getValue().toJson());
		}
		root.add("users", userJson);
		return root;
	}

	/** 保存到 {@link #getOutputFile()}。 */
	public synchronized void save() {
		if (outputFile == null) {
			return;
		}
		try {
			File parent = outputFile.getParentFile();
			if (parent != null) {
				parent.mkdirs();
			}
			FileUtil.transfer(toJson().toString(), outputFile);
			System.out.println("[OpenID] 已保存 " + groups.size() + " 个群、" + users.size()
					+ " 个用户 → " + outputFile.getPath());
		} catch (Throwable t) {
			System.err.println("[OpenID] 保存失败：" + t);
		}
	}

	private void read(JsonObject root) {
		long now = System.currentTimeMillis();
		if (root.has("groups") && root.get("groups").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("groups").entrySet()) {
				if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
					continue;
				}
				JsonObject json = entry.getValue().getAsJsonObject();
				GroupInfo group = new GroupInfo(entry.getKey());
				group.groupName = JsonConfig.getStringOrEmpty(json, "group_name");
				group.firstSeen = JsonConfig.getLong(json, "first_seen", now);
				group.lastSeen = JsonConfig.getLong(json, "last_seen", now);
				group.messages = (int) JsonConfig.getLong(json, "messages", 0);
				if (json.has("members") && json.get("members").isJsonObject()) {
					for (Map.Entry<String, JsonElement> memberEntry : json.getAsJsonObject("members").entrySet()) {
						if (memberEntry.getValue() == null || !memberEntry.getValue().isJsonObject()) {
							continue;
						}
						JsonObject memberJson = memberEntry.getValue().getAsJsonObject();
						MemberInfo member = new MemberInfo(memberEntry.getKey());
						member.name = JsonConfig.getStringOrEmpty(memberJson, "name");
						member.firstSeen = JsonConfig.getLong(memberJson, "first_seen", now);
						member.lastSeen = JsonConfig.getLong(memberJson, "last_seen", now);
						member.messages = (int) JsonConfig.getLong(memberJson, "messages", 0);
						group.members.put(member.memberOpenId, member);
					}
				}
				groups.put(group.groupOpenId, group);
			}
		}
		if (root.has("users") && root.get("users").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("users").entrySet()) {
				if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
					continue;
				}
				JsonObject json = entry.getValue().getAsJsonObject();
				UserInfo user = new UserInfo(entry.getKey());
				user.name = JsonConfig.getStringOrEmpty(json, "name");
				user.firstSeen = JsonConfig.getLong(json, "first_seen", now);
				user.lastSeen = JsonConfig.getLong(json, "last_seen", now);
				user.messages = (int) JsonConfig.getLong(json, "messages", 0);
				users.put(user.userOpenId, user);
			}
		}
		System.out.println("[OpenID] 已载入历史采集：群 " + groups.size() + " 个，用户 " + users.size() + " 个");
	}

	// ==================== 命令行 ====================

	public static void main(String[] args) throws Exception {
		Map<String, String> options = parseArgs(args);
		String appId = firstNonEmpty(options.get("appid"), System.getProperty("qqbot_appid"));
		String secret = firstNonEmpty(options.get("secret"), System.getProperty("qqbot_secret"));
		if (appId.isEmpty() || secret.isEmpty()) {
			System.out.println("用法：");
			System.out.println("  java -Dqqbot_appid=102xxxxxx -Dqqbot_secret=xxxxx \\");
			System.out.println("       -cp \"target/AIWuxia-0.0.1-SNAPSHOT.jar;target/aiwx_lib/*\" \\");
			System.out.println("       com.khjxiaogu.aiwuxia.chat.QqOpenIdCollector");
			System.out.println();
			System.out.println("可选参数：");
			System.out.println("  --out=路径        采集结果 JSON（默认 save/config/openid-directory.json）");
			System.out.println("  --data=目录       运行数据目录（默认 save）");
			System.out.println("  --api-base=地址   OpenAPI 根地址（默认 https://api.bot.qq.com）");
			System.out.println("  --token-url=地址  access_token 地址（默认 {api-base}/app/getAppAccessToken）");
			System.out.println("  --intents=数值    事件订阅位（默认 1<<25）");
			if (appId.isEmpty() || secret.isEmpty()) {
				System.exit(2);
			}
			return;
		}
		File dataFolder = new File(firstNonEmpty(options.get("data"), "save"));
		String outPath = options.get("out");
		File outputFile = outPath == null || outPath.trim().isEmpty()
				? new File(new File(dataFolder, "config"), "openid-directory.json")
				: new File(outPath.trim());
		QqOpenIdCollector collector = load(outputFile);
		String apiBase = options.get("api-base");
		String tokenUrl = options.get("token-url");
		long intents = 1L << 25;
		if (options.containsKey("intents")) {
			try {
				intents = Long.parseLong(options.get("intents").trim());
			} catch (RuntimeException e) {
				System.err.println("[OpenID] 无法解析 --intents=" + options.get("intents") + "，使用默认值");
			}
		}
		QqOfficialAdapter adapter;
		if (apiBase != null || tokenUrl != null || options.containsKey("intents")) {
			adapter = new QqOfficialAdapter(appId, secret,
					apiBase == null ? "" : apiBase,
					tokenUrl == null ? "" : tokenUrl, intents, dataFolder);
		} else {
			adapter = new QqOfficialAdapter(appId, secret, dataFolder);
		}
		adapter.setEventHandler(collector);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			collector.save();
			adapter.close();
		}, "openid-collector-save"));
		System.out.println("[OpenID] 正在连接 QQ 官方网关（AppID " + appId + "）……");
		adapter.connect();
		System.out.println("[OpenID] 已连接。");
		System.out.println("[OpenID] 请在群里 @机器人、私聊机器人，或把机器人重新拉进群，来收集 openid。");
		System.out.println("[OpenID] 按 Ctrl+C 结束并保存到 " + outputFile.getPath());
		new java.util.concurrent.CountDownLatch(1).await();
	}

	private static String shorten(String text) {
		String value = text == null ? "" : text.replace('\n', ' ').trim();
		return value.length() > 80 ? value.substring(0, 80) + "..." : value;
	}

	private static String firstNonEmpty(String... values) {
		for (String value : values) {
			if (value != null && !value.trim().isEmpty()) {
				return value.trim();
			}
		}
		return "";
	}

	private static Map<String, String> parseArgs(String[] args) {
		Map<String, String> options = new LinkedHashMap<>();
		if (args == null) {
			return options;
		}
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg == null || !arg.startsWith("--")) {
				continue;
			}
			int equal = arg.indexOf('=');
			if (equal > 2) {
				options.put(arg.substring(2, equal), arg.substring(equal + 1));
				continue;
			}
			String key = arg.substring(2);
			if (i + 1 < args.length && args[i + 1] != null && !args[i + 1].startsWith("--")) {
				options.put(key, args[++i]);
			} else {
				options.put(key, "");
			}
		}
		return options;
	}
}
