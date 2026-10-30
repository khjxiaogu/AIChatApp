# MCP 客户端 / 服务端与访问令牌

本文说明 AIWuxia 新增的 MCP（Model Context Protocol）能力：

- **服务端**：把本地 `ToolData` 以标准 MCP 工具形式暴露给外部客户端（HTTP Streamable 传输）；
- **客户端**：连接外部 MCP 服务端（HTTP 或 stdio），把远程工具转换为本地 `ToolData` 注册进会话；
- **访问令牌**：用户自助创建令牌，调用工具时从令牌所属用户的**额外配额（补充配额）**中扣费，并记录消费明细。
  计费方式为**固定调用费 100 + 工具内部产生的费用合计**。

---

## 1. 结构总览

```
外部 MCP 客户端 (Claude / VS Code / 脚本)
        │  Streamable HTTP + Bearer 令牌
        ▼
┌──────────────────────────────────────────────┐
│ /aichat/mcp  McpServerEndpoint               │
│  ├─ 认证：McpAccessTokenStore（SHA-256 摘要） │
│  ├─ 协议：McpServer（initialize/tools/*）     │
│  ├─ 工具：McpToolRegistry ← 本地 ToolData     │
│  └─ 计费：预扣 100 → 执行 → 结算内部用量      │
└──────────────────────────────────────────────┘
        ▲
        │ 同一份 messages.db
        ▼
┌──────────────────────────────────────────────┐
│ AIWuxia 会话 / 应用                           │
│  └─ McpClientManager ← save/mcp-client.json  │
│       └─ McpHttpClient / McpStdioClient       │
│            └─ McpToolBridge → ToolData        │
└──────────────────────────────────────────────┘
```

代码位置：

| 包 | 作用 |
| --- | --- |
| `com.khjxiaogu.aiwuxia.mcp.protocol` | JSON-RPC 消息、协议版本、错误码 |
| `com.khjxiaogu.aiwuxia.mcp.server` | 工具注册表、MCP 调度、HTTP 端点、令牌与计费 |
| `com.khjxiaogu.aiwuxia.mcp.client` | HTTP/stdio 客户端、ToolData 桥接、配置管理 |

---

## 2. 服务端

### 2.1 挂载与启用

MCP 服务端由 `AIChatService#mcp` 转发到 `/aichat/mcp`（不再在 `AIServerMain` 单独挂载）：

- MCP 端点：`http://<host>:8999/aichat/mcp`
- 令牌管理页：`http://<host>:8999/aichat/mcptoken`（用户标识由外部框架通过 URL 参数注入，页面上不展示）

配置文件 `save/mcp.json`（不存在时使用默认值，服务端默认启用）：

```json
{
  "enabled": true,
  "usageType": "MCP工具",
  "exposeSessionTools": false
}
```

| 字段 | 说明 |
| --- | --- |
| `enabled` | 服务端开关；`false` 时不转发 `/aichat/mcp`，也不初始化令牌存储（客户端不受影响） |
| `usageType` | 写入 `paid_usage_record.usage_type` 的用量类型，默认 `MCP工具` |
| `exposeSessionTools` | 是否把令牌所属用户**在线会话**中的工具一并暴露（默认关闭） |

> 旧配置项 `defaultPrice` 已废弃：调用费固定为 100，不再按工具单独定价。

服务端内置两个自检工具（不依赖会话状态）：

- `mcp_echo`：参数 `text`，原样返回；
- `mcp_server_time`：无参数，返回服务端当前时间。

