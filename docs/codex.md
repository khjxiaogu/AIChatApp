# Codex 接入：MCP 工具集 + 编排 agent 应用

本机已安装 Codex CLI（`codex app-server`，实测 `codex-cli 0.167.0-alpha.3.2`）。
本目录相关实现把 Codex 包装成两样东西：

| 组成 | 类 | 作用 |
|---|---|---|
| 运行时 | `com.khjxiaogu.aiwuxia.codex.CodexService` | 管理一个常驻 `codex app-server` 进程：线程、轮次、事件流、审批、超时、落盘 |
| MCP 工具集 | `com.khjxiaogu.aiwuxia.mcp.CodexMcp` | 10 个 `codex_*` 工具（含对象存储↔项目目录的文件传输），给 QQ agent、外部 MCP 客户端或任意 `ToolCallManager` 用 |
| agent 应用 | `com.khjxiaogu.aiwuxia.apps.AICodexAgentMain` | 「编排助手」：自己不写代码，调 `codex_*` 把活交给 Codex，再把结果讲给群友 |

三者关系：

```
QQ 群消息
   │  NapCatAIConnector（@ 触发、权限、发送）
   ▼
AICodexAgentMain（外层 LLM：理解需求、决定怎么问、汇总结果）
   │  codex_status / codex_send / codex_latest / codex_pending / codex_feedback / codex_interrupt
   ▼
CodexMcp ──► CodexService ──► codex app-server（stdio JSON-RPC）
                                 │  thread/start、turn/start、item/* 通知
                                 ▼
                            FrostedHeart 项目（改代码、跑 Gradle）
```

审批（Codex 要执行敏感命令 / 改工作目录外的文件）是**协议原生**的：Codex 发
`item/commandExecution/requestApproval`，`CodexService` 私聊管理员问「允许/拒绝」，
管理员在私聊里回 `1/2/3/4` 即可。QQ 官方机器人下优先发带 4 个按钮的审核卡片，
点击按钮即可，不需要打字；卡片发不出去时自动退回文本。**不需要** DSH 那套 `--patch`
插件与回环 TCP。

---

## 1. 挂到 NapCatAIConnector（QQ 群）

在 `save/config/agents.json` 里，给目标机器人加 `codex` 技能；工具名就是 `codex`：

```json
{
  "botId": 123456,
  "groupId": 789012,
  "name": "frostedheartirc",
  "save": "savegroup-frostedheartirc.json",
  "tools": ["fetch", "multimodal", "qq", "codex", "knowledge"],
  "adminId": 345678,
  "codex": {
    "cwd": "D:\\projects\\frostedheart-newdev",
    "sandbox": "workspace-write",
    "approval": "allow",
    "networkAccess": true
  }
}
```

要点：

- `tools` 里写 `codex` 即启用；连接器启动时会为该群注册 10 个工具，并按
  `qq:<botId>:<groupId>` 隔离线程 —— 不同群各聊各的，互不串话。
- `adminId` 是 Codex 审批的收件人，可以写单个值或数组。NapCat 用 QQ 号，QQ 官方机器人用
  管理员 `user_openid`；多个管理员时审批会私聊发给每个人，**任意一个先回复即生效**；
  没有管理员/审批桥时失败关闭。
- `codex` 段可省略（用默认值 + `-D` 覆盖）；`cwd` 只能落在允许的根目录内
  （默认项目目录 + 当前 `cwd`），越界会被拒绝。
- `group` 要同时出现在 `botId` 对应的群列表里（连接器按 `groupId` 匹配会话）。

## 2. agent 应用（编排助手）

新建 `save/apps/<名字>/meta.json`，`type` 必须是 `codex`：

```json
{
  "type": "codex",
  "name": "FrostedHeart 编程助手",
  "brief": "把群里的需求交给 Codex，在 FrostedHeart 项目上改代码",
  "prompt": "本群固定项目：FrostedHeart（Minecraft 1.20.1 Forge 模组）……"
}
```

可选再放 `rules.txt` / `role.txt`，会追加进系统提示。内置提示词已经把工作流写死：

