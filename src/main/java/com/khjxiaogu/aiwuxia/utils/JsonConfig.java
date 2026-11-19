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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * {@link JsonObject} 取值工具。
 * <p>
 * 把「键在不在 / 是不是 JSON null / 类型对不对 / 解析会不会抛异常」这套判断集中到一处，
 * agent 配置、协议事件、工具参数都从这里取值，不再每个类各写一份。所有方法在缺键、
 * 值为 null、类型不符或解析失败时都返回默认值，不会抛异常。
 * </p>
 * <p>
 * 字符串按「去不去首尾空白」和「空白算不算有值」分成几组，按调用方语义选择：
 * </p>
 * <ul>
 * <li>{@link #getString(JsonObject, String)}：原样返回，缺失为 {@code null}；</li>
 * <li>{@link #getString(JsonObject, String, String)}：原样返回，缺失用默认值；</li>
 * <li>{@link #getStringOrEmpty(JsonObject, String)}：原样返回，缺失为空串；</li>
 * <li>{@link #getTrimmed(JsonObject, String)}：去首尾空白，缺失或空白为空串；</li>
 * <li>{@link #getNonBlankString(JsonObject, String)}：去首尾空白，缺失或空白为 {@code null}；</li>
 * <li>{@link #getNonBlankString(JsonObject, String, String)}：去首尾空白，缺失或空白用默认值。</li>
 * </ul>
 */
public final class JsonConfig {
	private JsonConfig() {
	}

	/** 键存在且值不是 JSON null。 */
	public static boolean has(JsonObject json, String key) {
		return json != null && key != null && json.has(key) && !json.get(key).isJsonNull();
	}

	/** 原样取字符串；缺键、null 或不是字符串时返回 {@code null}。 */
	public static String getString(JsonObject json, String key) {
		JsonElement element = primitive(json, key);
		if (element == null) {
			return null;
		}
		try {
			return element.getAsString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** 原样取字符串；缺键、null 或不是字符串时返回默认值。 */
	public static String getString(JsonObject json, String key, String def) {
		String value = getString(json, key);
		return value == null ? def : value;
	}

	/** 原样取字符串；缺键、null 或不是字符串时返回空串。 */
	public static String getStringOrEmpty(JsonObject json, String key) {
		String value = getString(json, key);
		return value == null ? "" : value;
	}

	/** 去首尾空白取字符串；缺键、null 或空白时返回空串。 */
	public static String getTrimmed(JsonObject json, String key) {
		String value = getString(json, key);
		return value == null ? "" : value.trim();
	}

	/** 去首尾空白取字符串；缺键、null 或空白时返回 {@code null}。 */
	public static String getNonBlankString(JsonObject json, String key) {
		String value = getString(json, key);
		if (value == null) {
			return null;
		}
		value = value.trim();
		return value.isEmpty() ? null : value;
	}

	/** 去首尾空白取字符串；缺键、null 或空白时返回默认值。 */
	public static String getNonBlankString(JsonObject json, String key, String def) {
		String value = getNonBlankString(json, key);
		return value == null ? def : value;
	}

	/** 取布尔值；缺键、null 或解析失败时返回 false。 */
	public static boolean getBool(JsonObject json, String key) {
		return getBool(json, key, false);
	}

	/** 取布尔值；缺键、null 或解析失败时返回默认值。 */
	public static boolean getBool(JsonObject json, String key, boolean def) {
		if (!has(json, key)) {
			return def;
		}
		try {
			return json.get(key).getAsBoolean();
		} catch (RuntimeException e) {
			return def;
		}
	}

	/** 取长整数；缺键、null 或解析失败时返回 0。 */
	public static long getLong(JsonObject json, String key) {
		return getLong(json, key, 0L);
	}

	/** 取长整数；缺键、null 或解析失败时返回默认值。 */
	public static long getLong(JsonObject json, String key, long def) {
		if (!has(json, key)) {
			return def;
		}
		try {
			return json.get(key).getAsLong();
		} catch (RuntimeException e) {
			return def;
		}
	}

	/** 取整数；缺键、null 或解析失败时返回默认值。 */
	public static int getInt(JsonObject json, String key, int def) {
		if (!has(json, key)) {
			return def;
		}
		try {
			return json.get(key).getAsInt();
		} catch (RuntimeException e) {
			return def;
		}
	}

	/** 取浮点数；缺键、null 或解析失败时返回默认值。 */
	public static double getDouble(JsonObject json, String key, double def) {
		if (!has(json, key)) {
			return def;
		}
		try {
			return json.get(key).getAsDouble();
		} catch (RuntimeException e) {
			return def;
		}
	}

	/** 取可空长整数：数字或数字字符串都可以；缺失或无法解析时返回 {@code null}。 */
	public static Long getLongOrNull(JsonObject json, String key) {
		String value = getNonBlankString(json, key);
		if (value == null) {
			return null;
		}
		try {
			return Long.valueOf(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 子对象；缺失或不是对象时返回 {@code null}。 */
	public static JsonObject getObject(JsonObject json, String key) {
		JsonElement element = element(json, key);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	/** 子对象；缺失或不是对象时返回新的空对象。 */
	public static JsonObject getObjectOrEmpty(JsonObject json, String key) {
		JsonObject object = getObject(json, key);
		return object == null ? new JsonObject() : object;
	}

	/** 子数组；缺失或不是数组时返回 {@code null}。 */
	public static JsonArray getArray(JsonObject json, String key) {
		JsonElement element = element(json, key);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
	}

	/** 原始元素转字符串；null 或 JSON null 时返回 {@code null}。 */
	public static String getString(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		try {
			return element.getAsString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** 原始元素转整数；null、JSON null 或无法解析时返回 {@code null}。 */
	public static Integer getIntOrNull(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		try {
			return Integer.valueOf(element.getAsInt());
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** 字符串数组：去首尾空白、跳过空值、去重；缺失时返回空列表。 */
	public static List<String> getStringList(JsonObject json, String key) {
		JsonElement element = element(json, key);
		LinkedHashSet<String> values = new LinkedHashSet<>();
		if (element != null && element.isJsonArray()) {
			for (JsonElement item : element.getAsJsonArray()) {
				if (item == null || !item.isJsonPrimitive()) {
					continue;
				}
				String value = item.getAsString();
				if (value == null) {
					continue;
				}
				value = value.trim();
				if (!value.isEmpty()) {
					values.add(value);
				}
			}
		}
		return new ArrayList<>(values);
	}

	/**
	 * id 列表：配置里可以写单个字符串，也可以写字符串数组。
	 * <p>
	 * 结果去首尾空白、去重，并忽略无效占位 {@code 0}；一个都没有时用 {@code fallbackCsv}
	 * （逗号分隔）兜底，便于配置缺省时回退到启动参数。
	 * </p>
	 */
	public static List<String> getIds(JsonObject json, String key, String fallbackCsv) {
		List<String> ids = new ArrayList<>();
		JsonElement element = element(json, key);
		if (element != null) {
			if (element.isJsonArray()) {
				for (JsonElement item : element.getAsJsonArray()) {
					if (item != null && item.isJsonPrimitive()) {
						addId(ids, item.getAsString());
					}
				}
			} else if (element.isJsonPrimitive()) {
				addId(ids, element.getAsString());
			}
		}
		if (ids.isEmpty() && fallbackCsv != null) {
			for (String part : fallbackCsv.split(",")) {
				addId(ids, part);
			}
		}
		return ids;
	}

	/** 追加一个 id：去首尾空白、忽略空值与占位 {@code 0}、去重。 */
	private static void addId(List<String> ids, String raw) {
		if (raw == null) {
			return;
		}
		String id = raw.trim();
		if (id.isEmpty() || "0".equals(id) || ids.contains(id)) {
			return;
		}
		ids.add(id);
	}

	/** 取原始元素；缺键或 JSON null 时返回 {@code null}。 */
	private static JsonElement element(JsonObject json, String key) {
		if (json == null || key == null || !json.has(key)) {
			return null;
		}
		JsonElement element = json.get(key);
		return element == null || element.isJsonNull() ? null : element;
	}

	/** 取原始基本类型元素；缺键、null 或不是基本类型时返回 {@code null}。 */
	private static JsonElement primitive(JsonObject json, String key) {
		JsonElement element = element(json, key);
		return element != null && element.isJsonPrimitive() ? element : null;
	}
}
