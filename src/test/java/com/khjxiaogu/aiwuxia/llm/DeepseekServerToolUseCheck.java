package com.khjxiaogu.aiwuxia.llm;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.khjxiaogu.aiwuxia.llm.providers.deepseek.DeepseekModelProvider;
import com.khjxiaogu.aiwuxia.llm.providers.deepseek.DeepseekV4Usage;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolCall;
import com.khjxiaogu.aiwuxia.llm.scheme.Choice.ToolFunction;
import com.khjxiaogu.aiwuxia.llm.scheme.RespScheme;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.state.GsonHelper;
import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.history.message.ServerToolUseContent;
import com.khjxiaogu.aiwuxia.state.history.message.ToolCallContent;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

/**
 * DeepSeek 服务端联网工具（{@code server_tool_use}）的离线回归检查。
 * <p>
 * 覆盖这次改造里最容易出错、又不需要联网的几处约定，直接 {@code main} 运行即可，
 * 全部通过时退出码为 0，有失败用例时为 1（不依赖 JUnit，也不需要任何凭据）：
 * </p>
 * <ul>
 *   <li>Chat Completions 扩展字段 {@code server_tool_event} / {@code delta.server_tool_calls} 的解析；</li>
 *   <li>{@link ServerToolUseContent} 的占位文本、持久化往返（{@code MessageContents} 序列化）；</li>
 *   <li>{@link HistoryRequestBuilder} 对 DeepSeek 原样回传 {@code server_tool_calls}、
 *       对其它模型换成占位文本；</li>
 *   <li>{@link DeepseekModelProvider} 的请求体：{@code deepseek-flash} 声明 {@code deepseek_web_search}
 *       并剔除客户端 {@code web_search}（同时省去 {@code strict}）；{@code deepseek-v4-pro}、JSON 输出与
 *       {@code prefix} 续写保持客户端工具；</li>
 *   <li>{@code usage.server_tool_use} 只记录联网次数、不参与计价。</li>
 * </ul>
 * <p>
 * 运行方式（编译后）：
 * </p>
 * <pre>
 * java -cp target/classes:target/test-classes:target/aiwx_lib/* com.khjxiaogu.aiwuxia.llm.DeepseekServerToolUseCheck
 * </pre>
 */
public class DeepseekServerToolUseCheck {

	private static final String NL = System.lineSeparator();

	private static int failures = 0;

	private static final String CHUNK_EVENT = "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"先想想\"},"
			+ "\"server_tool_event\":{\"type\":\"call_started\",\"step\":0,"
			+ "\"call\":{\"index\":0,\"id\":\"srvtoolu_s_00_abc\",\"type\":\"deepseek_web_search\",\"kind\":\"search\","
			+ "\"action\":{\"type\":\"search\",\"queries\":[\"冰岛人口\"]},\"status\":\"completed\"}},"
			+ "\"finish_reason\":null}]}";

	private static final String CHUNK_FINAL = "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"冰岛约 39.4 万人。\","
			+ "\"server_tool_calls\":[{\"index\":0,\"reasoning_content\":\"\",\"calls\":["
			+ "{\"index\":0,\"id\":\"srvtoolu_s_00_abc\",\"type\":\"deepseek_web_search\",\"kind\":\"search\","
			+ "\"action\":{\"type\":\"search\",\"queries\":[\"冰岛人口 2024\",\"Iceland population\"]},\"status\":\"completed\","
			+ "\"sources\":[{\"type\":\"url\",\"url\":\"https://example.com/a\",\"title\":\"t\"}]},"
			+ "{\"index\":1,\"id\":\"srvtoolu_ds_00_def\",\"type\":\"deepseek_web_search\",\"kind\":\"deep_search\","
			+ "\"action\":{\"type\":\"open_page\",\"url\":\"https://example.com/page\"},\"status\":\"completed\"}]}]},"
			+ "\"finish_reason\":\"stop\"}]}";

