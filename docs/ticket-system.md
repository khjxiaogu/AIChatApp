# 工单系统（基于文档库的 agent 工具）

工单系统是“基于文档系统”的扩展：一张工单就是文档库中的一篇文档，结构化字段保存在文档文件开头的
JSON meta 里，正文保存标题和描述。工单工具只在 **agent 会话**中注册，不挂到
`/aichat/mcp` 在线 MCP 端点。

## 1. 启用

在 `save/config/agents.json` 对应 agent 的 `tools` 里加入 `ticket`（别名 `ticketmcp`）：

```json
{
  "botId": 2177216213,
  "groupId": 475448612,
  "name": "muxinirc",
  "tools": [
    "fetch",
    "doclib",
    "ticket"
  ]
}
```

可选的工单库参数写在同一个 agent 配置的 `ticket` 段；不写时使用库 id `tickets`：

```json
{
  "tools": ["ticket"],
  "ticket": {
    "libraryId": "tickets",
    "wakeOnNotify": true
  }
}
```

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `libraryId` | `tickets` | 工单库 id；同一个 id 的会话共享同一份工单库 |
| `wakeOnNotify` | `false` | 收到 `<ticket-update>` 后是否主动唤醒会话跑一轮；默认只入队，等下一轮再处理（避免通知风暴） |

`multi.json` 的会话项可以直接写同一段 `ticket` 配置，两个公屏会话都配 `wakeOnNotify: true`
即可让它们都立即响应工单变更：

```json
{
  "groupId": "789012",
  "extraTools": ["codex"],
  "ticket": { "libraryId": "tickets", "wakeOnNotify": true }
}
```

同一个 `libraryId` 的所有 agent 共享同一份工单库（`DocLibraryRegistry` 按库 id 复用运行时）。
每个持有 `ticket` 工具的会话都会各自注册一个工单事件监听者，所以“哪些 agent 监听工单”只取决于
该会话的 `tools`/`defaultTools` 是否包含 `ticket`。

工单库建议在 `save/config/doclibs.json` 里显式声明（示例见下面第 2 节），否则目录会退到
`doclibs/tickets-<hash>`。

## 2. 存储

默认目录：`save/doclibs/tickets/`（路径可在 `save/config/doclibs.json` 的
`libraries.tickets.path` 覆盖，参数与普通文档库一致：`maxDocChars`、`retainRevisions` 等）。

工单文档 id 为 `ticket/<工单号>`，例如 `ticket/T-20261124-095500-A1B2`。文件示例：

```json
{"schema":1,"kind":"ticket","ticket_id":"T-20261124-095500-A1B2","status":"待创建","priority":"normal","version":"1.2.0","assignee":"","reporter":"alice","labels":["bug"],"created_at":1763949300000,"updated_at":1763949300000,"sheet_row":2}
登录页报错

用户点击登录后返回 500，复现步骤……
```

- 文件第一个 `{...}` 是 meta（文档系统的通用扩展点），`doc_read` / `doc_list` / `doc_search`
  等普通文档查询看不到它；
- 第一行可见正文是工单标题，空行后是描述；
- 文档库的 `revision`、写锁、历史快照、监听通知对工单同样生效。

## 3. 工具

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `ticket_create` | `title` 必填；`description`、`version`、`reporter`、`assignee`、`priority`、`labels` 可选 | 新建工单，状态默认 `待创建`，优先级默认 `normal` |
| `ticket_list` | `status`、`assignee`、`reporter`、`label`、`keyword`、`limit`、`offset` 可选 | 列出工单；`status` 可用逗号分隔多个，`all` 表示全部 |
| `ticket_get` | `ticket_id` 必填；`include_body` 可选（默认 true） | 读取工单完整内容（含正文） |
| `ticket_update` | `ticket_id` 必填；`status`、`priority`、`version`、`assignee`、`reporter`、`labels`、`title`、`author`、`message`、`base_revision` 可选 | 更新字段；结束状态会自动记录 `closed_at`，重新打开会清除 |
| `ticket_write` | `ticket_id`、`mode`（`overwrite` / `append` / `patch`）、`content` 必填；`author`、`message`、`base_revision` 可选 | 编辑工单正文，语义与 `doc_write` 一致；只会改正文，状态等 meta 字段不变。标题始终取新正文第一行 |

状态：`待创建` / `无效` / `处理中` / `已修复` / `暂不修复` / `已解答` / `设计如此` /
`重复` / `未复现` / `待反馈` / `待补充` / `已拒绝`；
其中 `无效`、`已修复`、`暂不修复`、`已解答`、`设计如此`、`重复`、`未复现`、`已拒绝`
视为结束状态，会自动记录 `closed_at`。
优先级：`low` / `normal` / `high` / `urgent`。
`labels` 用逗号或 `|` 分隔；`assignee` / `reporter` / `labels` 传空串表示清空。
`base_revision` 用于乐观并发：与当前文档修订不一致时返回 `DOC_STALE`，避免覆盖别人刚提交的更新。

`ticket_*` 与 `doc_*` / `kb_*` / `history_*` 一样只豁免“工具调用轮次上限”，单工具调用次数上限仍然生效。

## 3.1 监听通知

doclib 只把原始 `DocEvent` 交给实现了 `DocEventListener` 的 MCP；工单通知由 `TicketMcp` 自己构建，
使用独立的 `<ticket-update>` 格式：

