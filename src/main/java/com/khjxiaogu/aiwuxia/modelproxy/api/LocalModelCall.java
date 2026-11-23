package com.khjxiaogu.aiwuxia.modelproxy.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.khjxiaogu.aiwuxia.modelproxy.ModelType;

/**
 * 本地模型一次调用的统一信封。
 * <p>
 * 每个 API 的参数是独立的成员对象（{@link SdxlParams}、{@link AudioParams}、
 * {@link SvsParams}、{@link QwenParams}、{@link H3Params}），彼此没有继承关系；
 * 信封只负责“类型 + 请求 ID + 附件引用 + 参数对象”，编解码统一交给 {@link LocalModelCodec}。
 * </p>
 * <p>
 * 附件只以 {@link AttachmentRef} 的形式出现在信封里，参数对象中保存的是附件 id；
 * 接收端拿到调用后按需向服务端拉取字节（见 {@code modelproxy.AttachmentFetcher}）。
 * </p>
 *
 * @param <P> 参数对象类型
 */
public final class LocalModelCall<P> {

	/** 协议版本；缺少该字段的报文按旧格式解析。 */
	public static final int API_VERSION = 2;

	/** 模型类型。 */
	public final ModelType type;
	/** 请求唯一 ID。 */
	public final String reqid;
	/** 参数对象。 */
	public final P params;
	/** 附件引用（只读）。 */
	public final List<AttachmentRef> attachments;

	public LocalModelCall(ModelType type, String reqid, P params, List<AttachmentRef> attachments) {
		this.type = type;
		this.reqid = reqid == null ? "" : reqid;
		this.params = params;
		this.attachments = attachments == null ? Collections.emptyList()
				: Collections.unmodifiableList(new ArrayList<>(attachments));
	}

	/**
	 * 创建 SDXL 调用。
	 *
	 * @param reqid       请求 ID
	 * @param params      SDXL 参数
	 * @param attachments 附件引用
	 * @return 信封
	 */
	public static LocalModelCall<SdxlParams> sdxl(String reqid, SdxlParams params, List<AttachmentRef> attachments) {
		return new LocalModelCall<>(ModelType.SDXL, reqid, params, attachments);
	}

	/**
	 * 创建朗读调用。
	 *
	 * @param reqid  请求 ID
	 * @param params 朗读参数
	 * @return 信封
	 */
	public static LocalModelCall<AudioParams> audio(String reqid, AudioParams params) {
		return new LocalModelCall<>(ModelType.AUDIO, reqid, params, null);
	}

	/**
	 * 创建 SVS 调用。
	 *
	 * @param reqid  请求 ID
	 * @param params SVS 参数
	 * @return 信封
	 */
	public static LocalModelCall<SvsParams> svs(String reqid, SvsParams params) {
		return new LocalModelCall<>(ModelType.SVS, reqid, params, null);
	}

	/**
	 * 创建 Qwen 调用。
	 *
	 * @param reqid       请求 ID
	 * @param params      Qwen 参数
	 * @param attachments 附件引用
	 * @return 信封
	 */
	public static LocalModelCall<QwenParams> qwen(String reqid, QwenParams params, List<AttachmentRef> attachments) {
		return new LocalModelCall<>(ModelType.QWEN, reqid, params, attachments);
	}

	/**
	 * 创建 H3 调用。
	 *
	 * @param reqid       请求 ID
	 * @param params      H3 参数
	 * @param attachments 附件引用
	 * @return 信封
	 */
	public static LocalModelCall<H3Params> h3(String reqid, H3Params params, List<AttachmentRef> attachments) {
		return new LocalModelCall<>(ModelType.H3, reqid, params, attachments);
	}

	/**
	 * 取参数对象并校验类型。
	 *
	 * @param clazz 期望的参数类型
	 * @param <T>   参数类型
	 * @return 参数对象
	 * @throws LocalModelApiException 参数不是期望类型
	 */
	public <T> T requireParams(Class<T> clazz) {
		if (!clazz.isInstance(params)) {
			throw new LocalModelApiException(
					"本地模型调用参数类型不匹配：期望 " + clazz.getSimpleName() + "，实际 "
							+ (params == null ? "null" : params.getClass().getSimpleName()));
		}
		return clazz.cast(params);
	}

	/**
	 * 按 id 找附件引用。
	 *
	 * @param id 附件 id
	 * @return 引用；找不到返回 null
	 */
	public AttachmentRef attachment(String id) {
		if (id == null || id.isEmpty()) {
			return null;
		}
		for (AttachmentRef ref : attachments) {
			if (id.equals(ref.id)) {
				return ref;
			}
		}
		return null;
	}

	@Override
	public String toString() {
		return "LocalModelCall[type=" + type + ", reqid=" + reqid + ", params="
				+ (params == null ? "null" : params.getClass().getSimpleName()) + ", attachments="
				+ attachments.size() + "]";
	}
}
