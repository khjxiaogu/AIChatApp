# 工具调用进度去 ThreadLocal 方案（ToolInvocation）

> 状态：**方案稿**，本次未改动任何源码。
> 目标：去掉 [`ToolProgressContext`](../src/main/java/com/khjxiaogu/aiwuxia/utils/ToolProgressContext.java:17) 的 `ThreadLocal`，
> 改成"每次工具调用一个显式对象"，由它同时承载**状态指示器**与**任务身份**。
> 结论：**可行**，且顺带修掉三个现存的进度丢失问题。两条落地路线：**方案 C（工具返回 `ToolJob`，约 12 个文件）** 与 **方案 A（`Tool` 加第 4 参，约 30 个文件）**，推荐 C，见 §10。

---

## 0. 结论（TL;DR）

| 你的设想 | 判定 | 实际落地形态 |
|---|---|---|
| 改造"工具返回的结果对象"承载状态指示器 | **不成立** | 结果对象在结束时才产生，而 `queued/running/进度` 发生在执行**中**；订阅必定晚于事件（§3.1） |
| 把"工具返回的 future"升级成 job 对象 | **成立，且改动比方案 A 小** | 新增 `ToolJob extends CompletableFuture<ToolResult>`，工具按需返回；管理器 `instanceof` 订阅。49 处 `.tool(...)` + 4 处适配器零改动，仅 `McpUrlAttachmentTool` 异步分支要改（§10） |
| "状态指示器 + 任务本身"放同一个对象 | **成立** | job 方案里这个对象就是**工具自己创建并返回的 `ToolJob`**；方案 A 则要由管理器在调用前注入（§3.2） |
| 去掉 ThreadLocal 后工具要改签名 | 不一定 | 方案 C 不改签名；方案 A 才需要 `Tool.run(...)` 加第 4 个参数 |
| 会不会丢现有功能 | **不会，反而补全** | 现在同步 Qwen 本地生图、异步 H3/Qwen、MCP 直调、转后台任务后的进度都在丢（§2.1~2.3，均有代码证据） |

推荐路线：**方案 C（`ToolJob`，见 §10）**——"job 对象"这条路可行且改动面最小；如果不能接受"继承 `CompletableFuture`"，再退回方案 A（`Tool` 加第 4 参，见 §4/§6）。方案 B 不再需要。

---

## 1. 现状：ThreadLocal 在链条上的确切位置

### 1.1 一次工具调用怎么变成 `tool_status` 帧

```
ToolCallContext.start()                                   ← 每次工具调用（跑在 tool-call-N 线程池）
  ├─ emitState(RUNNING)                                   ToolCallContext.java:813
  ├─ sink = p -> emitProgress(callId, call, ..., p)       ToolCallContext.java:815
  ├─ ToolProgressContext.callWith(sink, () -> tool.run()) ToolCallContext.java:821
  │      └─ ThreadLocal<Consumer<ToolProgress>> = sink    ToolProgressContext.java:17
  │
  │   工具内部（同一个线程）：
  │   H3/Qwen/SDXL → LocalModel.send/require(...)
  │      └─ Consumer<ToolProgress> sink = ToolProgressContext.current()   LocalModelHandshaker.java:1375
  │      ├─ 立刻上报"queued / 等待本地模型连接"                          LocalModelHandshaker.java:1380
  │      └─ progressListeners[reqid] = sink（全局 ConcurrentHashMap）    LocalModelHandshaker.java:1410
  │
  ├─ 本地模型 WS 线程上报进度：progressListeners[reqid].accept(...)       LocalModelHandshaker.java:960
  └─ whenComplete → emitState(终态)                        ToolCallContext.java:840-851

emitProgress  → ToolCallContext.java:908（排队不计执行时间 / running 记 startedAt）
              → ToolCallManager.notifyToolCallEvent                    ToolCallManager.java:757
              → AISession.handleToolCallEvent                          AISession.java:607
              → WebSocketAISession.onToolCallStatusChanged（tool_status 帧） WebSocketAISession.java:1007
                 └─ 帧里已经带 progress/eta/message                    WebSocketAISession.java:1043-1051
```

### 1.2 依赖面其实非常小

- **写**：`ToolCallContext.java:821`（唯一 `callWith`）。
- **读**：`LocalModelHandshaker.java:1375`（唯一 `current()`）。
- 全仓 `grep ToolProgressContext` 只命中这两个文件 + 自身。`grep ThreadLocal` 的其余命中都是 `ThreadLocalRandom`，以及与工具进度无关的两个字段
  （[`QqOfficialAdapter`](../src/main/java/com/khjxiaogu/aiwuxia/chat/QqOfficialAdapter.java:168) 的网关线程标记、[`WebSocketAISession`](../src/main/java/com/khjxiaogu/aiwuxia/state/session/WebSocketAISession.java:125) 的帧通道/发送上下文）。
- 这也说明了当初为什么用它：**工具不需要改签名**，媒体工具深处的 `LocalModel.require` 自动拿到"当前是哪个工具在跑"。

### 1.3 状态指示器当前的语义

| 事件 | 产生方 | 载体 |
|---|---|---|
| `queued` | 管理器（本地模型排队） | `ToolCallState.QUEUED` |
| `running` + `eta` + `progress` | 本地模型连接器（WS 上报） | `ToolCallState.RUNNING` |
| 终态 | 管理器 | `SUCCEEDED/FAILED/CANCELLED/REJECTED` |

