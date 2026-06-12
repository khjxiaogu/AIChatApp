package com.khjxiaogu.aiwuxia.state.history.message;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.khjxiaogu.aiwuxia.utils.FunctionUtil;

/**
 * 消息内容集合接口。
 * <p>
 * 扩展 {@link Iterable}{@code <MessageContent>}，表示一组有序的 {@link MessageContent} 的容器。
 * 支持对集合整体进行类型判断、文本转换以及 JSON 序列化/反序列化。
 * </p>
 *
 * @see MutableMessageContents
 */
public interface MessageContents extends Iterable<MessageContent> {

	/**
	 * 空的 {@link MessageContents} 单例。
	 * <p>
	 * 所有查询方法返回默认值：遍历返回空迭代器，{@link #isPlainText()} 和 {@link #isTextRepresentable()}
	 * 返回 {@code true}，{@link #toText()} 返回空字符串，{@link #isEmpty()} 返回 {@code true}。
	 * </p>
	 */
	public static final MessageContents EMPTY = new MessageContents() {
		@Override
		public Iterator<MessageContent> iterator() {
			return Collections.emptyIterator();
		}

		@Override
		public boolean isPlainText() {
			return true;
		}

		@Override
		public boolean isTextRepresentable() {
			return true;
		}

		@Override
		public String toText() {
			return "";
		}

		@Override
		public boolean isEmpty() {
			return true;
		}
	};

	/**
	 * 内容类型名称到实现类的映射表，用于 JSON 反序列化时查找对应的具体类。
	 */
	static Map<String, Class<? extends MessageContent>> types = FunctionUtil.make(() -> {
		Map<String, Class<? extends MessageContent>> types = new HashMap<>();
		types.put("image", ImageContent.class);
		types.put("text", PlainText.class);
		types.put("video", VideoContent.class);
		types.put("tool", ToolContent.class);
		types.put("tool_call", ToolCallContent.class);
		return types;
	});

	/**
	 * {@link MessageContents} 的 Gson 自定义序列化器/反序列化器。
	 * <p>
	 * 序列化时将集合转换为 JSON 数组：{@link PlainText} 直接序列化为字符串，
	 * 其他类型序列化为带 {@code "type"} 字段的 JSON 对象。
	 * 反序列化时自动识别数组元素类型并还原为对应的实现类。
	 * </p>
	 */
	public static class Serilizer implements JsonSerializer<MessageContents>, JsonDeserializer<MessageContents> {

		/**
		 * 从 JSON 元素反序列化为 {@link MutableMessageContents}。
		 * <p>
		 * 支持以下 JSON 格式：
		 * </p>
		 * <ul>
		 *   <li>JSON 数组：每个元素为带 {@code "type"} 字段的对象或纯字符串。</li>
		 *   <li>JSON 基本类型：直接作为纯文本追加。</li>
		 * </ul>
		 *
		 * @param json    JSON 元素
		 * @param typeOfT 目标类型
		 * @param context 反序列化上下文
		 * @return 反序列化得到的 {@link MessageContents}
		 * @throws JsonParseException 如果缺少 {@code "type"} 字段或类型无效
		 */
		@Override
		public MessageContents deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
				throws JsonParseException {
			MutableMessageContents content = new MutableMessageContents();
			if (json.isJsonArray()) {
				JsonArray data = json.getAsJsonArray();
				for (JsonElement je : data) {
					if (je.isJsonObject()) {
						JsonObject jo = je.getAsJsonObject();
						if (!jo.has("type"))
							throw new JsonSyntaxException("Type missing");
						String type = jo.get("type").getAsString();
						Class<? extends MessageContent> clazz = types.get(type);
						if (clazz == null)
							throw new JsonSyntaxException("Invalid type " + type);
						content.add(context.deserialize(je, clazz));
					} else if (je.isJsonPrimitive()) {
						content.append(je.getAsString());
					}
				}
			} else if (json.isJsonPrimitive()) {
				content.append(json.getAsString());
			}
			return content;
		}

		/**
		 * 将 {@link MessageContents} 序列化为 JSON 数组。
		 * <p>
		 * {@link PlainText} 元素直接输出为 JSON 字符串，
		 * 其他类型输出为 JSON 对象并附加 {@code "type"} 字段。
		 * </p>
		 *
		 * @param src       源 {@link MessageContents}
		 * @param typeOfSrc 源类型
		 * @param context   序列化上下文
		 * @return JSON 数组元素
		 */
		@Override
		public JsonElement serialize(MessageContents src, Type typeOfSrc, JsonSerializationContext context) {
			JsonArray data = new JsonArray();
			for (MessageContent message : src) {
				if (message instanceof PlainText) {
					data.add(((PlainText) message).text.toString());
				} else {
					JsonObject origData = context.serialize(message).getAsJsonObject();
					origData.addProperty("type", message.getType());
					data.add(origData);
				}

			}
			return data;
		}

	}

	/**
	 * 判断集合中的所有消息内容是否均为纯文本。
	 *
	 * @return {@code true} 如果全部为纯文本；{@code false} 否则
	 */
	boolean isPlainText();

	/**
	 * 判断集合中的所有消息内容是否均可表示为文本。
	 *
	 * @return {@code true} 如果全部可表示为文本；{@code false} 否则
	 */
	boolean isTextRepresentable();

	/**
	 * 将所有消息内容的文本表示拼接成一个完整的字符串。
	 *
	 * @return 拼接后的文本，不可为 null
	 */
	String toText();

	/**
	 * 判断集合是否为空。
	 *
	 * @return {@code true} 如果集合不含任何消息内容；{@code false} 否则
	 */
	boolean isEmpty();

}
