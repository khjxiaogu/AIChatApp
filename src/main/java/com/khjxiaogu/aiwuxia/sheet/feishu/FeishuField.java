package com.khjxiaogu.aiwuxia.sheet.feishu;

import java.util.Objects;

import com.google.gson.JsonObject;

/**
 * 飞书多维表格的字段（列）元数据。
 * <p>
 * 字段类型编号来自飞书开放平台；{@link #isReadOnly()} 表示该字段由飞书自动维护、不能写入，
 * {@link #isSupportedForWrite()} 表示本项目的通用单元格模型能否转换后写入该字段。
 * </p>
 */
public final class FeishuField {
	public static final int TYPE_TEXT = 1;
	public static final int TYPE_NUMBER = 2;
	public static final int TYPE_SINGLE_SELECT = 3;
	public static final int TYPE_MULTI_SELECT = 4;
	public static final int TYPE_DATE = 5;
	public static final int TYPE_CHECKBOX = 7;
	public static final int TYPE_USER = 11;
	public static final int TYPE_PHONE = 13;
	public static final int TYPE_URL = 15;
	public static final int TYPE_ATTACHMENT = 17;
	public static final int TYPE_LINK = 18;
	public static final int TYPE_LOOKUP = 19;
	public static final int TYPE_FORMULA = 20;
	public static final int TYPE_DUPLEX_LINK = 21;
	public static final int TYPE_LOCATION = 22;
	public static final int TYPE_GROUP = 23;
	public static final int TYPE_CREATED_TIME = 1001;
	public static final int TYPE_MODIFIED_TIME = 1002;
	public static final int TYPE_CREATED_USER = 1003;
	public static final int TYPE_MODIFIED_USER = 1004;
	public static final int TYPE_AUTO_NUMBER = 1005;

	private final String fieldId;
	private final String fieldName;
	private final int type;
	private final String uiType;
	private final boolean primary;
	private final boolean hidden;
	/** 字段属性（原样保留，重命名字段时回传，避免覆盖选项等设置） */
	private final JsonObject property;

	public FeishuField(String fieldId, String fieldName, int type, String uiType, boolean primary, boolean hidden) {
		this(fieldId, fieldName, type, uiType, primary, hidden, null);
	}

	public FeishuField(String fieldId, String fieldName, int type, String uiType, boolean primary, boolean hidden,
			JsonObject property) {
		this.fieldId = fieldId;
		this.fieldName = fieldName == null ? "" : fieldName;
		this.type = type;
		this.uiType = uiType == null ? "" : uiType;
		this.primary = primary;
		this.hidden = hidden;
		this.property = property;
	}

	public String getFieldId() {
		return fieldId;
	}

	public String getFieldName() {
		return fieldName;
	}

	public int getType() {
		return type;
	}

	public String getUiType() {
		return uiType;
	}

	/**
	 * @return 是否为数据表的索引列（第一列）
	 */
	public boolean isPrimary() {
		return primary;
	}

	public boolean isHidden() {
		return hidden;
	}

	/**
	 * @return 字段属性；未提供时为 {@code null}
	 */
	public JsonObject getProperty() {
		return property;
	}

	/**
	 * 是否由飞书自动维护、不可写入（公式、查找引用、创建/修改时间、创建/修改人、自动编号）。
	 *
	 * @return 只读时返回 true
	 */
	public boolean isReadOnly() {
		return type == TYPE_LOOKUP || type == TYPE_FORMULA || type == TYPE_CREATED_TIME
				|| type == TYPE_MODIFIED_TIME || type == TYPE_CREATED_USER || type == TYPE_MODIFIED_USER
				|| type == TYPE_AUTO_NUMBER;
	}

	/**
	 * 本项目的通用 {@code CellValue} 能否写入该字段。
	 * <p>
	 * 人员、附件、关联、群组、地理位置需要飞书内部 ID 或专用结构，通用模型无法安全表达，返回 false；
	 * 文本、数字、单选、多选、日期、复选框、电话、超链接可以转换后写入。
	 * </p>
	 *
	 * @return 可以写入时返回 true
	 */
	public boolean isSupportedForWrite() {
		return !isReadOnly() && (type == TYPE_TEXT || type == TYPE_NUMBER || type == TYPE_SINGLE_SELECT
				|| type == TYPE_MULTI_SELECT || type == TYPE_DATE || type == TYPE_CHECKBOX || type == TYPE_PHONE
				|| type == TYPE_URL);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof FeishuField)) {
			return false;
		}
		FeishuField other = (FeishuField) o;
		return type == other.type && primary == other.primary && hidden == other.hidden
				&& Objects.equals(fieldId, other.fieldId) && Objects.equals(fieldName, other.fieldName)
				&& Objects.equals(uiType, other.uiType) && Objects.equals(property, other.property);
	}

	@Override
	public int hashCode() {
		return Objects.hash(fieldId, fieldName, type, uiType, primary, hidden, property);
	}

	@Override
	public String toString() {
		return "FeishuField[fieldId=" + fieldId + ", name=" + fieldName + ", type=" + type + ", uiType=" + uiType
				+ ", primary=" + primary + ", hidden=" + hidden + "]";
	}
}
