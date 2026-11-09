package com.khjxiaogu.aiwuxia.mcp.server;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.AIOutput;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolResult;
import com.khjxiaogu.aiwuxia.llm.scheme.UsageIntf;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallManager;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpException;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpJson;
import com.khjxiaogu.aiwuxia.mcp.protocol.McpProtocol;
import com.khjxiaogu.aiwuxia.state.history.message.AudioContent;
import com.khjxiaogu.aiwuxia.state.history.message.ImageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.state.history.message.VideoContent;

/**
 * MCP 服务端的协议实现（与传输方式无关）。
 * <p>
 * 负责处理单条 JSON-RPC 消息：{@code initialize}、{@code ping}、{@code tools/list}、
 * {@code tools/call} 以及各类通知，并在 {@code tools/call} 前后完成计费：
 * </p>
 * <ol>
 *   <li>按 {@link McpToolRegistry} 查找当前用户可调用的 {@link ToolData}；</li>
 *   <li>按固定调用费 {@link #CALL_COST} 预扣费用（{@link McpAccessTokenStore#charge}）；</li>
 *   <li>执行工具（{@link ToolData#tool}，入参为 JSON 文本），并收集工具通过
 *       {@link AIOutput#addUsage} 写入的内部用量；</li>
 *   <li>成功则按内部用量结算“固定调用费 + 内部费用合计”
 *       （{@link McpAccessTokenStore#commit}），失败则退回固定调用费并以 {@code isError} 结果回传。</li>
 *   <li>工具明确标记为简单失败（{@link ToolResult#isSuccess()} 为 false，例如网络不可达、服务不存在）时，
 *       只免除固定调用费，工具内部已经产生的用量照常结算。</li>
 *   <li>声明为异步（{@code ToolData.async}）的工具不等待完成，立即返回 {@code task_id}；
 *       客户端用内置的 {@value #TASK_RESULT_TOOL} 工具轮询结果。</li>
 * </ol>
 * <p>
 * 计费公式为 {@code CALL_COST + Σ ceil(usage.getEquivantTokens())}：固定调用费与工具无关；
 * 工具内部产生的每一笔用量按与主流程补充配额一致的向上取整方式折算，执行完成后统一结算。
 * </p>
 * <p>
 * 成功执行后还会做一次固定的多媒体处理：把结果中的图片/音频/视频 id（以及
 * {@code 图片id：xxx} 这类文本引用）交给 {@link MediaUrlResolver} 换成可访问链接，
 * 不再把内部 id 直接返回给 MCP 客户端。
 * </p>
 * <p>
 * 本类不接触 HTTP、不解析 Authorization 头，认证结果（{@link McpAccessTokenStore.Token}）
 * 由传输层传入后调用 {@link #handle}，因此也可以被 stdio 等其他传输复用。
 * </p>
 */
public class McpServer {

    /** 每次工具调用的固定费用（额外配额单位），工具内部产生的用量在此基础上另行结算。 */
    public static final int CALL_COST = 100;
    /** 通用异步任务查询工具名。 */
    public static final String TASK_RESULT_TOOL = "mcp_task_result";
    /** 当前配额查询工具名。 */
    public static final String QUOTA_TOOL = "mcp_quota";
    /** 异步任务保留时间：30 分钟。 */
    private static final long TASK_TTL_MS = 30L * 60L * 1000L;
    /** 单次查询异步任务最多等待时间，避免 MCP 客户端超时。 */
    private static final long TASK_WAIT_SECONDS = 20L;

    /**
     * 多媒体 id → 可访问链接的解析器。
     * <p>
     * MCP 服务端在返回工具结果前，会把多媒体内容里的裸 id（图片/音频/视频 id、以及
     * {@code 图片id：xxx} 这类文本引用）交给本解析器换成链接，避免把内部 id 直接发给外部客户端。
     * </p>
     */
    @FunctionalInterface
    public interface MediaUrlResolver {
        /**
         * 把多媒体 id 解析为可访问链接。
         *
         * @param id       多媒体 id
         * @param addUsage 解析过程中产生的用量接收方，可为 null
         * @return 可访问链接；解析失败时返回 null 或原 id 由调用方回退
         */
        String resolve(String id, Consumer<UsageIntf<?>> addUsage);
    }

