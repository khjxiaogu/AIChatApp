package com.khjxiaogu.aiwuxia.sheet;

import java.util.Objects;

/**
 * 工作表（子表）的元数据。
 * <p>
 * 不同厂商能提供的字段不同：未知的数值字段用 {@code -1} 表示。{@link #getRowCount()} 与
 * {@link #getColumnCount()} 表示<b>已使用区域</b>的边界（例如数据写到第 5 行为止，则 rowCount 为 5，
 * 下一个可追加位置是第 6 行）；{@link #getRowTotal()} 与 {@link #getColumnTotal()} 表示工作表的总行/列数。
 * </p>
 */
public final class SheetMeta {
	private final String sheetId;
	private final String title;
	private final int rowCount;
	private final int columnCount;
	private final int rowTotal;
	private final int columnTotal;

	public SheetMeta(String sheetId, String title) {
		this(sheetId, title, -1, -1, -1, -1);
	}

	public SheetMeta(String sheetId, String title, int rowCount, int columnCount, int rowTotal, int columnTotal) {
		this.sheetId = sheetId;
		this.title = title;
		this.rowCount = rowCount;
		this.columnCount = columnCount;
		this.rowTotal = rowTotal;
		this.columnTotal = columnTotal;
	}

	/**
	 * @return 工作表 ID（唯一标识），厂商未返回时为 {@code null}
	 */
	public String getSheetId() {
		return sheetId;
	}

	/**
	 * @return 工作表名称，厂商未返回时为空串
	 */
	public String getTitle() {
		return title == null ? "" : title;
	}

	/**
	 * @return 已使用区域的行数；{@code -1} 表示厂商未提供
	 */
	public int getRowCount() {
		return rowCount;
	}

	/**
	 * @return 已使用区域的列数；{@code -1} 表示厂商未提供
	 */
	public int getColumnCount() {
		return columnCount;
	}

	/**
	 * @return 工作表总行数；{@code -1} 表示厂商未提供
	 */
	public int getRowTotal() {
		return rowTotal;
	}

	/**
	 * @return 工作表总列数；{@code -1} 表示厂商未提供
	 */
	public int getColumnTotal() {
		return columnTotal;
	}

	/**
	 * 取名称用于展示；没有名称时退回工作表 ID。
	 *
	 * @return 名称或 ID
	 */
	public String titleOrId() {
		String name = getTitle();
		return name.isEmpty() ? sheetId : name;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof SheetMeta)) {
			return false;
		}
		SheetMeta other = (SheetMeta) o;
		return rowCount == other.rowCount && columnCount == other.columnCount && rowTotal == other.rowTotal
				&& columnTotal == other.columnTotal && Objects.equals(sheetId, other.sheetId)
				&& Objects.equals(title, other.title);
	}

	@Override
	public int hashCode() {
		return Objects.hash(sheetId, title, rowCount, columnCount, rowTotal, columnTotal);
	}

	@Override
	public String toString() {
		return "SheetMeta[sheetId=" + sheetId + ", title=" + getTitle() + ", rowCount=" + rowCount
				+ ", columnCount=" + columnCount + ", rowTotal=" + rowTotal + ", columnTotal=" + columnTotal + "]";
	}
}