1. 只做转发：用户原话就是 `codex_send` 的 `task`（最多去掉 @/引用前缀），**不拆步骤、不补验收标准、不选技术方案**；
   只有完全看不出对象/目标（如只说「改一下」）时才追问一句；
2. `codex_send` 投递（换目录先 `codex_start`）后**本轮立即结束**，只回一句「已提交，完成后我会汇报」；
3. 任务结束时系统把结果作为一条新消息交给会话（`<tool name="codex_send" ...>`），触发新一轮 AI：
   带 `status` / `text` / `recent_tools` / `cursor`，必要时再用 `codex_latest`（`since=cursor`）读一次完整过程；
4. `pending` 非空 → `codex_pending` 查看，能决定的 `codex_feedback`，需要用户拍板的转述；
5. 汇总：转述 Codex 的结论 + 改动文件 + 验证方式；**禁止编造执行结果**。
6. **会话隔离**：同一串相关操作（同一目标、同一模块、接着上一轮改）用同一个线程；
   换项目或互不相关的新任务先 `codex_start` 开新会话，不要把所有事塞进一个线程。
7. 对用户只给结果与必要操作，**不透露工具名、会话/游标、附件 id、存储、沙箱/审批配置**等内部细节。

> 这就是「系统等待、完成后触发 AI」：模型不再靠反复 `codex_latest` 轮询。
> 轮次结束的判定来自 Codex 推来的 `turn/completed`，不需要定时查。
> 该异步模式只在 **QQ 连接器**里启用（会话有工具任务回调）；外部 MCP 客户端仍是同步返回，
> 需要自己用 `codex_latest` 跟进。

仓库里已给本机默认配置建好示例：`save/apps/frostedheartirc/`。

### 显式发送（默认只给 codex agent）

Codex agent 默认 **`explicitSend`**：AI 的正文不会自动发出去，必须调用发送工具；其它 agent 不受影响
（默认关闭，按需在 agent 配置里打开）。

```json
{
  "explicitSend": true
}
```

| 配置 | 默认 | 说明 |
|---|---|---|
| `explicitSend` | codex agent 为 `true`，其它为 `false` | 正文不自动发，必须用发送工具 |

工具（`GroupChatMcp`）：

- `send_group(text)`：发当前群公屏；
- `ask_user(question, options?)`：给当前用户发询问卡片，答复作为异步工具结果返回。

> 私聊发送（`send_private` / `handover_private` / `list_agents`）、私聊白名单与防抖、
> `privateChat*` / `privateWhitelist*` 配置都属于**多 agent 架构**，只在
> [multi-agent.md](multi-agent.md) 的连接器里生效；普通 agent 的私聊消息只用于审批与询问答复。

行为：

- 若同时开启 `autoReply`（主动回复判定），显式发送模式下**跳过 AI 判定**：到间隔/条数阈值就直接触发一轮，
  不再额外调用判定模型；回复仍由 `send_group` 显式发出。


### 撤回消息

适配器解析 OneBot v11 的 `post_type=notice`：`group_recall` / `friend_recall`，按 `message_id` 交给连接器：

- **群聊**：消息还在会话的待处理缓冲区（`AIGroupSession` 的队列，还没被 AI 取走）时，按 id 移除；
  已经 `submitMessage` / 已进入本轮的无法撤回（按需求只处理未提交的）。
- **私聊**（多 agent 架构）：从 10 秒防抖缓冲区移除该条；如果删空，本轮触发直接取消，不会再把空消息交给 AI。
- 日志：`[QQ] 撤回消息已从缓冲区移除：<id>（群 ...）` / `[Multi] 撤回私聊已从缓冲区移除：<id>（QQ ...）`。

### 续聊时的历史回放

连接器重启后，`save/codex-mcp/threads.json` 里的线程会 `thread/resume`；紧接着用
`thread/turns/list`（倒序取最近 `codex_history_turns` 轮、`itemsView=full`）把历史读回来，
归一化成与实时事件相同的 `assistant / reasoning / tool / turn` 事件：

- `codex_latest` 直接能读到这些历史（`history_loaded=true`），末尾有一条「以上为历史记录」分隔；
- AI 回答「之前做到哪了 / 继续」时**不需要给 Codex 发新消息**，读历史即可；
- 回放是只读、尽力而为：失败只记日志，不影响续聊；
- 不想回放就把 `codex_history_turns` 设为 `0`。

