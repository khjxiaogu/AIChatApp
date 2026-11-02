package com.khjxiaogu.aiwuxia.multimedia;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.AttachmentResolver;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.objectstorage.ObjectStorageProvider;

/**
 * 基于现有 {@link ToolData} 的通用多媒体 API 适配器。
 * <p>
 * 不改变原有工具的参数/返回约定：适配器在内部把统一请求转换成工具参数 JSON，调用工具后把
 * {@link ToolResult} 转换成统一结果。业务模块只要传入已构建好的工具即可获得多媒体 API 能力。
 * </p>
 */
public abstract class ToolBackedMultimediaApi implements MultimediaApi {

	private final String name;
	private final ToolData tool;
	private final ObjectStorageProvider storage;

	/**
	 * @param name    实现名称
	 * @param tool    要复用的现有工具
	 * @param storage 工具默认使用的对象存储，可为 null
	 */
	protected ToolBackedMultimediaApi(String name, ToolData tool, ObjectStorageProvider storage) {
		this.name = name;
		this.tool = tool;
		this.storage = storage;
	}

	@Override
	public String getName() {
		return name;
	}

	/** 现有工具定义。 */
	protected ToolData tool() {
		return tool;
	}

	/** 工具默认使用的对象存储。 */
	protected ObjectStorageProvider storage() {
		return storage;
	}

	/** 为一次请求创建附件解析器。 */
	protected AttachmentResolver resolver(MultimediaRequest request) {
		ObjectStorageProvider effective = request.getStorage() != null ? request.getStorage() : storage;
		return new AttachmentResolver(effective, MultimediaSupport.usage(request));
	}

	/**
	 * 把统一请求转换成现有工具的参数。
	 *
	 * @param request 统一请求
	 * @return 工具参数 JSON
	 * @throws Exception 参数不合法或附件处理失败
	 */
	protected abstract JsonObject arguments(MultimediaRequest request) throws Exception;

	/**
	 * 把现有工具结果转换为统一结果；默认收集附件并从结果文本取输出提示词。
	 */
	protected MultimediaResult toResult(ToolResult result, MultimediaRequest request) {
		return MultimediaSupport.fromToolResult(result, request, false, null);
	}

	@Override
	public CompletableFuture<MultimediaResult> generate(MultimediaRequest request) {
		if (request == null) {
			return CompletableFuture.completedFuture(MultimediaResult.failure("请求不能为空"));
		}
		CompletableFuture<JsonObject> arguments;
		try {
			arguments = CompletableFuture.supplyAsync(() -> {
				try {
					return arguments(request);
				} catch (Exception ex) {
					throw new CompletionException(ex);
				}
			});
		} catch (Throwable ex) {
			return CompletableFuture.completedFuture(MultimediaResult.failure(rootMessage(ex)));
		}
		return arguments.thenCompose(args -> tool.tool.run(args.toString(), request.getOutput(), null))
				.thenApply(result -> toResult(result, request))
				.exceptionally(ex -> MultimediaResult.failure(rootMessage(ex)));
	}

	/** 取最内层异常的可读信息。 */
	protected static String rootMessage(Throwable ex) {
		Throwable cause = ex;
		while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		if (cause == null) {
			return "未知错误";
		}
		String message = cause.getMessage();
		return message == null || message.trim().isEmpty() ? cause.getClass().getSimpleName() : message;
	}
}