前端协议不用改：`fillToolCallJson` 已经在发 `progress`/`eta`。

---

## 2. 为什么它"不优雅"，而且实际上"不安全"

### 2.1 跨线程即失效（实锤，且正好是最耗时的工具）

`ThreadLocal` 只跟线程走，工具内部一旦 `supplyAsync` 换线程，就读不到 sink：

| 位置 | 现象 |
|---|---|
| [`QwenLocalModelMcp.java:122`](../src/main/java/com/khjxiaogu/aiwuxia/mcp/QwenLocalModelMcp.java:122) → `:146` | **同步**模式也在 `supplyAsync` 里跑，最后 `join()`；Qwen 本地生图从来没上报过进度 |
| [`H3VideoMcp.java:519`](../src/main/java/com/khjxiaogu/aiwuxia/mcp/H3VideoMcp.java:519) | 异步 H3 走 `supplyAsync(work)`，`generate()` 里的 `require` 读到的 sink 是 `null` |
| [`QwenImageMcp.java:169`](../src/main/java/com/khjxiaogu/aiwuxia/mcp/QwenImageMcp.java:169) | 同理（该工具走云端 API，目前无进度需求，但模式相同） |
| [`QwenLocalModelMcp.java:161`](../src/main/java/com/khjxiaogu/aiwuxia/mcp/QwenLocalModelMcp.java:161) | 改图工具异步，同样丢 |

> 也就是说：**项目里最需要进度条的三条路径（H3 视频、Qwen 本地生图、Qwen 改图）恰恰拿不到进度**，只有"同步 + 在工具线程上直接 `require`"的路径（如同步 H3、Foley）能拿到。

### 2.2 直调路径根本没有绑定

| 路径 | 现状 |
|---|---|
| [`McpServer.java:736`](../src/main/java/com/khjxiaogu/aiwuxia/mcp/server/McpServer.java:736) / `:793` | 外部 MCP `tools/call` 自建 `ToolCallContext`（`:218`），但**不走** `start()`，从不绑定 sink |
| [`ToolBackedMultimediaApi.java:90`](../src/main/java/com/khjxiaogu/aiwuxia/multimedia/ToolBackedMultimediaApi.java:90) | 直接 `tool.tool.run(args, output, null)` |
| [`H3VideoAgentRunner.java:101`](../src/main/java/com/khjxiaogu/aiwuxia/mcp/H3VideoAgentRunner.java:101) | `run(input, null, null)` |

这些调用方即使将来想显示进度也无处可接，只能继续加 hack。

### 2.3 转后台任务后进度被直接丢弃

[`ToolCallContext.submitAsyncNow`](../src/main/java/com/khjxiaogu/aiwuxia/llm/toolcall/ToolCallContext.java:642) 在登记任务时把 `finished` 置 `true`，此后 `emitProgress` 直接 `return`（`:913-916`）；而 [`ToolTask`](../src/main/java/com/khjxiaogu/aiwuxia/llm/toolcall/ToolTask.java:14) 没有 progress 字段，
[`PainterSession.taskFrame`](../src/main/java/com/khjxiaogu/aiwuxia/state/session/PainterSession.java:630) 也不发进度。所以即梦这类跑几分钟的后台任务，前端只有"视频生成中"，没有百分比/ETA——这不是取舍，是信息在转任务那一刻被扔了。

### 2.4 隐式归属，两个方向都会错

- **多算**：工具线程上任何一次 `LocalModel` 调用都会被记到当前工具行上（例如工具内部"探一下时长""顺便合成一句语音"），用户看到的是工具 A 的进度条在跳，实际是工具 A 里顺手发起的请求 B。
- **少算**：工具把工作交给别的线程（§2.1）就彻底消失，而且**没有任何编译期或运行期提示**。

### 2.5 生命周期不可控

sink 闭包捕获了 `call`、`display`、`ToolCallContext`、`manager`。`require` 还把它放进**全局** `progressListeners` 表（[`:1410`](../src/main/java/com/khjxiaogu/aiwuxia/modelproxy/LocalModelHandshaker.java:1410)），
清理依赖 `finally`（`:1457-1459`）。当前没有发现泄漏路径，但"一个展示用的回调对象被登记进服务器级全局表、生命周期靠调用方自觉"是典型的隐式状态：一旦新增一条不经过 `finally` 的路径（或改造 `send` 的异常分支），
就会变成"谁都能往别人的工具行上推状态"或"对象滞留"。

### 2.6 与项目风格不一致

- [`Tool.java`](../src/main/java/com/khjxiaogu/aiwuxia/llm/Tool.java:24) 的 javadoc 已经写明"需要按轮次提交或收集状态的工具，把本轮数据对象放进上下文……**不需要线程本地变量**"。
- [`computeRoundState`](../src/main/java/com/khjxiaogu/aiwuxia/llm/toolcall/ToolCallContext.java:354) 是显式注入风格。
- `ToolProgressContext` 是全项目唯一的"隐式环境变量"，新人读代码时无法从 `LocalModel.require` 的签名看出"它会把状态推到哪一行 UI"。

### 2.7 不可测

想验证"进度能正确映射到状态事件"，必须真的起一个工具线程并塞 ThreadLocal；显式化以后，`new ToolInvocation(...)` + 一个记录型 `Consumer` 就能断言。

---

## 3. 你的设想：结果对象承载"状态指示器 + 任务本身"

### 3.1 为什么 `ToolResult`（或任何"返回的结果对象"）不行