    /** 工具注册表。 */
    private final McpToolRegistry registry;
    /** 令牌与计费存储。 */
    private final McpAccessTokenStore store;
    /** 服务名称，写入 initialize 响应的 serverInfo。 */
    private final String serverName;
    /** 服务版本，写入 initialize 响应的 serverInfo。 */
    private final String serverVersion;
    /** 多媒体 id → 链接解析器，可为 null（不处理多媒体 id）。 */
    private final MediaUrlResolver mediaUrlResolver;
    /** task_id → 异步工具任务。 */
    private final Map<String, AsyncTask> asyncTasks = new ConcurrentHashMap<>();

    /**
     * 创建协议实现。
     *
     * @param registry      工具注册表
     * @param store         令牌与计费存储
     * @param serverName    服务名称，null 时使用 {@code AIWuxia-MCP}
     * @param serverVersion 服务版本，null 时使用 {@code 0.0.1}
     */
    public McpServer(McpToolRegistry registry, McpAccessTokenStore store, String serverName, String serverVersion) {
        this(registry, store, serverName, serverVersion, null);
    }

    /**
     * 创建协议实现，并在返回工具结果前把多媒体 id 解析为链接。
     *
     * @param registry        工具注册表
     * @param store           令牌与计费存储
     * @param serverName      服务名称，null 时使用 {@code AIWuxia-MCP}
     * @param serverVersion   服务版本，null 时使用 {@code 0.0.1}
     * @param mediaUrlResolver 多媒体 id → 链接解析器，可为 null
     */
    public McpServer(McpToolRegistry registry, McpAccessTokenStore store, String serverName, String serverVersion,
            MediaUrlResolver mediaUrlResolver) {
        this.registry = registry == null ? new McpToolRegistry() : registry;
        this.store = store;
        this.serverName = serverName == null ? "AIWuxia-MCP" : serverName;
        this.serverVersion = serverVersion == null ? "0.0.1" : serverVersion;
        this.mediaUrlResolver = mediaUrlResolver;
        // 通用异步任务查询工具：所有 tool.async == true 的 MCP 工具都会返回 task_id
        this.registry.register(createTaskResultTool());
        this.registry.register(createQuotaTool());
    }

    /**
     * 兼容旧构造：单工具价格已废弃，调用费固定为 {@link #CALL_COST}。
     *
     * @param registry      工具注册表
     * @param store         令牌与计费存储
     * @param defaultPrice  旧版默认价格，已忽略
     * @param serverName    服务名称
     * @param serverVersion 服务版本
     * @deprecated 计费改为“固定调用费 + 工具内部用量”，请使用
     *             {@link #McpServer(McpToolRegistry, McpAccessTokenStore, String, String)}
     */
    @Deprecated
    public McpServer(McpToolRegistry registry, McpAccessTokenStore store, int defaultPrice, String serverName,
            String serverVersion) {
        this(registry, store, serverName, serverVersion);
    }

    /** 通用异步任务查询工具：传入任意异步工具返回的 task_id。 */
    private ToolData createTaskResultTool() {
        return new ToolData.Builder(TASK_RESULT_TOOL,
                "查询异步 MCP 工具的任务结果。凡是调用后返回 task_id 的工具，都用本工具传 task_id 查询；"
                        + "返回“处理中”时继续轮询，任务完成后返回实际结果并失效。")
                .putParam("task_id", "异步工具返回的 task_id。", "任务 ID")
                .tool((data, output, manager) -> CompletableFuture.completedFuture(queryTask(data, output)))
                .build();
    }

    /** 当前配额查询工具：只返回额外配额余额，不收费。 */
    private ToolData createQuotaTool() {
        return new ToolData.Builder(QUOTA_TOOL, "查询当前 MCP 令牌的额外配额余额。本工具免费。")
                .tool((data, output, manager) -> CompletableFuture.completedFuture(ToolResult.text("")))
                .build();
    }

    /** 生成当前令牌的配额文本：只返回额外配额余额。 */
    private String quotaText(McpAccessTokenStore.Token token) {
        if (store == null) {
            return "MCP 计费未启用，无法查询配额。";
        }
        if (token == null) {
            return "当前连接没有访问令牌，无法查询配额。";
        }
        return "当前额外配额：" + store.getPaidBalance(token.getUid());
    }

