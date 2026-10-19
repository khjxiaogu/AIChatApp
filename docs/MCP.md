# MCP 客户端 / 服务端与访问令牌

本文说明 AIWuxia 新增的 MCP（Model Context Protocol）能力：

- **服务端**：把本地 `ToolData` 以标准 MCP 工具形式暴露给外部客户端（HTTP Streamable 传输）；
- **客户端**：连接外部 MCP 服务端（HTTP 或 stdio），把远程工具转换为本地 `ToolData` 注册进会话；
- **访问令牌**：用户自助创建令牌，调用工具时从令牌所属用户的**额外配额（补充配额）**中扣费，并记录消费明细。

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
│  └─ 计费：预扣 → 执行 → 成功记账 / 失败退款   │
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
  "defaultPrice": 1000,
  "usageType": "MCP工具",
  "exposeSessionTools": false
}
```

| 字段 | 说明 |
| --- | --- |
| `enabled` | 服务端开关；`false` 时不转发 `/aichat/mcp`，也不初始化令牌存储（客户端不受影响） |
| `defaultPrice` | 未单独定价的工具每次调用扣除的额外配额，默认 `1000` |
| `usageType` | 写入 `paid_usage_record.usage_type` 的用量类型，默认 `MCP工具` |
| `exposeSessionTools` | 是否把令牌所属用户**在线会话**中的工具一并暴露（默认关闭） |

服务端内置两个自检工具（不依赖会话状态）：

- `mcp_echo`：参数 `text`，原样返回；
- `mcp_server_time`：无参数，返回服务端当前时间。

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
        .putParam("keyword", "关键词")
        .tool(arguments -> {
            // arguments 是 JSON 文本，例如 {"keyword":"xxx"}
            return "结果";
        })
        .build());

// 也可以整批注册一个 MCP 模块
registry.register(FetchMcp.create(session, tos));
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

### 2.4 访问令牌

令牌由用户自助创建，明文只在创建时返回一次，数据库只保存 SHA-256 摘要，因此无法从库里还原明文。

表结构（均创建在 `messages.db`）：

```sql
mcp_access_token(id, uid, name, token_hash, token_prefix,
                 quota_limit, quota_used, call_count, enabled,
                 created_at, expires_at, last_used_at)
mcp_tool_call_log(id, token_id, uid, tool, cost, ok, error,
                  request_id, client_ip, duration_ms, result_chars, created_at)
mcp_tool_price(tool PRIMARY KEY, cost)
```

- `quota_limit`：单个令牌累计可消耗的额外配额上限，`0` 表示不限制；
- `expires_at`：毫秒时间戳，空表示永不过期；
- `mcp_tool_call_log`：每一次调用（成功与失败）都有记录，包括工具、费用、耗时、结果长度、客户端 IP 与失败原因。

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
| `GET` | `/aichat/mcp/price` | 默认价与单工具价格 |
| `POST` | `/aichat/mcp/price` | 设置价格 `{tool,cost}`，`cost<0` 表示删除 |
| `GET` | `/aichat/mcp/status` | 统计信息 |
| `GET` | `/aichat/mcp/tools?uid=` | 当前暴露的工具列表 |
| `GET` | `/aichat/mcp/whoami` | 用令牌自检（需要 `Authorization`） |

### 2.5 计费规则

价格单位与配额单位一致（token，等同于“补充配额”）。一次 `tools/call` 的执行顺序：

1. 校验令牌（启用、未过期）；
2. 条件更新 `user_paid_balance`：`balance = balance - price WHERE balance >= price`。
   余额不足直接返回 JSON-RPC `-32002`，不执行工具；
3. 条件更新令牌 `quota_used`（校验 `quota_limit`）；超限则退回余额并返回 `-32002`；
4. 写入一条 `mcp_tool_call_log`（待完成）；
5. 执行工具：
   - 成功：写入 `daily_usage.paid_used` 与 `paid_usage_record`（`usage_type = MCP工具`），
     记录标记为成功，返回文本结果；
   - 失败：退回已扣余额与令牌额度，结果带 `isError: true` 返回给客户端，日志记录失败原因。

因此：

- 扣的是**用户额外配额**（`user_paid_balance`，界面上的“补充配额”），不占用每日免费额度；
- 用户消费明细会出现在原有的用量统计中；
- 失败不扣费；
- 并发调用通过条件更新保证不会把余额扣成负数。

单工具价格可通过 API 或数据库设置，优先级高于 `defaultPrice`：

```bash
curl -X POST http://127.0.0.1:8999/aichat/mcp/price \
  -H 'Content-Type: application/json' \
  -d '{"tool":"sdxl_gen_image","cost":5000}'
```

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

# 4) 调用工具（按 mcp_echo 价格扣费）
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
    "_meta": { "price": 1000, "paidBalance": 49000 }
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
MCPTools tools = McpToolBridge.toTools(client, "wx_");
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
- 客户端工具自动注册到 Web 聊天会话，NapCat 群聊等独立会话需手动注册（见 3.2）；
- 客户端只在启动时连接一次，修改 `mcp-client.json` 后需重启；代码内可调用 `getMcpClientManager().reload()` 热重载；
- `paid_usage_record` 与 `daily_usage` 依赖主服务已建表；独立使用本模块时需自行准备这两张表。

---

## 6. 验证

`mvn clean package` 可编译。已通过两组临时端到端测试（未提交进仓库）：

- 服务端 + HTTP/stdio 客户端 + 令牌 + 计费 41 项断言：初始化/列表/调用、按价扣费、价格覆盖、
  管理 API、令牌额度、余额不足、失败退款、吊销、批量请求、stdio 客户端、ToolData 桥接；
- `AIChatService` 集成测试：`/aichat/mcp` 由服务内方法转发、令牌扣费写入主库、管理 API 可达。