1. **时序**：`ToolResult` 在工具执行完成时才产生（[`ToolResult`](../src/main/java/com/khjxiaogu/aiwuxia/llm/ToolResult.java:28) 是不可变 final 类，`withSuccess` 返回新对象）。
   `queued/running/百分比` 发生在它诞生之前。
2. **同步工具没有"返回早期"**：H3/Qwen/SDXL 的工具体都在 `LocalModel.send(...).get(...)` 上阻塞（[`H3VideoMcp.java:743`](../src/main/java/com/khjxiaogu/aiwuxia/mcp/H3VideoMcp.java:743)、[`QwenLocalModelMcp.java:221`](../src/main/java/com/khjxiaogu/aiwuxia/mcp/QwenLocalModelMcp.java:221)、[`SDXLMcp.java:126`](../src/main/java/com/khjxiaogu/aiwuxia/mcp/SDXLMcp.java:126)）。
   `run()` 返回时已经结束，管理器拿到"结果对象"再订阅，只能收到 0 个事件。
3. **异步工具也不保险**：`run()` 返回 future 之前，`require()` 已经同步上报过 `queued`（[`:1380`](../src/main/java/com/khjxiaogu/aiwuxia/modelproxy/LocalModelHandshaker.java:1380)），订阅仍然晚一步。
4. 即使改成"返回 `{future, status}`"的组合对象，`status` 也只有在 `run()` 返回后才可被管理器看到——同上。

**所以"结果对象"这条路线不可行，不是实现成本问题，是时序问题。**

### 3.2 可行形态：同样一个对象，但由管理器**先创建、再注入**工具

把"工具返回的结果对象"换成"工具接收的调用对象"，语义完全一样（状态指示器 + 任务身份都在这一个对象上），只是方向相反：

```
管理器：invocation = new ToolInvocation(callId, tool, display, round, index, count, sink)
        └─ tool.run(params, output, context, invocation)
              └─ invocation → LocalModel.send(uid, call, timeout, invocation)
                    └─ handshaker 直接把它登记进 progressListeners（不再读 ThreadLocal）
```

- 工具**持有**它，所以跨线程（`supplyAsync(work)`）也能带着它走——这正好修掉 §2.1。
- 直调路径传 `ToolInvocation.NONE`（空对象），工具代码不用判空——§2.2 的路径将来可以按需接入。
- 事件在 `invocation.close(...)` 之后丢弃，生命周期由管理器闭合——§2.5 可控。

### 3.3 "任务本身"落在哪：复用 `ToolTask` 还是新建对象？

两种都行，推荐**阶段 1 先只做状态对象**（C：`ToolJob`；A：`ToolInvocation`），阶段 3 再让 `ToolTask` 引用它：

| 做法 | 评价 |
|---|---|
| 状态对象独立存在，`ToolTask` 后续引用 | 改动小、语义清晰：状态对象是"一次调用"，`ToolTask` 是"转后台的那次调用"，生命周期可叠加 |
| 直接把 `ToolTask` 改成每次调用都建 | 会让同步调用也走任务表（`tasks` map 增长、`getPendingAsyncTaskCount` 语义变化），影响异步容量判断，不建议 |

### 3.4 判定表

| 候选方案 | 结论 |
|---|---|
| 改 `ToolResult` | 不可行（时序） |
| `Tool.run` 返回 `{future, status}` 组合对象（plain class） | **成立，但要求工具先返回、后干活**（body 卸载到 job 线程池）；阻塞到结束才返回则拿不到过程事件。落地形态见 §10 |
| sink 放进 `ToolCallContext` | **不可行**：context 是"一次生成"粒度，同一批多个工具**并发**共用一个 context（[`ToolCallContext.java:314`](../src/main/java/com/khjxiaogu/aiwuxia/llm/toolcall/ToolCallContext.java:314)），会互相覆盖，最后还是得靠 ThreadLocal 区分 |
| sink 塞进 `LocalModelCall`/请求 JSON | 不可行：`LocalModelCall` 是跨进程协议模型，序列化由 [`LocalModelCodec`](../src/main/java/com/khjxiaogu/aiwuxia/modelproxy/api/LocalModelCodec.java:42) 显式生成；且工具端依旧拿不到 sink |
| 每次调用一个 `ToolInvocation`，4 参 `Tool.run` | 备选（§4） |
| **`ToolJob extends CompletableFuture`，工具按需返回** | ✅ 推荐（§10），接口零改动 |

---

## 4. 目标设计（方案 A：管理器注入 `ToolInvocation`）

### 4.1 新类型 `ToolInvocation`（`llm/toolcall`）

```java
public final class ToolInvocation implements Consumer<ToolProgress> {
    /** 空对象：直调路径使用，所有方法 no-op，工具不用判空。 */
    public static final ToolInvocation NONE = ...;

    // 身份：给状态事件用，工具可读但不该改
    public String getCallId();
    public String getToolName();
    public ToolCallDisplay getDisplay();

    // 状态指示器（线程安全；close 之后静默丢弃）
    @Override public void accept(ToolProgress progress);
    public void queued(String message);
    public void running(Double progress, Long etaMs, String message);

    // 终态：由管理器（ToolCallContext）在完成/转后台时调用，CAS 只生效一次
    public void close(ToolCallState terminalState, String message);

    // 供轮询/任务帧使用（阶段 3）
    public ToolCallState getState();
    public Double getProgress();
    public Long getEtaMs();
    public long getStartedAt();
    public boolean isClosed();
}
```

