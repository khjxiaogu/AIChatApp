package com.khjxiaogu.aiwuxia.sheet;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 与厂商无关的单元格值。
 * <p>
 * 支持文本、数字、超链接、时间与空单元格。读取厂商数据时，无法直接对应到这些类型的单元格
 * （例如地理位置、单选/多选）会降级为文本，避免信息丢失；写入时各厂商按自己的能力映射，
 * 例如腾讯文档 V3 写入只保证 {@code text/number/link}，时间会以格式化文本提交。
 * </p>
 */
public final class CellValue {
	/** 单元格值类型。 */
	public enum Kind {
		/** 空单元格 */
		EMPTY,
		/** 文本 */
		TEXT,
		/** 数字 */
		NUMBER,
		/** 超链接 */
		LINK,
		/** 时间 */
		TIME
	}

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final CellValue EMPTY = new CellValue(Kind.EMPTY, "", 0d, null, null, null);

	private final Kind kind;
	/** TEXT 的原文 */
	private final String text;
	private final double number;
	private final String linkUrl;
	private final String linkText;
	private final LocalDateTime time;

	private CellValue(Kind kind, String text, double number, String linkUrl, String linkText, LocalDateTime time) {
		this.kind = kind;
		this.text = text;
		this.number = number;
		this.linkUrl = linkUrl;
		this.linkText = linkText;
		this.time = time;
	}

	/**
	 * @return 空单元格
	 */
	public static CellValue empty() {
		return EMPTY;
	}

	/**
	 * 文本单元格；{@code null} 或空串返回空单元格。
	 *
	 * @param text 文本
	 * @return 单元格值
	 */
	public static CellValue of(String text) {
		if (text == null || text.isEmpty()) {
			return EMPTY;
		}
		return new CellValue(Kind.TEXT, text, 0d, null, null, null);
	}

	/**
	 * 数字单元格。
	 *
	 * @param number 数字
	 * @return 单元格值
	 */
	public static CellValue of(double number) {
		return new CellValue(Kind.NUMBER, "", number, null, null, null);
	}

	/**
	 * 数字单元格（按 double 存储，超出 double 精确范围的大整数会丢失精度）。
	 *
	 * @param number 数字
	 * @return 单元格值
	 */
	public static CellValue of(long number) {
		return of((double) number);
	}

	/**
	 * 超链接单元格；URL 为空时退化为文本，显示文本为空时用 URL 代替。
	 *
	 * @param url  链接地址
	 * @param text 显示文本
	 * @return 单元格值
	 */
	public static CellValue link(String url, String text) {
		String linkUrl = url == null ? "" : url.trim();
		String linkText = text == null ? "" : text.trim();
		if (linkUrl.isEmpty()) {
			return of(linkText);
		}
		if (linkText.isEmpty()) {
			linkText = linkUrl;
		}
		return new CellValue(Kind.LINK, "", 0d, linkUrl, linkText, null);
	}

	/**
	 * 时间单元格。
	 *
	 * @param time 时间，{@code null} 返回空单元格
	 * @return 单元格值
	 */
	public static CellValue time(LocalDateTime time) {
		if (time == null) {
			return EMPTY;
		}
		return new CellValue(Kind.TIME, "", 0d, null, null, time);
	}

	/**
	 * @return 值类型
	 */
	public Kind getKind() {
		return kind;
	}

	/**
	 * 展示文本：TEXT 原文、LINK 显示文本、NUMBER 格式化数字、TIME 格式化时间，空单元格为空串。
	 *
	 * @return 可直接展示/写入文本单元格的字符串
	 */
	public String getText() {
		return asText();
	}

	/**
	 * @return NUMBER 的数值；其他类型为 0
	 */
	public double getNumber() {
		return number;
	}

	/**
	 * @return LINK 的地址；其他类型为 {@code null}
	 */
	public String getLinkUrl() {
		return linkUrl;
	}

	/**
	 * @return LINK 的显示文本；其他类型为 {@code null}
	 */
	public String getLinkText() {
		return linkText;
	}

	/**
	 * @return TIME 的时间；其他类型为 {@code null}
	 */
	public LocalDateTime getTime() {
		return time;
	}

	/**
	 * 是否为空单元格（或只有空白文本）。
	 *
	 * @return 没有可展示内容时返回 true
	 */
	public boolean isBlank() {
		switch (kind) {
		case TEXT:
			return text.trim().isEmpty();
		case LINK:
			return (linkText == null || linkText.trim().isEmpty()) && (linkUrl == null || linkUrl.trim().isEmpty());
		case NUMBER:
		case TIME:
			return false;
		default:
			return true;
		}
	}

	/**
	 * 与 {@link #getText()} 相同，语义更明确。
	 *
	 * @return 展示文本
	 */
	public String asText() {
		switch (kind) {
		case TEXT:
			return text;
		case NUMBER:
			return formatNumber(number);
		case LINK:
			return linkText == null || linkText.isEmpty() ? linkUrl : linkText;
		case TIME:
			return time == null ? "" : TIME_FORMAT.format(time);
		default:
			return "";
		}
	}

	private static String formatNumber(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return Double.toString(value);
		}
		return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof CellValue)) {
			return false;
		}
		CellValue other = (CellValue) o;
		return kind == other.kind && Double.compare(number, other.number) == 0 && Objects.equals(text, other.text)
				&& Objects.equals(linkUrl, other.linkUrl) && Objects.equals(linkText, other.linkText)
				&& Objects.equals(time, other.time);
	}

	@Override
	public int hashCode() {
		return Objects.hash(kind, text, number, linkUrl, linkText, time);
	}

	@Override
	public String toString() {
		return kind + "(" + asText() + ")";
	}
}