### 截图 / 图片（对象存储 id）

图片一律走项目既有的对象存储约定：**先存对象存储换 id，agent 与 QQ 之间只传 id**，不传 base64。

Codex 产出的图片有三类来源，都会被识别：

| 来源 | 协议 | 说明 |
|---|---|---|
| `imageView` | 条目 `{type:"imageView", path}` | Codex 查看/截取的图片（浏览器、computer-use 截图通常走这里） |
| `imageGeneration` | 条目 `{type:"imageGeneration", savedPath, status}` | Codex 图片生成插件的产物 |
| MCP 图片结果 | `mcpToolCall.result.content` 里的 `{type:"image", data, mimeType}` | 外部 MCP 工具返回的内联图片 |

处理链路：

```
图片字节 → ObjectStorage.uploadIfNotExists → 图片 id
  → 事件/完成载荷里的 images=[{id, source, mime}]
  → 异步工具附件 new ImageContent(id, "Codex 截图")
  → 连接器 imageFileForSend(id) → tos.getUrl(id) → 发到 QQ
```

- QQ 会话用连接器的 `tos`，外部 MCP 用 `mcpStorage`；服务启动时绑定，之后补绑也生效。
- 没有对象存储（或上传失败）时降级成 data URL，仍能发出去，但不产生 id。
- 每轮最多 4 张、单张 ≤ 8MB；读本地文件时只允许**工作目录、`codex_roots`、临时目录、`CODEX_HOME`** 下的图片。
- 历史回放的旧图不会重发，避免每次续聊刷屏。
- 模型只看到 `images` 元信息（id/source/mime），拿不到 base64；提示词要求只说明图片是什么。

**反向：把 QQ 里的图片给 Codex。** 用户发来的图片在消息里是 `<image id=...>`，agent 调
`codex_send` 时把 id 放进 `images`（逗号分隔，最多 4 张）。服务用
`ObjectStorage.getHttpUrl(id)`（本地存储不支持公网地址时退回 `getDataUrl` 的 data URL）解析成 Codex 可访问的地址，
以 `{"type":"image","url":...}` 作为 `turn/start` 的输入块。

**回复里的 markdown 图片。** Codex 经常在最终回复里写 `![截图](screenshots/a.png)` 而不是单独发
`imageView`，这类本地路径也会被解析：

- 相对路径按线程 `cwd` 解析，支持 `file://` 与 `<带 空格.png>`；网络图片、`data:`、锚点跳过；
- 走同一套白名单（cwd / `codex_roots` / 临时目录 / `CODEX_HOME`）与大小限制，上传对象存储换 id 作为附件；
- 在该条 Codex 消息末尾追加「（已上传为附件：`<path>` → `<id>`；…）」，agent 能对上文件与附件；
- 同一路径每轮只处理一次，相同内容按 id 去重（不会重复发同一张图）。

**只给路径时的提醒（由 AI 判断）。** Codex 在回复里只写本地图片路径、没有用 markdown 图片语法时，
提示词要求 agent 自己识别这一情况，并用 `codex_send` 发一句
「请用 ![说明](路径) 输出图片，不要只给路径」，同一问题只提醒一次。
服务端只负责解析 markdown 图片并上传，**不自动判断/提醒**。

- 外部 MCP 客户端（同步模式）返回图片元信息（id）但不负责发图；要看图请在 QQ 会话里用。
- Codex 能不能真的截图取决于它自己的插件/工具（browser、computer-use 等），那是 Codex 配置，不在本连接器范围内。

### 文件传输（对象存储 ↔ 项目固定目录）

两个工具把「对象存储 id」和「Codex 项目里的真实文件」接起来。**AI 不能选择目录**：文件只会落在固定的
`ai_files/`（相对线程 `cwd`；常量在 `CodexService.TRANSFER_DIR`，换位置只改这一处），名称由 `name` 控制：

