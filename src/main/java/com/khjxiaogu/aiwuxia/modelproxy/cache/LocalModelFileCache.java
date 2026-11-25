package com.khjxiaogu.aiwuxia.modelproxy.cache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
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

	/**
	 * 打开或创建缓存目录。
	 *
	 * @param root     缓存根目录
	 * @param maxBytes 容量上限（字节），非正数表示不限制
	 */
	public LocalModelFileCache(File root, long maxBytes) {
		this.directory = root;
		this.objects = new File(root, OBJECTS_DIR);
		this.maxBytes = maxBytes;
		if (!objects.exists() && !objects.mkdirs() && !objects.isDirectory()) {
			System.err.println("[LocalModelCache] 无法创建缓存目录：" + objects);
		}
		this.totalBytes.set(scanBytes());
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
				+ misses.get() + " 写入=" + stores.get();
	}

	@Override
	public String toString() {
		return "LocalModelFileCache[" + summary() + "]";
	}
}