- 构造参数就是现有 `emitProgress` 需要的全部东西：`callId`、`call`、`display`、`round/index/count`、`startedAt`、`finished`、`manager`。
  实现上直接把 [`emitProgress`](../src/main/java/com/khjxiaogu/aiwuxia/llm/toolcall/ToolCallContext.java:908) 搬进来（或由 context 传一个转发 `Consumer<ToolProgress>`）。
- `accept` 内部保留现有语义：`queued` 时 `startedAt=0`；`running` 时首次补 `startedAt`；`finished` 后丢弃。
- `close(ToolCallState, String message)` 用 CAS 保证只关一次（复用现有 `finished` 原子量）。

### 4.2 `Tool` 接口

```java
CompletableFuture<ToolResult> run(String params, AIOutput output,
                                  ToolCallContext context, ToolInvocation invocation);
```

- `invocation` **永不为 `null`**：直调传 `ToolInvocation.NONE`，避免每个工具都写判空。
- `TextTool`（`(params, output, context) -> String`）**不新增参数**：它由 `ToolData.Builder.toolText` 适配，媒体工具都不走它。这样 9 个文件、22 处 `toolText(...)` 完全不用动。
- 1 参 `.tool(arguments -> ...)` / `.tool(TextTool)` / `toolFuture` / `toolResult` 适配器只在 `ToolData` 内部改（4 处）。

### 4.3 管理器（`ToolCallContext`）

- `start()`：先构造 `invocation`（含 `finished`/`startedAt` 与 `emitProgress` 闭包），再 `data.tool.run(arguments, output, this, invocation)`，**删除** `ToolProgressContext.callWith`。
- `whenComplete`：先 `invocation.close(终态, message)`，再发终态事件（先关再发，避免最后一个进度事件与终态竞争）。
- `submitAsyncNow`（阶段 3）：不 `close`，而把 invocation 交给 `ToolTask`（新增 `submitTask(..., ToolInvocation)` 重载），任务帧/`statusExtras` 可读 `progress/eta`。
- 直调路径不动（`ToolCallContext.create` 本来就不经过 `start()`）。

### 4.4 `LocalModel` / `LocalModelHandshaker`

- 新增带 sink 的重载，其余旧方法委托 `null`：

```java
require(String uid, ModelType type, String reqid, JsonObject request, long timeoutMs,
        Consumer<ToolProgress> sink)
send(String uid, LocalModelCall<?> call, long timeoutMs, Consumer<ToolProgress> sink)
```

- `require` 里把 `ToolProgressContext.current()` 换成入参；`progressListeners.put(reqid, sink)` 逻辑不变（该表仍是"按 reqid 转发 WS 上报"的正确结构）。
- `clearProgressState` 不变。
- `SDXLMcp.Channel`（[`:106`](../src/main/java/com/khjxiaogu/aiwuxia/mcp/SDXLMcp.java:106)）需要把 sink 带进通道：
  `byte[] send(LocalModelCall<SdxlParams> call, Consumer<ToolProgress> sink)`；`remoteChannel(uid)` 的两个实现各加一个参数。

### 4.5 工具侧（必改 3 个文件 + 可选 2 组）

| 工具 | 改法 |
|---|---|
| `H3VideoMcp` | 3 个 `LocalModel.lhs.send`（`:531`、`:626`、`:743`）各加 `invocation`；异步分支把 `invocation` 捕获进 `supplyAsync(work)` |
| `QwenLocalModelMcp` | `generate(call)` 加 `invocation` 参数（2 个工具调用它）；异步 lambda 捕获 |
| `SDXLMcp` | `Channel` 加 sink；5 个工具把 `invocation` 传给 `channel.send(call, invocation)` |
| `SvsMcp` / `MusicMcp`（可选） | `requireSvs(...)` 加 sink，让歌声合成也有进度；不做就是"无进度"，行为同今天 |
| `LocalVoiceMcp` / `LocalVoiceApi`（可选） | `synthesizeSegments(...)` 加 sink；不做同上 |

> 阶段 1 只做前三个，语音类保持现状即可（它们本来就没有长任务）。

### 4.6 阶段 3：任务与状态合一（可选增强）

- `ToolTask` 增加 `ToolInvocation getInvocation()`；`PainterSession.taskFrame` 增加 `progress`/`eta`/`message`（`state` 已在发）。
- 其他会话（`WebSocketAISession`）若想显示后台任务进度，可在 `handleToolTaskCreated` 里继续用同一个 `callId` 发 `tool_status`（前端已有快照机制 `buildToolCallSnapshot`）。
- 需要产品决策：后台任务期间工具行是继续显示"执行中 + 百分比"，还是按现在这样结束在"已转后台任务"、由任务条显示进度。**建议保持当前 UI 语义，只在任务帧上补进度**，避免前端把已结束的工具行又点亮。

---

## 5. 改动清单（方案 A）

### 5.1 核心（9 个文件）