| 工具 | 方向 | 参数 | 说明 |
|---|---|---|---|
| `codex_pull_file` | 对象存储 → 项目固定目录 | `file_id`、`name`（`thread_id`、`overwrite` 可选） | 写到 `<cwd>/ai_files/<name>`；`name` 只能是纯文件名（可带扩展名，如 `icon.png`）；同名已存在时默认拒绝，`overwrite=true` 才覆盖 |
| `codex_push_file` | 项目固定目录 → 对象存储 | `name`（`thread_id` 可选） | 读取 `<cwd>/ai_files/<name>`，按内容寻址上传（不带扩展名，72 位 id） |

约束与行为：

- 目录固定为 `ai_files`，AI 不能指定其它目录；`name` 不能带目录、盘符或 `\ / : * ? " < > |` 等字符，也不能是 `.`/`..` 或 Windows 保留名；
- 对象存储 id 只接受字母/数字/点/下划线/短横线，不接受链接或本地路径；
- 单文件上限 64MB（对象存储接口按整块读写，避免误传超大文件）；
- 没有线程时退回该归属键的默认项目目录，不会为了传文件去启动 Codex；`thread_id` 显式给了但不存在则直接报错；
- `pull` 成功后返回 `relative`（`ai_files/<name>`），agent 应在 `codex_send` 的任务里写它，Codex 才能找到文件；
- 两个工具都声明了顺序敏感，和同一批里的 `codex_send` 会按模型给出的顺序执行。

## 3. 挂到 Codex 现有项目（FrostedHeart）

Codex 桌面端里的项目保存在 `CODEX_HOME/state_*.sqlite` 的 `projects` 表（示例：

| 字段 | 值 |
|---|---|
| 项目名 | `Frostedheart` |
| 项目 id | `01a1c400-c867-71b1-a631-8337bff84975` |
| 项目根 | `D:\projects\frostedheart-newdev` |

app-server 的 `thread/start.projectId` 只接受 id、且在 `experimentalApi` 门后，所以连接器做了两件事：

1. `codex_project` 可以写**项目名**：先只读查询 `state_*.sqlite` 解析成 id；找不到时调用
   app-server 的 `project/create`，按当前线程的 `cwd` **自动创建项目**并拿到 id；
2. 自动把 `experimentalApi` 打开（只影响被门控的字段/方法，项目相关方法都在这个门后）。

```json
"codex": {
  "cwd": "D:\\projects\\frostedheart-newdev",
  "project": "Frostedheart",
  "projectId": "01a1c400-c867-71b1-a631-8337bff84975",
  "experimental": true
}
```

`experimental` 的含义：初始化 app-server 时把 `capabilities.experimentalApi` 设为 `true`，
解锁 Codex 的**实验性协议方法/字段**，例如 `thread/start.projectId`、`project/create`、
`project/list` 等。它不影响模型行为、沙箱或审批，只是让连接器能调用这些接口。
配置了 `project`/`projectId` 时默认开启；没有项目时可以保持 `false`。

效果（实测）：

- `thread/start` 返回的 `thread.projectId` = 上述 id，`codex_threads` 里也会带 `project_id`；
- Codex 状态库里新线程的 `project_id` 与该项目的机器人会话一致，桌面端项目视图里能看到这些会话；
- 之后 `thread/resume` 续聊仍在同一项目下。

只写项目 id 也可以（`"project": "01a1c400-..."`），跳过查库；或者写 `"projectId": "01a1c400-..."`
更明确。**本机示例配置同时写了两者**：`project` 便于人读，`projectId` 保证不依赖状态库、Codex 改名或被锁时也能挂上。
若项目被删，`thread/start` 会失败一次，连接器自动改为「不挂项目」重试，并在日志里提示。

> 自动创建用「项目名 + 当前 cwd」：不同用户用不同 repo 时，请给各自不同的 `project`
> （或直接写 `projectId`），否则同名项目会复用第一个路径。

## 4. 工具清单（`CodexMcp`）