    /** 查询异步任务；最多等待 20 秒，避免单次轮询导致客户端超时。 */
    private ToolResult queryTask(String data, AIOutput output) {
        cleanupAsyncTasks();
        String taskId;
        try {
            JsonObject json = JsonParser.parseString(data == null ? "{}" : data).getAsJsonObject();
            JsonElement value = json.get("task_id");
            taskId = value == null || value.isJsonNull() ? "" : value.getAsString().trim();
        } catch (RuntimeException ex) {
            return ToolResult.text("参数错误：task_id 必须是字符串。");
        }
        if (taskId.isEmpty()) {
            return ToolResult.text("参数错误：缺少 task_id。");
        }
        AsyncTask task = asyncTasks.get(taskId);
        if (task == null) {
            return ToolResult.failure("任务不存在或已过期：" + taskId);
        }
        try {
            // 等待任务完成并完成计费；查询本身不再把任务用量转进来，并发查询不会重复计费
            task.processed.get(TASK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            return ToolResult.text("任务仍在处理中，task_id=" + taskId
                    + "。请继续调用 " + TASK_RESULT_TOOL + " 轮询。");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("任务查询被中断：" + taskId);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            return ToolResult.failure("任务查询失败：" + describe(cause));
        }
        if (!task.success) {
            return ToolResult.failure(task.resultText);
        }
        if (task.result == null) {
            return ToolResult.text(task.resultText);
        }
        return ToolResult.of(task.resultText, task.result.getAttachments());
    }

    /**
     * 异步任务完成回调：立即解析结果、收集用量并扣费。
     * <p>
     * 扣费发生在任务完成时而不是查询时，因此多个客户端并发查询同一任务不会重复计费。
     * 简单失败只免基础调用费，任务内部已经产生的用量仍然扣除。
     * </p>
     */
    private void finishTask(AsyncTask task, ToolResult result, Throwable error) {
        boolean success;
        String text;
        try {
            if (error != null) {
                success = false;
                text = "任务执行失败：" + describe(error);
                task.result = null;
            } else {
                success = result == null || result.isSuccess();
                MessageContents content = result == null ? MessageContents.EMPTY : result.getContent();
                text = resolveMedia(content, usage -> task.output.addUsage(usage)).toText();
                if (text == null) {
                    text = "";
                }
                task.result = result;
            }
        } catch (Throwable ex) {
            ex.printStackTrace();
            success = false;
            text = "任务执行失败：" + describe(ex);
            task.result = null;
        }
        task.resultText = text;
        task.success = success;
        if (task.settled.compareAndSet(false, true)) {
            int usagePrice = usageCostOf(task.output);
            if (store != null && task.charge != null) {
                try {
                    store.commit(task.charge, usagePrice, System.currentTimeMillis() - task.startedAt,
                            text.length(), success, success);
                } catch (Throwable ex) {
                    ex.printStackTrace();
                }
            }
        }
        task.processed.complete(null);
    }

    /** 清理超过 30 分钟仍未完成或未被取走的任务。 */
    private void cleanupAsyncTasks() {
        long now = System.currentTimeMillis();
        Iterator<Entry<String, AsyncTask>> iterator = asyncTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<String, AsyncTask> entry = iterator.next();
            AsyncTask task = entry.getValue();
            if (now - task.createdAt <= TASK_TTL_MS) {
                continue;
            }
            if (task.settled.compareAndSet(false, true) && store != null && task.charge != null) {
                // 任务超时未完成：退回预扣的基础调用费，任务内部未结算的用量不再收费
                store.refund(task.charge, "async task expired");
            }
            iterator.remove();
            task.processed.complete(null);
        }
    }

    /** 异步工具任务。 */
    private static final class AsyncTask {
        private final CompletableFuture<ToolResult> future;
        private final CompletableFuture<Void> processed = new CompletableFuture<>();
        private final AIOutput output;
        private final McpAccessTokenStore.Charge charge;
        private final long startedAt;
        private final long createdAt = System.currentTimeMillis();
        private final AtomicBoolean settled = new AtomicBoolean(false);
        private volatile ToolResult result;
        private volatile String resultText = "";
        private volatile boolean success = true;

