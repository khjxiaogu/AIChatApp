package com.khjxiaogu.aiwuxia.state;

import java.lang.reflect.Type;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.khjxiaogu.aiwuxia.llm.providers.deepseek.DeepseekUsage;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.voice.VolcanoVoiceUsage;

public class UsageTracker {
	public static class Serilizer implements JsonSerializer<UsageTracker>, JsonDeserializer<UsageTracker> {

		@Override
		public UsageTracker deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
			UsageTracker tracker = new UsageTracker();
			if (json.isJsonArray()) {
				JsonArray data = json.getAsJsonArray();
				for (JsonElement je : data) {
					if (je.isJsonObject()) {
						Object usage;
						try {
							usage = context.deserialize(je, Class.forName(je.getAsJsonObject().get("type").getAsString()));
							tracker.usages.put(usage.getClass(), (UsageIntf<?>) usage);
						} catch (ClassNotFoundException e) {
							throw new JsonParseException(e);
						}
						

					}
				}
			} else if (json.isJsonObject()) {
				JsonObject rdt = json.getAsJsonObject();
				tracker.usages.put(DeepseekUsage.class, context.deserialize(json, DeepseekUsage.class));
				if(rdt.has("voice_tokens"))
					tracker.usages.put(VolcanoVoiceUsage.class, new VolcanoVoiceUsage(rdt.get("voice_tokens").getAsInt()));

			}
			return tracker;
		}

		@Override
		public JsonElement serialize(UsageTracker src, Type typeOfSrc, JsonSerializationContext context) {
			JsonArray data = new JsonArray();
			for (UsageIntf<?> usage : src.usages.values()) {
				JsonObject origData = context.serialize(usage).getAsJsonObject();
				origData.addProperty("type", usage.getClass().getName());
				data.add(origData);
			}
			return data;
		}

	}

	Map<Class<?>, UsageIntf<?>> usages = new LinkedHashMap<>();

	public UsageTracker() {
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public synchronized void add(UsageIntf uit) {
		Class<?> type = uit.getClass();
		UsageIntf orig = usages.get(type);
		if (orig != null)
			orig.add(uit);
		else
			usages.put(type, uit);
	}

	public float getTotalTokenPrice() {
		float val = 0;
		for (UsageIntf<?> ri : usages.values()) {
			val += ri.getEquivantTokens();
		}
		return val;
	}

	public String calculatePrice() {
		DecimalFormat format = new DecimalFormat("#0.00###");
		return format.format(getTotalTokenPrice() * 2 / 1000000d);
	}
	public String toString() {
		StringBuilder sb=new StringBuilder();
		for(Entry<Class<?>, UsageIntf<?>> i:usages.entrySet()) {
			sb.append(i.getKey().getSimpleName()).append(":").append(i.getValue()).append("\n");
		}
		sb.append(calculatePrice());
		return sb.toString();
	}
}