| 工具 | 必填 | 说明 |
|---|---|---|
| `codex_status` | — | 安装路径、版本（`codex --version`）、进程、cwd、沙箱、审批模式、线程数 |
| `codex_threads` | — | 当前归属键下的线程：id、cwd、模型、running、pending、最后错误 |
| `codex_start` | — | 新线程（`cwd`/`title` 可选），只建线程、不投任务 |
| `codex_send` | `task` | 投任务（`thread_id` 省略用默认线程）；**QQ 里是异步工具**：立即返回，轮次结束时由系统触发 AI；`images` 可传对象存储图片 id（逗号分隔）；MCP 客户端为同步返回 |
| `codex_latest` | — | 增量读输出：`since`/`include_reasoning`/`max_items`/`wait_seconds`/`only_if_changed` |
| `codex_pending` | — | 待审批 / 待回答的交互 |
| `codex_feedback` | `task_id` | 审批 `decision=allow/allow_session/deny/cancel`；提问用 `text`/`selected` |
| `codex_review_approval` | `task_id`、`decision` | 对话 AI 预审：`approve` / `ask_user` / `reject`（`reason` 可选；`ask_user` 时可带 `reviewer` 建议审核人） |
| `codex_interrupt` | — | 中断当前轮（`turn/interrupt`） |
| `codex_pull_file` | `file_id`、`name` | 对象存储 → 项目固定目录 `ai_files/<name>`（`name` 只能是纯文件名；`overwrite` 可选，默认不覆盖） |
| `codex_push_file` | `name` | 项目固定目录 `ai_files/` 里的文件 → 对象存储 id（`name` 只能是纯文件名） |

`codex_latest` 返回的事件 `kind`：

| kind | 含义 |
|---|---|
| `assistant` | 正文（流式增量按相邻合并，轮末用 `item/completed` 的完整文本校正） |
| `reasoning` | 思维链摘要（`summaryTextDelta`；原始推理不转发） |
| `tool` | 命令 / 改文件 / MCP 工具 / 搜索 / 计划 / 压缩，带 `tool`、`summary`、`status` |
| `turn` | 轮次结束：`status=completed / failed / interrupted`，失败带 `error` |
| `error` | 错误通知（含 `retry`，Codex 会自动重连 5 次） |
| `approval` | 审批发起 / 结果（`status=pending` 或 `decision=...`） |
| `notice` | 会话创建、超时中断等提示 |

## 5. 审批

> 当前配置使用 `"approval": "allow"`：自动放行，不询问对话 AI 或管理员；沙箱仍然生效。
> 下面的 AI 预审/人工审批流程仅在 `"approval": "ask"` 时启用。

Codex 侧配置为 `approvalPolicy=on-request`（`approval=ask` 时），触发场景：

- 命令要联网 / 越出沙箱（`item/commandExecution/requestApproval`）
- 改工作目录之外的文件（`item/fileChange/requestApproval`）
- 需要澄清的提问（`item/tool/requestUserInput`）

### 对话 AI 预审（`codex_ai_review`）

收到审批请求后，`CodexService` 不再单独调用审核模型，而是登记一条 `review` 待办，并通过
`CodexReviewHandler` 唤醒**当前会话的 agent**（注入 `<codex-approval task_id="...">`）。
agent 逐字核对后调用 `codex_review_approval` 决定：

| 决定 | 处理 |
|---|---|
| `approve` | 直接 `accept`（记录 `note=ai:<理由>`），不打扰用户 |
| `reject` | 直接 `decline`（记录 `note=ai:<理由>`） |
| `ask_user` / 超时 / 会话不可用 | 转人工：走 QQ 私聊管理员流程（记录 `ai-ask` / `ai-timeout`） |

agent 的核对要求（写在 codex agent 提示词里）：逐字核对命令、参数、路径、cwd 是否与任务一致、拼写正确；
检查破坏性操作、越界写入、读取/外传凭证、可疑网络、下载执行远程脚本、base64/编码混淆、串接命令
（`&&`、`;`、管道）；**不确定一律 `ask_user`**。超时 `codex_review_timeout_ms`（默认 30s）转人工；
没有管理员/审批桥时失败关闭。

- `codex_ai_review=false` 时退回「每条都问管理员」。
- 预审由对话 agent 完成，不再有独立的审核模型；`codex_review_model` 已移除。
- 转人工时 AI 可以额外给 `reviewer=<账号>` 作为建议审核人（NapCat QQ 号 / 官方 openid）：
  仅当该 agent 开启 `openApproval`（`codex_open_approval`）时生效，审批私聊会发给这个用户；
  未开启或未给 `reviewer` 时仍发给配置的管理员。

