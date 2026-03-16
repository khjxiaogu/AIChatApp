package com.khjxiaogu.aiwuxia.state;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

/**
 * key-value 验证器，支持按顺序匹配 key 的正则表达式，
 * 并在匹配的规则下验证 value 是否匹配任意一个关联的正则表达式。
 */
public class AttributeValidator {
    private final List<Rule> rules = new ArrayList<>();

    /**
     * 添加一条验证规则。
     * @param keyRegex   匹配 key 的正则表达式
     * @param valueRegex 匹配 value 的正则表达式（可多个）
     */
    public AttributeValidator addRule(String keyRegex, String... valueRegex) {
        Pattern keyPattern = Pattern.compile(keyRegex);
        List<Pattern> valuePatterns = new ArrayList<>();
        for (String regex : valueRegex) {
            valuePatterns.add(Pattern.compile(regex));
        }
        rules.add(new Rule(keyPattern, valuePatterns));
        return this;
    }

    /**
     * 验证给定的 key-value 对。
     * @param key   待验证的 key
     * @param value 待验证的 value
     * @return 如果找到匹配 key 的第一个规则，并且 value 匹配该规则下任意一个 value 正则，则返回 true；
     *         否则返回 false。
     */
    public boolean validate(String key, String value) {
        for (Rule rule : rules) {
            if (rule.keyPattern.matcher(key).matches()) {
                // key 匹配，检查 value 是否匹配任意一个 value 正则
                for (Pattern valuePattern : rule.valuePatterns) {
                    if (valuePattern.matcher(value).matches()) {
                        return true;
                    }
                }
                // key 匹配但 value 不匹配任何正则，验证失败
                return false;
            }
        }
        // 没有规则匹配该 key，验证失败
        return false;
    }

    /**
     * 内部规则类，封装 key 的正则和对应的 value 正则列表。
     */
    private static class Rule {
        final Pattern keyPattern;
        final List<Pattern> valuePatterns;

        Rule(Pattern keyPattern, List<Pattern> valuePatterns) {
            this.keyPattern = keyPattern;
            this.valuePatterns = valuePatterns;
        }
    }
    /**
     * Gson 序列化适配器：将 Rule 转换为其正则字符串表示。
     */
    private static class RuleSerializer implements JsonSerializer<Rule>,JsonDeserializer<Rule>  {
        @Override
        public JsonElement serialize(Rule src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            obj.addProperty("keyPattern", src.keyPattern.pattern());
            JsonArray valueArray = new JsonArray();
            for (Pattern p : src.valuePatterns) {
                valueArray.add(p.pattern());
            }
            obj.add("valuePatterns", valueArray);
            return obj;
        }
        @Override
        public Rule deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            String keyRegex = obj.get("keyPattern").getAsString();
            Pattern keyPattern = Pattern.compile(keyRegex);
            JsonArray valueArray = obj.getAsJsonArray("valuePatterns");
            List<Pattern> valuePatterns = new ArrayList<>();
            for (JsonElement elem : valueArray) {
                valuePatterns.add(Pattern.compile(elem.getAsString()));
            }
            return new Rule(keyPattern, valuePatterns);
        }
    }
    static Gson gson = new GsonBuilder()
        .registerTypeAdapter(Rule.class, new RuleSerializer())
        .create();
    /**
     * 从 JSON 字符串反序列化为 KeyValueValidator 对象。
     * @param json JSON 字符串
     * @return 还原的 KeyValueValidator 实例
     * @throws JsonParseException 如果 JSON 格式非法
     */
    public static AttributeValidator fromJson(String json) throws JsonParseException {
       
        return gson.fromJson(json, AttributeValidator.class);
    }
}