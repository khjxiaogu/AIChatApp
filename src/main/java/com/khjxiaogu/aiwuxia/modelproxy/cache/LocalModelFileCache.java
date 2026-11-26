package com.khjxiaogu.aiwuxia.modelproxy.cache;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.khjxiaogu.aiwuxia.utils.ContentHash;
import com.khjxiaogu.aiwuxia.utils.FileUtil;

/**
 * 本地模型部署侧的磁盘缓存（内容寻址）。
 * <p>
 * 部署侧有两个使用场景，共用同一份缓存：
 * </p>
 * <ul>
 *   <li><b>输出</b>：处理器返回的结果按内容键落盘。服务端把结果存进对象存储时用的是同一套键
 *       （{@link ContentHash}），所以“上次生成的图片/视频”接下来被当作输入时，服务端只要在附件里
 *       带上这个键，部署侧就能直接读本地文件，不必再走一次附件拉取；</li>
 *   <li><b>输入</b>：从服务端拉回来的附件同样按内容键落盘。同一份文件再次出现（哪怕换了一次请求、
 *       换了一个附件 id）时可以直接命中，不再下载。</li>
 *   <li><b>URL</b>：本地模型客户端直接下载的附件 URL 也会记录映射（{@code urls.index}，
 *       只保存 URL 的哈希）。同一个链接再次出现时按映射直接命中文件，不再重复下载。</li>
 * </ul>
 * <p>
 * 缓存目录默认是 {@code save/localmodel_cache}，可用 {@code -DlocalModelCacheDir} 覆盖；上限默认
 * 4096 MB，可用 {@code -DlocalModelCacheMb} 调整（{@code 0} 表示不限制，负数表示关闭缓存）。
 * {@code -DlocalModelCache=off} 也可以直接关闭。超出上限时按最后访问时间从旧到新删除，
 * 直到降到上限的 90%。
 * </p>
 */
public final class LocalModelFileCache {

	/** 关闭缓存 / 指定目录 / 上限（MB）的系统属性名。 */
	public static final String ENABLED_PROPERTY = "localModelCache";
	/** 缓存目录的系统属性名。 */
	public static final String DIR_PROPERTY = "localModelCacheDir";
	/** 缓存上限（MB）的系统属性名。 */
	public static final String MAX_MB_PROPERTY = "localModelCacheMb";
	/** 默认上限：4 GB。 */
	public static final long DEFAULT_MAX_MB = 4096L;
	/** 缓存内容存放的子目录。 */
	private static final String OBJECTS_DIR = "objects";
	/** URL → 内容键索引文件名（只保存 URL 的哈希，避免把带签名的长链接写进磁盘）。 */
	private static final String URL_INDEX_FILE = "urls.index";
	/** 索引条目超过该值时重写索引文件，丢掉已被淘汰的映射。 */
	private static final int URL_INDEX_COMPACT_THRESHOLD = 4096;

	/** 当前部署进程使用的缓存实例；没有安装时为空。 */
	private static volatile LocalModelFileCache shared;

	/**
	 * 当前部署进程使用的缓存实例。
	 * <p>
	 * 由 {@code LocalModelConnectionService} 在建立连接时安装，供没有拿到实例的处理器
	 * （H3/Qwen 的旧报文处理器）把输入文件也写入同一份缓存。
	 * </p>
	 *
	 * @return 缓存实例；未启用缓存时为 null
	 */
	public static LocalModelFileCache shared() {
		return shared;
	}

	/**
	 * 安装当前进程使用的缓存实例。
	 *
	 * @param cache 缓存实例，null 表示取消
	 */
	public static void install(LocalModelFileCache cache) {
		shared = cache;
	}

