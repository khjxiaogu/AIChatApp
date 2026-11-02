package com.khjxiaogu.aiwuxia.multimedia;

import java.util.concurrent.CompletableFuture;

/**
 * 通用多媒体生成 API。
 * <p>
 * 统一描述“可选附件 + 提示词”的输入与“附件 + 提示词”的输出，屏蔽底层是图片、视频还是语音。
 * 具体实现由各业务模块提供适配器（SDXLAgent、即梦图片/视频、本地语音等），本接口不绑定任何
 * 具体模型或传输方式。
 * </p>
 * <p>
 * 附件统一使用 {@link com.khjxiaogu.aiwuxia.state.history.message.MessageContent} 表示：
 * 图片 {@code ImageContent}、视频 {@code VideoContent}、音频 {@code AudioContent}；
 * 约定使用对象存储 ID，具体解析/上传由实现层负责。
 * </p>
 */
public interface MultimediaApi {

	/**
	 * 实现名称，用于日志、注册表或前端展示。
	 *
	 * @return 稳定的名称
	 */
	String getName();

	/**
	 * 执行一次多媒体生成。
	 * <p>
	 * 返回的 future 正常完成表示请求已处理；业务失败（参数错误、生成失败等）通过
	 * {@link MultimediaResult#isSuccess()} 表达，不强制抛异常。
	 * </p>
	 *
	 * @param request 输入附件与提示词
	 * @return 输出附件与提示词
	 */
	CompletableFuture<MultimediaResult> generate(MultimediaRequest request);
}
