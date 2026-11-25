package com.khjxiaogu.aiwuxia.sheet;

import java.util.Locale;

/**
 * 电子表格供应商。
 * <p>
 * 通过配置里的字符串选择实现，默认 {@link #FEISHU}。支持常用别名，大小写不敏感：
 * </p>
 * <ul>
 * <li>{@code tencent}、{@code tencent-docs}、{@code tencent_docs}、{@code docs}、{@code qq} → 腾讯文档；</li>
 * <li>{@code feishu}、{@code lark}、{@code bitable}、{@code feishu-bitable} → 飞书多维表格。</li>
 * </ul>
 */
public enum SheetProvider {
	/** 腾讯文档在线表格。 */
	TENCENT("tencent", "腾讯文档"),
	/** 飞书多维表格（Bitable）。 */
	FEISHU("feishu", "飞书多维表格");

	private final String id;
	private final String displayName;

	SheetProvider(String id, String displayName) {
		this.id = id;
		this.displayName = displayName;
	}

	/**
	 * @return 配置里使用的稳定标识，例如 {@code tencent}
	 */
	public String getId() {
		return id;
	}

	/**
	 * @return 用于日志/界面的中文名称
	 */
	public String getDisplayName() {
		return displayName;
	}

	/**
	 * 解析供应商配置；空值返回 {@link #FEISHU}。
	 *
	 * @param raw 配置值
	 * @return 供应商
	 * @throws IllegalArgumentException 未知供应商
	 */
	public static SheetProvider parse(String raw) {
		if (raw == null || raw.trim().isEmpty()) {
			return FEISHU;
		}
		String value = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
		switch (value) {
		case "tencent":
		case "tencent-docs":
		case "docs":
		case "qq":
			return TENCENT;
		case "feishu":
		case "lark":
		case "bitable":
		case "feishu-bitable":
			return FEISHU;
		default:
			throw new IllegalArgumentException(
					"未知的电子表格供应商：" + raw + "（可选：tencent、feishu）");
		}
	}

	@Override
	public String toString() {
		return id;
	}
}