除此之外还会注册角色设定查询工具（`list_characters` / `search_characters` /
`get_character_setting`），见 [2.8](#28-角色设定工具)。

### 2.2 协议支持

- 传输：**Streamable HTTP**（`POST /aichat/mcp`，返回 `application/json`；客户端 `Accept` 含 `text/event-stream` 时仍返回 JSON）
- 版本协商：`2025-06-18` / `2025-03-26` / `2024-11-05`，客户端提出受支持的版本时原样回显
- 方法：
  - `initialize`、`notifications/initialized`、`ping`
  - `tools/list`（自动生成 `inputSchema`，参数类型均为 `string` 且全部 required）
  - `tools/call`（计费入口）
  - `resources/list`、`resources/templates/list`、`prompts/list` 返回空列表（兼容探测）
  - `logging/setLevel`、`completion/complete` 空实现
  - 未知方法返回 `-32601`；支持 JSON-RPC 批量请求
- 会话：`initialize` 下发 `Mcp-Session-Id`，后续请求可回传；服务端无状态，不强制校验
- `GET /aichat/mcp` 返回 405（不支持服务端主动推送），`DELETE /aichat/mcp` 返回 200（结束会话）

### 2.3 注册要暴露的工具

任何地方拿到端点后都可以注册 `ToolData`：

```java
McpToolRegistry registry = chatService.getMcpEndpoint().getRegistry();

registry.register(new ToolData.Builder("my_search", "搜索内部资料")
        .putParam("keyword", "关键词", "关键词")   // 第三个参数是人可读名称，仅用于本地审核界面
        .tool((arguments, output, manager) -> {
            // arguments 是 JSON 文本，例如 {"keyword":"xxx"}
            // 工具不绑定会话：需要记账时写 output.addUsage(...)，需要会话上下文时读 manager
            return "结果";
        })
        .build());

// 也可以整批注册一个 MCP 模块（工具不再需要会话参数）
registry.register(FetchMcp.create(tos));
```

也可以挂载**按用户动态返回工具**的来源：

```java
registry.addSource(new McpToolSource() {
    public String getName() { return "my-source"; }
    public Collection<ToolData> getTools(String uid) {
        return toolsOf(uid); // uid 来自访问令牌
    }
});
```

`exposeSessionTools=true` 时，内置会话来源会返回该用户在线 WebSocket 会话里的工具（例如 `web_view`、`image_recognition`）。默认关闭，避免外部调用触发聊天副作用。

工具统一返回 `ToolResult`，职责分两部分：

- **结果内容**（`MessageContents`）：只提供给模型进一步处理，可以按顺序包含文本、图片等；
  例如 `SDXLMcp` 的图片生成工具把生成的图片 Content 插在结果文本中间，而不是放进附件；
- **附件**：用于附加在结果中返回给用户（例如 QQ 发图/发语音），由会话统一处理。

服务端还会按令牌所属用户暴露 `AIChatService` 中的 SDXL 图片工具（`image_interrogate`、
`sdxl_gen_image`、`regional_image`、`image_to_image`，以及配置了角色列表时的 `search_character`），
与绘画会话使用同一套按用户绑定的本地模型。

同时注册本地语音工具：

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `list_voice_speakers` | 无 | 列出本地语音配置中的全部可用说话人（角色名称） |
| `generate_voice` | `role`、`text` | 用角色名称和话语内容生成语音；内部先经过 `VoiceTagger` 分句与情感标注，再调用本地语音模型 |

**MCP 生成内容统一存储与访问**：

- 所有 MCP 工具生成的图片、语音等内容统一写入 MCP 本地存储（`save/mcp_files`）；
- 对外统一使用一个入口 + 文件 id 参数访问：

```text
GET /aichat/mcp/media?file_id=<文件id>
```

- 工具结果里可以放对象存储 id，MCP 服务端会把它以及 `图片id：...`、`<image id=...>` 等多媒体引用
  统一转换为上面的入口链接，不再直接返回内部 id，也不再区分图片/语音各自的访问路径；
- 图片 id 没有扩展名时，入口会根据文件头自动识别 `Content-Type`。

### 2.4 访问令牌

令牌由用户自助创建，明文只在创建时返回一次，数据库只保存 SHA-256 摘要，因此无法从库里还原明文。

表结构（均创建在 `messages.db`）：

```sql
mcp_access_token(id, uid, name, token_hash, token_prefix,
                 quota_limit, quota_used, call_count, enabled,
                 created_at, expires_at, last_used_at)
mcp_tool_call_log(id, token_id, uid, tool, cost, call_cost, usage_cost,
                  ok, error, request_id, client_ip, duration_ms,
                  result_chars, created_at)
```

- `quota_limit`：单个令牌累计可消耗的额外配额上限，`0` 表示不限制；
- `expires_at`：毫秒时间戳，空表示永不过期；
- `mcp_tool_call_log`：每一次调用（成功与失败）都有记录，包括工具、耗时、结果长度、客户端 IP 与失败原因。
  `call_cost` 为固定调用费（100），`usage_cost` 为工具内部用量折算出的费用，`cost` 为实际扣除的合计；
  调用失败时 `cost` 与 `usage_cost` 为 0（固定费用已退回），`call_cost` 保留名义值。
- 旧库启动时会自动补上 `call_cost` / `usage_cost` 两列；旧版 `mcp_tool_price` 表不再使用。

管理 API：

用户标识（`uid`）只从请求 URL 的查询参数读取，由外部框架在打开页面/发起请求时注入；
**请求体一律不携带用户标识**，只放业务字段。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/aichat/mcp/token?uid=` | 创建令牌，请求体 `{name,quotaLimit,expiresInDays}`，返回一次性明文 |
| `GET` | `/aichat/mcp/token?uid=` | 列出用户的令牌（不含明文） |
| `POST` | `/aichat/mcp/token/revoke?uid=` | 停用令牌，请求体 `{id}` |
| `POST` | `/aichat/mcp/token/delete?uid=` | 删除令牌及其调用记录，请求体 `{id}` |
| `GET` | `/aichat/mcp/usage?uid=&tokenId=&limit=` | 消费记录 + 汇总统计 |
| `GET` | `/aichat/mcp/status` | 统计信息 |
| `GET` | `/aichat/mcp/tools?uid=` | 当前暴露的工具列表 |
| `GET` | `/aichat/mcp/media?file_id=` | MCP 生成内容的统一访问入口（图片、语音等） |
| `GET` | `/aichat/mcp/whoami` | 用令牌自检（需要 `Authorization`） |

### 2.5 计费规则

价格单位与配额单位一致（token，等同于“补充配额”）。一次 `tools/call` 的实际费用为：

```text
总费用 = 固定调用费 100 + Σ ceil(工具内部每一笔用量的 getEquivantTokens())
```

固定调用费与工具无关，任何工具每次调用都先扣 100；工具执行期间通过 `output.addUsage(...)`
写入的每一笔用量（额外模型调用、图片/视频生成、搜索、对象存储等）都会在调用结束后按与主流程
补充配额一致的向上取整规则折算并累加。

执行顺序：

1. 校验令牌（启用、未过期）；
2. 条件更新 `user_paid_balance`：`balance = balance - 100 WHERE balance >= 100`。
   余额不足直接返回 JSON-RPC `-32002`，不执行工具；
3. 条件更新令牌 `quota_used`（校验 `quota_limit`）；超限则退回余额并返回 `-32002`；
4. 写入一条 `mcp_tool_call_log`（待完成，`call_cost = 100`）；
5. 执行工具，收集工具内部写入的全部用量；
6. 成功：按内部用量合计补扣费用并累加令牌 `quota_used`，把日志 `cost` 更新为
   “100 + 内部费用”，写入 `daily_usage.paid_used` 与 `paid_usage_record`
   （`usage_type = MCP工具`），记录标记为成功，返回文本结果；
7. 失败：退回固定调用费与令牌额度，结果带 `isError: true` 返回给客户端，日志 `cost = 0`
   并记录失败原因。

因此：

- 扣的是**用户额外配额**（`user_paid_balance`，界面上的“补充配额”），不占用每日免费额度；
- 固定费用在调用前检查余额，内部费用在工具执行完成后按实际发生额结算；因此内部费用可能
  超过调用前的余额或令牌额度（余额可能为负），后续调用需补足后才能继续；
- 用户消费明细会出现在原有的用量统计中；
- 调用失败不扣费：固定调用费退回，工具内部用量不向用户收费；
- 并发调用通过条件更新保证固定调用费不会把余额扣成负数。

调用结果的 `_meta` 会返回本次费用明细：

```json
"_meta": { "price": 100, "callPrice": 100, "usagePrice": 0, "charged": 100, "paidBalance": 49000 }
```

`price` 为合计费用，`callPrice` 为固定调用费，`usagePrice` 为工具内部费用，`charged` 为实际扣除，
`paidBalance` 为调用后的额外配额余额。

### 2.6 调用示例

```bash
# 1) 创建令牌（用户标识从 URL 查询参数读取，请求体不带）
curl -X POST 'http://127.0.0.1:8999/aichat/mcp/token?uid=your-uid' \
  -H 'Content-Type: application/json' \
  -d '{"name":"claude","quotaLimit":0,"expiresInDays":30}'

# 2) initialize（响应头会带 Mcp-Session-Id）
curl -X POST http://127.0.0.1:8999/aichat/mcp \
  -H 'Authorization: Bearer mcp_xxxxxxxx' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
        "protocolVersion":"2025-06-18","capabilities":{},
        "clientInfo":{"name":"curl","version":"1.0"}}}'

