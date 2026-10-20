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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据目录下配置文件的路径定义。
 * <p>
 * 站点的手工配置统一放在数据目录的 {@code config} 子目录，文件名取可读的英文名，
 * 生图（SDXL / Seedream）相关的放进 {@code config/sdxl} 子目录。
 * 代码里一律通过这里取值，避免路径字符串散落各处：
 * </p>
 * <pre>
 * save/
 * └─ config/
 *    ├─ object-storage.json     对象存储（火山 TOS）凭据
 *    ├─ image-generation.json   图片生成（Seedream）凭据
 *    ├─ voice-tags.txt          语音标签（配音情感标注）提示词
 *    ├─ local-voice.json        本地语音模型 bot 映射
 *    ├─ volcano-voice.json      火山语音 bot 映射
 *    ├─ agents.json             QQ 群 agent 清单
 *    ├─ crontab.json            定时任务
 *    └─ sdxl/                   生图相关
 *       ├─ character-tags.txt   角色 tag 库（提示词关键词）
 *       ├─ loras.json           LoRA 配置
 *       ├─ reference-images.json 参考图
 *       └─ agent/               绘画 agent 提示词（role.txt / charaset.txt / rules.txt / character-list.txt）
 * </pre>
 * <p>
 * 为兼容旧部署，新位置不存在时会退回 {@code save} 根目录下的旧位置（{@code save/tos.json} 等），
 * 并提示一次迁移建议；旧位置也不存在时返回新路径，便于报错信息直接指向应该放文件的位置。
 * </p>
 */
public final class ConfigPaths {

	/** 配置子目录名 */
	public static final String FOLDER = "config";
	/** 生图配置子目录名（{@code config} 下） */
	public static final String SDXL_FOLDER = "sdxl";

	// ---- 通用配置文件名 ----
	/** 对象存储凭据（原 {@code save/tos.json}） */
	public static final String OBJECT_STORAGE = "object-storage.json";
	/** 图片生成凭据（原 {@code save/img.json}） */
	public static final String IMAGE_GENERATION = "image-generation.json";
	/** 语音标签提示词（原 {@code save/voicetag.txt}） */
	public static final String VOICE_TAGS = "voice-tags.txt";
	/** 本地语音 bot 映射（原 {@code save/localvoice.json}） */
	public static final String LOCAL_VOICE = "local-voice.json";
	/** 火山语音 bot 映射（原 {@code save/volcanovoice.json}） */
	public static final String VOLCANO_VOICE = "volcano-voice.json";
	/** QQ 群 agent 清单（原 {@code save/agents.json}） */
	public static final String AGENTS = "agents.json";
	/** 定时任务（原 {@code save/crontab.json}） */
	public static final String CRONTAB = "crontab.json";

	// ---- 生图（config/sdxl）文件名 ----
	/** 角色 tag 库（原 {@code save/promptdo.txt}） */
	public static final String SDXL_CHARACTER_TAGS = "character-tags.txt";
	/** LoRA 配置（原 {@code save/loras.json}） */
	public static final String SDXL_LORAS = "loras.json";
	/** 参考图（原 {@code save/reference_image.json}） */
	public static final String SDXL_REFERENCE_IMAGES = "reference-images.json";
	/** 绘画 agent 提示词目录（原 {@code save/painteragent}） */
	public static final String SDXL_AGENT = "agent";
	/** 角色设定索引（作品公共设定 + 角色设定 + 别名，人类可读） */
	public static final String SDXL_CHARACTER_LIST = "character-list.txt";

	// ---- 旧位置，仅用于平滑升级 ----
	private static final String LEGACY_OBJECT_STORAGE = "tos.json";
	private static final String LEGACY_IMAGE_GENERATION = "img.json";
	private static final String LEGACY_VOICE_TAGS = "voicetag.txt";
	private static final String LEGACY_LOCAL_VOICE = "localvoice.json";
	private static final String LEGACY_VOLCANO_VOICE = "volcanovoice.json";
	private static final String LEGACY_AGENTS = "agents.json";
	private static final String LEGACY_CRONTAB = "crontab.json";
	private static final String LEGACY_SDXL_CHARACTER_TAGS = "promptdo.txt";
	private static final String LEGACY_SDXL_LORAS = "loras.json";
	private static final String LEGACY_SDXL_REFERENCE_IMAGES = "reference_image.json";
	private static final String LEGACY_SDXL_AGENT = "painteragent";

	/** 已提示过迁移建议的旧路径，避免每次调用都刷日志。 */
	private static final Set<String> LEGACY_NOTICED = ConcurrentHashMap.newKeySet();

	private ConfigPaths() {
	}

	/**
	 * 配置目录（{@code save/config}）。不保证已存在。
	 *
	 * @param dataFolder 数据目录
	 * @return 配置目录
	 */
	public static File folder(File dataFolder) {
		return new File(dataFolder, FOLDER);
	}

	/**
	 * 生图配置目录（{@code save/config/sdxl}）。不保证已存在。
	 *
	 * @param dataFolder 数据目录
	 * @return 生图配置目录
	 */
	public static File sdxlFolder(File dataFolder) {
		return new File(folder(dataFolder), SDXL_FOLDER);
	}

