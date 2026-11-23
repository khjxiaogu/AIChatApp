package com.khjxiaogu.aiwuxia.voice;

import java.util.Locale;

/**
 * 本地模型请求的预计总耗时（ETA）解析。
 * <p>
 * 同一个模型下不同 API/动作的耗时差别很大（例如 SDXL 的 {@code txt2img} 与
 * {@code interrogate}、Qwen 的 {@code text2image} 与 {@code edit}、H3 的 {@code video} /
 * {@code foley} / 截帧），因此每个处理器按“最具体的属性优先”传入一串属性名：
 * </p>
 * <ul>
 *   <li>API 级：{@code -DsdxlImg2imgEtaMs=60000}，命名见
 *       {@link #propertyName(String, String)}；</li>
 *   <li>模型级：{@code -DsdxlEtaMs=45000}，作为该模型所有 API 的兜底；</li>
 *   <li>都没有配置时返回调用方给出的默认值。</li>
 * </ul>
 * <p>
 * 只有正数才生效；属性缺失、空串或非法数字都继续往下一级找。这样既保留了原来的
 * {@code sdxlEtaMs} / {@code qwenEtaMs} / {@code h3EtaMs} 等模型级配置，又能给单个 API
 * 单独设置预计时间。
 * </p>
 */
public final class LocalModelEta {

	private LocalModelEta() {
	}

	/**
	 * 按顺序取第一个有效的预计耗时。
	 *
	 * @param defaultMs     所有属性都没配置时使用的默认值（毫秒，非正数按 1 处理）
	 * @param propertyNames 属性名，优先级从高到低；null / 空串会被跳过
	 * @return 预计总耗时（毫秒，至少 1）
	 */
	public static long resolve(long defaultMs, String... propertyNames) {
		if (propertyNames != null) {
			for (String name : propertyNames) {
				if (name == null || name.trim().isEmpty()) {
					continue;
				}
				String raw = System.getProperty(name.trim(), "").trim();
				if (raw.isEmpty()) {
					continue;
				}
				try {
					long value = Long.parseLong(raw);
					if (value > 0) {
						return value;
					}
				} catch (NumberFormatException ignored) {
					// 非法值按未配置处理，继续找下一级
				}
			}
		}
		return defaultMs > 0 ? defaultMs : 1L;
	}

	/**
	 * 组装“模型 + API”粒度的属性名。
	 * <p>
	 * 模型名转小写；API 名去掉非字母数字并转成驼峰片段；最后拼上 {@code EtaMs}。
	 * 例：{@code propertyName("sdxl", "img2img") == "sdxlImg2imgEtaMs"}、
	 * {@code propertyName("h3", "foley_segment") == "h3FoleySegmentEtaMs"}、
	 * {@code propertyName("qwen", "text2image") == "qwenText2imageEtaMs"}。
	 * </p>
	 *
	 * @param model 模型名，例如 {@code sdxl} / {@code qwen} / {@code h3}
	 * @param api   API/动作名；null 或空串返回空串（表示没有 API 级属性）
	 * @return 属性名，模型或 API 为空时返回空串
	 */
	public static String propertyName(String model, String api) {
		String base = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
		String segment = camel(api);
		if (base.isEmpty() || segment.isEmpty()) {
			return "";
		}
		return base + segment + "EtaMs";
	}

	/** 把 API 名转成属性名里的驼峰片段：{@code txt2img → Txt2img}。 */
	private static String camel(String raw) {
		if (raw == null) {
			return "";
		}
		StringBuilder builder = new StringBuilder(raw.length());
		boolean upper = true;
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (!Character.isLetterOrDigit(c)) {
				upper = true;
				continue;
			}
			builder.append(upper ? Character.toUpperCase(c) : c);
			upper = false;
		}
		return builder.toString();
	}
}
