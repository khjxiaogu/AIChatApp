# Harness MCP：把 DeepSeek Harness 暴露给外部 AI

`/aichat/mcp` 新增一组 `harness_*` 工具，让外部 MCP 客户端（Claude、Cursor、自建 AI 等）
可以像“监工”一样使用 DeepSeek Harness（DSH）：

- 列出配置的工作区；
- 列出工作区里的聊天；
- 新建聊天；
- 向聊天投递任务；
- 读取聊天最新输出与思维链；
- 接收并回答 DSH 侧的提问（`ask_user_question`、计划审核）；
- 批准或拒绝 DSH 侧的工具调用审批。

实现方式参考 `DeepseekHarnessConnector`：由本程序用 `--patch` 注入
`src/main/resources/dsh/harness-mcp-bridge.mjs`，拉起一个**独立的无头 DSH web 实例**，
再通过本机回环 TCP（`HarnessBridge`）通信。它不会接管或修改现有桌面 DSH 的界面。

## 启用

在 `save/mcp.json` 中加入 `harness` 段：

```json
{
  "enabled": true,
  "harness": {
    "enabled": true,
    "workspaces": [
      "D:\\projects\\AIWuxia",
      "D:\\work\\another-project"
    ],
    "provider": "deepseek-account",
    "model": "deepseek-flash",
    "reasoning": "high",
    "agentPreset": "standard",
    "approval": "ask",
    "approvalTimeoutMs": 120000,
    "questionTimeoutMs": 120000
  }
}
```

也可以单独放一个 `save/harness-mcp.json`（内容就是 `harness` 段本身），这样不用和
MCP 服务端的其它配置混在一起。没有 `harness` 段且没有 `-Ddsh_mcp_enabled=true` 时，
这组工具不会注册，也不会有额外进程。

配置优先级：`-D` 系统属性 > 配置文件 > 默认值。

### 常用系统属性

| 属性 | 默认值 | 含义 |
|---|---|---|
| `dsh_mcp_enabled` | `false` | 没有配置文件时用它强制启用 |
| `dsh_mcp_workspaces` | 空 | 多个工作区路径，用 `;` 或 `,` 分隔 |
| `dsh_mcp_data` | `save/harness-mcp` | patch、插件、心跳文件目录 |
| `dsh_home` | `用户目录/.dsh` | DSH_HOME（会话与工作区注册表） |
| `dsh_root` | 自动探测 | DeepSeek Harness 安装目录 |
| `dsh_launcher` | 自动探测 | `DeepSeek Harness.exe` 路径 |
| `dsh_cli` | 自动探测 | `dsh-desktop-host/lib/cli.js` 路径 |
| `dsh_profile` | `web` | 运行的 profile；配成 `sdk` 会自动回退到 `web` |
| `dsh_provider` | `deepseek-account` | 模型提供方 |
| `dsh_model` | `deepseek-flash` | 模型 id |
| `dsh_reasoning` | `high` | 推理强度，留空不指定 |
| `dsh_agent_preset` | `standard` | 挂工具/提示词的 agent preset |
| `dsh_approval` | `ask` | `ask` 交给外部 AI，`allow`/`deny` 无人值守，`off` 交给 GUI |

复用了连接器的 `dsh_*` 属性，已有 QQ 连接器配置的部署可以直接沿用。

## 工具

| 工具 | 作用 |
|---|---|
| `harness_workspaces` | 列出配置的工作区、是否存在、聊天数量 |
| `harness_chats` | 列出聊天；可选 `workspace`、`limit` |
| `harness_new_chat` | 新建聊天；可选 `workspace`、`title`、`task` |
| `harness_send` | 向 `chat_id` 投递任务；可选 `wait_seconds` |
| `harness_latest` | 读取最新输出/思维链；`since` 传上次的 `cursor` |
| `harness_pending` | 列出待回答的提问与审批任务；可选 `wait_seconds` 长轮询 |
| `harness_feedback` | 回答提问或批准/拒绝审批 |

### 推荐工作流

1. `harness_workspaces` → 选一个工作区；
2. `harness_chats` 找已有聊天，或 `harness_new_chat` 建一个；
3. `harness_send` 投递任务；
4. 反复 `harness_latest`：
   - 第一次不传 `since`，拿到最近内容和 `cursor`；
   - 之后把 `cursor` 作为 `since` 传回去；
   - 没有新内容时只返回 `未变更`，不会重复灌入整段聊天；
   - 返回值出现 `pending` 时，说明 DSH 在提问或申请工具权限，用
     `harness_feedback` 传 `task_id` 处理。
5. 想长轮询时把 `wait_seconds` 设为 1-25。

`harness_latest` 的 `items` 里，`role` 可能是：

- `user`：真实用户/任务输入（DSH 注入的 system-reminder 会被过滤）；
- `assistant`：正文 `text`、思维链 `reasoning`、工具调用 `tools`；
- `tool`：工具结果；
- `system`：非正常结束的 `turn/end` 原因。

### 提问与审批

- DSH 的 `user-questions/request` 瀑布被插件以 `prepend` 方式拦截，问题会变成
  `kind=question` 的待办；外部 AI 用 `harness_feedback` 返回
  `{answers:[{id, selected, custom}]}`。
- DSH 的 `approval/request` 同样被拦截，变成 `kind=approval` 的待办；
  `harness_feedback` 的 `decision=allow/deny` 会回传为 `allowed-once` / `rejected`。
- 外部 AI 一直不回答时，插件超时后调用 `next()`，把问题/审批交回 DSH 自己的 GUI。

## 注意事项

- MCP 会拉起一个独立的 `--profile web --no-open --port 0` DSH 实例，首次启动需要
  十几秒到一分钟；工具会返回“正在启动，请稍后重试”。它和桌面 DSH 共享
  `DSH_HOME`，因此能看到同样的会话与工作区记录。
- 同一个聊天如果正被桌面 DSH 打开，DSH 的会话写锁可能让投递失败；错误会原样返回。
  由 MCP 新建的聊天不会和桌面实例抢同一个会话。
- 如果本程序本身运行在 DSH 文件沙箱里（例如作为 DSH 的子进程），DSH 子进程可能
  无法写 `~/.dsh/profiles/web/cordis.yml`。此时用 `dsh_home` 指向一个可写目录，
  或让本程序在沙箱外运行。
- 这组工具和普通 MCP 工具一样，每次调用固定扣除 `McpServer.CALL_COST` 额外配额；
  内部不产生模型用量（模型用量发生在 DSH 自己的实例里）。