| 文件 | 改动 |
|---|---|
| `llm/toolcall/ToolInvocation.java` | **新增**，约 150 行（含空对象与注释） |
| `llm/Tool.java` | 接口加第 4 参 + javadoc |
| `llm/toolcall/ToolCallContext.java` | 构造 invocation、去掉 `callWith`、`emitProgress` 迁移/转发、`whenComplete` 关闭；阶段 3 改 `submitAsyncNow` |
| `llm/toolcall/ToolTask.java` | 阶段 3：持有 invocation |
| `llm/toolcall/ToolCallManager.java` | 阶段 3：`submitTask(..., ToolInvocation)` 重载 |
| `llm/ToolData.java` | 4 个内部适配 lambda 加参数（`:361`、`:374`、`:386`、`:398`） |
| `modelproxy/LocalModel.java` / `LocalModelHandshaker.java` | 新增 sink 重载；删除 `ToolProgressContext.current()`（`:1375`） |
| `utils/ToolProgressContext.java` | **删除**（`ToolProgress` 保留为事件载荷；可选迁到 `llm/toolcall`） |

### 5.2 `.tool(...)` 里的工具定义 lambda（53 处，20 个文件，每处一行）

```
AIChatService 1            llm/ToolData 4            mcp/SvsMcp 1
mcp/GroupFileMcp 1         mcp/H3VideoAgentMcp 2     mcp/H3VideoMcp 3
mcp/CodexMcp 2             mcp/SDXLAgentMcp 2        mcp/QwenImageMcp 2
mcp/QwenLocalModelMcp 2    mcp/SeedreamMcp 3         mcp/SDXLMcp 5
mcp/MusicMcp 4             mcp/MultiModalMcp 2       mcp/MediaOutputMcp 5
mcp/LocalVoiceMcp 2        mcp/chat/GroupChatMcp 2   mcp/chat/PrivateChatMcp 5
mcp/knowledge/KnowledgeBaseMcp 3  mcp/server/McpServer 2
```

这些都是"加一个未使用的参数名"，编译期全量校验，不需要思考语义。

### 5.3 包装器（4 处，必须把 invocation 透传，否则链路断）

| 文件 | 改动 |
|---|---|
| `mcp/server/McpUrlAttachmentTool.java:75`（不在 5.2 清单里的第 54 个 `Tool` lambda） | `original.run(resolved, output, context, invocation)` |
| `mcp/H3VideoAgentMcp.java:375` / `mcp/SDXLAgentMcp.java:187` | 同上（子 agent 包装） |
| `AIChatService.java:681`（绘画页同步视频工具） | 同上 |

### 5.4 不需要动的部分

- `TextTool` 与 22 处 `Builder.toolText(...)`（9 个文件）；
- 1 参 `.tool(arguments -> ...)`（`HarnessMcp`、`McpBuiltinTools`、`AgentPingMcp` 等）；
- `toolFuture`/`toolResult` 的调用方；
- MCP 协议、`ToolCallEvent`、`tool_status` 帧与前端（`progress/eta` 字段已存在）；
- `LocalModelCodec`、`LocalModelCall`、本地模型客户端（wire 协议不变）。

---

## 6. 方案对比与阶段

| | **C. ToolJob**（推荐，详见 §10） | **A. 全量 4 参 Tool** | **B. 双接口渐进** |
|---|---|---|---|
| 做法 | 新增 `ToolJob extends CompletableFuture`，工具按需返回，管理器 `instanceof` 订阅 | `Tool` 直接加第 4 参，54 处 lambda 一次改完 | 保留 `Tool`，新增 `InvocationAwareTool` + `Builder.toolWithInvocation(...)`，只迁媒体工具 |
| 工具 lambda 改动 | 53 处不改；第 54 处（包装器）只动异步分支 | 54 处 | 0 处 |
| 改动量 | ~12 文件 / 200~300 行 | ~30 文件 / 400~500 行 | ~12 文件 / 150~200 行 |
| "任务 + 状态"同一对象 | **是**（job 就是 future） | 否（需再挂到 ToolTask） | 否 |
| 风险 | 继承 `CompletableFuture`；要求 Java 9+ API | 机械改动，编译器兜底；接口变更 | runtime 判断 `instanceof`，包装器要同时处理两种工具，长期双路径 |
| 适合 | 想改干净且改动最小 | 不愿继承 JDK 类 | 想先验证效果、或近期有其它分支在改工具 |

**分阶段建议**（C / A 通用）

1. **阶段 1**：选定路线，新增状态对象（C：`ToolJob`；A：`ToolInvocation`）→ 管理器接线（C：`instanceof` 订阅；A：调用前注入）→ `LocalModel/Handshaker` 加 sink 重载 → 删除 `ToolProgressContext`。
2. **阶段 2**：媒体工具透传（H3/Qwen/SDXL；C 还需要把 body 包进 `ToolJob.supply`；A 需要加参数），语音/SVS 可选。
3. **阶段 3（可选，建议做）**：`ToolTask` 持有 job/invocation；任务帧带 `progress/eta`；MCP 直调可选接入。
4. **阶段 4**：走查（§7）、`Tool`/`ToolData` javadoc、更新 `docs/dev/java-api.md`。

工作量：C 约 1 天；A 约 1~1.5 天；阶段 3 另约 0.5 天。

---

## 7. 验证方式

本机是开发环境、无日志，按项目约定**只做编译与代码走查**：

```bat
C:\maven\bin\mvn -q -DskipTests compile
```

走查清单（编译通过后再过一遍）：

1. `grep -rn "ToolProgressContext" src/` → 0 命中。
2. **方案 C**：`grep -rn "ToolJob" src/` → 只出现在 `ToolJob` 自身、`ToolCallContext`、H3/Qwen/SDXL、`McpUrlAttachmentTool`；每个 `ToolJob.supply(...)` 的 body 里 `LocalModel.send/require` 都带上了 `job`。
   **方案 A**：`grep -rn "\.tool\.run(\|tool\.run(" src/` → 全部 4 参（`McpServer:736/793`、`ToolBackedMultimediaApi:90` 传 `ToolInvocation.NONE`）。