# 3) 列出工具
curl -X POST http://127.0.0.1:8999/aichat/mcp \
  -H 'Authorization: Bearer mcp_xxxxxxxx' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# 4) 调用工具（固定调用费 100，mcp_echo 无内部用量）
curl -X POST http://127.0.0.1:8999/aichat/mcp \
  -H 'Authorization: Bearer mcp_xxxxxxxx' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
        "name":"mcp_echo","arguments":{"text":"hello"}}}'
```

工具调用结果：

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{ "type": "text", "text": "hello" }],
    "_meta": { "price": 100, "callPrice": 100, "usagePrice": 0, "charged": 100, "paidBalance": 49000 }
  }
}
```

### 2.7 令牌管理页

浏览器打开：

```
http://127.0.0.1:8999/aichat/mcptoken
```

页面不展示、也不手工填写用户标识：外部框架打开页面时通过 URL 参数注入身份，
页面把该 URL 参数原样转发到管理接口（请求体不携带），服务端只从请求 URL 读取。
页面支持创建令牌（明文复制一次）、查看额度/调用次数/过期时间、停用或删除、查看消费记录，
并给出客户端配置示例。页面文件为 `save/mcptoken.html`。

### 2.8 角色设定工具

服务端启动时还会注册一组只读的「角色设定」工具，数据来自人类可读的索引文件
`character-list.txt`（不再读取 `charaset.txt`）。索引文件同时承担“角色列表”和“角色设定数据”
两个职责，每次调用时重新读取，修改后立即生效。