        AsyncTask(CompletableFuture<ToolResult> future, AIOutput output, McpAccessTokenStore.Charge charge,
                long startedAt) {
            this.future = future;
            this.output = output;
            this.charge = charge;
            this.startedAt = startedAt;
        }
    }

    /** 工具注册表。 */
    public McpToolRegistry getRegistry() {
        return registry;
    }

    /** 每次工具调用的固定费用。 */
    public int getCallCost() {
        return CALL_COST;
    }

    /**
     * 旧版默认单次调用价格。
     *
     * @return 固定调用费 {@link #CALL_COST}
     * @deprecated 计费改为“固定调用费 + 工具内部用量”，请使用 {@link #getCallCost()}
     */
    @Deprecated
    public int getDefaultPrice() {
        return CALL_COST;
    }

    /**
     * 某个工具的调用价格。
     *
     * @param tool 工具名称（已不参与定价）
     * @return 固定调用费 {@link #CALL_COST}
     * @deprecated 计费改为“固定调用费 + 工具内部用量”，请使用 {@link #getCallCost()}
     */
    @Deprecated
    public int priceOf(String tool) {
        return CALL_COST;
    }

    /**
     * 处理一条 JSON-RPC 请求。
     *
     * @param token     已认证的访问令牌
     * @param request   JSON-RPC 请求对象
     * @param clientIp  客户端 IP（写入消费记录，可为 null）
     * @return 响应对象；请求是通知（无 id）时返回 null
     * @throws McpException 请求非法、方法不存在、未授权、配额不足等
     */
    public JsonObject handle(McpAccessTokenStore.Token token, JsonObject request, String clientIp) throws McpException {
        if (request == null) {
            throw new McpException(McpProtocol.ERROR_INVALID_REQUEST, "请求为空");
        }
        if (!request.has("method") || request.get("method").isJsonNull()
                || !request.get("method").isJsonPrimitive()) {
            throw new McpException(McpProtocol.ERROR_INVALID_REQUEST, "缺少 method 字段");
        }
        String method = request.get("method").getAsString();
        JsonObject params = request.has("params") && request.get("params").isJsonObject()
                ? request.getAsJsonObject("params")
                : new JsonObject();
        // 通知（无 id）不需要响应，也不做认证以外的任何事
        boolean notification = !request.has("id") || request.get("id").isJsonNull();
        switch (method) {
        case McpProtocol.METHOD_INITIALIZE:
            return handleInitialize(params);
        case McpProtocol.METHOD_PING:
            return McpJson.result(null, new JsonObject());
        case McpProtocol.METHOD_TOOLS_LIST:
            return handleToolsList(token);
        case McpProtocol.METHOD_TOOLS_CALL:
            return handleToolsCall(token, params, request.get("id"), clientIp);
        case McpProtocol.METHOD_RESOURCES_LIST:
        case McpProtocol.METHOD_RESOURCES_TEMPLATES_LIST:
        case McpProtocol.METHOD_PROMPTS_LIST:
            return emptyListResult(method);
        case McpProtocol.METHOD_LOGGING_SET_LEVEL:
        case McpProtocol.METHOD_COMPLETION_COMPLETE:
            return McpJson.result(null, new JsonObject());
        default:
            if (notification || method.startsWith("notifications/")) {
                return null;
            }
            throw McpException.methodNotFound(method);
        }
    }