	/**
	 * 对象存储凭据文件。
	 *
	 * @param dataFolder 数据目录
	 * @return {@code config/object-storage.json}
	 */
	public static File objectStorage(File dataFolder) {
		return locate(folder(dataFolder), OBJECT_STORAGE, dataFolder, LEGACY_OBJECT_STORAGE);
	}

	/**
	 * 图片生成凭据文件。
	 *
	 * @param dataFolder 数据目录
	 * @return {@code config/image-generation.json}
	 */
	public static File imageGeneration(File dataFolder) {
		return locate(folder(dataFolder), IMAGE_GENERATION, dataFolder, LEGACY_IMAGE_GENERATION);
	}

	/**
	 * 语音标签提示词文件。
	 *
	 * @param dataFolder 数据目录
	 * @return {@code config/voice-tags.txt}
	 */
	public static File voiceTags(File dataFolder) {
		return locate(folder(dataFolder), VOICE_TAGS, dataFolder, LEGACY_VOICE_TAGS);
	}

	/**
	 * 本地语音 bot 映射文件。
	 *
	 * @param dataFolder 数据目录
	 * @return {@code config/local-voice.json}
	 */
	public static File localVoice(File dataFolder) {
		return locate(folder(dataFolder), LOCAL_VOICE, dataFolder, LEGACY_LOCAL_VOICE);
	}

	/**
	 * 火山语音 bot 映射文件。
	 *
	 * @param dataFolder 数据目录
	 * @return {@code config/volcano-voice.json}
	 */
	public static File volcanoVoice(File dataFolder) {
		return locate(folder(dataFolder), VOLCANO_VOICE, dataFolder, LEGACY_VOLCANO_VOICE);
	}

	/**
	 * QQ 群 agent 清单文件。
	 *
	 * @param dataFolder 数据目录
	 * @return {@code config/agents.json}
	 */
	public static File agents(File dataFolder) {
		return locate(folder(dataFolder), AGENTS, dataFolder, LEGACY_AGENTS);
	}

	/**
	 * 定时任务文件。
	 *
	 * @param dataFolder 数据目录
	 * @return {@code config/crontab.json}
	 */
	public static File crontab(File dataFolder) {
		return locate(folder(dataFolder), CRONTAB, dataFolder, LEGACY_CRONTAB);
	}

	/**
	 * 角色 tag 库文件（SDXL 提示词关键词）。
	 *
	 * @param dataFolder 数据目录
	 * @return {@code config/sdxl/character-tags.txt}
	 */
	public static File sdxlCharacterTags(File dataFolder) {
		return locate(sdxlFolder(dataFolder), SDXL_CHARACTER_TAGS, dataFolder, LEGACY_SDXL_CHARACTER_TAGS);
	}

	/**
	 * LoRA 配置文件。
	 *
	 * @param dataFolder 数据目录
	 * @return {@code config/sdxl/loras.json}
	 */
	public static File sdxlLoras(File dataFolder) {
		return locate(sdxlFolder(dataFolder), SDXL_LORAS, dataFolder, LEGACY_SDXL_LORAS);
	}

	/**
	 * 参考图配置文件。
	 *
	 * @param dataFolder 数据目录
	 * @return {@code config/sdxl/reference-images.json}
	 */
	public static File sdxlReferenceImages(File dataFolder) {
		return locate(sdxlFolder(dataFolder), SDXL_REFERENCE_IMAGES, dataFolder, LEGACY_SDXL_REFERENCE_IMAGES);
	}

	/**
	 * 绘画 agent 提示词目录。
	 *
	 * @param dataFolder 数据目录
	 * @return {@code config/sdxl/agent}
	 */
	public static File sdxlAgent(File dataFolder) {
		return locate(sdxlFolder(dataFolder), SDXL_AGENT, dataFolder, LEGACY_SDXL_AGENT);
	}

	/**
	 * 绘画 agent 相关文件可能所在的目录，新位置在前。
	 * <p>
	 * 迁移过程中可能出现“新目录只放了部分文件”的情况，调用方应依次在两个目录中查找目标文件，
	 * 而不是只看 {@link #sdxlAgent(File)} 返回的目录。
	 * </p>
	 *
	 * @param dataFolder 数据目录
	 * @return 候选目录列表，新位置在前，旧位置在后
	 */
	public static List<File> sdxlAgentCandidates(File dataFolder) {
		List<File> candidates = new ArrayList<>(2);
		candidates.add(new File(sdxlFolder(dataFolder), SDXL_AGENT));
		candidates.add(new File(dataFolder, LEGACY_SDXL_AGENT));
		return candidates;
	}

	/**
	 * 取配置文件：优先新位置，其次数据目录根下的旧位置（兼容旧部署），都没有时返回新位置。
	 *
	 * @param targetFolder 新位置所在目录
	 * @param name         新文件名
	 * @param dataFolder   数据目录
	 * @param legacyName   旧位置文件名（位于数据目录根下）
	 * @return 配置路径
	 */
	private static File locate(File targetFolder, String name, File dataFolder, String legacyName) {
		File target = new File(targetFolder, name);
		if (target.exists())
			return target;
		File legacy = new File(dataFolder, legacyName);
		if (legacy.exists()) {
			if (LEGACY_NOTICED.add(legacyName)) {
				System.out.println("[配置] 仍在使用旧位置 " + legacy.getPath()
					+ "，建议移动到 " + target.getPath() + "（新位置生效后旧位置会被忽略）");
			}
			return legacy;
		}
		return target;
	}
}
