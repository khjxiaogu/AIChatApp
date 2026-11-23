package com.khjxiaogu.aiwuxia.modelproxy.api;

/**
 * SVS 调用的参数，独立的值对象。
 */
public final class SvsParams {

	/** 音源地址（待转换/演唱的歌曲）。 */
	public final String url;
	/** 音源名称，可为 null。 */
	public final String name;
	/** 合成参数。 */
	public final SvsOptions options;

	public SvsParams(String url, String name, SvsOptions options) {
		this.url = url == null ? "" : url;
		this.name = name;
		this.options = options;
	}

	/**
	 * 校验参数。
	 *
	 * @throws LocalModelApiException 音源或参数缺失
	 */
	public void validate() {
		if (url.isEmpty()) {
			throw new LocalModelApiException("SVS 缺少音源地址 url");
		}
		if (options == null) {
			throw new LocalModelApiException("SVS 缺少合成参数");
		}
		if ((options.timbre == null || options.timbre.isEmpty()) && (options.voice == null || options.voice.isEmpty())) {
			throw new LocalModelApiException("SVS 需要 timbre 或 voice");
		}
	}
}