3. `grep -rn "LocalModel\.\|lhs\.send\|lhs\.require" src/` → 媒体工具都带 sink，语音/非工具路径显式 `null`。
4. `SDXLMcp.Channel` 的两个实现签名一致；sink 来自入参而不是线程环境。
5. 异步分支里状态对象是**捕获**的（C：`ToolJob.supply(job -> ...)`；A：lambda 捕获 `invocation`），不是重新查线程。
6. **方案 C 额外**：`ToolJob` 的 `listen` 在订阅前缓冲、订阅时回放；`isDone()` 后丢弃事件；`newIncompleteFuture` 返回同类型（可用 §10.4 的小程序复核）。

可选手工验点（有环境时）：绘画页 Qwen 本地生图 → 工具行出现"排队中/执行中 + 百分比"；同步 H3 视频同上；即梦异步任务完成后视频仍正常落到回复里；MCP `tools/call` 返回值不变。

---

## 8. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 漏传 sink（工具在 `LocalModel.send` 处传 `null`） | 静默丢进度，无报错 | 走查清单第 3 条；`require/send` 的 javadoc 写明"工具路径必须传 invocation"；阶段 3 后可在 `Task`/`ToolInvocation` 上统计"有过事件"用于自检 |
| `close` 与 `accept` 竞态 | 终态后仍发进度 / 终态发两次 | invocation 内部 CAS；`accept` 先读 closed 再转发；`close` 只在 `whenComplete` 调一次（现有 `finished` 原子量已保证） |
| 4 参接口改动面大 | 编译错误一片 | 一次性改完，编译器是按文件列清单的；没有外部实现者（全仓只有 lambda 与 `ToolData` 适配器） |
| `ToolInvocation` 被后台任务长期持有 | 引用滞留 | invocation 只存展示所需字段 + 转发 `Consumer`；`close` 后置空转发器；不含 `history`/`output` |
| 阶段 3 改变前端语义 | 工具行与任务条重复/闪烁 | 保持"工具行在转后台时结束"，进度只进任务帧 |
| 空对象 `NONE` 被误用于真实调用 | 事件丢失但无异常 | `NONE` 的 `toString` 标 `NONE`；管理器路径永远构造真实对象，`NONE` 只出现在直调 |

---

## 9. 明确排除的做法

| 做法 | 为什么不 |
|---|---|
| `InheritableThreadLocal` | 只对"new Thread"生效；`cachedThreadPool`/`commonPool` 复用线程，子任务依旧拿不到；问题更隐蔽 |
| 包装线程池 Runnable 复制 ThreadLocal | 要覆盖所有提交点（`supplyAsync`、`get`、`whenComplete`、第三方库内部），等于把隐式状态扩散 |
| 把 sink 塞进 `ToolCallContext` | context 是"一次生成"粒度、同批并发共享，必然串台（§3.4） |
| 保留 ThreadLocal，只加日志/告警 | 不解决 §2.1~2.3 的丢失，也不解决隐式归属 |
| 把 sink 放进 `LocalModelCall`/请求 JSON | 跨进程协议模型，序列化显式生成；工具端仍拿不到 sink |

---

## 10. 追加讨论：把"工具返回的东西"换成 `ToolJob`（方案 C，推荐）

### 10.1 结论

**能，而且改动比方案 A 小得多。** 关键是改**返回的 future**，而不是结果内容：

- `Tool` 接口与全部 49 处 `.tool(...)` lambda、4 处 `ToolData` 适配器**签名都不动**，仍是 `CompletableFuture<ToolResult> run(...)`；
- 新增 `ToolJob extends CompletableFuture<ToolResult>`：它既是任务（future），又是状态指示器（`queued/running/progress/eta/message`）；
- 想上报进度的工具返回 `ToolJob`，不想上报的照旧返回普通 future；管理器拿到返回值后 `instanceof ToolJob` 就订阅，不是就什么都不做。

真正要改的只有 **H3 / Qwen / SDXL 三个媒体工具 + `McpUrlAttachmentTool` 的异步分支**（第 54 个 lambda）。

### 10.2 前提：job 必须先于耗时工作创建并返回

这是和 §3.1 同一个时序约束：

- 如果工具阻塞到结束才把 job 返回，管理器订阅时事件已经全部过去 → 回放只能看到一串过期状态，没有意义。
- 所以媒体工具从"在工具线程里阻塞跑完再 return"改成 `return ToolJob.supply(job -> { ...原逻辑... })`：
  - `supply` 在 **job 专用线程池**上执行 body，立即返回 job；
  - body 里的 `LocalModel.send/require(...)` 把 `job` 传下去（和方案 A 一样，只是 sink 的来源从"管理器注入"变成"工具自己创建"）。
- 顺带一个简化：工具内部不再需要 `async ? supplyAsync(work) : completedFuture(work.get())` 这种分支（H3VideoMcp:519、QwenLocalModelMcp:146 等处），统一 `return ToolJob.supply(work)`；同步/异步只影响管理器的 1 秒窗口。

### 10.3 设计示意

