package com.khjxiaogu.aiwuxia.modelproxy.api;

import com.google.gson.JsonObject;

/**
 * SVS（歌声合成）参数，独立的值对象。
 */
public final class SvsOptions {

	/** 滤波强度最小值。 */
	public static final int MIN_FILTER = 0;
	/** 滤波强度最大值。 */
	public static final int MAX_FILTER = 7;
	/** 合成强度最小值。 */
	public static final double MIN_STRENGTH = 0.00;
	/** 合成强度最大值。 */
	public static final double MAX_STRENGTH = 1.00;

	/** 音高。 */
	public int pitch = 0;
	/** 是否分离人声（旧字段）。 */
	public boolean separate = false;
	/** localemp.py 是否执行伴奏分离与合成。 */
	public boolean doSplit = true;
	/** 音色标识（旧字段）。 */
	public String timbre;
	/** 滤波强度。 */
	public int filter = MIN_FILTER;
	/** 合成强度。 */
	public double strength = MAX_STRENGTH;
	/** localemp.py 的模型名（不含 .pth）。 */
	public String voice;
	/** localemp.py 的中值滤波半径。 */
	public int filterRadius = 0;
	/** localemp.py 的防撕裂保护。 */
	public double protect = 0d;

	/** @return 新的参数对象 */
	public static SvsOptions builder() {
		return new SvsOptions();
	}

	public SvsOptions pitch(int pitch) {
		this.pitch = pitch;
		return this;
	}

	public SvsOptions separate(boolean separate) {
		this.separate = separate;
		return this;
	}

	public SvsOptions doSplit(boolean doSplit) {
		this.doSplit = doSplit;
		return this;
	}

	public SvsOptions timbre(String timbre) {
		if (timbre == null || timbre.isEmpty())
			throw new IllegalArgumentException("timbre must not be empty");
		this.timbre = timbre;
		return this;
	}

	public SvsOptions voice(String voice) {
		if (voice == null || voice.isEmpty())
			throw new IllegalArgumentException("voice must not be empty");
		this.voice = voice;
		return this;
	}

	public SvsOptions filter(int filter) {
		if (filter < MIN_FILTER || filter > MAX_FILTER)
			throw new IllegalArgumentException(
					"filter out of range [" + MIN_FILTER + ", " + MAX_FILTER + "]: " + filter);
		this.filter = filter;
		return this;
	}

	public SvsOptions filterRadius(int filterRadius) {
		if (filterRadius < 0 || filterRadius > 7)
			throw new IllegalArgumentException("filterRadius out of range [0, 7]: " + filterRadius);
		this.filterRadius = filterRadius;
		return this;
	}

	public SvsOptions strength(double strength) {
		if (strength < MIN_STRENGTH || strength > MAX_STRENGTH)
			throw new IllegalArgumentException(
					"strength out of range [" + MIN_STRENGTH + ", " + MAX_STRENGTH + "]: " + strength);
		this.strength = strength;
		return this;
	}

	public SvsOptions protect(double protect) {
		if (protect < 0d || protect > 1d)
			throw new IllegalArgumentException("protect out of range [0, 1]: " + protect);
		this.protect = protect;
		return this;
	}

	/**
	 * 把参数写入请求对象。
	 *
	 * @param request 目标对象
	 * @return 传入的对象
	 */
	public JsonObject applyTo(JsonObject request) {
		if ((timbre == null || timbre.isEmpty()) && (voice == null || voice.isEmpty()))
			throw new IllegalArgumentException("timbre or voice must not be empty");
		request.addProperty("pitch", pitch);
		request.addProperty("separate", separate);
		request.addProperty("do_split", doSplit);
		if (timbre != null && !timbre.isEmpty())
			request.addProperty("timbre", timbre);
		if (voice != null && !voice.isEmpty())
			request.addProperty("voice", voice);
		request.addProperty("filter", filter);
		request.addProperty("filter_radius", filterRadius);
		request.addProperty("strength", strength);
		request.addProperty("protect", protect);
		return request;
	}

	/** @return 仅包含 SVS 参数的 JSON */
	public JsonObject toJson() {
		return applyTo(new JsonObject());
	}
}
