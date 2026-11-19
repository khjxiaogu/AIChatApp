# Codex 接入方案（CodexConnector）

> 状态：**方案稿**，本次未改动任何代码。
> 实现状态：**已落地**（`codex/`、`mcp/codex/`、`apps/AICodexAgentMain`、`save/apps/frostedheartirc`），
> 使用说明见 [codex.md](codex.md)。
> 目标：把 Codex 接到 QQ，功能对齐 [`DeepseekHarnessConnector`](../src/main/java/com/khjxiaogu/aiwuxia/DeepseekHarnessConnector.java:146)。
> 假设：这里的 "Codex" 指 **OpenAI Codex CLI**（`codex`，Rust 实现，含 `app-server` 子命令）。
> 如果你指的是别的形态（Codex SDK / 云端 Codex），见 §12；方案主体不变，只是把「子进程 + 协议」换成「SDK / HTTP」。
>
> 标记约定：✅ = 已从官方文档或 codex 源码核实；⚠️ = 需要 P0 实测确认（版本相关）。

---

## 0. 结论（TL;DR）

**可行，而且比 DSH 少一层 hack。**

| 维度 | DSH 的做法 | Codex 的做法 |
|---|---|---|
| Agent 通道 | `dsh --profile web/sdk` + `--patch` 注入 SDK stdio JSON-RPC 服务端 | `codex app-server` 原生就是 JSON-RPC 服务端（IDE 用它） |
| 审批 | `--patch` 插 `approval-bridge.mjs`，再经本机回环 TCP 转给 Java | **服务端主动发 JSON-RPC 请求**，连接器直接回 `{"decision":"accept"}` |
| 流式 | `session.event` 里的 `assistant/message` 增量 | `item/agentMessage/delta`、`item/reasoning/summaryTextDelta` 逐字增量 |
| 会话 | `session/prompt` + 自造确定性 sessionId | `thread/start` 拿 `threadId`，`thread/resume` 续聊 |
| 多群 | 一个 DSH 进程多 session | 一个 app-server 进程多 thread（天然对应多群） |

**主要代价**（都不是硬伤）：

1. 多一个外部依赖：Codex CLI（Rust 二进制 / npm 包），要装、要登录、要锁版本。
2. 协议还在演进（v2 表面每版都有新字段、部分字段挂在 `experimentalApi` 后面）。
3. Codex 是**代码代理**，跑在群里意味着给群友一个能改文件、能跑命令的机器人 —— 权限模型必须比 DSH 更严（见 §4.10，Codex 反而能做得更严）。

---

## 1. 已核实的事实