```java
public class ToolJob<T> extends CompletableFuture<T> {
    /** 在 job 专用 daemon 线程池上执行 body(job)，立即返回 job。 */
    public static ToolJob<ToolResult> supply(Body body);
    /** 已经有了结果。 */
    public static ToolJob<ToolResult> completed(ToolResult result);
    /** 包装一个普通 future（没有进度事件）。 */
    public static ToolJob<ToolResult> of(CompletableFuture<ToolResult> future);

    /** 状态指示器，线程安全；job 完成后静默丢弃。 */
    public void queued(String message);
    public void running(Double progress, Long etaMs, String message);

    /** 管理器订阅：先按序回放已缓冲的事件，之后实时转发。 */
    public void listen(Consumer<ToolProgress> sink);

    /** 让 thenApply/thenCompose/... 派生出的 future 仍是 job 并共享状态（见 10.4）。 */
    @Override public <U> CompletableFuture<U> newIncompleteFuture();

    @FunctionalInterface
    interface Body {
        ToolResult run(ToolJob<ToolResult> job) throws Exception;
    }
}
```

管理器（`ToolCallContext.start`，现在 `:819-829` 处）：

```java
CompletableFuture<ToolResult> raw = data.tool.run(arguments, output, this);
if (raw instanceof ToolJob) {
    // 回放 + 后续事件都走现有 emitProgress，排队/进度/startedAt 语义不变
    ((ToolJob<?>) raw).listen(p -> emitProgress(callId, call, callDisplay, round, index, count,
            startedAt, finished, p));
}
return raw;   // 外层继续 supplyAsync(...).thenCompose(f -> f)
```

- 内部结构：一个 `Status` 对象（state / progress / eta / message + 监听器 + 事件缓冲）由 job 与所有派生 job 共享；`newIncompleteFuture()` 返回 `new ToolJob<>(status)`。
- 管理器用 raw type 判断：`raw instanceof ToolJob`，再转 `ToolJob<?>` 调 `listen`（不需要强转 `ToolResult`）。
- **终态仍然只由管理器发**（现有 `whenComplete`，`:840-851`），job 只负责过程态，不会出现"两个人发终态"。
- `Execution` 记下 job；阶段 3 转后台任务时交给 `ToolTask`，任务帧即可显示 `progress/eta`。
- `ToolInvocation` 不再需要：方案 C 里 job 就是那个"状态指示器 + 任务本身"。

### 10.4 关键验证：派生 future 仍带着 job（已实测）

问题：三个包装器都是 `source.tool.run(...).thenApply(...)`（`H3VideoAgentMcp:375`、`SDXLAgentMcp:187`、`AIChatService:681`），`thenApply` 默认返回普通 `CompletableFuture`，job 会丢。

`CompletableFuture` 从 Java 9 起提供 `public <U> CompletableFuture<U> newIncompleteFuture()`，正是给子类用的扩展点：所有派生阶段都通过它创建"派生 future"。覆盖成返回同类型 job 并共享状态对象即可。

本机实测（JDK 21，`javac --release 9`）：

```
applied=Job        shared=true
composed=Job       shared=true
handled=Job        shared=true
exceptionally=Job
whenComplete=Job
results applied=x! composed=x?
exc propagated=true
compose throw=true
chain=Job shared=true
```

复现用最小代码（独立 Java 程序，未加入仓库）：

```java
static class Status { volatile String state = "new"; }
static class Job<T> extends CompletableFuture<T> {
    final Status status;
    Job(Status s) { this.status = s; }
    @Override public <U> CompletableFuture<U> newIncompleteFuture() { return new Job<>(status); }
}
// Job<String> j = new Job<>(status);
// j.thenApply(...) / j.thenCompose(...) / j.whenComplete(...) / j.handle(...) / j.exceptionally(...)
// 全部返回 Job 且共享同一个 status；j.complete(...) / completeExceptionally(...) 正常传播
```

即 `.thenApply(...)` / `.thenCompose(...)` / `.whenComplete(...)` / `.handle(...)` / `.exceptionally(...)` 之后仍是 `ToolJob` 且共享同一份状态，完成与异常传播都正常。**那三个包装器一行都不用改。**

唯一例外：`McpUrlAttachmentTool` 的异步分支是 `CompletableFuture.supplyAsync(resolve).thenCompose(resolved -> original.run(...))`——外层是普通 future，派生结果也是普通 future。这里需要一个"代理 job"（约 10 行）：先建 `ToolJob proxy`，在内层拿到 job 时把状态转发给 proxy，链完成时完成 proxy。
（不依赖 `newIncompleteFuture` 也行：手动改这 3 个包装器各加 3~5 行转发；覆盖它只是让"包装器不丢 job"成为默认行为。）

### 10.5 传播边界

| 场景 | job 会不会丢 | 处理 |
|---|---|---|
| 工具直接 `return ToolJob.supply(...)` | 不丢 | 管理器订阅 |
| `job.thenApply(...)` / `thenCompose` / `whenComplete` | 不丢（`newIncompleteFuture`） | 无需处理 |
| `CompletableFuture.supplyAsync(...).thenCompose(...)` 包住 job | 丢 | 代理（仅 `McpUrlAttachmentTool` 异步分支） |
| 工具返回普通 `CompletableFuture` | 无进度 | 保持现状 |
| `ToolData.Builder` 的 `tool(TextTool)` / `tool(Function)` 适配器 | 普通 future | 保持现状 |
| 直调（MCP `tools/call`、multimedia、`H3VideoAgentRunner`） | 能拿到 job 但没人订阅 | 可选：以后接 MCP 任务进度 |