	/**
	 * 按系统属性打开缓存；关闭或配置非法时返回 {@code null}。
	 *
	 * @param dataFolder 运行数据目录（save/）
	 * @return 缓存实例，或 null（表示不启用缓存）
	 */
	public static LocalModelFileCache open(File dataFolder) {
		String enabled = System.getProperty(ENABLED_PROPERTY, "").trim();
		if ("off".equalsIgnoreCase(enabled) || "false".equalsIgnoreCase(enabled)
				|| "0".equalsIgnoreCase(enabled)) {
			return null;
		}
		long maxMb = readMaxMb();
		if (maxMb < 0) {
			return null;
		}
		String configuredDir = System.getProperty(DIR_PROPERTY, "").trim();
		File root = configuredDir.isEmpty() ? new File(dataFolder, "localmodel_cache") : new File(configuredDir);
		long maxBytes = maxMb == 0 ? -1L : maxMb * 1024L * 1024L;
		return new LocalModelFileCache(root, maxBytes);
	}

	/** 读取上限（MB）：缺省 {@value #DEFAULT_MAX_MB}，非法值同样回退默认。 */
	private static long readMaxMb() {
		String raw = System.getProperty(MAX_MB_PROPERTY, "").trim();
		if (raw.isEmpty()) {
			return DEFAULT_MAX_MB;
		}
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException ex) {
			return DEFAULT_MAX_MB;
		}
	}

	/** 缓存根目录。 */
	private final File directory;
	/** 内容文件目录。 */
	private final File objects;
	/** 容量上限（字节）；非正数表示不限制。 */
	private final long maxBytes;

	/** 当前占用字节数（近似值，按写入/删除维护）。 */
	private final AtomicLong totalBytes = new AtomicLong();
	/** 命中次数（按附件内容键直接命中）。 */
	private final AtomicLong hits = new AtomicLong();
	/** 未命中次数。 */
	private final AtomicLong misses = new AtomicLong();
	/** 新写入次数。 */
	private final AtomicLong stores = new AtomicLong();
	/** URL 索引：URL 哈希 → 内容键。 */
	private final Map<String, String> urlIndex = new ConcurrentHashMap<>();
	/** URL 索引文件（与内容文件一起位于缓存根目录）。 */
	private final File urlIndexFile;
	/** 索引写入锁。 */
	private final Object urlIndexLock = new Object();

	/**
	 * 打开或创建缓存目录。
	 *
	 * @param root     缓存根目录
	 * @param maxBytes 容量上限（字节），非正数表示不限制
	 */
	public LocalModelFileCache(File root, long maxBytes) {
		this.directory = root;
		this.objects = new File(root, OBJECTS_DIR);
		this.urlIndexFile = new File(root, URL_INDEX_FILE);
		this.maxBytes = maxBytes;
		if (!objects.exists() && !objects.mkdirs() && !objects.isDirectory()) {
			System.err.println("[LocalModelCache] 无法创建缓存目录：" + objects);
		}
		this.totalBytes.set(scanBytes());
		loadUrlIndex();
		pruneIfNeeded();
	}

	/** 缓存根目录（日志/诊断用）。 */
	public File directory() {
		return directory;
	}

	/** 容量上限（字节）；非正数表示不限制。 */
	public long maxBytes() {
		return maxBytes;
	}

	/**
	 * 按内容键读取缓存。
	 *
	 * @param key 内容键（对象 id，可带扩展名）
	 * @return 内容；不存在或读取失败时返回 null
	 */
	public byte[] get(String key) {
		String normalized = ContentHash.normalize(key);
		if (normalized == null) {
			return null;
		}
		File file = new File(objects, normalized);
		if (!file.isFile()) {
			misses.incrementAndGet();
			return null;
		}
		try {
			byte[] data = FileUtil.readAll(file);
			hits.incrementAndGet();
			touch(file);
			return data;
		} catch (IOException ex) {
			System.err.println("[LocalModelCache] 读取缓存失败：" + normalized + " 错误=" + ex);
			return null;
		}
	}

	/**
	 * 判断内容是否已在缓存里（不读取内容）。
	 *
	 * @param key 内容键
	 * @return 存在时返回 true
	 */
	public boolean contains(String key) {
		String normalized = ContentHash.normalize(key);
		return normalized != null && new File(objects, normalized).isFile();
	}

	/**
	 * 按 URL 查缓存键。
	 * <p>
	 * 用于“同一个链接第二次出现”时跳过下载。映射里指向的内容如果已经被淘汰，会顺手清掉该条映射。
	 * </p>
	 *
	 * @param url 下载地址
	 * @return 内容键；没有映射或内容已不存在时返回 null
	 */
	public String keyForUrl(String url) {
		if (url == null || url.isEmpty()) {
			return null;
		}
		String hash = urlHash(url);
		String key = urlIndex.get(hash);
		if (key == null) {
			return null;
		}
		if (!contains(key)) {
			urlIndex.remove(hash, key);
			return null;
		}
		return key;
	}

	/**
	 * 记录 URL 与内容键的对应关系，并持久化到 {@code urls.index}。
	 * <p>
	 * 只保存 URL 的哈希：链接里可能带签名/令牌，不能原样落盘。
	 * </p>
	 *
	 * @param url 下载地址
	 * @param key 内容键（72 位十六进制，可带扩展名）
	 */
	public void rememberUrl(String url, String key) {
		String normalized = ContentHash.normalize(key);
		if (url == null || url.isEmpty() || normalized == null) {
			return;
		}
		String hash = urlHash(url);
		String previous = urlIndex.put(hash, normalized);
		if (normalized.equals(previous)) {
			return;
		}
		synchronized (urlIndexLock) {
			appendUrlIndex(hash, normalized);
			if (urlIndex.size() > URL_INDEX_COMPACT_THRESHOLD) {
				rewriteUrlIndex();
			}
		}
	}

	/** URL 的索引键：SHA-256(URL) + 长度，与内容键同样的 72 位十六进制。 */
	private static String urlHash(String url) {
		return ContentHash.keyOf(url.getBytes(StandardCharsets.UTF_8));
	}

	/** 启动时读取索引，丢掉已经失效的映射。 */
	private void loadUrlIndex() {
		if (!urlIndexFile.isFile()) {
			return;
		}
		int loaded = 0;
		int dropped = 0;
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new FileInputStream(urlIndexFile), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				int tab = line.indexOf('\t');
				if (tab <= 0) {
					continue;
				}
				String hash = line.substring(0, tab).trim();
				String key = ContentHash.normalize(line.substring(tab + 1));
				if (hash.length() != ContentHash.KEY_LENGTH || key == null) {
					dropped++;
					continue;
				}
				if (!contains(key)) {
					dropped++;
					continue;
				}
				urlIndex.put(hash, key);
				loaded++;
			}
		} catch (IOException ex) {
			System.err.println("[LocalModelCache] 读取 URL 索引失败：" + ex);
			return;
		}
		System.out.println("[LocalModelCache] URL 索引已加载：可用 " + loaded + " 条，丢弃 " + dropped + " 条");
		if (dropped > 0) {
			synchronized (urlIndexLock) {
				rewriteUrlIndex();
			}
		}
	}

	/** 追加一条 URL 映射。 */
	private void appendUrlIndex(String hash, String key) {
		try (FileOutputStream out = new FileOutputStream(urlIndexFile, true)) {
			out.write((hash + "\t" + key + "\n").getBytes(StandardCharsets.UTF_8));
		} catch (IOException ex) {
			System.err.println("[LocalModelCache] 写入 URL 索引失败：" + ex);
		}
	}

	/** 重写 URL 索引（先写临时文件再原子替换）。 */
	private void rewriteUrlIndex() {
		File temp = new File(directory, URL_INDEX_FILE + ".tmp");
		List<String> lines = new ArrayList<>(urlIndex.size());
		for (Map.Entry<String, String> entry : urlIndex.entrySet()) {
			if (contains(entry.getValue())) {
				lines.add(entry.getKey() + "\t" + entry.getValue());
			}
		}
		try (FileOutputStream out = new FileOutputStream(temp)) {
			for (String line : lines) {
				out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
			}
		} catch (IOException ex) {
			temp.delete();
			System.err.println("[LocalModelCache] 重写 URL 索引失败：" + ex);
			return;
		}
		try {
			Files.move(temp.toPath(), urlIndexFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomicFailed) {
			try {
				Files.move(temp.toPath(), urlIndexFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ex) {
				temp.delete();
				System.err.println("[LocalModelCache] 替换 URL 索引失败：" + ex);
			}
		}
	}

	/**
	 * 把内容写入缓存（已存在时只更新访问时间）。
	 *
	 * @param data 内容
	 * @return 内容键；内容为空或写入失败时返回 null
	 */
	public String put(byte[] data) {
		StoreResult result = store(data);
		return result == null ? null : result.key;
	}

	/**
	 * 把内容写入缓存，并返回是本次新写入还是已存在。
	 *
	 * @param data 内容
	 * @return 写入结果；内容为空或写入失败时返回 null
	 */
	public StoreResult store(byte[] data) {
		if (data == null || data.length == 0) {
			return null;
		}
		String key = ContentHash.keyOf(data);
		File target = new File(objects, key);
		if (target.isFile()) {
			// 相同内容已经缓存过：只刷新访问时间，不重写文件
			touch(target);
			return new StoreResult(key, false);
		}
		File temp = new File(objects, key + "." + Long.toHexString(System.nanoTime()) + ".tmp");
		try {
			try (FileOutputStream out = new FileOutputStream(temp)) {
				out.write(data);
			}
			move(temp, target);
		} catch (IOException ex) {
			temp.delete();
			System.err.println("[LocalModelCache] 写入缓存失败：" + key + " 错误=" + ex);
			return null;
		}
		totalBytes.addAndGet(data.length);
		stores.incrementAndGet();
		pruneIfNeeded();
		return new StoreResult(key, true);
	}

	/** 一次写入的结果：内容键 + 是否新写入。 */
	public static final class StoreResult {
		/** 内容键。 */
		public final String key;
		/** true 表示本次新写入；false 表示缓存里本来就有。 */
		public final boolean created;

		StoreResult(String key, boolean created) {
			this.key = key;
			this.created = created;
		}
	}

	/** 先尝试原子移动，文件系统不支持时退回普通覆盖移动。 */
	private static void move(File source, File target) throws IOException {
		try {
			Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException atomicFailed) {
			Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** 更新访问时间，供按最久未使用淘汰。 */
	private static void touch(File file) {
		file.setLastModified(System.currentTimeMillis());
	}

	/** 扫描缓存目录，得到当前占用字节数。 */
	private long scanBytes() {
		File[] files = objects.listFiles();
		if (files == null) {
			return 0L;
		}
		long total = 0L;
		for (File file : files) {
			if (file.isFile()) {
				total += file.length();
			}
		}
		return total;
	}

	/** 超过上限时按最后访问时间删除旧文件，直到降到上限的 90%。 */
	private void pruneIfNeeded() {
		if (maxBytes <= 0) {
			return;
		}
		if (totalBytes.get() <= maxBytes) {
			return;
		}
		synchronized (this) {
			long size = totalBytes.get();
			if (size <= maxBytes) {
				return;
			}
			File[] files = objects.listFiles();
			if (files == null) {
				totalBytes.set(0L);
				return;
			}
			Arrays.sort(files, Comparator.comparingLong(File::lastModified));
			long target = maxBytes * 9 / 10;
			for (File file : files) {
				if (size <= target) {
					break;
				}
				long length = file.length();
				if (file.delete()) {
					size -= length;
				}
			}
			totalBytes.set(Math.max(0L, size));
			System.out.println("[LocalModelCache] 缓存超过上限，已清理到 " + size + " 字节");
		}
	}

	/**
	 * 当前缓存状态：命中/未命中/写入次数与占用空间。
	 *
	 * @return 一行可读状态
	 */
	public String summary() {
		return "目录=" + directory + " 占用=" + totalBytes.get() + "B 命中=" + hits.get() + " 未命中="
				+ misses.get() + " 写入=" + stores.get() + " URL映射=" + urlIndex.size();
	}

	@Override
	public String toString() {
		return "LocalModelFileCache[" + summary() + "]";
	}
}