- `ticket_update` 修改状态/优先级/负责人/报告人/标签时，`changes` 里是具体旧值/新值，例如：
  `status: 待创建 → 已修复; assignee: (空) → alice`；
- `ticket_write` 修改正文时，`change` 里是 `+x/-y` 摘要；
- 同一次更新同时改正文和 meta（例如同时改标题和状态）时只产生**一条统一通知**：
  `<ticket-update>` 里的同一个 `<ticket>` 同时带 `change` 和 `changes`；
- 同一批通知里同一工单只保留一条条目，正文与 meta 变化会合并，不会拆成两条；
- 普通文档库的 meta 变化不会由普通文档 MCP 通知；只有工单 MCP 会解释并发送工单字段变化；
- meta 通知不受“已读 revision”过滤：`doc_read` 看不到 meta，不能据此认为 agent 已知字段变化；
- 通知一定入队到监听会话的上下文；是否立即唤醒由该会话的 `ticket.wakeOnNotify` 决定
  （默认 `false`，只入队；`true` 时若会话空闲会立即触发一轮）。

## 4. 在线表格看板（可选）

工单系统可以把关键字段自动写到在线表格，表格只作为看板。支持两种供应商，通过 `provider` 切换
（默认 **飞书多维表格**，可切回腾讯文档）：

- 只在工单创建/更新/编辑正文后刷新，不需要监听表格上的修改；
- **表格不写工单正文**；`summary` 列是看板简述：正文/标题变化时调用一个非推理模型生成
  一小段中文简述（不超过 80 字），存进工单 meta 的 `sheet_summary`，之后只更新该行；
  状态、优先级等 meta 变化不会重新生成简述；
- 工单系统假设自己是唯一写入方；表格只做看板，外部对表格的修改会在下次对应行刷新时被覆盖；
- 同步失败只记日志，不影响工单操作；
- **看板同步不会修改工单正文**；行编码/简述属于看板 meta，会正常产生结构化 meta 事件，
  但 TicketMcp 只挑工单字段（status/priority/assignee/reporter/labels），sheet_row / sheet_summary
  等字段被忽略，不通知 agent；
- 默认最多展示 999 张工单（加表头共 1000 行）。

配置 `save/config/ticket-sheet.json`：

```json
{
  "enabled": true,
  "provider": "feishu",
  "tableId": "",
  "sheetTitle": "工单看板",
  "syncOnStart": true,
  "syncDelayMs": 1500,
  "minSyncIntervalMs": 5000,
  "columns": [
    "ticket_id", "title", "summary", "version", "status", "priority", "assignee", "reporter",
    "labels", "revision", "created_at", "updated_at"
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `enabled` | 是否启用看板同步，默认 `true`（文件存在时） |
| `provider` | 供应商：`feishu`（默认，飞书多维表格）或 `tencent`（腾讯文档） |
| `tableId` | 业务表格 ID：飞书填数据表 `table_id`，腾讯填子表 ID；留空时按 `sheetTitle` 查找/新建 |
| `sheetTitle` | 找不到对应工作表/数据表时自动新建的名称，默认 `工单看板` |
| `syncOnStart` | 启动后是否先同步一次，默认 `true` |
| `syncDelayMs` | 工单变更后的合并延迟，默认 1500ms |
| `minSyncIntervalMs` | 两次真实写入的最小间隔，默认 5000ms，避免触发在线表格限频 |
| `columns` | 展示列，可用 `ticket_id`、`doc_id`、`title`、`summary`、`version`、`status`、`priority`、`assignee`、`reporter`、`labels`、`revision`、`created_at`、`updated_at`、`closed_at` |

凭据按 `provider` 读取：`feishu` 用 `save/config/feishu-bitable.json`（`app_id` / `app_secret` /
`access_token` + 连接级 `app_token`），`tencent` 用 `save/config/tencent-docs.json`。飞书多维表格首次
同步时会自动建数据表、写 A1 表头并建立“逻辑列名 → 实际字段”的映射，之后按 `sheet_row` 写行时会
自动按映射重排，不受字段物理顺序影响。每张工单的 meta 里有 `sheet_row`（1 是表头，数据从 2 开始）
和可选的 `sheet_summary`（看板简述）：

- 启动时：给缺失行编码的旧工单补 `sheet_row`，读取一次表头；工单数与表格数据行数一致、
  且表头一致时**完全不覆盖**；数量一致但表头不一致时只刷新表头；数量不一致时先删除表格
  全部数据行（飞书多维表格直接删除记录），再整表填入；
- 之后：工单创建/更新只按 `sheet_row` 写 `A{row}:{lastCol}{row}`，不碰表头和其它行；
- 正文/标题变化时，先在后台调用非推理摘要器生成/更新 `sheet_summary`，再更新该行；
- 表格首次同步时自动建工作表，行数上限 1000；超过 999 张工单的行不会展示。

## 5. 与在线 MCP 的关系

- 工单工具注册在 agent 会话的 `ToolCallManager` 中（`sessionOnly`），不会出现在
  `/aichat/mcp` 的 `tools/list` 里，也不会被 `exposeSessionTools` 暴露给远程客户端；
- 外部系统若要使用工单能力，应通过 agent（例如 QQ 机器人）或直接调用文档库 API，而不是在线 MCP 端点；
- 文档库的通用 meta 规则见 [agent-doc-library.md](agent-doc-library.md)。