	private static final String CHUNK_FAILED = "{\"choices\":[{\"index\":0,\"delta\":{\"server_tool_calls\":["
			+ "{\"index\":0,\"calls\":[{\"index\":0,\"id\":\"srvtoolu_rl_00_x\",\"type\":\"deepseek_read_url\",\"kind\":\"read_link\","
			+ "\"action\":{\"type\":\"read_link\",\"url\":\"https://example.com/read\"},\"status\":\"failed\","
			+ "\"error\":{\"code\":\"url_not_accessible\"}}]}]},\"finish_reason\":\"stop\"}]}";

	/** 可独立构造的响应结构，避免依赖某个提供者的 package-private 实现。 */
	static class Scheme extends RespScheme {
		@Override
		public UsageIntf<?> getUsage() {
			return null;
		}
	}

	/** 构造带推理内容的历史条目（测试用）。 */
	static class Item implements HistoryItem {
		private final Role role;
		private final MutableMessageContents context;
		private final MutableMessageContents reasoning;

		Item(Role role, String context, MessageContent... reasoning) {
			this.role = role;
			this.context = new MutableMessageContents(context);
			this.reasoning = new MutableMessageContents(reasoning);
		}

		@Override
		public MessageContents getContextContent() {
			return context;
		}

		@Override
		public MessageContents getDisplayContent() {
			return context;
		}

		@Override
		public Role getRole() {
			return role;
		}

		@Override
		public int getIdentifier() {
			return 1;
		}

		@Override
		public MessageContents getReasoningContent() {
			return reasoning;
		}

		@Override
		public boolean isValidContext() {
			return true;
		}

		@Override
		public boolean isDeleted() {
			return false;
		}

		@Override
		public String getAudioId() {
			return null;
		}

		@Override
		public ApplicationState getLastState() {
			return null;
		}

		@Override
		public int getPrevIdentifier() {
			return -1;
		}

		@Override
		public long getTokenLength() {
			return 0;
		}

		@Override
		public boolean maySendReasoner() {
			return true;
		}
	}

