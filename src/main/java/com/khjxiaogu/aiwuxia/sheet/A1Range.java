package com.khjxiaogu.aiwuxia.sheet;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A1 表示法描述的一片连续单元格范围。
 * <p>
 * 支持 {@code "A1"}、{@code "B2:D10"}、{@code "$B$2:$D$10"} 以及带工作表前缀的
 * {@code "BB08J2!A1:D10"}（前缀会被忽略，工作表由调用方单独指定）。行列下标都从 0 开始，
 * 结束下标是<b>开区间</b>（与 Google Sheets、腾讯文档 V3 的 GridRange 一致），因此
 * {@code "A1:D10"} 的 {@code endRow} 为 10、{@code endColumn} 为 4。
 * </p>
 * <p>
 * 该类型只做地址解析与范围计算，不涉及任何厂商协议；具体 API 的尺寸上限由实现类校验。
 * </p>
 */
public final class A1Range {
	/** 单元格地址：可选绝对标记 + 1~3 位列字母 + 可选绝对标记 + 行号。 */
	private static final Pattern CELL = Pattern.compile("^\\$?([A-Za-z]{1,3})\\$?([0-9]{1,7})$");
	/** 支持的最后一列（XFD，与主流表格软件一致）。 */
	private static final int MAX_COLUMN_INDEX = 16383;

	private final int startRow;
	private final int startColumn;
	private final int endRow;
	private final int endColumn;

	private A1Range(int startRow, int startColumn, int endRow, int endColumn) {
		this.startRow = startRow;
		this.startColumn = startColumn;
		this.endRow = endRow;
		this.endColumn = endColumn;
	}

	/**
	 * 解析 A1 范围。
	 *
	 * @param range A1 表示法，例如 {@code "A1"}、{@code "A1:D10"}
	 * @return 解析后的范围
	 * @throws IllegalArgumentException 格式不正确、起止顺序颠倒或列号越界
	 */
	public static A1Range parse(String range) {
		if (range == null) {
			throw new IllegalArgumentException("范围不能为 null");
		}
		String text = range.trim();
		if (text.isEmpty()) {
			throw new IllegalArgumentException("范围不能为空");
		}
		int bang = text.indexOf('!');
		if (bang >= 0) {
			text = text.substring(bang + 1).trim();
		}
		int colon = text.indexOf(':');
		String first = colon < 0 ? text : text.substring(0, colon);
		String second = colon < 0 ? text : text.substring(colon + 1);
		int[] start = parseCell(first);
		int[] end = colon < 0 ? start : parseCell(second);
		if (end[0] < start[0] || end[1] < start[1]) {
			throw new IllegalArgumentException("范围起止顺序颠倒：" + range);
		}
		return new A1Range(start[0], start[1], end[0] + 1, end[1] + 1);
	}

	private static int[] parseCell(String cell) {
		String text = cell == null ? "" : cell.trim();
		Matcher matcher = CELL.matcher(text);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("无法识别的单元格地址：" + text);
		}
		int column = columnIndex(matcher.group(1));
		int row;
		try {
			row = Integer.parseInt(matcher.group(2));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("无法识别的行号：" + text);
		}
		if (row < 1) {
			throw new IllegalArgumentException("行号必须从 1 开始：" + text);
		}
		return new int[] { row - 1, column };
	}

	/**
	 * 列字母转下标（A=0、Z=25、AA=26）。
	 *
	 * @param letters 列字母
	 * @return 列下标
	 */
	public static int columnIndex(String letters) {
		if (letters == null || letters.isEmpty()) {
			throw new IllegalArgumentException("列字母不能为空");
		}
		int index = 0;
		for (int i = 0; i < letters.length(); i++) {
			char c = Character.toUpperCase(letters.charAt(i));
			if (c < 'A' || c > 'Z') {
				throw new IllegalArgumentException("非法列字母：" + letters);
			}
			index = index * 26 + (c - 'A' + 1);
			if (index > MAX_COLUMN_INDEX + 1) {
				throw new IllegalArgumentException("列号超出范围：" + letters);
			}
		}
		return index - 1;
	}

	/**
	 * 列下标转列字母（0=A、25=Z、26=AA）。
	 *
	 * @param index 列下标
	 * @return 列字母
	 */
	public static String columnName(int index) {
		if (index < 0) {
			throw new IllegalArgumentException("列下标不能为负数：" + index);
		}
		StringBuilder sb = new StringBuilder();
		int value = index + 1;
		while (value > 0) {
			int remainder = (value - 1) % 26;
			sb.insert(0, (char) ('A' + remainder));
			value = (value - 1) / 26;
		}
		return sb.toString();
	}

	/**
	 * @return 起始行（0 开始）
	 */
	public int getStartRow() {
		return startRow;
	}

	/**
	 * @return 起始列（0 开始）
	 */
	public int getStartColumn() {
		return startColumn;
	}

	/**
	 * @return 结束行（不含）
	 */
	public int getEndRow() {
		return endRow;
	}

	/**
	 * @return 结束列（不含）
	 */
	public int getEndColumn() {
		return endColumn;
	}

	/**
	 * @return 行数
	 */
	public int getRowCount() {
		return endRow - startRow;
	}

	/**
	 * @return 列数
	 */
	public int getColumnCount() {
		return endColumn - startColumn;
	}

	/**
	 * @return 单元格总数
	 */
	public long getCellCount() {
		return (long) getRowCount() * getColumnCount();
	}

	/**
	 * @return 左上角单元格，例如 {@code "B2"}
	 */
	public String getStartCell() {
		return cellName(startRow, startColumn);
	}

	private static String cellName(int row, int column) {
		return columnName(column) + (row + 1);
	}

	/**
	 * 转回 A1 表示法；单个单元格不带冒号。
	 *
	 * @return 形如 {@code "A1"} 或 {@code "A1:D10"} 的字符串
	 */
	public String toA1() {
		String start = getStartCell();
		if (getRowCount() == 1 && getColumnCount() == 1) {
			return start;
		}
		return start + ":" + cellName(endRow - 1, endColumn - 1);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof A1Range)) {
			return false;
		}
		A1Range other = (A1Range) o;
		return startRow == other.startRow && startColumn == other.startColumn && endRow == other.endRow
				&& endColumn == other.endColumn;
	}

	@Override
	public int hashCode() {
		return Objects.hash(startRow, startColumn, endRow, endColumn);
	}

	@Override
	public String toString() {
		return toA1();
	}
}
