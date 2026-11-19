package com.khjxiaogu.aiwuxia.llm.providers.deepseek;

/**
 * DeepSeek 联网工具的名称常量。
 * <p>
 * DeepSeek 有两套联网能力，名字容易混淆，这里集中说明：
 * </p>
 * <ul>
 *   <li>{@link #CLIENT_SEARCH_TOOL}：<b>项目自己注册的工具</b>（{@code DeepseekSearchMcp}）。
 *       模型下发普通 {@code tool_calls}，由本进程调用
 *       {@code POST https://api.deepseek.com/v1/tools/search} 取回结果，再发一轮请求；
 *       按成功请求次数计费，其它模型提供商也靠它联网。</li>
 *   <li>{@link #WEB_SEARCH} / {@link #READ_URL}：<b>官方服务端工具</b>。在 {@code tools} 里只声明
 *       {@code {"type": "deepseek_web_search"}}，搜索由 DeepSeek 服务端执行，客户端不做任何事，
 *       响应里以 {@code server_tool_use}/{@code server_tool_calls} 的形式回传调用记录
 *       （见 {@code ServerToolUseContent}）。官方已取消服务端搜索的按次计费，只按模型 token 计费。</li>
 * </ul>
 * <p>
 * {@link DeepseekModelProvider} 在检测到会话注册了 {@link #CLIENT_SEARCH_TOOL}
 * 时改用服务端工具，并把客户端搜索工具从 {@code tools} 里剔除；这一优化<b>只对
 * {@code deepseek-flash} 生效</b>，{@code deepseek-v4-pro} 仍走客户端的 {@code web_search}。
 * 另外官方不接受客户端工具的 {@code strict} 字段与联网工具同时出现
 * （会返回 400「{@code strict} cannot be used together with {@code deepseek_web_search}」），
 * 因此联网请求里的函数工具一律不下发该字段。
 * </p>
 */
public final class DeepseekServerTools {

	/**
	 * 项目注册的 DeepSeek 搜索 MCP 工具名。
	 * <p>
	 * 它同时是“本会话允许联网搜索”的开关：没注册（例如未配置 {@code deepseektoken}、
	 * 或工具设置里关掉了 fetch）就完全不开联网，避免模型凭空搜索。
	 * </p>
	 */
	public static final String CLIENT_SEARCH_TOOL = "web_search";

	/** 官方服务端联网搜索工具（Chat Completions 里以 {@code {"type":"deepseek_web_search"}} 声明）。 */
	public static final String WEB_SEARCH = "deepseek_web_search";

	/** 官方服务端链接阅读工具（本次未声明，保留常量以便后续启用“自动阅读用户消息里的链接”）。 */
	public static final String READ_URL = "deepseek_read_url";

	private DeepseekServerTools() {
	}
}