	public static void main(String[] args) throws Exception {
		Gson gs = GsonHelper.getStorageGson();

		// 1. 流式 chunk 解析
		Scheme eventChunk = gs.fromJson(CHUNK_EVENT, Scheme.class);
		Choice.ServerToolEvent event = eventChunk.choices.get(0).server_tool_event;
		check("server_tool_event 解析", event != null && "call_started".equals(event.type) && event.step != null
				&& event.call != null && "deepseek_web_search".equals(event.call.get("type").getAsString()), event);

		Scheme finalChunk = gs.fromJson(CHUNK_FINAL, Scheme.class);
		JsonArray calls = finalChunk.choices.get(0).delta.server_tool_calls;
		check("delta.server_tool_calls 解析", calls != null && calls.size() == 1, calls);
		check("delta.content 不受影响", "冰岛约 39.4 万人。".equals(finalChunk.choices.get(0).delta.content),
				finalChunk.choices.get(0).delta.content);
		check("finish_reason 解析", "stop".equals(finalChunk.choices.get(0).finish_reason),
				finalChunk.choices.get(0).finish_reason);

		// 2. 占位文本（其它模型与历史检索看到的形态）
		ServerToolUseContent serverContent = new ServerToolUseContent(calls);
		check("占位文本含搜索词", serverContent.toText().contains("[联网搜索] 冰岛人口 2024"), serverContent.toText());
		check("占位文本含打开网页", serverContent.toText().contains("[打开网页] https://example.com/page"),
				serverContent.toText());
		check("getBrief", "联网搜索".equals(serverContent.getBrief()), serverContent.getBrief());
		check("getToolName", "deepseek_web_search".equals(serverContent.getToolName()), serverContent.getToolName());

		ServerToolUseContent failedContent = new ServerToolUseContent(
				gs.fromJson(CHUNK_FAILED, Scheme.class).choices.get(0).delta.server_tool_calls);
		check("失败调用带占位说明", failedContent.toText().contains("（未完成）"), failedContent.toText());
		check("失败调用简述", "阅读链接".equals(failedContent.getBrief()), failedContent.getBrief());

		// 3. 历史持久化：序列化 -> 反序列化
		MutableMessageContents stored = new MutableMessageContents();
		stored.add(new PlainText("先想想"));
		stored.add(serverContent);
		MessageContents restored = gs.fromJson(gs.toJson(stored), MutableMessageContents.class);
		ServerToolUseContent restoredServer = null;
		for (MessageContent content : restored) {
			if (content instanceof ServerToolUseContent) {
				restoredServer = (ServerToolUseContent) content;
			}
		}
		check("反序列化恢复 server_tool_use",
				restoredServer != null && restoredServer.getServerToolCalls().size() == 1
						&& restoredServer.toText().equals(serverContent.toText()),
				restoredServer);

		// 4. DeepSeek：server_tool_calls 原样挂到助手消息上
		Item assistantItem = new Item(Role.ASSISTANT, "冰岛约 39.4 万人。", new PlainText("先想想"), serverContent);
		AIRequest request = AIRequest.builder("checker").addHistoryItem(new Item(Role.USER, "冰岛有多少人？"))
				.addHistoryItem(assistantItem).build();
		JsonArray replay = HistoryRequestBuilder.createRequest(request, true);
		JsonObject last = replay.get(replay.size() - 1).getAsJsonObject();
		check("回传：最后一条是助手", "assistant".equals(last.get("role").getAsString()), last);
		check("回传：回答内容保留", "冰岛约 39.4 万人。".equals(last.get("content").getAsString()), last.get("content"));
		check("回传：server_tool_calls 原样带上",
				last.has("server_tool_calls")
						&& last.getAsJsonArray("server_tool_calls").toString().equals(calls.toString()),
				last.get("server_tool_calls"));

		// 5. 其它模型：不出现原始块，只留带截断的占位文本
		JsonArray other = HistoryRequestBuilder.createRequest(request, false);
		check("其它模型：不含原始调用块",
				!other.toString().contains("srvtoolu_") && !other.toString().contains("server_tool_calls"), other);
		JsonObject otherLast = other.get(other.size() - 1).getAsJsonObject();
		check("其它模型：占位文本并入思考",
				otherLast.has("reasoning_content")
						&& otherLast.get("reasoning_content").getAsString().contains("[联网搜索] 冰岛人口 2024"),
				otherLast.get("reasoning_content"));
		check("其它模型：回答内容保留", "冰岛约 39.4 万人。".equals(otherLast.get("content").getAsString()),
				otherLast.get("content"));

		// 6. 同时有客户端工具调用时，回传块仍挂在助手消息上
		List<ToolCall> toolCalls = Collections.singletonList(
				new ToolCall(0, "call_1", new ToolFunction("web_view", "{\"url\":\"https://example.com\"}")));
		AIRequest toolRequest = AIRequest.builder("checker")
				.addHistoryItem(new Item(Role.ASSISTANT, "最终回答", new PlainText("查一下"),
						new ToolCallContent(toolCalls), new ServerToolUseContent(calls)))
				.build();
		JsonArray toolReplay = HistoryRequestBuilder.createRequest(toolRequest, true);
		boolean attached = false;
		for (int i = 0; i < toolReplay.size(); i++) {
			JsonObject message = toolReplay.get(i).getAsJsonObject();
			if (message.has("server_tool_calls") && "assistant".equals(message.get("role").getAsString())) {
				attached = true;
			}
		}
		check("混合工具：server_tool_calls 挂在助手消息上", attached, toolReplay);
		check("混合工具：客户端 tool_calls 仍在",
				toolReplay.toString().contains("\"tool_calls\"") && toolReplay.toString().contains("web_view"), toolReplay);

		// 7. 请求体：声明服务端联网工具、剔除客户端 web_search、保留其它工具
		ToolCallManager manager = new ToolCallManager()
				.registerTool(new ToolData.Builder("web_search", "搜索网页").putParam("query", "搜索词").build())
				.registerTool(new ToolData.Builder("web_view", "打开网页").putParam("url", "地址").build());
		AIRequest searchRequest = AIRequest.builder("checker").toolCallManager(manager)
				.addHistoryItem(assistantItem).addHistoryItem(new Item(Role.USER, "再说说")).build();
		boolean serverSearch = (Boolean) invokeProvider("useServerSearch", searchRequest);
		check("服务端搜索：文本输出时启用", serverSearch, serverSearch);

		JsonObject body = (JsonObject) invokeProvider("createRequest", searchRequest, Boolean.valueOf(serverSearch));
		JsonArray tools = body.getAsJsonArray("tools");
		boolean serverDeclared = false;
		boolean clientSearchDeclared = false;
		boolean webViewDeclared = false;
		for (int i = 0; i < tools.size(); i++) {
			JsonObject tool = tools.get(i).getAsJsonObject();
			if (tool.has("type") && "deepseek_web_search".equals(tool.get("type").getAsString())) {
				serverDeclared = true;
			}
			if (tool.has("function") && "web_search".equals(tool.getAsJsonObject("function").get("name").getAsString())) {
				clientSearchDeclared = true;
			}
			if (tool.has("function") && "web_view".equals(tool.getAsJsonObject("function").get("name").getAsString())) {
				webViewDeclared = true;
			}
		}
		check("请求体：声明 deepseek_web_search", serverDeclared, tools);
		check("请求体：不再下发客户端 web_search", !clientSearchDeclared, tools);
		check("请求体：其它客户端工具保留", webViewDeclared, tools);
		// 官方限制：strict 不能与 deepseek_web_search 同时出现，开启联网后必须整字段省去
		check("请求体：联网时不带 strict", !hasFunctionField(tools, "strict"), tools);
		check("请求体：历史回传 server_tool_calls", body.get("messages").toString().contains("server_tool_calls"),
				body.get("messages"));
		check("请求体：model 为 deepseek-flash", "deepseek-flash".equals(body.get("model").getAsString()), body.get("model"));
		check("请求体：thinking 与联网工具可共存", body.has("thinking"), body.get("thinking"));

		// 8. JSON 输出 / prefix 续写时退回客户端搜索工具
		AIRequest jsonRequest = AIRequest.builder("checker").toolCallManager(manager)
				.format(AIRequest.ResponseFormat.JSON).addHistoryItem(assistantItem).build();
		check("服务端搜索：JSON 输出时关闭", !((Boolean) invokeProvider("useServerSearch", jsonRequest)), "json");
		AIRequest prefixRequest = AIRequest.builder("checker").toolCallManager(manager).prefix("{")
				.addHistoryItem(assistantItem).build();
		check("服务端搜索：prefix 续写时关闭", !((Boolean) invokeProvider("useServerSearch", prefixRequest)), "prefix");
		JsonObject jsonBody = (JsonObject) invokeProvider("createRequest", jsonRequest, Boolean.FALSE);
		check("JSON 输出：仍用客户端 web_search",
				hasFunctionTool(jsonBody.getAsJsonArray("tools"), "web_search"), jsonBody.getAsJsonArray("tools"));
		// 没有服务端联网工具时保持原有行为：无可选参数的工具仍声明 strict
		check("JSON 输出：仍保留 strict", hasFunctionField(jsonBody.getAsJsonArray("tools"), "strict"),
				jsonBody.getAsJsonArray("tools"));

		// 9. deepseek-v4-pro 不使用服务端联网优化：保留客户端 web_search 与 strict
		AIRequest proRequest = AIRequest.builder("checker").toolCallManager(manager).modelHint("deepseek/pro")
				.addHistoryItem(assistantItem).build();
		check("服务端搜索：v4-pro 不启用", !((Boolean) invokeProvider("useServerSearch", proRequest)), "pro");
		JsonObject proBody = (JsonObject) invokeProvider("createRequest", proRequest, Boolean.FALSE);
		check("v4-pro：model 正确", "deepseek-v4-pro".equals(proBody.get("model").getAsString()), proBody.get("model"));
		check("v4-pro：保留客户端 web_search",
				hasFunctionTool(proBody.getAsJsonArray("tools"), "web_search"), proBody.getAsJsonArray("tools"));
		check("v4-pro：不声明服务端联网工具",
				!proBody.getAsJsonArray("tools").toString().contains("deepseek_web_search"),
				proBody.getAsJsonArray("tools"));
		check("v4-pro：仍保留 strict", hasFunctionField(proBody.getAsJsonArray("tools"), "strict"),
				proBody.getAsJsonArray("tools"));

		// 10. usage.server_tool_use：只记录联网次数，不参与计价
		DeepseekV4Usage withSearch = gs.fromJson(
				"{\"prompt_tokens\":100,\"completion_tokens\":50,\"prompt_cache_hit_tokens\":0,"
						+ "\"prompt_cache_miss_tokens\":100,\"server_tool_use\":{\"tool_search\":3,\"tool_fetch\":1}}",
				DeepseekV4Usage.class);
		DeepseekV4Usage withoutSearch = gs.fromJson(
				"{\"prompt_tokens\":100,\"completion_tokens\":50,\"prompt_cache_hit_tokens\":0,"
						+ "\"prompt_cache_miss_tokens\":100}",
				DeepseekV4Usage.class);
		check("联网次数解析",
				withSearch.server_tool_use != null && withSearch.server_tool_use.tool_search == 3
						&& withSearch.server_tool_use.tool_fetch == 1,
				withSearch.server_tool_use);
		check("联网次数不参与计价",
				Double.compare(withSearch.getEquivantTokens(), withoutSearch.getEquivantTokens()) == 0,
				withSearch.getEquivantTokens() + " vs " + withoutSearch.getEquivantTokens());
		DeepseekV4Usage sumUsage = new DeepseekV4Usage();
		sumUsage.add(withSearch);
		sumUsage.add(withSearch);
		check("联网次数随轮次累加", sumUsage.server_tool_use != null && sumUsage.server_tool_use.tool_search == 6,
				sumUsage.server_tool_use);

		System.out.println(NL + "==== 用例结束：" + (failures == 0 ? "全部通过" : failures + " 个失败") + " ====");
		if (failures != 0) {
			System.exit(1);
		}
	}