| 事实 | 依据 / 验证方式 |
|---|---|
| 仓库里目前**没有任何** codex 相关代码 | 全仓 `grep -i codex` 无匹配 → 全新实现，无历史包袱 |
| 本机目前**没装** codex | `codex --version` 无输出、退出码 1 → P0 第一件事是安装并抓帧 |
| DSH 连接器 = OneBot WS 客户端 + 子进程 stdio JSON-RPC + 本地 TCP 审批桥 | 读 [`DeepseekHarnessConnector.java`](../src/main/java/com/khjxiaogu/aiwuxia/DeepseekHarnessConnector.java:146)：进程管理 `:667`、帧分发 `:1047`、事件消费 `:1079`、流式 flush `:1337`、审批 `:901`、会话映射 `:2184` |
| 项目已有协议无关的聊天抽象 | [`ChatAdapter`](../src/main/java/com/khjxiaogu/aiwuxia/chat/ChatAdapter.java:26) + [`NapcatAdapter`](../src/main/java/com/khjxiaogu/aiwuxia/chat/NapcatAdapter.java:48) |
| Codex 有 4 类入口：TUI、`exec`、`app-server`、SDK | [Codex App Server 文档](https://developers.openai.com/codex/app-server/)、[Non-interactive](https://developers.openai.com/codex/noninteractive/) |

**Codex 侧的协议依据**（我按这些源码确认了字段名和枚举值，不是猜的）：

- [app-server 文档](https://developers.openai.com/codex/app-server/)：传输、握手、thread/turn 生命周期、通知列表、审批流程。
- [openai/codex `common.rs`](https://github.com/openai/codex/blob/main/codex-rs/app-server-protocol/src/protocol/common.rs)：请求/通知/服务端请求的方法名表。
- [`item.rs`](https://github.com/openai/codex/blob/main/codex-rs/app-server-protocol/src/protocol/v2/item.rs)：审批请求参数、`decision` 枚举、item 结构、delta 通知。
- [`turn.rs`](https://github.com/openai/codex/blob/main/codex-rs/app-server-protocol/src/protocol/v2/turn.rs)：`turn/start` 参数、`turn/completed`。
- [`thread.rs`](https://github.com/openai/codex/blob/main/codex-rs/app-server-protocol/src/protocol/v2/thread.rs)：`thread/start` / `thread/resume` 参数与返回。
- [`shared.rs`](https://github.com/openai/codex/blob/main/codex-rs/app-server-protocol/src/protocol/v2/shared.rs)：`approvalPolicy`、`sandbox`、`approvalsReviewer` 的取值。
- [`permissions.rs`](https://github.com/openai/codex/blob/main/codex-rs/app-server-protocol/src/protocol/v2/permissions.rs)：`SandboxPolicy` 的 JSON 形状。
- 社区整理版（含 MCP 模式被放弃的判断）：[Codex App Server — Technical Reference](https://github.com/majiayu000/harness/blob/main/docs/codex-app-server-reference.md)。

---

## 2. 三条接入通道对比

| | **A. `codex app-server`（主选）** | **B. `codex exec --json`（降级）** | **C. `codex mcp-server`（排除）** |
|---|---|---|---|
| 进程模型 | 一个常驻进程，内部多个 thread | 每条消息一个进程 | 一个 MCP stdio 进程 |
| 传输 | stdio JSONL（默认）/ `ws://` / `unix://` | stdout JSONL 事件流 | MCP over stdio |
| 流式粒度 | **逐字**（agentMessage / reasoning / 命令输出 delta） | 只有 `item.completed`（整条 agent_message），无逐字 delta | 只能靠 MCP progress，粒度粗 |
| 工具审批 | **原生**：服务端发请求，客户端回 decision | ❌ 无（只能 `-a never` 预先授权） | ❌ 无 |
| 会话续聊 | `thread/resume`（按 threadId） | `codex exec resume <SESSION_ID>` | 自己维护 |
| 中途插话 | `turn/steer`（带 `expectedTurnId`） | ❌ | ❌ |
| 附加能力 | `review/start`、`thread/compact/start`、`model/list`、`account/rateLimits/read`、`turn/interrupt` | 少 | 极少 |
| 结论 | ✅ **主方案** | ⚠️ 仅作「app-server 起不来」时的应急模式，功能降级 | ❌ 排除：MCP 工具模型承载不了审批与增量事件；社区文档也指出 codex 已把重心放在 app-server 上 |

**决策**：默认走 A（stdio）。`--listen ws://127.0.0.1:PORT` 留作调试用，不作为生产默认：

- stdio 的好处是**生命周期绑定**：连接器一死，stdin 关闭，子进程跟着退出（⚠️ P0 验证 codex 是否真的 EOF 退出）。
- ws 的好处是连接器可独立重启、可多客户端挂同一个 codex；坏处是要配 `--ws-auth capability-token --ws-token-file`，多一层凭证管理。

---

## 3. 协议映射表（DSH → Codex）

| 能力 | DSH 现实现 | Codex 等价物 | 差异 / 注意 |
|---|---|---|---|
| 启动 | `dsh --profile web --patch ...` + `ELECTRON_RUN_AS_NODE` + `DSH_HOME` | `codex app-server` + `CODEX_HOME`（可选） | 见 §6.1，Windows 要包 `cmd /c` |
| 握手 | `initialize`（含 provider/model/reasoningEffort/cwd） | `initialize`（`clientInfo` + `capabilities`）→ 通知 `initialized` | ⚠️ 必须先 initialize，否则 `Not initialized` |
| 建模/会话 | `session/prompt`（sessionId 自造、确定性） | `thread/start`（返回 `thread.id`）→ 存映射；续聊 `thread/resume` | threadId 由 codex 生成，必须落盘 |
| 投递一轮 | `session/prompt {sessionId, contentBlocks}` | `turn/start {threadId, input:[{type:"text",...}]}` | input 支持 text / image / localImage / skill / mention |
| 正文增量 | `assistant/message` 的 content | `item/agentMessage/delta {delta}` | 逐字，直接喂现有 flush 逻辑 |
| 思维链 | `assistant/reasoning` 或 stream 里的 reasoning | `item/reasoning/summaryTextDelta`、`item/reasoning/textDelta` | **只有 summary 默认可用**；raw reasoning 视模型 |
| 工具调用 | `tool/call` → `ToolCallContent` | `item/started` 的 `commandExecution` / `fileChange` / `mcpToolCall` / `webSearch` | 工具**结果**在 `item/completed` |
| 工具结果 | `tool/result` → `ToolContent` | `item/completed` 的 `aggregatedOutput` / `changes` / `result` | 建议只显示工具名（与 DSH 默认一致） |
| 轮次结束 | `session.status` idle / `turn/end` | `turn/completed {turn.status: completed\|interrupted\|failed}` | failed 带 `error.codexErrorInfo` |
| 审批 | 插件 `approval/request` → 回环 TCP → QQ | **服务端请求** `item/commandExecution/requestApproval` / `item/fileChange/requestApproval` | 回 `{"decision": ...}`，见 §6.6 |
| 中断 | `session/cancel` | `turn/interrupt {threadId, turnId}` | 超时兜底用 |
| 心跳/看门狗 | 连接器写心跳文件，插件侧检查 | stdin EOF + 退出钩子；必要时 pid 文件兜底 | 见 §6.8 |
| 用量 | — | `thread/tokenUsage/updated`、`turn/completed` 的 usage | 可选：给管理员报用量 |

---

## 4. 功能对齐清单

> 逐条对照 DSH 连接器的现行行为（[README §让 Deepseek Harness 接管 QQ](../README.md:254)），说明在 Codex 上怎么落地、有什么差异。

### 4.1 QQ 接入、过滤、@ 触发 ✅ 可 1:1 复用

- 直接复用 [`NapcatAdapter`](../src/main/java/com/khjxiaogu/aiwuxia/chat/NapcatAdapter.java:48)，**不要**像 DSH 连接器那样再继承 `WebSocketClient` 手写一遍 OneBot。
- `bot` / `group` 钉死、私聊一律不处理、群消息必须 at 机器人：逻辑与 DSH 完全相同。
- 被 at 时贴表情（默认 `424`）：`ChatAdapter.react(...)` 已有。

### 4.2 流式分段发送 ✅ 可 1:1 复用

DSH 的阈值语义是「单行攒到 700 / 多行累计到 400 就发，遇到工具调用先冲掉」，这套算法与 agent 无关，直接搬到 `CodexTurnBuffer`：

- `item/agentMessage/delta` → 追加到 `pendingOutput` → 到阈值立刻发（普通消息或合并转发）。
- `item/reasoning/summaryTextDelta` → 追加到 `pendingReasoning` → 到阈值发（带 💭）。
- `item/started`（工具类）→ 先 `flushPending(force)`，再发工具行（🔧 + 工具名）。
- `turn/completed` → 强制 flush 收尾。

### 4.3 合并转发（forward）⚠️ 需要给抽象补一个方法

DSH 用 OneBot 的合并转发发正文/思维链/工具，失败回退普通消息。`ChatAdapter` 目前**没有**转发能力，两个选择：

- **推荐**：给 `ChatAdapter` 加一个 default 方法 `CompletableFuture<String> sendForward(String chatId, String nodeName, List<String> chunks)`，在 `NapcatAdapter` 里实现（`send_group_forward_msg`）。`CodexConnector` 只依赖抽象，转发失败 `catch` 后回退 `sendText`，与 DSH 行为一致。
- 备选：`CodexConnector` 里直接用 OneBot `action` 直连。省事，但把协议细节又漏回连接器，和项目近期的 `ChatAdapter` 重构方向相反。

> 顺带收益：`NapcatAdapter` 补上转发后，后续也可以把 DSH 连接器迁到同一套抽象。

### 4.4 思维链 ✅ 可做，但有降级

- Codex 的**推理摘要**是稳定流（`summaryTextDelta`）；**原始推理**只有部分模型给（`textDelta`）。
- 配置项保留 `codex_output_reasoning`（默认 `false` 更稳妥：Codex 的摘要往往很长，群聊刷屏），`codex_reasoning_forward`（整轮合并成一条转发）保留。
- 思维链同样以「💭 前缀 + 纯文本段落」进 `MutableMessageContents`，与正文分开两份缓冲。

### 4.5 工具可见性 ✅ 可做，且语义更好

| DSH | Codex |
|---|---|
| `tool/call` 合并成一行、只显示工具名 | `commandExecution` 显示命令行首段；`fileChange` 显示 `M`/`A`/`D` + 路径；`mcpToolCall` 显示 `server/tool`；`webSearch` 显示 query |

`codex_output_tools` 默认 `false`（与 DSH 一致）。打开后建议：
- 同类工具在一次回复期间合并成一条，避免刷屏；
- 不显示命令输出（`aggregatedOutput` 可能上千行），需要时单独给管理员发私聊。

### 4.6 会话持久化 ✅ 可做，实现更简单

- `thread/start` 返回 `thread.id`，落盘 `save/codex-threads.json`（QQ 会话键 → threadId + cwd + 创建时间）。
- 每次投递前：有 id 就 `thread/resume`（失败/被删就 `thread/start` 重建，并在群里提示「会话已重置」）。
- 不要照搬 DSH 的「确定性 sessionId」——codex 的 threadId 自己生成，**映射表才是唯一真源**。
- ⚠️ 注意 `CODEX_HOME`：thread rollout 存在 `$CODEX_HOME/sessions/...`，换 home 就「看不到旧会话」。默认用用户默认 home（复用已登录状态），只有用 API key 时才切到 `save/codex-home`。

### 4.7 工具审批（重点）✅ **Codex 更强**

DSH 需要插件桥；Codex 是协议原生能力：

1. codex 要执行需要批准的动作 → 发服务端请求：
   - `item/commandExecution/requestApproval`，params 含 `threadId`、`turnId`、`itemId`、`startedAtMs`、`reason?`、`command?`、`cwd?`、`commandActions?`、`networkApprovalContext?`、`availableDecisions?`；
   - `item/fileChange/requestApproval`，params 含 `reason?`、`grantRoot?`。
2. 连接器在 QQ 私聊里问管理员 → 等答复 → 回 JSON-RPC result：
   ```json
   {"id": 17, "result": {"decision": "accept"}}
   ```
   `decision` 取值：`accept` / `acceptForSession` / `decline` / `cancel`（命令还多两种 amendment 形式，本项目不用）。
3. codex 收到后继续执行，并发 `serverRequest/resolved`，最终 `item/completed`（`status: completed | failed | declined`）。

与 DSH 一致的部分：走私聊、只有管理员能批、超时按拒绝、非管理员触发时先告知「等待管理员批准」。
比 DSH 多出来的能力：

- **`acceptForSession`**：管理员回「允许并记住」→ 本 thread 后续同类不再问（DSH 没这个）。
- **`cancel`**：拒绝并**立即中断整轮**（DSH 只能拒绝单次）。
- **`availableDecisions`**：codex 会告诉客户端哪些选项合法（例如 `humanCommand` 只允许 accept/decline/cancel），按它渲染选项，不要硬编码。
- **`thread/status/changed` 的 `activeFlags: ["waitingOnApproval"]`**：可用来在群里提示「正在等管理员批准」，而不是干等。

### 4.8 超时、中断、单轮上限 ✅ 可做

- `codex_turn_timeout_ms`（默认 300000）：超时发 `turn/interrupt {threadId, turnId}`，再等 10s 收尾；收不到就丢弃该轮缓冲并给群里发提示。
- `codex_max_messages`（默认 200）：超过后停止发送，只发一次「内容过长」提示（复用 DSH 的 `truncatedNotified` 语义）。
- 单条长度：复用 `codex_msg_limit`（默认 600）按**字数**切，不按行切。

### 4.9 看门狗与优雅退出 ⚠️ 简化，但要实测

DSH 三层：钩子 `shutdown` → stdin EOF → 插件看门狗。
Codex 可以简化为两层：

1. JVM 关闭钩子里：`stdin.close()` → 等 3s → `destroy()` → 再等 2s → 强杀；
2. 连接器被 `kill -9` 时，管道写端消失，codex 读到 stdin EOF 应自行退出 —— ⚠️ P0 必须验证这一点。

如果实测发现 codex 不退出，再加第三层（按复杂度排序）：

- 启动时清理孤儿：`save/codex.pid` + 用 `Get-CimInstance Win32_Process` 匹配 `codex app-server` 且命令行含本部署的 `cwd`，只杀匹配项；
- 或定时写心跳文件 + 一个极小的看门狗进程（不推荐，多一个要维护的可执行体）。

### 4.10 权限模型：Codex 能做得**比 DSH 更严**（建议纳入 P2）

DSH 对非管理员只有「提示词约束」（`[不可信]` 标记 + 要求只读），这是**提示层**，不是安全边界。
Codex 支持**每轮覆盖** `approvalPolicy` / `sandboxPolicy`，可以在协议的层面强制：

- 管理员发言：`sandboxPolicy = {type:"workspaceWrite", writableRoots:[codex_cwd], networkAccess:false}` + `approvalPolicy:"on-request"`（越界来问）。
- 非管理员发言：`sandboxPolicy = {type:"readOnly"}` + `approvalPolicy:"on-request"`（只能读；要写就必须让管理员批）。
- ⚠️ **坑**：`turn/start` 的覆盖是「本轮及后续轮次」的粘性设置，所以**每一轮都要显式设置**，否则会继承上一个管理员留下的宽权限。

---

## 5. 目标代码结构

新增包 `com.khjxiaogu.aiwuxia.codex`（与 `apps/`、`chat/`、`mcp/` 平级）：

```
src/main/java/com/khjxiaogu/aiwuxia/codex/
├─ CodexConnector.java       (~700 行)  main + 编排：NapCat 事件 → 线程/轮次；事件 → QQ；权限判定
├─ CodexProcess.java         (~400 行)  定位 codex、起进程、握手、重连、退出、pid/心跳
├─ CodexProtocol.java        (~250 行)  JSONL 读写、请求 id ↔ 回调、服务端请求分发、通知路由
├─ CodexTurnBuffer.java      (~150 行)  复用 MutableMessageContents + 两套 pending 文本 + flush 阈值
├─ CodexThreadStore.java     (~100 行)  QQ 会话键 ↔ threadId 映射持久化
└─ CodexApprovalRouter.java  (~250 行)  QQ 私聊审批：提问、等待、超时、三态答复、并发排队
```

**复用的现有件**（不要重造）：

| 现有件 | 用途 |
|---|---|
| `state.history.message.MutableMessageContents` / `MessageContents` / `ToolCallContent` / `ToolContent` | 与 DSH 连接器完全相同的消息模型；新增内容类型只改一处 |
| [`ChatAdapter`](../src/main/java/com/khjxiaogu/aiwuxia/chat/ChatAdapter.java:26) / `NapcatAdapter` | 连接、发送、私聊、表情、媒体读取 |
| `utils.FileUtil` | 映射文件读写（`transfer`） |
| `llm.scheme.Choice.ToolCall` | 工具调用展示结构（如需与历史消息一致） |
| `opt/optBool/optLong` 的写法 | 保持项目既有的 `-D` 配置风格（建议抽到 `CodexConfig`，顺手复用给未来连接器） |

**取舍说明**：

- DSH 连接器是单个 2354 行的类。新连接器拆成 6 个类更好维护，且 `CodexProtocol` 可以被将来的「别的 agent 通道」复用。
- 若你更在意「与 DSH 连接器长得一样、方便对照维护」，也可以先写单类，等 P2 再拆。**建议直接拆**：审批和流式的状态机比 DSH 更复杂（多 thread 并发）。

---

## 6. 关键实现细节

### 6.1 定位与启动 ⚠️

```java
// 1) 定位（按优先级）
//    -Dcodex_launcher=<path>  显式指定
//    %APPDATA%\npm\codex.cmd                    （npm 全局安装）
//    %LOCALAPPDATA%\Programs\codex\codex.exe    （安装器，若存在）
//    PATH 上的 codex / codex.cmd
// 2) 探测：<launcher> --version 能打印版本号才算可用
// 3) 启动：
//    Windows 上 .cmd 不能保证被 ProcessBuilder 直接执行 → 包一层 cmd.exe /c
//    ["cmd.exe", "/c", launcher, "app-server"]
//    ["codex", "app-server"]  （原生 exe）
```

进程环境：

- `CODEX_HOME`：默认**不设**（复用用户已登录的 `~/.codex`）；`-Dcodex_home=save/codex-home` 时隔离状态（此时必须单独登录或注入 API key）。
- 工作目录：`pb.directory(codex_cwd)`。
- `stdout` = 协议通道，必须独占读取；`stderr` 单线程 drain 并 `log("[codex] ...")`（与 DSH 的 `drainDshStderr` 同构）。
- ⚠️ 首跑可能触发 Windows 沙箱安装/授权（`windowsSandbox/setupStart`）或项目信任提示；日志里要能看出来，必要时群里提示管理员。

### 6.2 握手 ✅

```json
{"id":1,"method":"initialize","params":{
  "clientInfo":{"name":"codex-connector","title":"Codex Connector","version":"0.1.0"},
  "capabilities":{"experimentalApi":false,
                  "optOutNotificationMethods":["item/commandExecution/outputDelta"]}
}}
{"method":"initialized","params":{}}
```

要点：

- **不要**带 `"jsonrpc":"2.0"`：codex 的线上格式省略该字段（与 DSH 连接器相反，照抄会踩坑）。
- 默认不开 `experimentalApi`：开了才能用 `availableDecisions`、`additionalPermissions` 等；建议先关（稳定面足够），P3 再评估。
- `optOutNotificationMethods` 用来退掉命令输出增量（我们不转发命令输出），省带宽和 CPU。
- 之后调 `account/read` 检查登录：未登录 → 私聊管理员提示 `codex login`；`model/list` 校验 `codex_model` 是否存在。

### 6.3 建线程 ✅

```json
{"id":2,"method":"thread/start","params":{
  "model":"gpt-5.4",
  "cwd":"D:\\projects\\AIWuxia\\save\\codex-workspace",
  "approvalPolicy":"on-request",
  "sandbox":"workspace-write",
  "serviceName":"codex-connector",
  "developerInstructions":"（§6.4 的群聊安全规则，整段）"
}}
```

- `approvalPolicy` 取值：`"untrusted"`（只信任安全命令，其余都问）/ `"on-request"`（越界才问）/ `"never"`（从不问）/ `granular` 对象（实验）。
- `sandbox` 取值：`"read-only"` / `"workspace-write"` / `"danger-full-access"`。
- 返回里带 `cwd`、`approvalPolicy`、`sandbox`、`reasoningEffort`，**启动时打日志核对**（防止配置没生效）。
- 安全规则建议放 `developerInstructions`（每线程一次），而不是像 DSH 那样每条消息重复一遍；每条消息仍保留 `<qq-message ...>` 信封与 `[不可信]` 标记。
- `ephemeral` 保持 `false`（要续聊）。

### 6.4 投递一轮 ✅

```json
{"id":3,"method":"turn/start","params":{
  "threadId":"thr_...",
  "input":[{"type":"text","text":"<qq-message group=\"123\" sender=\"张三\" senderId=\"456\" role=\"member\" untrusted=\"true\"><text>帮我看看这个报错</text></qq-message>\n[不可信] ..."}],
  "approvalPolicy":"on-request",
  "sandboxPolicy":{"type":"readOnly"},
  "effort":"high"
}}
```

- **每轮都带** `approvalPolicy` + `sandboxPolicy`（见 §4.10 的粘性坑）。
- `input` 支持 `{"type":"image","url":"data:..."}` / `{"type":"localImage","path":"..."}` → QQ 图片可以直接喂给 Codex（用 `ChatAdapter.fetchMedia` 落地成临时文件，走 `localImage`）。这是相对 DSH 的净增能力，放 P3。
- 若该 thread 正在跑一轮：默认**排队**（每 thread 一把锁，与 DSH 的 `sessionLocks` 同构）；`codex_steer=true` 时改用 `turn/steer {threadId, input, expectedTurnId}` 把新消息插进当前轮（更自然，但要处理 `ActiveTurnNotSteerable` 错误）。

### 6.5 事件循环与 flush ✅

```text
stdout 每行 → JSON 解析
├─ 有 id + 有 result/error → 请求回调（thread/start、turn/start、interrupt…）
├─ 有 id + 有 method   → 服务端请求（审批 / requestUserInput）→ CodexApprovalRouter
├─ 无 id + 有 method   → 通知：
│    turn/started            → 记录 turnId，重置 buffer
│    item/agentMessage/delta → appendText(正文)；到阈值 flush
│    item/reasoning/summaryTextDelta → appendText(思维链)；到阈值 flush
│    item/started            → 若是工具类：flush 文本 + 发工具行
│    item/completed          → 工具结果；agentMessage 用最终 text 兜底校正
│    turn/completed          → flush(force) + deliver；status=failed 走错误映射
│    error                   → 记录 codexErrorInfo（可能先于 turn/completed）
│    thread/status/changed   → activeFlags 含 waitingOnApproval → 群里提示「等批准」
└─ 都不是 → log 原文（协议演进时便于发现新事件）
```

`appendText` 的语义与 DSH 完全相同：同一份 `MutableMessageContents` 承载正文 + 工具调用/结果（顺序天然正确），思维链单独一份。

### 6.6 审批流程 ✅

```text
收到 item/*/requestApproval (id=R)
├─ 记下 R → pendingApproval（含 threadId/turnId/命令或文件列表/提问时间）
├─ 私聊管理员：
│    🔧 需要授权：命令 `curl https://api.example.com`（cwd=...，原因：需要网络访问）
│    回复：1 允许 / 2 允许并记住本会话 / 3 拒绝 / 4 拒绝并中断
├─ 等答复（codex_approval_timeout_ms，默认 120s）→ 回：
│    {"id":R,"result":{"decision":"accept"}}            // 1
│    {"id":R,"result":{"decision":"acceptForSession"}}  // 2
│    {"id":R,"result":{"decision":"decline"}}           // 3 / 超时
│    {"id":R,"result":{"decision":"cancel"}}            // 4
└─ 群里同步一句「已允许 / 已拒绝」（不回细节，避免泄露命令）
```

要点：

- **只接受管理员**的私聊答复；非管理员的私聊一律忽略（与 DSH 一致）。
- 批准答复要被消费掉，不能变成新一轮对话（DSH 的 `handlePrivateApproval` 语义）。
- 超时按 `decline`（不按 `cancel`，避免整轮被打断）。
- `availableDecisions` 存在时按它裁剪选项（例如 `humanCommand` 只有 3 项）。
- 同一时刻多个审批请求：串行排队（DSH 的 `approvalQueue`），避免管理员被并发提问搞混。
- `codex_approval` 模式映射：
  | 模式 | approvalPolicy | 行为 |
  |---|---|---|
  | `qq`（默认） | `on-request` | 私聊问管理员 |
  | `allow` | `never` | 不问；沙箱内的动作直接跑，越界动作**失败**（要真正放开须 `codex_sandbox=danger-full-access`，高危） |
  | `deny` | `on-request` | 仍收请求，但一律自动回 `decline`（用于观察 agent 想干什么） |
  | `off` | `never` | 不接审批；万一收到请求一律 `decline`（安全兜底） |

### 6.7 错误码映射（给群友看中文）✅

`turn.completed.turn.error.codexErrorInfo`（或 `error` 通知）：

| codexErrorInfo | 群里提示 |
|---|---|
| `usageLimitExceeded` / `rateLimitExceeded` | 账号额度/限流用尽，稍后再试 |
| `contextWindowExceeded` | 上下文超限，已建议 /compact 或开新会话 |
| `unauthorized` | Codex 登录失效，请管理员重新登录 |
| `sandboxError` | 沙箱拒绝执行（可能需要授权或调整 sandbox） |
| `httpConnectionFailed` / `responseStreamDisconnected` / `responseStreamConnectionFailed` | 上行网络异常，请重试 |
| `serverOverloaded` | 服务端繁忙，稍后重试 |
| `modelNotFound` | 模型 id 不存在，检查 `-Dcodex_model` |
| `other` / 未知 | 原样回传 message（截断 200 字） |

### 6.8 关闭 ✅

```text
JVM shutdown hook:
  stdin.close()                         // 让 codex 看到 EOF
  process.waitFor(3s)
  process.destroy()  → waitFor(2s)
  process.destroyForcibly()             // 兜底
  （可选）写 save/codex.pid 便于下次启动清孤儿
```

---

## 7. 配置项（`-Dcodex_*`，尽量与 `dsh_*` 同名，便于沿用文档习惯）

| 属性 | 默认 | 说明 |
|---|---|---|
| `napcat_url` / `napcat_token` | `127.0.0.1:3001` / 空 | 与 DSH 连接器共用 |
| `bot` / `group` / `adminId` | `0` / `0` / `0` | 与 DSH 完全一致 |
| `codex_launcher` | 自动探测 | `codex.cmd` / `codex.exe` 路径 |
| `codex_home` | 空（用户默认） | `CODEX_HOME`；设了要单独登录 |
| `codex_cwd` | `save/codex-workspace` | 会话工作目录（建议 `git init`） |
| `codex_model` | 空（用 codex 默认） | 传 `thread/start.model`；启动时用 `model/list` 校验 |
| `codex_effort` | 空 | `low` / `medium` / `high` / `ultra` |
| `codex_sandbox` | `workspace-write` | `read-only` / `workspace-write` / `danger-full-access` |
| `codex_approval` | `qq` | `qq` / `allow` / `deny` / `off`（见 §6.6） |
| `codex_experimental` | `false` | 是否开 `experimentalApi` |
| `codex_msg_limit` | `600` | 单条最大字数 |
| `codex_flush_line` / `codex_flush_multiline` | `700` / `400` | 流式 flush 阈值 |
| `codex_output_reasoning` | `false` | 是否转发推理摘要 |
| `codex_reasoning_forward` | `false` | 思维链整轮合并成一条转发 |
| `codex_forward_text` | `true` | 正文用合并转发（失败自动回退） |
| `codex_output_tools` | `false` | 是否转发工具调用 |
| `codex_prefix` | `true` | 💭 / 🔧 前缀 |
| `codex_send_delay_min_ms` / `max_ms` | `1000` / `3000` | 发送节流（等上一条回执后随机等） |
| `codex_send_ack_timeout_ms` | `15000` | 发送回执超时 |
| `codex_like_emoji` | `424` | 被 at 时贴的表情 |
| `codex_turn_timeout_ms` | `300000` | 单轮超时（到点 `turn/interrupt`） |
| `codex_max_messages` | `200` | 单轮最多发多少条 |
| `codex_relay_context` | `false` | 未被 at 的群聊是否作为上下文附带 |
| `codex_guard_prompt` | `true` | 是否启用 `qq-message` 信封与不可信标记 |
| `codex_approval_timeout_ms` | `120000` | 等管理员答复的超时 |
| `codex_steer` | `false` | 运行中是否用 `turn/steer` 插话 |
| `codex_image_input` | `false` | 是否把 QQ 图片作为 `localImage` 投给 Codex |
| `codex_watchdog` | `true` | 启动时清理孤儿 + 心跳（若 P0 证明 EOF 足够，可只保留清理） |

---

## 8. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| **协议漂移**（v2 表面每版加字段、实验字段要 `experimentalApi`） | 升级 codex 后连接器报错 | ①锁版本（记录到 README）；②启动时 `codex app-server generate-json-schema --out save/codex-schema` 存档；③解析容错：只取白名单字段，遇未知字段/事件只 log 不炸；④固定跑稳定面（`experimentalApi:false`） |
| **Windows 沙箱**（elevated/unelevated 安装、首次授权） | 首跑失败或命令跑不起来 | 启动日志明确提示；失败时降级 `read-only` 并私聊管理员；文档写清 `windowsSandbox/setupStart` 路径 |
| **账号额度**（ChatGPT 计划有周/5h 限额） | 群里突然不能用了 | 错误码映射 + 管理员私聊告警；可选接 `account/rateLimits/read` 做 `/额度` 指令 |
| **群友 = 代码执行权限** | 有人诱导 agent 删文件/外发数据 | 默认 `workspace-write` + `networkAccess:false`；非管理员每轮 `readOnly`；写入/联网必须管理员私聊批准；`cwd` 指向专用目录而非仓库根 |
| **凭证共享**（一台机器一个 codex 登录） | 所有群共用一个账号额度 | 文档写明；将来可做「按群指定 model/provider」 |
| **孤儿进程** | 占会话写锁/吃内存 | 关闭三层（§6.8）+ 启动清理 pid/命令行匹配 |
| **长输出刷屏** | 群被封/被踢 | 发送节流 + 单轮上限 + 合并转发 + 超长截断提示 |
| **项目没有测试套件** | 改动无回归保障 | 纯解析逻辑（帧 → 事件）抽成无副作用方法，便于后续补 `src/test` 单测；先靠抓帧脚本 + 手工清单 |

---

## 9. 分阶段实施

### P0 环境探针（0.5 天，**不写生产代码**）

- 安装 codex，确认 `codex --version`、`codex app-server --help`。
- 用一次性脚本（Node 或 PowerShell）跑通：`initialize` → `thread/start` → `turn/start`，抓完整帧序列。
- 重点验证（⚠️ 项）：
  1. `.cmd` 启动方式（直接 vs `cmd /c`）；
  2. 关闭 stdin 后 codex 是否退出、耗时多少；
  3. 审批帧的真实形状与 `decision` 回包的接受情况（用会触发审批的命令，如 workspace 外写入/联网）；
  4. `thread/resume` 在进程重启后是否可续（同一 `CODEX_HOME`）；
  5. `turn/start` 在已有活动轮次时的行为（报错？排队？）。
- 产出：`docs/codex-frames.md`（真实帧样例 + 上述 5 条结论），必要时修正本文档。

### P1 最小闭环（1–1.5 天）

进程管理 + 握手 + thread/turn + 正文流式 + napcat 发送。单群、`codex_approval=never`、`read-only`。
**验收**：群里 at 机器人 → 收到分段流式回复；重启连接器后同一群能续聊。

### P2 功能对齐（1.5–2 天）

审批路由（4 态）、思维链、工具行、合并转发、管理员/不可信信封、超时中断、单轮上限、错误码映射、关闭钩子、per-turn 权限。
**验收**：§4 清单逐条勾掉；触发一次越权写入 → 管理员私聊收到审批 → 拒绝 → 群里看到「已拒绝」且轮次正常结束。

### P3 体验与增强（0.5–1 天）

图片输入、`turn/steer`、`/状态` `/额度` `/压缩` `/审查` `/新会话` 等指令、断线重连退避、用量上报。
**验收**：连续多群并发、长时间运行无泄漏。

### P4 可选：反向 MCP（把本程序的工具给 Codex 用）

DSH 方向是「本程序把工具喂给 DSH」（[`mcp/harness`](../src/main/java/com/khjxiaogu/aiwuxia/mcp/harness/HarnessMcp.java)）。
Codex 侧等价做法更简单：在 `config.toml` 里注册一个 MCP server（本程序已有 [`mcp/server/McpServer.java`](../src/main/java/com/khjxiaogu/aiwuxia/mcp/server/McpServer.java)），codex 就能用图片生成/搜索/语音等工具。
**验收**：群里让 codex 画张图，它调用本程序的绘图工具并把结果发回群。

---

## 10. 验证方式（无测试套件）

1. `mvn clean package` 必须通过（Java 8 语法，不用 `var` / `List.of` / `Process.pid()`）。
2. 抓帧脚本：`tools/codex-spike.mjs`（P0 临时产物）能复现 §6.2–6.6 的帧。
3. 手工回归清单（每次改连接器跑一遍）：
   - 普通问答、超长回答（>600 字切分）、多段流式；
   - 工具调用（只读 ls）显示/不显示两种配置；
   - 审批：允许 / 允许并记住 / 拒绝 / 拒绝并中断 / 超时；
   - 重启续聊、换 `codex_cwd` 后的行为；
   - 非管理员发言被 `readOnly` 限制；
   - 杀连接器后 codex 是否退出。
4. 可选：把 `CodexProtocol` 的帧→事件解析抽成纯函数，补 `src/test/java` 单测（项目目前没有测试，属于净增收益，但不是 P2 的阻塞项）。

---

## 11. 明确不做

- 不复刻 Codex 的历史管理/UI（`thread/list`、`thread/read`、归档等）：QQ 侧只用「续聊」。
- 不把 QQ 图片 base64 塞进文本（用 `localImage`，P3）。
- 不允许多个部署共享同一个 `CODEX_HOME`（会话写锁会打架；`dsh` 那边也有同样问题）。
- 不在群聊里暴露审批命令：审批只在管理员私聊。
- 不做「所有人自动放行」的默认配置（`allow` 也不是无限制，见 §6.6）。

---

## 12. 待拍板

1. **目标确认**：就是 OpenAI Codex CLI（`codex app-server`）吗？如果你指的是 Codex SDK 或云端 Codex，§3 的「通道」要换成 SDK/HTTP，但 §4 的功能清单和 §5 的结构基本不变。
2. **接入范围**：先做「单群 + 只读演示」，还是一步到位「多群 + 可写工程助手」？这决定 P2 的权限模型要不要立刻做。
3. **认证方式**：ChatGPT 账号（额度制，复用 `~/.codex` 登录）还是 API key（计费，可放进 `save/codex-home`）？这决定 `codex_home` 默认值。
4. **抽象复用**：同意给 `ChatAdapter` 加 `sendForward`（并让 `NapcatAdapter` 实现）吗？不同意就退回「连接器内直连 OneBot」，代码量 +150 行左右。
5. **P4 反向 MCP**：要不要把本程序的工具接给 Codex（§9 P4）？要的话需要先确认 `McpServer` 的鉴权方式能否被 codex 的 `mcp_servers` 配置携带。
