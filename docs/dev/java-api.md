# 开发者参考：新增 app 类型（Java）

> **面向开发者**：本篇涉及 Java 代码，供**要新增 app 类型（`type`）**的人参考。
> 只想用配置和提示词做出 app，不需要写代码 —— 看 [`docs/apps/`](../apps/README.md) 那套教程。

---
本篇是给**要写新 app 类型（`type`）的人**看的：继承 `AIApplication`、把 app 挂到注册表、然后按需覆写钩子。
目录结构与文件作用见 [files.md](../apps/files.md)，配置字段见 [configuration.md](../apps/configuration.md)。

> 只想改提示词、不想动代码？那你需要的不是本篇，而是**配置型智能体**（`custom` 类型 +
> `agentedit` 页面运行时配置），见 [配置型智能体](../apps/configuration.md#4-配置型智能体不改代码不重启)。

---

## 1. 最小可用 app：5 个抽象方法

`com.khjxiaogu.aiwuxia.apps.AIApplication` 声明了 5 个抽象方法，全部实现才能编译通过：

| 方法 | 何时被调用 | 约定返回 |
|---|---|---|
| `String getName()` | 应用列表（`/applist`）、存档页（`/archiveApplist`）、日志 | **UI 上显示的应用名就是它**（`ApplicationAttributes.getName()` 直接转发到这里）。`meta.json` 的 `name` 只用于"加载门槛"和传给工厂，**不影响显示**——两者不一致是常见困惑来源 |
| `String constructSystem(ApplicationState state)` | 框架内部在需要"纯状态版"系统提示时调用 | 绝大多数 app 直接 `return ""`，真正的系统提示在自己的 `constructSystem(AISession)` 重载里拼 |
| `String getBrief(AISession state)` | 对话标题（未手动重命名时） | 一句话标题；`custom` 用存档里的 `brief`，角色类 app 用应用名 |
| `String getMemory(AISession state)` | 记忆导出（`/exportMemory`）、记忆页 | 记忆文本，通常 `return state.getLastSummary()` |
| `void provideInitial(AISession state)` | **每次 WebSocket 连上时**都会跑（`WebSocketAISession.onOpen` → `prepareInitialHint`），加载已有存档时 `onLoad` 还会再跑一次 | 初始化阶段与首条提示。**必须用 `stage` 守卫**，否则重连会重复弹窗/重复开局，见 [§3](#3-生命周期与阶段) |

可选覆写（都有默认空实现）：

| 钩子 | 用途 |
|---|---|
| `void prepareScene(AISession state)` | 准备场景图片数据（要求线程安全、只读），配合 `state.sendSceneContent(...)` |
| `String getRoleName(AISession state, Role role)` | 自定义角色显示名（`Role.ASSISTANT` 换成角色名） |
| `CompletableFuture<Boolean> generateVoice(AISession state, String text, String audioId)` | 生成语音，默认返回 `false`（不生成） |
| `void onload(AISession state)` | 默认实现就是 `provideInitial(state)`，一般不用覆写 |
| `String constructBackLog(AISession state)` | 导出/调试用的纯文本历史 |

> ⚠️ **`constructSystem` 有两个同名重载**：抽象的是 `constructSystem(ApplicationState)`，
> 各 app 自己加的便利方法是 `constructSystem(AISession)`（能读 `state.getExtra()` 里的存档级配置）。
> 写新 app 时两个都写：抽象的那个 `return ""`，实际逻辑放 `AISession` 版。

---

## 2. 构造函数：读文件 + 装消息处理器

app 实例在 `AIChatService.reload()` 里由工厂创建一次，**全局共享**（不是每个会话一个），
所以：**构造函数里只读文件、只做不可变初始化，绝不要存会话状态**。

```java
public AICustomMain(File basePath, JsonObject meta) throws IOException {
    super();                                             // ← 必须，基类会装 checkTokenUse
    compactor = new HistoryCompacter(
        FileUtil.readString(new File(basePath, "summary.txt")).replace("\r", ""), "1");
    handlers.add((state, ret) -> { ... });               // ← 注册消息处理器（见 §4）
}
```

- `basePath` 是**应用自己的目录** `save/apps/<appid>`，不是数据根目录。
  要读全局配置（`save/config/*`）请用 `ConfigPaths`，别用 `basePath` 拼。
- 文本文件统一用 `AIApplication.readFile(File)`（会顺手去掉 `\r`）或 `FileUtil.readString`。
- `meta` 是 `meta.json` 解析后的对象，app 私有字段（如 `charaName`）在这里读。
- 文件缺失会让构造函数抛异常 → `reload()` 捕获后打印"AI加载失败"，该 app 直接不出现。
  所以**要么把文件随 app 一起交付，要么显式降级**。

---

## 3. 生命周期与阶段

```
createSession/loadSession          工厂已创建好 app 实例
        ↓
WebSocket 连上 → provideInitial(state)        初始化弹窗/首条消息（重连也会跑）
        ↓
WebSocketAISession.onOpen → prepareScene(state) → 下发 status 帧
        ↓
用户输入 → state.handleUserSpeech → app.handleSpeech(state, msg)
        ↓                                    ↓
   commandExec(单线程)            onGenerateStart → handlers 链 → onGenComplete
        ↓
   app.handleSpeech 结束 → state 推 done 帧（前端停止"生成中"）
```

> ⚠️ `provideInitial` 有**两条触发路径**：新建会话时 `onOpen → provideInitialHint`，
> 加载已有存档时再走 `onLoad → onload → provideInitial`。断线重连也会重跑 `onOpen` 那一次。
> 所有内置 app 都用 `if (state.getStage() != STARTED)` 之类的守卫来保证幂等——**你的 app 也必须这么写**，
> 否则重连会重复问候、重复开局、甚至把存档里的开场白又写一遍。

`ApplicationStage`（存档级，随会话持久化）只有三态：

| 阶段 | 含义 |
|---|---|
| `INITIALIZE` | 刚创建，还没开始正式对话 |
| `NAMING` | 需要 AI 给对话起名（框架会调用 app 生成标题） |
| `STARTED` | 正式进行中 |

多数 app 在 `provideInitial` 里直接 `state.setStage(ApplicationStage.STARTED)`；
角色对话类会在首轮里塞开局文本（`prelogue.txt`）再把阶段推到 `STARTED`。

**线程模型**：每个会话有一条**单线程** `commandExec`，`onLoad` / `handleUserSpeech` / 各种
回调都提交到它上面串行执行。这意味着同一会话内不必加锁，但也**不要在里面长时间阻塞**，
否则该会话所有操作都会卡住（LLM 调用本身是流式的，会把线程占住，属预期行为）。

---

## 4. 消息处理链（handlers）

`handleSpeech` 会把消息依次喂给 `handlers` 里的每个 `MessageHandler`：

```java
public interface MessageHandler {
    MessageContents apply(AISession state, MessageContents message) throws Throwable;
}
```

- 返回 `null` → **终止链**（视为本轮结束）；
- 返回消息 → 继续传给下一个 handler；
- handler 抛异常会被基类捕获并打印，**不会**中断其它 handler；
- 基类构造时已把 `checkTokenUse` 放在第 0 位：配额不足/服务不可用时它 `sendNotice` +
  `refillChatBox` 并返回 `null`，链直接结束。**自己写的 handler 不需要再查配额**。

最典型的一条链（`AICustomMain` 的写法）：

```java
// 1) 欢迎/阶段修正
handlers.add((state, ret) -> {
    if (state.getStage() != ApplicationStage.STARTED)
        state.setStage(ApplicationStage.STARTED);
    return ret;
});
// 2) 写用户消息 + 调模型 + 收尾
handlers.add((state, ret) -> {
    try {
        try (HistoryWriteContext write = state.beginWrite(Role.USER)) {
            write.append(ret, true);                        // 第二个参数 = 是否同时进上下文
        }
        try (HistoryWriteContext write = state.beginWrite(Role.ASSISTANT)) {
            sendAndProcessResultStreamed(state, write, constructAIrequest(state));
        }
    } finally {
        // 失败/空回复回滚：把刚写的用户消息撤掉并把内容退回输入框
        if (state.getLast().getRole() == Role.USER) {
            HistoryItem hi = state.removeLast();
            state.refillChatBox(hi.getContextContent());
        }
    }
    state.addDialogRow();                                  // 回合数 +1（影响标题生成、可撤回范围）
    return null;
});
```

`schema` 校验型 app（武侠、TRPG 等）会在 handler 里先要求模型输出 JSON、解析校验、
失败就重问，再把结果落成历史——套路相同，只是中间多了状态计算。

---

## 5. 写入历史：`HistoryWriteContext`

**所有**历史写入都必须通过它拿到的上下文，用 try-with-resources 包住：

```java
try (HistoryWriteContext write = state.beginWrite(Role.ASSISTANT)) {
    write.append(text, true);
}   // close() 时才真正提交；没写过任何内容 → 整条记录被自动撤回
```

| 方法 | 说明 |
|---|---|
| `append(String content, boolean addToContext)` | 追加一段。`addToContext=true` 时这段也作为"上下文内容"发给模型；`false` 表示只用于显示 |
| `appendLine(String content, boolean addToContext)` | 同上，末尾补换行 |
| `append(MessageContent content, boolean addToContext)` | 追加结构化内容（文本/图片/工具调用等） |
| `appendDisplayOnly(String content)` | 只显示、不发给模型（"查看面板"这类系统提示用；上下文会被置空） |
| `setContextContent(String fullContent)` | 直接指定"上下文内容"（与显示内容不同的场景，如绘画页） |
| `appendReasoner(MessageContent content)` | 追加思维链（不会发给模型，只用于展示） |
| `setLastState(ApplicationState)` | 把本条记录对应的状态快照存下来，供「撤回/重新生成」回滚 |
| `setAudioId(String)` | 绑定本条消息的语音 ID |
| `commit()` / `rollback()` | 显式提交 / 硬删除该记录并通知前端移除；`close()` 会自动处理，重复调用是空操作 |

`beginWrite` vs `continueWrite`：

- `beginWrite(role)` —— **新开**一条同角色的记录；
- `continueWrite(role)` —— 在**当前**记录上继续追加（流式分段写入时用）。

历史行数控制（决定"能撤回多远"）：`state.addDialogRow()`、`state.getDialogRow()`、
`state.minDialogRow()`、`state.setDialogRows(n)`、`state.minDialogRows(n)`。
压缩历史后框架会调用 `setDialogRows((int)(history.getContextLimit()-5))` 来收紧可撤回范围。

---

## 6. 调用模型：`AIRequest` / `AIOutput`

```java
Builder b = AIRequest.builder(state)                  // ← 传 state 才会自动带上该会话的模型选择与工具
        .taskType(TaskType.STORY)
        .strength(ReasoningStrength.WEAK)
        .multimodal(MultimodalType.TEXT_ONLY)
        .streamed()                                   // 流式（前端逐字显示）
        .temperature(1.3f)
        .maxTokens(8192);
b.addHistoryItem(Role.SYSTEM, systemPrompt);          // 系统提示
b.addHistoryItem(Role.USER, "额外要求");               // 也可直接塞字符串
for (Iterator<HistoryItem> it = history.validContextIterator(); it.hasNext(); )
    b.addHistoryItem(it.next());                      // 有效上下文（压缩后的历史）
AIOutput resp = LLMConnector.call(b.build());
resp.addUsageListener(state::addUsage);               // ← 计费/用量统计，必须加
```

`Builder` 主要参数：

| 参数 | 取值 | 说明 |
|---|---|---|
| `taskType(...)` | `LOGIC` / `CODE` / `ANALYSIS` / `STORY` | 影响路由到哪类模型 |
| `strength(...)` | `NONE` / `WEAK` / `MEDIUM` / `STRONG` | 推理强度（推理型模型才生效） |
| `format(...)` | `ResponseFormat.TEXT` / `JSON` | 要求模型输出 JSON（结构化 app 必备） |
| `multimodal(...)` | `TEXT_ONLY` / `TEXT_IMAGE` / `TEXT_AUDIO` / `TEXT_VIDEO` / `IMAGE_ONLY` | 输入模态 |
| `modelHint(...)` | 模型标识，如 `deepseek/pro` | 不传则用会话当前模型 |
| `stream(boolean)` / `streamed()` | — | 流式开关 |
| `temperature(float)` / `maxTokens(int)` | — | 采样温度与上限 |
| `enableDeepThink()` | — | 开启深度思考 |
| `prefix(String)` | — | 续写前缀 |
| `toolCallManager(...)` | — | 显式指定工具集；`builder(state)` 已自动取 |

`AIOutput`（`com.khjxiaogu.aiwuxia.llm.AIOutput`）：

| 成员 | 说明 |
|---|---|
| `Reader getContent()` | 正文流。用 `BufferedReader` 逐行读；**流式时开头可能有空行**，先跳过 |
| `MessageReader getReasoner()` | 思维链流，逐段 `read()` 成 `MessageContent` |
| `String getContentText()` | 一次读完全部正文（非流式/摘要场景用） |
| `boolean isEnded()` | 流是否已结束 |
| `void interrupt()` | 中断生成 |
| `addUsageListener(Consumer<UsageIntf<?>>)` | 用量回调，接 `state::addUsage` 才会计费 |
| `setUsage(...)` | 由 provider 内部调用 |

**思维链必须异步读**，用基类给的方法，别自己另起线程：

```java
CompletableFuture<Void> reasoner = handleReasonerContent(resp, state, write); // 边产生边写入
BufferedReader reader = new BufferedReader(resp.getContent());
// ... 处理正文 ...
reasoner.join();   // 正文处理完再 join，异常会在这里抛出
```

原因：推理流往往要等整轮（含工具调用）结束才关闭，同步读会把 `commandExec` 的线程一直占住，
正文就只能等推理流结束才被处理。

---

## 7. 历史压缩与"长期记忆"

上下文超限时把早期对话压成摘要，两件东西配合：

```java
HistoryCompactor.compact(history, limit, min,           // 触发阈值 / 保留下限
    role -> role == Role.USER ? "【你】：" : "",          // 压缩文本的行前缀
    summary -> {                                        // 超出 limit 时的回调
        state.setLastSummary(compactor.compactHistory(state, state.getHistoryState(),
                summary, state.getExtra().get("charaset"), state::addUsage));
        state.setDialogRows((int) (history.getContextLimit() - 5));
    });
String lastSummary = state.getLastSummary();           // 每轮都作为 SYSTEM 消息塞进请求
```

| 类型 | 作用 |
|---|---|
| `utils.HistoryCompactor` | **静态工具**：判断/触发压缩、遍历有效上下文（`history.validContextIterator()`） |
| `subagent.HistoryCompacter` | **有状态摘要器**：`new HistoryCompacter(摘要系统提示, 版本号)`，把对话块压成摘要文本，结果存在 `state.getHistoryState()` 里 |

`state.getHistoryState()` 是随存档持久化的 `Map<String,String>`，可以放自己的分级记忆
（各 app 用它存"前情提要""角色记忆"等分节文本）。

---

## 8. 状态与数值（`ApplicationState`）

需要"血量/好感/时间/阶段"这类数值的 app 用 `ApplicationState`：

```java
ApplicationState status = state.getState();
AttributeSet hp = status.getOrCreateInterface("hp");   // 按名取/建一组属性
hp.put("current", "100");
hp.get("current");                                      // "100"（全是字符串）
status.perks.put("buff", "attack+10");
status.extras.add("已完成的支线：初遇");
```

- 一组属性 = 一个 `AttributeSet`（`Map<String,String>` 语义），`ApplicationState.intfs` 保持插入顺序；
- `new ApplicationState(old)` 是**快照拷贝**，配合 `write.setLastState(snapshot)` 实现「撤回/重新生成」时状态一起回滚；
- `state.getExtra()` 是**存档级的 `Map<String,String>`**（字符串！），放配置/计数；
- 校验与约束可复用 `state.status.AttributeValidator`（按规则表校验/裁剪属性）。

---

## 9. 注册到工厂

新 type 必须登记，否则 `meta.json` 里写了也不会被创建：

```java
// apps/AIApplicationRegistry.java 的 static 块
register("trpg", (base, folder, name, meta) -> new AITRPGSceneMain(base, folder, name, meta));
```

工厂签名固定：

```java
public interface AIApplicationFactory {
    AIApplication createInstance(File basePath, File folder, String name, JsonObject meta) throws Throwable;
}
```

| 参数 | 含义 |
|---|---|
| `basePath` | 数据根目录（`save`），用于读全局配置 |
| `folder` | 该应用的目录（`save/apps/<appid>`） |
| `name` | `meta.json` 的 `name` |
| `meta` | `meta.json` 全文 |

`register` 重名会抛 `RuntimeException`；`meta.json` 的 `type` 没注册则抛 `NoSuchElementException`，
现象是启动日志里的"AI加载失败：<appid>"。

---

## 10. 工具调用（MCP）

QQ 群侧（`NapCatAIConnector`）会给会话挂工具（`SDXLMcp`、`QQMcp`、`FetchMcp`…）。
自己写 app 时加工具的标准姿势：

```java
ToolData t = new ToolData.Builder("get_weather", "查询城市天气")
        .putParam("city", "城市名")          // 参数名 → 说明（写进给模型的工具描述）
        .tool(paramsJson -> "晴，26℃")       // Tool 接口：String run(String params)
        .build();
MCPTools tools = new MCPTools();
tools.register(t);
state.registerTools(tools);                  // 挂到会话，随后 AIRequest.builder(state) 自动带上
```

- `Tool.run(String params)` 收到的是**参数 JSON 字符串**，返回的字符串直接给模型看；
- 工具回调里可拿到 `ToolCallContext`，用 `state.addUsage(...)` 记账、`callback.addToolStatus(...)` 反馈进度；
- 审批流由 `ToolApprovalManager` / `ToolCallApprover` 控制；生产环境建议挂审批，见 `ManagedToolCallApprover`；
- 注意：**内置 app 一个工具都没挂**（`apps/` 里没有 `registerTool` 调用），工具只在 QQ 群侧
  （`NapCatAIConnector` 按 skill 注册 SDXL/QQ/Fetch 等）和绘画会话里用。

---

## 11. 与前端协作：帧协议

服务端 → 浏览器都是带 `type` 的 JSON 帧，前端由 `save/ai-protocol.js` 统一分发。app 常用的出口：

| API | 前端效果 |
|---|---|
| `state.sendNotice(msg)` | 系统提示条（如"token限额已达到"） |
| `state.refillChatBox(text)` | 把文本填回输入框 |
| `state.requestUserInput(input, prompt, consumer)` | 弹输入框，用户提交后回调 |
| `state.sendSceneContent(type, value)` | 切换立绘/场景图（配合 `prepareScene`） |
| `state.postAudioComplete(id, audioId)` | 给某条消息挂语音 |
| `write.append(...)` / `appendReasoner(...)` | 正文 / 思维链逐段推送 |

完整帧表见 `save/ai-protocol.js` 文件头注释（`status`/`message`/`content`/`remove`/`batch_messages`/
`thinking`/`tool_call`/`audio`/`done`/`scene`/`input`/`toolApproval`/`local_request`/`pong`…）。
前端页面通过 `?chatid=xxx` 打开，WebSocket 地址由 `aiSiteWsBase() + this.url + "/chatsocket?..."` 拼出。

---

## 12. 常见坑

| 现象 | 原因与处理 |
|---|---|
| 用户消息没落库 | `beginWrite` 块里什么都没写 → 记录被自动撤回；确认 `append` 至少调用了一次 |
| 回复重复/串轮 | 在 handler 外自己起线程调模型；所有模型调用都应在 `commandExec` 线程内完成 |
| 重连后重复问候/重复开局 | `provideInitial` 没有 `stage` 守卫（它每次连上都会跑） |
| 「撤回」后状态没回滚 | 写记录时没 `write.setLastState(snapshot)`，基类的 `doRevert/doRegen` 只能回滚历史，回滚不了状态 |
| 撤回/重新生成没反应 | `doRevert` 要求 `getDialogRow() > getMinDialogRow()`；另外 Web 端走的是 WS 帧 `revert`/`regen`，**在输入框里打「撤回」两个字没用** |
| 流式正文开头总是空一行 | 正常现象，流式实现会先推空段落，读的时候跳过开头空行 |
| 一句话生成完但前端一直"生成中" | handler 链没有正常结束（抛了未捕获的 `Error`，或阻塞在流读取上） |
| 上下文越来越长、费用暴涨 | 没做历史压缩；补 `HistoryCompactor.compact(...)`（`write` 与 `sql` 两个内置 app 就没做，长文写作必然撞上限） |
| 计费为 0 | 忘了 `resp.addUsageListener(state::addUsage)` |
| 显示名和 `meta.json` 里的 `name` 不一致 | 正常：显示名取 `getName()`，`meta.name` 只用于加载门槛 |
| 改完文件不生效 | app 实例是全局单例，构造在启动/`reload()` 时完成，**改提示词要重启或触发 reload** |
| `meta.json` 写了但应用不出现 | `enabled` 为 `false`、缺 `name`、`type` 未注册、或构造函数抛异常（看启动日志） |
