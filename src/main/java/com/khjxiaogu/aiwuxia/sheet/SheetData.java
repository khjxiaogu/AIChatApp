package com.khjxiaogu.aiwuxia.sheet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次范围读取的结果。
 * <p>
 * 行列下标都从 0 开始；{@link #getStartRow()} / {@link #getStartColumn()} 表示结果左上角在工作表中的位置，
 * 与请求的 A1 范围左上角一致（厂商返回了起始位置时以厂商为准）。所有行会补齐到相同宽度，
 * 缺失的单元格用 {@link CellValue#empty()} 填充，便于按下标访问。
 * </p>
 */
public final class SheetData {
	private final int startRow;
	private final int startColumn;
	private final List<List<CellValue>> rows;

	public SheetData(int startRow, int startColumn, List<List<CellValue>> rows) {
		this.startRow = Math.max(0, startRow);
		this.startColumn = Math.max(0, startColumn);
		int width = 0;
		if (rows != null) {
			for (List<CellValue> row : rows) {
				if (row != null) {
					width = Math.max(width, row.size());
				}
			}
		}
		List<List<CellValue>> copy = new ArrayList<>();
		if (rows != null) {
			for (List<CellValue> row : rows) {
				List<CellValue> line = new ArrayList<>(width);
				for (int column = 0; column < width; column++) {
					CellValue value = row != null && column < row.size() ? row.get(column) : null;
					line.add(value == null ? CellValue.empty() : value);
				}
				copy.add(Collections.unmodifiableList(line));
			}
		}
		this.rows = Collections.unmodifiableList(copy);
	}

	/**
	 * @return 结果在工作表中的起始行（0 开始）
	 */
	public int getStartRow() {
		return startRow;
	}

	/**
	 * @return 结果在工作表中的起始列（0 开始）
	 */
	public int getStartColumn() {
		return startColumn;
	}

	/**
	 * @return 不可变的行数据，每行宽度相同
	 */
	public List<List<CellValue>> getRows() {
		return rows;
	}

	/**
	 * @return 行数
	 */
	public int getRowCount() {
		return rows.size();
	}

	/**
	 * @return 列数；没有数据时为 0
	 */
	public int getColumnCount() {
		return rows.isEmpty() ? 0 : rows.get(0).size();
	}

	/**
	 * 按下标取单元格，越界返回空单元格。
	 *
	 * @param row    行下标（0 开始）
	 * @param column 列下标（0 开始）
	 * @return 单元格值
	 */
	public CellValue getCell(int row, int column) {
		if (row < 0 || row >= rows.size() || column < 0 || column >= getColumnCount()) {
			return CellValue.empty();
		}
		return rows.get(row).get(column);
	}

	/**
	 * 转成制表符分隔的文本（TSV），便于直接给模型或写日志。
	 *
	 * @return 按行用 {@code \n}、按列用 {@code \t} 分隔的文本
	 */
	public String toText() {
		StringBuilder sb = new StringBuilder();
		for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
			if (rowIndex > 0) {
				sb.append('\n');
			}
			List<CellValue> row = rows.get(rowIndex);
			for (int column = 0; column < row.size(); column++) {
				if (column > 0) {
					sb.append('\t');
				}
				sb.append(row.get(column).asText());
			}
		}
		return sb.toString();
	}

	@Override
	public String toString() {
		return "SheetData[startRow=" + startRow + ", startColumn=" + startColumn + ", rows=" + getRowCount()
				+ ", columns=" + getColumnCount() + "]";
	}
}
