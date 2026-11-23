package com.khjxiaogu.aiwuxia.vision;

import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModel;
import com.khjxiaogu.aiwuxia.modelproxy.ModelGenerationResult;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient.ProgressReporter;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient.RequestHandler;
import com.khjxiaogu.aiwuxia.modelproxy.LocalModelClient.Response;
import com.khjxiaogu.aiwuxia.tools.ResourceLock;
import com.khjxiaogu.aiwuxia.tools.ResourceLock.ResourcePermit;
import com.khjxiaogu.aiwuxia.tools.TaskPriority;
import com.khjxiaogu.aiwuxia.utils.JsonConfig;

/**
 * SVS（歌声转换）的请求处理器。
 * <p>
 * 收到 SVS 请求后转给 localemp.py（HTTP）处理：先通过 {@link LocalModel#registerTask(String)}
 * 注册本地任务，等 localemp 把音频回调到服务器的 {@code /kh$localModelDeployData} 后，
 * 再把结果回传。因此本处理器所在进程必须能收到服务器的回调。
 * </p>
 * <p>
 * 真正拼装 localemp 请求、注册回调并等待结果的逻辑在 {@link SvsApi} 中；本处理器只负责
 * 资源锁、进度上报与错误包装。地址取构造参数或 {@code -DsvsHost=host:port}，默认
 * {@value SvsApi#DEFAULT_HOST}。
 * </p>
 * <p>
 * 支持 {@code localemp_api.md} 的参数：{@code url}、{@code voice}（兼容旧 {@code timbre}）、
 * {@code reqid}、{@code name}、{@code pitch}、{@code filter_radius}（兼容旧 {@code filter}）、
 * {@code protect}（兼容旧 {@code strength}）、{@code do_split}（默认 true）。
 * </p>
 */
public final class SvsLocalModelHandler {

	/** localemp.py 默认地址。 */
	public static final String DEFAULT_SVS_HOST = SvsApi.DEFAULT_HOST;
	/** localemp 处理长音频可能较慢，客户端占用 4 份资源。 */
	private static final int SVS_RESOURCE_AMOUNT = 4;

	private SvsLocalModelHandler() {
	}

	/**
	 * 创建处理器。
	 *
	 * @param lock    与其它本地模型共享的资源锁，null 时新建一个容量 4 的锁
	 * @param svsHost localemp.py 地址，空串表示按 {@code -DsvsHost} / 默认地址
	 * @return 请求处理器
	 */
	public static RequestHandler create(ResourceLock lock, String svsHost) {
		final ResourceLock effectiveLock = lock == null ? new ResourceLock(SVS_RESOURCE_AMOUNT) : lock;
		final SvsApi api = new SvsApi(svsHost);
		return new RequestHandler() {
			@Override
			public Response handle(JsonObject request) {
				return SvsLocalModelHandler.handle(request, effectiveLock, api, ProgressReporter.NOOP);
			}

			@Override
			public Response handle(JsonObject request, ProgressReporter progress) {
				return SvsLocalModelHandler.handle(request, effectiveLock, api, progress);
			}
		};
	}

	/** 把服务器的 SVS 请求转给 localemp.py；结果由 localemp 回调完成本地任务后再回复。 */
	private static Response handle(JsonObject request, ResourceLock lock, SvsApi api,
			ProgressReporter progress) {
		String reqid = JsonConfig.getTrimmed(request, "reqid");
		progress.queued("等待本地模型资源");
		long queuedAt = System.currentTimeMillis();
		try (ResourcePermit permit = lock.acquire(SVS_RESOURCE_AMOUNT, TaskPriority.NORMAL)) {
			System.out.println("[SVS-LocalModel] 已获取资源锁（排队 " + (System.currentTimeMillis() - queuedAt)
					+ "ms） reqid=" + reqid + " localemp=" + api.getHost());
			// 排队结束、正式把请求下发给 localemp 的那一刻才切到“执行中”
			progress.running("已下发 localemp", Long.getLong("svsEtaMs", 60000L));
			ModelGenerationResult result = api.convert(request);
			System.out.println("[SVS-LocalModel] 转换完成：reqid=" + reqid + " 结果="
					+ (result.bodyData == null ? 0 : result.bodyData.length) + "B/" + result.format);
			return new Response(result.bodyData, result.format);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(ex);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}
}
