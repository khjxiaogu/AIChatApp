package com.khjxiaogu.aiwuxia.sheet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 通用在线表格 API。
 * <p>
 * 把「读工作表、读/写范围、追加行、清空范围、增删工作表」这套操作抽象出来，屏蔽底层是腾讯文档、
 * 飞书还是 Google Sheets。范围统一使用 A1 表示法（见 {@link A1Range}），单元格统一使用
 * {@link CellValue}，调用方不需要接触任何厂商协议。
 * </p>
 * <p>
 * 典型用法：
 * </p>
 * <pre>
 * SheetApi sheet = TencentSheetApi.fromDataFolder(new File("save"));
 * List&lt;SheetMeta&gt; sheets = sheet.listSheets();
 * List&lt;List&lt;String&gt;&gt; rows = sheet.readText("BB08J2", "A1:D10");
 * sheet.writeText("BB08J2", "A1", Arrays.asList(Arrays.asList("姓名", "年龄")));
 * sheet.appendRows("BB08J2", Arrays.asList(Arrays.asList("张三", "18")));
 * </pre>
 * <p>
 * 约定：
 * </p>
 * <ul>
 * <li>实现类在参数非法时抛 {@link IllegalArgumentException}，在调用失败（网络、鉴权、限频、
 * 厂商业务错误）时抛 {@link SheetException}；</li>
 * <li>{@link #listSheets()} 只要求轻量元数据，{@link #getSheet(String)} 才保证尽可能返回已用行列数
 * （{@link SheetMeta#getRowCount()}），{@link #appendRows(String, List)} 依赖后者；</li>
 * <li>{@link #writeRange(String, String, List)} 只取范围的左上角作为写入起点，实际写入尺寸由
 * {@code values} 决定；单次范围的上限由实现类校验，超过时请分批调用；</li>
 * <li>各方法是否线程安全由实现类说明。</li>
 * </ul>
 */
public interface SheetApi {

	/**
	 * @return 实现名称，例如 {@code "腾讯文档"}，用于日志与展示
	 */
	String getProviderName();

	/**
	 * 列出所有工作表的轻量元数据。
	 * <p>
	 * 为保证速度，实现类可以不返回已使用行列数（此时对应字段为 {@code -1}）。需要追加位置时请用
	 * {@link #getSheet(String)}。
	 * </p>
	 *
	 * @return 工作表列表，可能为空
	 * @throws IOException 调用失败
	 */
	List<SheetMeta> listSheets() throws IOException;

	/**
	 * 查询单个工作表元数据，要求尽可能包含已使用行列数。
	 * <p>
	 * 默认实现从 {@link #listSheets()} 中查找；支持完整元数据的实现应覆盖该方法。
	 * </p>
	 *
	 * @param sheetId 工作表 ID
	 * @return 工作表元数据
	 * @throws IOException 调用失败或工作表不存在
	 */
	default SheetMeta getSheet(String sheetId) throws IOException {
		if (sheetId == null || sheetId.trim().isEmpty()) {
			throw new IllegalArgumentException("sheetId 不能为空");
		}
		String target = sheetId.trim();
		for (SheetMeta meta : listSheets()) {
			if (target.equals(meta.getSheetId())) {
				return meta;
			}
		}
		throw new SheetException("工作表不存在：" + target);
	}

	/**
	 * 读取 A1 范围的内容。
	 *
	 * @param sheetId 工作表 ID
	 * @param a1Range A1 范围，例如 {@code "A1:D10"}
	 * @return 范围内容，起始位置与请求范围一致
	 * @throws IOException 调用失败
	 */
	SheetData readRange(String sheetId, String a1Range) throws IOException;

	/**
	 * 写入 A1 范围。
	 * <p>
	 * 只使用范围的左上角作为起点，写入尺寸由 {@code values} 的行列数决定；范围本身主要用于表达
	 * 「从哪里开始写」。超出实现类上限时应由调用方拆分后分批写入。
	 * </p>
	 *
	 * @param sheetId 工作表 ID
	 * @param a1Range A1 范围或起始单元格，例如 {@code "A1"}、{@code "B2:D10"}
	 * @param values  按行组织的单元格值
	 * @throws IOException 调用失败
	 */
	void writeRange(String sheetId, String a1Range, List<List<CellValue>> values) throws IOException;

	/**
	 * 在已使用区域之后追加若干行。
	 * <p>
	 * 默认实现通过 {@link #getSheet(String)} 取得已使用行数，再从下一行开始写。实现类若提供原生
	 * 追加能力可以覆盖；拿不到已用行数时应抛出 {@link SheetException}，提示调用方改用
	 * {@link #writeRange(String, String, List)}。
	 * </p>
	 *
	 * @param sheetId 工作表 ID
	 * @param values  要追加的行
	 * @throws IOException 调用失败
	 */
	default void appendRows(String sheetId, List<List<CellValue>> values) throws IOException {
		if (values == null || values.isEmpty()) {
			throw new IllegalArgumentException("追加内容不能为空");
		}
		SheetMeta meta = getSheet(sheetId);
		int usedRows = meta.getRowCount();
		if (usedRows < 0) {
			throw new SheetException("无法确定工作表「" + meta.titleOrId() + "」的已用行数，请改用 writeRange 指定起始位置");
		}
		writeRange(sheetId, A1Range.columnName(0) + (usedRows + 1), values);
	}

	/**
	 * 清空 A1 范围的内容（保留单元格样式）。
	 *
	 * @param sheetId 工作表 ID
	 * @param a1Range A1 范围
	 * @throws IOException 调用失败
	 */
	void clearRange(String sheetId, String a1Range) throws IOException;

	/**
	 * 清空全部数据行（保留第 1 行表头）。
	 * <p>
	 * 默认实现按已用行数清空 A2 到最后一列；多维表格等记录模型应覆盖本方法，直接删除全部记录，
	 * 而不是把字段写成空值。用于“整表重建前先删除旧数据”。
	 * </p>
	 *
	 * @param sheetId 工作表 ID
	 * @param columns 需要清空的列数；小于等于 0 时按工作表元数据推断
	 * @throws IOException 调用失败
	 */
	default void clearAllData(String sheetId, int columns) throws IOException {
		SheetMeta meta = getSheet(sheetId);
		int rows = meta == null ? -1 : meta.getRowCount();
		if (rows <= 1) {
			return;
		}
		int width = columns > 0 ? columns : meta.getColumnCount();
		if (width <= 0) {
			width = 1;
		}
		clearRange(sheetId, "A2:" + A1Range.columnName(width - 1) + rows);
	}

	/**
	 * 新建工作表。
	 *
	 * @param title       工作表名称
	 * @param rowCount    初始行数；小于等于 0 时使用实现类默认值
	 * @param columnCount 初始列数；小于等于 0 时使用实现类默认值
	 * @return 新建工作表的元数据
	 * @throws IOException 调用失败
	 */
	SheetMeta addSheet(String title, int rowCount, int columnCount) throws IOException;

	/**
	 * 删除工作表。
	 *
	 * @param sheetId 工作表 ID
	 * @throws IOException 调用失败
	 */
	void deleteSheet(String sheetId) throws IOException;

	/**
	 * 读取 A1 范围并转成字符串矩阵（数字按展示文本转换）。
	 *
	 * @param sheetId 工作表 ID
	 * @param a1Range A1 范围
	 * @return 字符串矩阵
	 * @throws IOException 调用失败
	 */
	default List<List<String>> readText(String sheetId, String a1Range) throws IOException {
		SheetData data = readRange(sheetId, a1Range);
		List<List<String>> rows = new ArrayList<>(data.getRowCount());
		for (List<CellValue> row : data.getRows()) {
			List<String> line = new ArrayList<>(row.size());
			for (CellValue cell : row) {
				line.add(cell.asText());
			}
			rows.add(line);
		}
		return rows;
	}

	/**
	 * 把字符串矩阵写入 A1 范围；{@code null} 和空串写为空单元格。
	 *
	 * @param sheetId 工作表 ID
	 * @param a1Range A1 范围或起始单元格
	 * @param rows    字符串矩阵
	 * @throws IOException 调用失败
	 */
	default void writeText(String sheetId, String a1Range, List<List<String>> rows) throws IOException {
		if (rows == null) {
			throw new IllegalArgumentException("写入内容不能为 null");
		}
		List<List<CellValue>> values = new ArrayList<>(rows.size());
		for (List<String> row : rows) {
			if (row == null) {
				values.add(Collections.emptyList());
				continue;
			}
			List<CellValue> line = new ArrayList<>(row.size());
			for (String text : row) {
				line.add(CellValue.of(text));
			}
			values.add(line);
		}
		writeRange(sheetId, a1Range, values);
	}

	/**
	 * 把二维字符串数组写入 A1 范围；{@code null} 和空串写为空单元格。
	 *
	 * @param sheetId 工作表 ID
	 * @param a1Range A1 范围或起始单元格
	 * @param rows    字符串数组
	 * @throws IOException 调用失败
	 */
	default void writeText(String sheetId, String a1Range, String[][] rows) throws IOException {
		if (rows == null) {
			throw new IllegalArgumentException("写入内容不能为 null");
		}
		List<List<String>> values = new ArrayList<>(rows.length);
		for (String[] row : rows) {
			values.add(row == null ? Collections.emptyList() : Arrays.asList(row));
		}
		writeText(sheetId, a1Range, values);
	}
}