    /**
     * 处理 initialize：协商协议版本并返回服务能力。
     *
     * @param params 客户端参数（可含 protocolVersion）
     * @return initialize 结果
     */
    public JsonObject handleInitialize(JsonObject params) {
        String requested = params != null && params.has("protocolVersion") && !params.get("protocolVersion").isJsonNull()
                ? params.get("protocolVersion").getAsString()
                : null;
        JsonObject toolsCapability = new JsonObject();
        // 本服务端不主动发送 tools/list_changed 通知，客户端每次请求都会拿到最新工具表
        toolsCapability.addProperty("listChanged", false);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", toolsCapability);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", serverName);
        serverInfo.addProperty("version", serverVersion);
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", McpProtocol.negotiate(requested));
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions",
                "本服务把 AIWuxia 的 ToolData 以 MCP 工具形式提供。每次调用固定扣除 " + CALL_COST
                        + " 额外配额，工具内部产生的用量（模型调用、图片/视频生成、搜索、存储等）按实际用量另行结算。");
        return result;
    }

    /**
     * 处理 tools/list：返回当前令牌所属用户可见的工具及其 JSON Schema。
     *
     * @param token 已认证令牌
     * @return 工具列表结果
     */
    public JsonObject handleToolsList(McpAccessTokenStore.Token token) {
        List<ToolData> tools = registry.list(token == null ? null : token.getUid());
        JsonArray array = new JsonArray();
        for (ToolData tool : tools) {
            array.add(toMcpTool(tool));
        }
        JsonObject result = new JsonObject();
        result.add("tools", array);
        return result;
    }

    /**
     * 处理 tools/call：查找工具 → 预扣固定调用费 → 执行 → 结算内部用量 / 失败退款。
     *
     * @param token    已认证令牌
     * @param params   请求参数（name、arguments）
     * @param id       请求 ID（写入消费记录）
     * @param clientIp 客户端 IP
     * @return 工具调用结果
     * @throws McpException 参数错误、工具不存在、固定调用费不足
     */
    public JsonObject handleToolsCall(McpAccessTokenStore.Token token, JsonObject params, JsonElement id,
            String clientIp) throws McpException {
        if (params == null || !params.has("name") || params.get("name").isJsonNull()) {
            throw McpException.invalidParams("缺少工具名称 name");
        }
        String name = params.get("name").getAsString();
        JsonObject arguments;
        if (params.has("arguments") && params.get("arguments").isJsonObject()) {
            arguments = params.getAsJsonObject("arguments");
        } else {
            arguments = new JsonObject();
        }
        ToolData tool = registry.find(name, token == null ? null : token.getUid());
        if (tool == null || tool.tool == null) {
            throw McpException.invalidParams("工具不存在或当前不可用：" + name);
        }
        String requestId = id == null || id.isJsonNull() ? null : id.getAsString();
        System.out.println("[MCP] tools/call name=" + name + " arguments=" + arguments);
        if (QUOTA_TOOL.equals(name)) {
            // 配额查询是免费工具：不预扣、不写消费记录
            boolean available = store != null && token != null;
            String text = quotaText(token);
            Integer balance = available ? Integer.valueOf(store.getPaidBalance(token.getUid())) : null;
            return successResult(text, 0, 0, Integer.valueOf(0), balance, available);
        }
        McpAccessTokenStore.Charge charge = null;
        if (store != null) {
            // 先按固定调用费预扣：固定费用都不够时直接拒绝，不执行工具
            charge = store.charge(token, name, CALL_COST, clientIp, requestId);
        }
        long start = System.currentTimeMillis();
        // 服务端调用没有会话，用一个只收集用量的输出对象和一个空管理器传入；
        // 工具内部写入的用量在工具执行完成后统一换算成费用。
        AIOutput output = new AIOutput.FilledAIOutput(MessageContents.EMPTY, "", null);
        String text;
        boolean success = true;
        boolean deferredCommit = tool.async;
        if (tool.async) {
            // 所有 async 工具统一转为后台任务：立即返回 task_id，由 mcp_task_result 查询
            String taskId = UUID.randomUUID().toString();
            AIOutput taskOutput = new AIOutput.FilledAIOutput(MessageContents.EMPTY, "", null);
            CompletableFuture<ToolResult> future;
            try {
                future = tool.tool.run(McpJson.stringifyArguments(arguments), taskOutput, new ToolCallManager());
            } catch (Throwable ex) {
                ex.printStackTrace();
                if (store != null && charge != null) {
                    store.refund(charge, ex.toString());
                }
                return errorResult("工具执行失败：" + describe(ex), CALL_COST);
            }
            if (future == null) {
                if (store != null && charge != null) {
                    store.refund(charge, "async tool returned null future");
                }
                return errorResult("工具执行失败：异步工具没有返回任务。", CALL_COST);
            }
            // 本地模型未在线时工具通常会立即返回失败 Future，直接失败而不是先返回 task_id
            if (future.isDone()) {
                ToolResult immediate = null;
                Throwable failure = null;
                try {
                    immediate = future.getNow(null);
                } catch (CompletionException ex) {
                    failure = ex.getCause() == null ? ex : ex.getCause();
                } catch (CancellationException ex) {
                    failure = ex;
                }
                if (failure != null || (immediate != null && !immediate.isSuccess())) {
                    String failText = failure != null ? "工具执行失败：" + describe(failure)
                            : immediate.getText();
                    if (failText == null || failText.isEmpty()) {
                        failText = "本地模型服务不在线或工具不可用。";
                    }
                    int failUsage = usageCostOf(taskOutput);
                    if (store != null && charge != null) {
                        // 简单失败免基础费，但任务已经产生的用量照常结算
                        store.commit(charge, failUsage, System.currentTimeMillis() - start,
                                failText.length(), false, false);
                    }
                    Integer failCharged = store == null ? null : Integer.valueOf(failUsage);
                    Integer failBalance = store == null || token == null ? null
                            : Integer.valueOf(store.getPaidBalance(token.getUid()));
                    return successResult(failText, 0, failUsage, failCharged, failBalance, false);
                }
            }
            AsyncTask task = new AsyncTask(future, taskOutput, charge, start);
            asyncTasks.put(taskId, task);
            // 完成时立即结算：并发查询不再承担任务用量，避免重复扣费
            future.whenComplete((result, error) -> finishTask(task, result, error));
            cleanupAsyncTasks();
            text = "任务已提交，task_id=" + taskId + "。请调用 " + TASK_RESULT_TOOL
                    + " 并传入该 task_id 查询结果；不要重复提交。";
        } else {
            try {
                ToolResult result = tool.tool.run(McpJson.stringifyArguments(arguments), output, new ToolCallManager())
                        .join();
                success = result == null || result.isSuccess();
                // 固定处理：多媒体内容里的 id 统一通过 TOS 换成可访问链接，不再把内部 id 直接返回给客户端
                MessageContents content = result == null ? MessageContents.EMPTY : result.getContent();
                text = resolveMedia(content, usage -> output.addUsage(usage)).toText();
                if (text == null) {
                    text = "";
                }
            } catch (Throwable ex) {
                ex.printStackTrace();
                if (store != null && charge != null) {
                    store.refund(charge, ex.toString());
                }
                return errorResult("工具执行失败：" + describe(ex), CALL_COST);
            }
        }
        int usagePrice = usageCostOf(output);
        System.out.println("[MCP] tools/call result name=" + name + " success=" + success + " usagePrice=" + usagePrice
                + " text=" + (text.length() > 500 ? text.substring(0, 500) + "..." : text));
        if (store != null && charge != null && !deferredCommit) {
            // 简单失败不扣基础调用费，但工具内部已经产生的用量照常扣费
            store.commit(charge, usagePrice, System.currentTimeMillis() - start, text.length(), success, success);
        }
        int callPrice = success ? CALL_COST : 0;
        Integer charged = store == null ? null : Integer.valueOf(totalCost(callPrice, usagePrice));
        Integer balance = store == null || token == null ? null : Integer.valueOf(store.getPaidBalance(token.getUid()));
        return successResult(text, callPrice, usagePrice, charged, balance, success);
    }

    // ==================== 内部实现 ====================

    /**
     * 把工具执行期间写入的全部用量换算成费用合计。
     * <p>
     * 与主流程补充配额（{@code paidOnly}）的计费方式一致：每一笔用量按
     * {@code ceil(getEquivantTokens())} 取整后累加，因此工具内部每产生一笔费用都会计入本次调用。
     * </p>
     *
     * @param output 工具执行时传入的输出对象，可为 null
     * @return 内部费用合计（额外配额单位），不小于 0
     */
    public static int usageCostOf(AIOutput output) {
        if (output == null) {
            return 0;
        }
        long total = 0L;
        for (UsageIntf<?> usage : output.getUsages()) {
            if (usage == null) {
                continue;
            }
            double tokens = usage.getEquivantTokens();
            if (Double.isNaN(tokens) || Double.isInfinite(tokens) || tokens <= 0) {
                continue;
            }
            total += (long) Math.ceil(tokens);
            if (total >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    /** 多媒体 id 的通用格式：72 位内容哈希，可带音频/图片扩展名。 */
    private static final String MEDIA_ID_REGEX = "[0-9a-f]{72}"
            + "(?:\\.(?:png|jpe?g|gif|webp|mp3|wav|ogg|m4a|flac|aac|mp4|webm))?";
    /** 文本中的多媒体标签 id：{@code <image id=...>}、{@code <audio id=...>}、{@code <video id=...>}。 */
    private static final Pattern MEDIA_TAG_ID = Pattern.compile(
            "(?i)(<(?:image|audio|video)\\s+id=)(" + MEDIA_ID_REGEX + ")(>)");
    /** 文本中的多媒体 id 引用：{@code 图片id：...}、{@code 图片id为：...} 等。 */
    private static final Pattern MEDIA_TEXT_ID = Pattern.compile(
            "(?i)((?:图片|音频|视频)\\s*id\\s*(?:为|是)?\\s*[:：]?\\s*)(" + MEDIA_ID_REGEX + ")");

    /**
     * 固定处理工具结果中的多媒体 id：把图片/音频/视频内容以及文本中的裸 id 通过 TOS 解析成链接。
     *
     * @param contents 工具结果内容，可为 null
     * @param addUsage 解析过程中产生的用量接收方，可为 null
     * @return 处理后的内容，永不为 null
     */
    private MessageContents resolveMedia(MessageContents contents, Consumer<UsageIntf<?>> addUsage) {
        if (contents == null) {
            return MessageContents.EMPTY;
        }
        if (contents.isEmpty() || mediaUrlResolver == null) {
            return contents;
        }
        MutableMessageContents resolved = new MutableMessageContents();
        for (MessageContent content : contents) {
            MessageContent item = resolveMediaContent(content, addUsage);
            if (item != null) {
                resolved.add(item);
            }
        }
        return resolved;
    }

    /** 解析单个内容段中的多媒体 id。 */
    private MessageContent resolveMediaContent(MessageContent content, Consumer<UsageIntf<?>> addUsage) {
        if (content == null) {
            return null;
        }
        if (content instanceof ImageContent) {
            ImageContent media = (ImageContent) content;
            String url = resolveMediaUrl(media.getImageUrl(), addUsage);
            if (url == null || url.equals(media.getImageUrl())) {
                return media;
            }
            return new ImageContent(url, media.getDescription(), media.isCensored());
        }
        if (content instanceof AudioContent) {
            AudioContent media = (AudioContent) content;
            String url = resolveMediaUrl(media.getAudioUrl(), addUsage);
            if (url == null || url.equals(media.getAudioUrl())) {
                return media;
            }
            return new AudioContent(url, media.getDescription());
        }
        if (content instanceof VideoContent) {
            VideoContent media = (VideoContent) content;
            String url = resolveMediaUrl(media.getVideoUrl(), addUsage);
            if (url == null || url.equals(media.getVideoUrl())) {
                return media;
            }
            return new VideoContent(url, media.getDescription());
        }
        if (content.isPlainText()) {
            String text = content.toText();
            String resolved = resolveMediaIdsInText(text, addUsage);
            return resolved.equals(text) ? content : new PlainText(resolved);
        }
        return content;
    }

    /** 把文本中的多媒体 id 替换为链接。 */
    private String resolveMediaIdsInText(String text, Consumer<UsageIntf<?>> addUsage) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = replaceMediaIds(text, MEDIA_TAG_ID, 3, addUsage);
        return replaceMediaIds(result, MEDIA_TEXT_ID, 0, addUsage);
    }

    /**
     * 按正则替换多媒体 id。
     *
     * @param text        原始文本
     * @param pattern     匹配模式，group(1) 为前缀、group(2) 为 id、可选 group(suffixGroup) 为后缀
     * @param suffixGroup 后缀分组序号，0 表示没有后缀
     * @param addUsage    用量接收方，可为 null
     * @return 替换后的文本
     */
    private String replaceMediaIds(String text, Pattern pattern, int suffixGroup,
            Consumer<UsageIntf<?>> addUsage) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer buffer = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String id = matcher.group(2);
            String url = resolveMediaUrl(id, addUsage);
            if (url == null || url.equals(id)) {
                continue;
            }
            changed = true;
            String replacement = matcher.group(1) + url;
            if (suffixGroup > 0) {
                replacement += matcher.group(suffixGroup);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        if (!changed) {
            return text;
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /** 通过注册的解析器把媒体 id 换成链接；已经是链接的值原样返回。 */
    private String resolveMediaUrl(String value, Consumer<UsageIntf<?>> addUsage) {
        if (value == null || value.isEmpty() || isMediaLink(value)) {
            return value;
        }
        try {
            String resolved = mediaUrlResolver.resolve(value, addUsage);
            return resolved == null || resolved.isEmpty() ? value : resolved;
        } catch (Throwable ex) {
            ex.printStackTrace();
            return value;
        }
    }

    /** 判断一个媒体地址是否已经是可直接访问的链接，而不是对象存储 id。 */
    private static boolean isMediaLink(String value) {
        if (value == null) {
            return false;
        }
        return value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")
                || value.startsWith("file:") || value.startsWith("/") || value.contains("://")
                || (value.length() > 1 && value.charAt(1) == ':');
    }

    /** 固定调用费与内部费用相加，超过 int 上限时截断。 */
    private static int totalCost(int callCost, int usageCost) {
        long total = (long) Math.max(0, callCost) + Math.max(0, usageCost);
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /** 把 ToolData 转换为 MCP 工具描述。 */
    private JsonObject toMcpTool(ToolData tool) {
        JsonObject json = new JsonObject();
        json.addProperty("name", tool.name);
        json.addProperty("description", tool.description == null ? "" : tool.description);
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        if (tool.params != null) {
            for (Entry<String, String> entry : tool.params.entrySet()) {
                JsonObject property = new JsonObject();
                property.addProperty("type", "string");
                property.addProperty("description", entry.getValue() == null ? "" : entry.getValue());
                properties.add(entry.getKey(), property);
                required.add(entry.getKey());
            }
        }
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        json.add("inputSchema", schema);
        // 计费方式通过 _meta 暴露，方便客户端在调用前展示
        JsonObject meta = new JsonObject();
        meta.addProperty("price", CALL_COST);
        meta.addProperty("callPrice", CALL_COST);
        meta.addProperty("usagePrice", 0);
        meta.addProperty("charging", "每次调用固定 " + CALL_COST + " + 工具内部实际用量");
        json.add("_meta", meta);
        return json;
    }

    /** 构造文本结果；{@code success=false} 时按 MCP 约定标记 isError。 */
    private static JsonObject successResult(String text, int callPrice, int usagePrice, Integer charged,
            Integer balance, boolean success) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("type", McpProtocol.CONTENT_TYPE_TEXT);
        item.addProperty("text", text);
        content.add(item);
        result.add("content", content);
        JsonObject meta = new JsonObject();
        meta.addProperty("price", totalCost(callPrice, usagePrice));
        meta.addProperty("callPrice", callPrice);
        meta.addProperty("usagePrice", usagePrice);
        if (charged != null) {
            meta.addProperty("charged", charged.intValue());
        }
        if (balance != null) {
            meta.addProperty("paidBalance", balance.intValue());
        }
        result.add("_meta", meta);
        if (!success) {
            // 简单失败：工具已经明确标记，基础调用费已免除，但内部用量费用仍可能计入
            result.addProperty("isError", true);
        }
        return result;
    }

    /** 构造文本错误结果（工具内部失败，按 MCP 约定用 isError 而不是 JSON-RPC 错误）。 */
    private static JsonObject errorResult(String text, int callPrice) {
        // 失败时固定调用费已退回，charged 为 0；price 仍给出本次调用的名义固定费用
        return successResult(text, callPrice, 0, Integer.valueOf(0), null, false);
    }

    /** 空列表结果（resources/prompts 等本服务不提供的类别）。 */
    private static JsonObject emptyListResult(String method) {
        JsonObject result = new JsonObject();
        String key = method.startsWith("resources/templates") ? "resourceTemplates"
                : method.startsWith("resources") ? "resources" : "prompts";
        result.add(key, new JsonArray());
        return result;
    }

    /** 工具异常的可读描述。 */
    private static String describe(Throwable ex) {
        if (ex == null) {
            return "未知错误";
        }
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return ex.getClass().getName();
        }
        return message;
    }

    /** 当前可见工具（供管理接口展示）。 */
    public Collection<ToolData> listToolsFor(String uid) {
        return registry.list(uid);
    }
}