QQ 私聊文案：

```
【Codex 审批】执行命令
内容：gradlew.bat build
目录：D:\projects\frostedheart-newdev
原因：构建需要联网下载依赖
回复：1 允许 / 2 允许并记住本会话 / 3 拒绝 / 4 拒绝并中断
（任务 codex-7-1a2b3c4d）
```

- `1` → `accept`（只放行这一次）
- `2` → `acceptForSession`（本线程后续同类不再问）
- `3` → `decline`（拒绝，本轮继续，Codex 会换方案）
- `4` → `cancel`（拒绝并中断整轮）
- 超时（默认 120s）按拒绝处理；处理结果会再私聊一条确认。
- 非管理员私聊、无待审批任务时，这些消息不会被消费（不会误当聊天内容）。

> QQ 官方机器人下，上述审批会以 Markdown 卡片 + 四个按钮发送；按钮 data 绑定
> `task_id`，点击后适配器先 `PUT /interactions/{id}` 回应平台，再按 `task_id` 精确处理，
> 不会误答同一管理员的其它待审批任务。自定义按钮需要平台内邀权限，发送失败会退回上面的文本
> 文案；文本回复 `1/2/3/4` 仍然有效。

`approval` 模式：

| 值 | 行为 |
|---|---|
| `ask`（默认） | 先由对话 AI 预审：合理自动放行，不确定/危险才私聊管理员；没有管理员/审批桥时**失败关闭**（拒绝） |
| `allow` | 一律放行（沙箱仍然生效；越界动作直接失败） |
| `deny` | 仍收请求但一律拒绝（观察 Codex 想干什么时用） |
| `off` | 不接审批，收到也拒绝（最保守） |

## 6. 配置项（`-D` 系统属性，或 `agents.json`/`mcp.json` 的 `codex` 段）

| 属性 | 默认 | 说明 |
|---|---|---|
| `codex_enabled` | `true` | 是否启用 |
| `codex_launcher` | 自动探测 | `codex.exe`/`codex.cmd`；自动找 `%LOCALAPPDATA%\OpenAI\Codex\bin\*\codex.exe`、PATH、npm 全局 |
| `codex_home` | 空 | `CODEX_HOME`；空 = 用户默认 `~/.codex`（复用已登录状态） |
| `codex_cwd` | `save/codex-workspace` | 默认工作目录（相对数据目录）；也可用 `codex_default_project` 指定一个存在的项目目录 |
| `codex_default_project` | 空 | 默认项目目录绝对路径；目录存在时优先作为 `cwd`，并在 `codex_roots` 里始终放行 |
| `codex_roots` | 空 | 额外允许的目录，`;` 分隔 |
| `codex_allow_any_cwd` | `false` | 允许任意工作目录（危险） |
| `codex_model` | 空 | 模型 id；空 = Codex 默认（本机为 `gpt-5.5`） |
| `codex_effort` | 空 | `low` / `medium` / `high` / `ultra` |
| `codex_sandbox` | `workspace-write` | `read-only` / `workspace-write` / `danger-full-access` |
| `codex_network_access` | `false` | workspace-write 时是否允许命令联网（Gradle 拉依赖需要） |
| `codex_approval` | `ask` | 见上表 |
| `codex_approval_policy` | 自动 | 覆盖下发的 `approvalPolicy`（`untrusted`/`on-request`/`never`） |
| `codex_approvals_reviewer` | `user` | `user` / `auto_review` |
| `codex_approval_timeout_ms` | `120000` | 等管理员答复的超时 |
| `codex_ai_review` | `true` | 审批先交对话 AI 预审，合理自动放行，不确定/危险才转人工 |
| `codex_open_approval` / `openApproval` | `false` | 允许 AI 转人工时用 `reviewer` 指定建议审核人；关闭时忽略该参数、一律管理员审核 |
| `codex_review_timeout_ms` | `30000` | 等对话 AI 预审答复的超时，超时转人工 |
| `codex_request_timeout_ms` | `60000` | 单条 JSON-RPC 超时 |
| `codex_turn_timeout_ms` | `900000` | 单轮上限，到点自动 `turn/interrupt` |
| `codex_max_wait_seconds` | `20` | 工具长轮询上限（≤25） |
| `codex_max_items` | `100` | 一次 `codex_latest` 最多返回事件数 |
| `codex_history_turns` | `5` | 续聊时回放最近多少轮历史（0 关闭）；回放内容进 `codex_latest` 事件流 |
| `codex_max_event_chars` | `8000` | 单条事件文本上限 |
| `codex_ephemeral` | `false` | `true` = 不落盘的临时线程（无法重启续聊） |
| `codex_project` | 空 | 挂到 Codex 桌面端已有的项目：项目名（如 `Frostedheart`）或项目 id（UUID） |
| `codex_project_id` | 空 | 显式项目 id；配置后不查状态库，最稳（推荐和 `codex_project` 一起写） |
| `codex_experimental` | 配了项目时为 `true` | 开 `experimentalApi`；`thread/start.projectId` 在实验门后，挂项目必须开 |
| `codex_data` | `save/codex-mcp` | 线程映射 `threads.json` 所在目录 |