### 10.6 与方案 A 对比

| 维度 | **C. ToolJob**（推荐） | **A. Tool 加第 4 参** |
|---|---|---|
| 工具定义 lambda 改动 | 53 处不改；第 54 处（包装器）只动异步分支 | 54 处 |
| 接口/签名 | 不变 | `Tool.run` 加参数 |
| 媒体工具改造 | 3 个文件包 `ToolJob.supply` + 传 job | 3 个文件加参数透传 |
| 包装器 | 1 处代理（其余靠 `newIncompleteFuture`） | 4 处透传参数 |
| "任务 + 状态"同一对象 | **是（job 就是 future）** | 否（invocation ≠ future，需再挂到 ToolTask） |
| 新概念数量 | 1 个（`ToolJob`） | 2 个（`ToolInvocation` + 参数约定） |
| 依赖 | Java 9+（`newIncompleteFuture`） | Java 8+ |
| 代码味道 | 继承 `CompletableFuture` | 每个工具多一个没人用的参数 |
| 不能接受时的替代 | 退回 A | — |

### 10.7 改动清单（方案 C）

| 文件 | 改动 |
|---|---|
| `llm/ToolJob.java` | **新增**，约 180 行（状态、监听、回放、`supply/completed/of`、`newIncompleteFuture`、专用线程池） |
| `llm/toolcall/ToolCallContext.java` | `start()` 里 `instanceof ToolJob` 订阅；`Execution` 记 job；阶段 3 改 `submitAsyncNow` |
| `modelproxy/LocalModel.java` / `LocalModelHandshaker.java` | 新增 `Consumer<ToolProgress> sink` 重载；删除 `ToolProgressContext.current()` |
| `utils/ToolProgressContext.java` | **删除**（`ToolProgress` 保留为事件载荷） |
| `mcp/H3VideoMcp.java` | 3 个工具包 `ToolJob.supply`；3 处 `send` 传 job |
| `mcp/QwenLocalModelMcp.java` | 2 个工具包 `ToolJob.supply`；`generate` 传 job |
| `mcp/SDXLMcp.java` | `Channel` 加 sink 参数；5 个工具包 job；2 个通道实现传 sink |
| `mcp/server/McpUrlAttachmentTool.java` | 仅异步分支加代理 job（约 10 行） |
| 阶段 3 | `ToolTask` 持有 job；`ToolCallManager.submitTask(..., job)`；`PainterSession.taskFrame` 加 `progress/eta` |
| **不改** | 53 处工具/适配器 lambda（49 处 `.tool(...)` + 4 处 `ToolData` 适配器）、`TextTool`、22 处 `toolText(...)`、MCP 协议、`tool_status` 帧与前端、`LocalModelCodec` |

### 10.8 风险与对策

| 风险 | 说明 | 对策 |
|---|---|---|
| 继承 `CompletableFuture` 的"味道" | "future 兼状态"不是教科书设计 | 只继承、不重写完成逻辑；`newIncompleteFuture` 是官方扩展点；对外只暴露 `supply/completed/of/queued/running/listen` |
| Java 9 API | `newIncompleteFuture` 是 Java 9+ | 项目本来就是 source/target 9（[`pom.xml:55-56`](../pom.xml:55)），且已在用 `List.of`；README 若写"Java 8 兼容"需澄清 |
| 工具作者忘了"先建 job" | 阻塞到结束才返回 → 没进度（功能仍正常） | `ToolJob`/`Tool` 的 javadoc 写清契约，媒体工具作范例；这是唯一的新约定 |
| 早期事件回放 | 订阅前的事件要缓存 | job 缓存最近 N 条（如 64），`listen` 时按序回放后转实时；超出丢弃最旧 |
| 事件与终态竞争 | 完成后仍有进度 | job 在 `isDone()` 后丢弃；管理器 `finished` 仍兜底 |
| 线程池 | 不能用 `commonPool`（现在 H3 异步就在 commonPool 上阻塞最长 61 分钟） | `ToolJob` 自带 daemon cached pool（或共享 `ToolExecutors`） |
| 引用滞留 | job 被 `ToolTask` 持有 | job 只存状态与一个监听器；完成时清空监听器 |

### 10.9 小结

你的直觉是对的：把"返回的 future"升级成 job，是**接口零改动 + 概念更自然（一次调用就是一个 job）**的方案。它和方案 A 在"媒体工具要改 body、要把 sink 传进 `LocalModel`"上成本相同，但省掉了 54 处签名改动，并且天然满足"任务与状态在同一个对象上"。

---

## 11. 待确认

1. **走方案 C（`ToolJob`，推荐）还是方案 A（4 参 `Tool`）？** C 的核心分歧点是"能否接受让 `ToolJob` 继承 `CompletableFuture`"；能接受就 C，否则 A。
2. **能否接受 `ToolJob` 要求 Java 9 API（`newIncompleteFuture`）？** 项目 pom 已是 source/target 9、且在用 `List.of`，我判断可以；若要求 Java 8 源码兼容则退回 A。
3. **语音/SVS 工具要不要进度？** 要的话多改 `SvsMcp`/`MusicMcp`/`LocalVoiceMcp`/`LocalVoiceApi` 四处；不要就保持现状（无声丢失）。
4. **阶段 3（后台任务帧带 progress/eta）是否这次一起做？** 建议一起做——"状态指示器 + 任务本身"在同一对象上，正是你最初描述的目标。