	/** 断言并打印结果。 */
	private static void check(String name, boolean ok, Object actual) {
		if (ok) {
			System.out.println("[PASS] " + name);
		} else {
			failures++;
			System.out.println("[FAIL] " + name + " -> " + actual);
		}
	}

	/** 工具数组里是否有指定名字的客户端函数工具。 */
	private static boolean hasFunctionTool(JsonArray tools, String name) {
		for (int i = 0; i < tools.size(); i++) {
			JsonObject tool = tools.get(i).getAsJsonObject();
			if (tool.has("function") && name.equals(tool.getAsJsonObject("function").get("name").getAsString())) {
				return true;
			}
		}
		return false;
	}

	/** 是否有任意客户端函数工具声明了指定字段（例如 {@code strict}）。 */
	private static boolean hasFunctionField(JsonArray tools, String field) {
		for (int i = 0; i < tools.size(); i++) {
			JsonObject tool = tools.get(i).getAsJsonObject();
			if (tool.has("function") && tool.getAsJsonObject("function").has(field)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 调用提供者的私有静态方法（仅供本检查使用）。
	 * <p>按方法名与参数个数匹配；找不到时直接抛出，避免静默通过。</p>
	 */
	private static Object invokeProvider(String name, Object... args) throws Exception {
		List<Method> candidates = new ArrayList<>();
		for (Method method : DeepseekModelProvider.class.getDeclaredMethods()) {
			if (method.getName().equals(name) && method.getParameterTypes().length == args.length) {
				candidates.add(method);
			}
		}
		if (candidates.size() != 1) {
			throw new NoSuchMethodException(name + "（匹配到 " + candidates.size() + " 个）");
		}
		Method method = candidates.get(0);
		method.setAccessible(true);
		return method.invoke(null, args);
	}
}