文件位置：新位置 `save/config/sdxl/agent/`，兼容旧位置 `save/painteragent/`。

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `list_characters` | 无 | 列出全部作品与角色（含别名） |
| `search_characters` | `query` | 按角色名、别名（简称/拼音/外号）或作品名模糊搜索 |
| `get_character_setting` | `character`、`work` | 取角色完整设定，并附所属作品的公共设定；同名角色用 `work` 区分 |

索引文件格式：

```text
[work] 雪之本境

[character] 江城雪
别名：城雪, chengxue
性别：女
年龄：18
身高：168
设定：手无缚鸡之力，独自很难存活。
身份：校花、江庭雪之姐
外貌：留着通透亮粉色的顺滑长直发……
常服：内搭黑色高领打底衫……
角色提示词：chengxue,red eyes,pink hair,hair ornament,hairclip
服装提示词：chengxuefu,pleated skirt,shoes,……
```

- `[work]` 开始作品，`[public]` 开始该作品的公共设定（可省略），`[character]` 开始角色；
- 其余普通行按原文保存并返回，建议统一写成 `字段：值`；
- `别名：` 行只用于模糊搜索，不会出现在设定正文里；
- 查询角色时自动附带所属作品的 `[public]` 内容；
- 同一角色名出现在多部作品时（如「归海枫」同时在《雾之本境》《雪之本境》），
  用 `work` 指定作品。

调用示例（JSON-RPC）：

```json
{"jsonrpc":"2.0","id":10,"method":"tools/call","params":{
  "name":"get_character_setting",
  "arguments":{"character":"枫茜","work":""}}}
```

绘画会话（`PainterSession`）默认已注册这组工具；其它会话也可以手动注册：

```java
session.registerTools(CharacterSettingMcp.create(dataFolder));
```

---

## 3. 客户端

### 3.1 配置

配置文件 `save/mcp-client.json`（不存在时不启用，不影响启动）：

```json
{
  "servers": [
    {
      "name": "aiwuxia",
      "type": "http",
      "url": "http://127.0.0.1:8999/aichat/mcp",
      "token": "mcp_xxxxxxxx",
      "prefix": "wx_",
      "connectTimeoutMs": 10000,
      "readTimeoutMs": 60000
    },
    {
      "name": "filesystem",
      "type": "stdio",
      "command": ["npx", "-y", "@modelcontextprotocol/server-filesystem", "I:\\data"],
      "env": { "npm_config_cache": "I:\\cache" },
      "cwd": "I:\\data",
      "prefix": "fs_",
      "timeoutMs": 60000
    }
  ]
}
```

| 字段 | 适用 | 说明 |
| --- | --- | --- |
| `name` | 全部 | 客户端名称，用于日志与默认前缀 |
| `type` | 全部 | `http`（默认）或 `stdio` |
| `enabled` | 全部 | 默认 `true` |
| `prefix` | 全部 | 工具名前缀，默认 `name + "_"` |
| `url` / `token` / `headers` | http | 端点、Bearer 令牌、附加请求头 |
| `command` / `env` / `cwd` | stdio | 启动命令（数组）、环境变量、工作目录 |
| `connectTimeoutMs` / `readTimeoutMs` | http | 超时，默认 10s / 60s |
| `timeoutMs` | stdio | 单次请求超时，默认 60s |

