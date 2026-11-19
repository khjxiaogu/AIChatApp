package com.khjxiaogu.aiwuxia.chat;

import java.io.Closeable;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * 聊天用户资料缓存：昵称与头像 URL。
 * <p>
 * 不同协议能拿到的资料不同：
 * </p>
 * <ul>
 *   <li>NapCat：入站消息里有昵称/群名片，头像按 QQ 号拼地址；</li>
 *   <li>QQ 官方：入站事件/用户信息查询里有 username 和 avatar。</li>
 * </ul>
 * <p>
 * 同一个用户在不同群、私聊里的头像默认是一样的，因此这里按“用户”缓存一份；拿到
 * {@code union_openid} 时用它作为主键，并把见过的 {@code member_openid}/{@code user_openid}
 * 都作为别名指向同一条记录。
 * </p>
 * <p>
 * 缓存会定期写入 {@code save/config/profile-cache.json}，退出时也会保存。
 * </p>
 */
public final class ChatProfileCache implements Closeable {
	/** 一个用户的资料。 */
	public static final class Profile {
		public final String key;
		public String unionId = "";
		public String name = "";
		public String avatar = "";
		public final Set<String> ids = new LinkedHashSet<>();
		public long updatedAt;

		Profile(String key) {
			this.key = key;
		}

		JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("key", key);
			json.addProperty("union_openid", unionId);
			json.addProperty("name", name);
			json.addProperty("avatar", avatar);
			json.addProperty("updated_at", updatedAt);
			JsonArray idArray = new JsonArray();
			for (String id : ids) {
				idArray.add(id);
			}
			json.add("ids", idArray);
			return json;
		}
	}

	/** 一个群的资料（群名、头像、群成员名片）。 */
	public static final class GroupProfile {
		public final String groupId;
		public String name = "";
		public String avatar = "";
		public long updatedAt;
		/** member_openid -> 群名片/群昵称 */
		public final Map<String, String> members = new LinkedHashMap<>();

		GroupProfile(String groupId) {
			this.groupId = groupId;
		}

		JsonObject toJson() {
			JsonObject json = new JsonObject();
			json.addProperty("group_openid", groupId);
			json.addProperty("name", name);
			json.addProperty("avatar", avatar);
			json.addProperty("updated_at", updatedAt);
			JsonObject memberJson = new JsonObject();
			for (Map.Entry<String, String> entry : members.entrySet()) {
				memberJson.addProperty(entry.getKey(), entry.getValue());
			}
			json.add("members", memberJson);
			return json;
		}
	}

	private static final long SAVE_INTERVAL_SECONDS = 5L;

	private final File file;
	private final Map<String, Profile> profiles = new LinkedHashMap<>();
	private final Map<String, String> aliases = new LinkedHashMap<>();
	private final Map<String, GroupProfile> groups = new LinkedHashMap<>();
	private final ScheduledExecutorService saver;
	private boolean dirty;

	public ChatProfileCache(File file) {
		this.file = file;
		if (file != null) {
			load();
			this.saver = Executors.newSingleThreadScheduledExecutor(r -> {
				Thread thread = new Thread(r, "chat-profile-save");
				thread.setDaemon(true);
				return thread;
			});
			saver.scheduleWithFixedDelay(this::saveIfDirty, SAVE_INTERVAL_SECONDS, SAVE_INTERVAL_SECONDS,
					TimeUnit.SECONDS);
		} else {
			this.saver = null;
		}
	}

	/**
	 * 记录一条用户资料；空字段不会覆盖已有值。
	 *
	 * @param id      协议里的用户/成员 id（QQ 号、member_openid、user_openid 等）
	 * @param unionId 跨应用统一 id，可为空
	 * @param name    昵称/群名片
	 * @param avatar  头像 URL
	 */
	public synchronized void rememberUser(String id, String unionId, String name, String avatar) {
		String primary = trim(unionId);
		String local = trim(id);
		if (primary.isEmpty() && local.isEmpty()) {
			return;
		}
		Long now = System.currentTimeMillis();
		String key = firstNonEmpty(aliasOf(primary), aliasOf(local), primary, local);
		Profile profile = profiles.get(key);
		if (profile == null) {
			profile = new Profile(key);
			profiles.put(key, profile);
		}
		// 之前可能分别以 member_openid / user_openid 建过记录，合并到当前主键
		mergeInto(key, aliasOf(primary));
		mergeInto(key, aliasOf(local));
		profile = profiles.get(key);
		if (profile == null) {
			profile = new Profile(key);
			profiles.put(key, profile);
		}
		if (!primary.isEmpty()) {
			profile.unionId = primary;
			aliases.put(primary, key);
		}
		if (!local.isEmpty()) {
			profile.ids.add(local);
			aliases.put(local, key);
		}
		aliases.put(key, key);
		if (!trim(name).isEmpty()) {
			profile.name = trim(name);
		}
		if (!trim(avatar).isEmpty()) {
			profile.avatar = trim(avatar);
		}
		profile.updatedAt = now;
		dirty = true;
	}

	/** 按任意 id 查资料；找不到返回 null。 */
	public synchronized Profile profile(String id) {
		String key = aliasOf(id);
		return key == null ? null : profiles.get(key);
	}

	/** 昵称；找不到返回 null。 */
	public synchronized String userName(String id) {
		Profile profile = profile(id);
		return profile == null || profile.name.isEmpty() ? null : profile.name;
	}

	/** 头像 URL；找不到返回 null。 */
	public synchronized String userAvatar(String id) {
		Profile profile = profile(id);
		return profile == null || profile.avatar.isEmpty() ? null : profile.avatar;
	}

	/** 记录群资料；空字段不会覆盖已有值。 */
	public synchronized void rememberGroup(String groupId, String name, String avatar) {
		String id = trim(groupId);
		if (id.isEmpty()) {
			return;
		}
		GroupProfile group = groups.get(id);
		if (group == null) {
			group = new GroupProfile(id);
			groups.put(id, group);
		}
		if (!trim(name).isEmpty()) {
			group.name = trim(name);
		}
		if (!trim(avatar).isEmpty()) {
			group.avatar = trim(avatar);
		}
		group.updatedAt = System.currentTimeMillis();
		dirty = true;
	}

	/** 群头像 URL；找不到返回 null。 */
	public synchronized String groupAvatar(String groupId) {
		GroupProfile group = groupId == null ? null : groups.get(groupId);
		return group == null || group.avatar.isEmpty() ? null : group.avatar;
	}

	/** 群名称；找不到返回 null。 */
	public synchronized String groupName(String groupId) {
		GroupProfile group = groupId == null ? null : groups.get(groupId);
		return group == null || group.name.isEmpty() ? null : group.name;
	}

	/** 记录群成员名片/群昵称。 */
	public synchronized void rememberMemberName(String groupId, String memberId, String name) {
		String gid = trim(groupId);
		String mid = trim(memberId);
		String value = trim(name);
		if (gid.isEmpty() || mid.isEmpty() || value.isEmpty()) {
			return;
		}
		GroupProfile group = groups.get(gid);
		if (group == null) {
			group = new GroupProfile(gid);
			groups.put(gid, group);
		}
		group.members.put(mid, value);
		group.updatedAt = System.currentTimeMillis();
		dirty = true;
	}

	/** 群成员名片/群昵称；找不到返回 null。 */
	public synchronized String memberName(String groupId, String memberId) {
		GroupProfile group = groupId == null ? null : groups.get(groupId);
		if (group == null || memberId == null) {
			return null;
		}
		String name = group.members.get(memberId);
		return name == null || name.isEmpty() ? null : name;
	}

	public File getFile() {
		return file;
	}

	/** 立即保存（没有变化时也写一次）。 */
	public synchronized void save() {
		if (file == null) {
			return;
		}
		try {
			File parent = file.getParentFile();
			if (parent != null) {
				parent.mkdirs();
			}
			JsonObject root = new JsonObject();
			root.addProperty("updated_at", System.currentTimeMillis());
			JsonArray userArray = new JsonArray();
			for (Profile profile : profiles.values()) {
				userArray.add(profile.toJson());
			}
			root.add("users", userArray);
			JsonObject groupObject = new JsonObject();
			for (Map.Entry<String, GroupProfile> entry : groups.entrySet()) {
				groupObject.add(entry.getKey(), entry.getValue().toJson());
			}
			root.add("groups", groupObject);
			FileUtil.transfer(root.toString(), file);
			dirty = false;
		} catch (Throwable t) {
			System.err.println("[ProfileCache] 保存失败：" + t);
		}
	}

	@Override
	public synchronized void close() {
		save();
		if (saver != null) {
			saver.shutdownNow();
		}
	}

	private void saveIfDirty() {
		synchronized (this) {
			if (!dirty) {
				return;
			}
		}
		save();
	}

	private boolean load() {
		if (file == null || !file.isFile()) {
			return false;
		}
		try {
			JsonObject root = JsonParser.parseString(FileUtil.readString(file)).getAsJsonObject();
			if (root.has("users") && root.get("users").isJsonArray()) {
				for (JsonElement element : root.getAsJsonArray("users")) {
					if (element == null || !element.isJsonObject()) {
						continue;
					}
					JsonObject json = element.getAsJsonObject();
					String key = JsonConfig.getStringOrEmpty(json, "key");
					if (key.isEmpty()) {
						continue;
					}
					Profile profile = new Profile(key);
					profile.unionId = JsonConfig.getStringOrEmpty(json, "union_openid");
					profile.name = JsonConfig.getStringOrEmpty(json, "name");
					profile.avatar = JsonConfig.getStringOrEmpty(json, "avatar");
					profile.updatedAt = JsonConfig.getLong(json, "updated_at", 0L);
					if (json.has("ids") && json.get("ids").isJsonArray()) {
						for (JsonElement idElement : json.getAsJsonArray("ids")) {
							if (idElement != null && idElement.isJsonPrimitive()) {
								profile.ids.add(idElement.getAsString());
							}
						}
					}
					profiles.put(key, profile);
					aliases.put(key, key);
					if (!profile.unionId.isEmpty()) {
						aliases.put(profile.unionId, key);
					}
					for (String id : profile.ids) {
						aliases.put(id, key);
					}
				}
			}
			if (root.has("groups") && root.get("groups").isJsonObject()) {
				for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("groups").entrySet()) {
					if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
						continue;
					}
					JsonObject json = entry.getValue().getAsJsonObject();
					GroupProfile group = new GroupProfile(entry.getKey());
					group.name = JsonConfig.getStringOrEmpty(json, "name");
					group.avatar = JsonConfig.getStringOrEmpty(json, "avatar");
					group.updatedAt = JsonConfig.getLong(json, "updated_at", 0L);
					if (json.has("members") && json.get("members").isJsonObject()) {
						for (Map.Entry<String, JsonElement> memberEntry : json.getAsJsonObject("members")
								.entrySet()) {
							if (memberEntry.getValue() != null && memberEntry.getValue().isJsonPrimitive()) {
								group.members.put(memberEntry.getKey(), memberEntry.getValue().getAsString());
							}
						}
					}
					groups.put(group.groupId, group);
				}
			}
			return true;
		} catch (Throwable t) {
			System.err.println("[ProfileCache] 读取失败：" + t);
			return false;
		}
	}

	/** 把 fromKey 合并到 intoKey，并修正别名。 */
	private void mergeInto(String intoKey, String fromKey) {
		if (fromKey == null || fromKey.isEmpty() || intoKey.equals(fromKey)) {
			return;
		}
		Profile from = profiles.remove(fromKey);
		if (from == null) {
			return;
		}
		Profile into = profiles.get(intoKey);
		if (into == null) {
			profiles.put(intoKey, from);
		} else {
			if (into.unionId.isEmpty()) {
				into.unionId = from.unionId;
			}
			if (into.name.isEmpty()) {
				into.name = from.name;
			}
			if (into.avatar.isEmpty()) {
				into.avatar = from.avatar;
			}
			into.ids.addAll(from.ids);
			into.updatedAt = Math.max(into.updatedAt, from.updatedAt);
		}
		for (String id : from.ids) {
			aliases.put(id, intoKey);
		}
		if (!from.unionId.isEmpty()) {
			aliases.put(from.unionId, intoKey);
		}
		aliases.put(fromKey, intoKey);
		// 其它指向旧主键的别名也一起改过去
		for (Map.Entry<String, String> entry : aliases.entrySet()) {
			if (fromKey.equals(entry.getValue())) {
				entry.setValue(intoKey);
			}
		}
	}

	private String aliasOf(String id) {
		String value = trim(id);
		return value.isEmpty() ? null : aliases.get(value);
	}

	private static String firstNonEmpty(String... values) {
		for (String value : values) {
			if (value != null && !value.isEmpty()) {
				return value;
			}
		}
		return "";
	}

	private static String trim(String value) {
		return value == null ? "" : value.trim();
	}

}