优先级：`-D` > `agents.json`/`mcp.json` 的 `codex` 段 > 默认值。

## 7. 外部 MCP 客户端

`save/mcp.json` 里加 `codex` 段后，`McpServerEndpoint` 会把同一套工具暴露给
`/aichat/mcp` 的调用方，每个令牌的用户一个归属键（`mcp:<uid>`）：

```json
{
  "codex": {
    "enabled": true,
    "cwd": "D:\\projects\\frostedheart-newdev",
    "sandbox": "workspace-write",
    "approval": "ask"
  }
}
```

外部场景没有 QQ 私聊可问，`approval=ask` 会退化成失败关闭（拒绝）。要放行请配
`"approval": "allow"`，或让调用方自己用 `codex_feedback` 回答审批。

## 8. 运行时文件与排查

| 文件 | 说明 |
|---|---|
| `save/codex-mcp/threads.json` | 归属键 → threadId 映射；重启后自动 `thread/resume` |
| `save/codex-workspace/` | 未指定项目时的默认工作目录 |
| 日志前缀 `[Codex]` | 握手、线程、审批、stderr |

常见问题：

| 现象 | 处理 |
|---|---|
| `未找到 codex 可执行文件` | `-Dcodex_launcher=...` 指定；或确认 `codex --version` 可运行 |
| `Project-local config ... disabled until the project is trusted` | 项目里的 `.codex`/hooks 不生效：在 `~/.codex/config.toml` 加 `[projects."D:\\projects\\frostedheart-newdev"]` + `trust_level = "trusted"`（AGENTS.md 不受影响，仍会加载） |
| `failed to initialize sqlite state runtime` | `CODEX_HOME` 不可写：换目录，或去掉 `codex_home` 用默认 |
| `codex_status` 报 `version` 为空 | `codex --version` 输出被环境吞掉；看 `[Codex] [stderr]` |
| 群里只得到「正在执行」，没有结果 | 用 `codex_latest` 看 `turn`/`error`；`error` 里 `codexErrorInfo=httpConnectionFailed` 多为上游网关问题 |
| 审批一直没人收到 | 检查该 agent 的 `adminId`；`approval=off/deny` 时不会发问 |
| 想换项目 | 改 `codex.cwd` 并保证在 `codex_roots` 内；或 `codex_start` 时传 `cwd` |
| `没有在 ... 里找到项目「X」` | 项目名写错或该 Codex home 里没有这个项目；改用 `codex_project=<项目id>`，或去桌面端确认项目名 |

## 9. 安全建议

- 默认 `sandbox=workspace-write`，只写 `cwd`；`networkAccess` 默认关闭，按需在单个群打开。
- 非管理员在群里也能触发 Codex；如需收紧，把 `codex_send` 放进 `toolApprovals`，
  由现有 QQ 工具审核先过一道，或只让管理员在群里 @ 机器人。
- 不要把 `approval` 设成 `allow` 又给 `danger-full-access`，那等于把整台机器交给群友。
- `codex app-server` 是**实验性**接口：升级 Codex 后先跑一次
  `codex app-server generate-json-schema --out save/codex-schema` 对比字段。