### 3.2 工作方式

`AIChatService` 启动时在后台线程连接所有服务端：

1. `initialize` 握手（协商版本、发送 `notifications/initialized`）；
2. `tools/list` 拉取工具（自动跟随分页游标）；
3. `McpToolBridge` 把每个远程工具转换为 `ToolData`：名称加前缀并清洗、参数说明取自 `inputSchema`；
4. 汇总后注册到 Web 聊天会话（当前在线会话与之后新建/加载的会话都会带上这些工具）。

> NapCat 群聊等独立创建、不经过 `AIChatService#loadSession` 的会话不会自动注册，
> 需要时可在自己的初始化代码里调用 `state.registerTools(chatService.getMcpClientManager().getTools())`。

工具调用时，本地 `ToolData#tool.run` 把 JSON 文本参数解析为对象，调用远程 `tools/call`，
把返回的文本内容作为工具结果；远程报错时返回带 `MCP工具调用失败：` 的文本，不会中断对话。

连接失败只写日志（`simpleLogger` 标签 `MCP客户端`），不影响主服务；单个服务端失败不影响其他服务端。

### 3.3 代码内使用

```java
McpHttpClient client = new McpHttpClient("remote", "http://127.0.0.1:8999/aichat/mcp", token, null, 10000, 60000);
client.initialize();
List<ToolData> tools = McpToolBridge.toTools(client, "wx_");
session.registerTools(tools);          // AI 即可调用
String text = client.callTool("mcp_echo", new JsonObject());
```

`McpClientManager` 也提供 `reload()` 重新连接、`getTools()` 取当前快照、`addChangeListener()` 监听变化。

---

## 4. 安全与部署建议

- 令牌明文只返回一次，数据库只存 SHA-256；泄露后可在管理页停用或删除。
- 令牌权限等于所属用户：会扣除该用户的额外配额，并可调用该用户可见的工具。
- 管理 API（`/aichat/mcp/token` 等）与本项目其他接口一样**不做登录校验**：
  身份只认请求 URL 中的用户标识，且该参数由外部框架注入，请求体不作为身份来源。
  对外部署时请：
  - 把 `/aichat/mcp/token`、`/aichat/mcp/usage` 等管理路径限制为内网/本机；
  - 或只监听 `127.0.0.1`，由反向代理转发、注入 URL 参数并添加鉴权；
  - 生产环境使用 HTTPS（令牌以 Bearer 明文传输）。
- 建议为对外令牌设置 `quotaLimit` 与有效期，降低泄露风险。

---

## 5. 已知限制

- 服务端只实现 Streamable HTTP 的 POST 通道，不支持旧版 HTTP+SSE 的 `GET /sse` 长连接；
- `ToolData` 的参数模型只有“字符串参数 + 全部必填”，因此 MCP 工具的 `inputSchema` 会被简化；
- `exposeSessionTools` 默认关闭；即使开启，也只覆盖 WebSocket 聊天会话，且可能带来副作用（如发图、发消息）；
- 工具内部费用无法在调用前预知：固定调用费在调用前校验余额，内部费用只能等工具执行完成后按实际用量补扣，
  因此内部费用可能超过调用前的余额或令牌额度；
- 旧的单工具价格表 `mcp_tool_price` 与 `/aichat/mcp/price` 接口已不再使用，计费统一为“固定 100 + 内部用量”；
- 客户端工具自动注册到 Web 聊天会话，NapCat 群聊等独立会话需手动注册（见 3.2）；
- 客户端只在启动时连接一次，修改 `mcp-client.json` 后需重启；代码内可调用 `getMcpClientManager().reload()` 热重载；
- `paid_usage_record` 与 `daily_usage` 依赖主服务已建表；独立使用本模块时需自行准备这两张表。

---

## 6. 验证

`mvn clean package` 可编译。已通过两组临时端到端测试（未提交进仓库）：

- 服务端 + HTTP/stdio 客户端 + 令牌 + 计费断言：初始化/列表/调用、固定 100 调用费、
  工具内部用量合计、管理 API、令牌额度、余额不足、失败退款、吊销、批量请求、stdio 客户端、ToolData 桥接；
- `AIChatService` 集成测试：`/aichat/mcp` 由服务内方法转发、令牌扣费写入主库、管理 API 可达。
