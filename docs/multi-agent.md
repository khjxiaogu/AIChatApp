# 多 agent 架构

在聊天控制器 `NapCatAIConnector` 之上做的一层路由：一个公屏总览 agent（按公开群）+ 每个私聊用户一个私人 agent。
公屏和私聊都走“显式发送”工具，正文不会自动发出。控制器通过 `ChatAdapter` 适配不同平台，
因此本架构同时支持 NapCat（QQ 数字账号）和 QQ 官方机器人（`openid`），差异见下方“平台支持”。

## 路由规则

| 输入 | 处理 |
|---|---|
| 配置里的公开群消息 | 追加到**所有 agent** 的缓冲区；触发该群的公屏 agent（`send_group` 回复） |
| 配置里的私聊用户消息 | 只追加并触发该用户的私人 agent（`send_private` 回复） |
| 未被配置接管的私聊 | 按 `send_private` / `handover_private` 留下的路由或 `privateWhitelist` 兜底触发（10 秒防抖） |
| 未配置的群消息 | 交回 `NapCatAIConnector` 的公屏逻辑（不会进入多 agent 路由） |

> 私聊接管（路由表、白名单、防抖、`send_private` / `handover_private` 工具）只在本架构里存在；
> `NapCatAIConnector` 本身只做公屏，私聊消息只用于审批与询问答复。

- 公屏 agent 每个公开群一个，但会看到**所有公开群**的公屏内容，用于总览。
- 私人 agent 每个用户一个独立会话；也能看到全部公屏内容，但看不到其他用户的私聊。
- 私人 agent 默认审核人 = 对应用户；公屏 agent 默认审核人 = 配置的 `adminId`。

## 平台支持

| 平台 | `botId` | 公屏 `groupId` | 私聊 `userId` / `adminId` | 备注 |
|---|---|---|---|---|
| NapCat / OneBot | 机器人 QQ 号 | QQ 群号 | QQ 号 | 可收到群内全部消息 |
| QQ 官方机器人 | `AppID` | `group_openid` | `user_openid` | 默认只收到 `@机器人` 消息；公屏总览需要平台开通“接收所有消息”（`GROUP_MESSAGE_CREATE`） |

- 官方模式下 `groupId` 和 `userId` 都是**每个 AppID 独立**的 `openid`，换机器人要重取。
- 官方模式建议一个 AppID 只跑一份多 agent 实例；不同实例用各自 `save/` 工作目录。
- 官方 C2C 私聊被动回复窗口 60 分钟、每条消息最多 4 次；超出后按主动消息发送。
- Codex 审批同样按字符串账号匹配：NapCat 填 QQ 号，官方机器人填 `user_openid`；`codex_review_approval` 的 `reviewer` 也接受 openid。其余工具（fetch/multimodal/music/cron 等）不受影响。

## 配置

`save/config/multi.json`：

```json
{
  "enabled": true,
  "botId": "123456",
  "app": "frostedheartirc",
  "promptDir": "multi-prompts",
  "defaultTools": ["fetch", "multimodal", "qq", "knowledge"],
  "defaultCodex": {
    "cwd": "D:\\projects\\frostedheart-newdev",
    "sandbox": "workspace-write",
    "approval": "ask",
    "networkAccess": true,
    "project": "Frostedheart",
    "experimental": true
  },
  "publicSessions": [
    {
      "groupId": "789012",
      "enabled": true,
      "adminId": ["345678"],
      "extraTools": ["codex"]
    }
  ],
  "privateSessions": [
    {
      "userId": "345678",
      "enabled": true,
      "extraTools": ["codex"],
      "codex": { "cwd": "D:\\projects\\my-project" },
      "promptFile": "users/345678.txt"
    }
  ],
  "napcatUrl": "ws://127.0.0.1:3001",
  "napcatToken": "",
  "qqbotAppId": "",
  "qqbotSecret": ""
}
```

> 二选一：填 `napcatUrl`/`napcatToken` 走 NapCat；填 `qqbotAppId`/`qqbotSecret` 走 QQ 官方机器人。
> 也可以不写，改用启动参数 `-Dnapcat_url`/`-Dnapcat_token` 或 `-Dqqbot_appid`/`-Dqqbot_secret`。

| 字段 | 说明 |
|---|---|
| `botId` | 机器人自身 id |
| `app` | 复用现有 app 目录名（`save/apps/<app>` 且必须有 `meta.json`），例如 `frostedheartirc`、`fengxiirc`；不会自动创建新类型 |
| `promptDir` | 提示词目录，相对 `save/config/`，默认 `multi-prompts` |
| `defaultTools` | 默认工具，每个会话都会带上；工具最终列表 = `defaultTools + extraTools` |
| `defaultCodex` | 默认 Codex 配置；会话的 `codex` 覆盖同名字段 |
| `autoCreatePrivate` | 收到未配置用户的私聊时是否自动创建私人会话；默认 `false`，未配置用户不会创建会话 |
| `publicSessions` | 公屏会话数组，每项像 `agents.json` 的一项，可单独配置 `groupId`、`enabled`、`adminId`、`extraTools`、`codex`、`name`/`save`、`promptFile` |
| `privateSessions` | 私聊会话数组，每项可单独配置 `userId`、`enabled`、`adminId`、`extraTools`、`codex`、`name`/`save`、`promptFile`；`adminId` 默认就是该用户 |
| `napcatUrl` / `napcatToken` | NapCat WebSocket 地址/token；为空时用 `-Dnapcat_url` / `-Dnapcat_token` |
| `qqbotAppId` / `qqbotSecret` | QQ 官方机器人 AppID/AppSecret；为空时用 `-Dqqbot_appid` / `-Dqqbot_secret`。配了就走官方适配器 |

会话公共字段：

| 字段 | 说明 |
|---|---|
| `enabled` | 是否启用该会话，默认 `true` |
| `extraTools` | 在 `defaultTools` 基础上追加的工具；例如 `["codex"]`、`["knowledge"]` |
| `codex` | 该会话的 Codex 配置，覆盖 `defaultCodex` 同名字段（cwd/project/sandbox 等） |
| `adminId` | 公屏审核人（单值或数组）；私聊不写时用该用户 |
| `contextGroups` | 除自身群外还要接收哪些群的消息；公屏自己的群始终包含，私聊按此配置接收 |
| `handoverTargets` | 可以把信息交给哪些 agent；`["*"]` 表示任意可接手 agent，空数组表示不能向外移交 |
| `mentionOnly` | 公屏会话是否只有被 @ 才触发回复；`true` 时普通消息只记上下文、不触发 AI |
| `name` / `save` | 会话名和存档名；不写自动生成 |
| `promptFile` | 提示词文件，相对 `promptDir`；不写则用默认文件 |

## 群上下文

- 每个公开群只在内存里保留最近 **200** 条消息，供 `group_history` 按需查询。
- 一个会话能看到：自己的公屏群（公屏 agent）+ `contextGroups` 里配置的群。
- 公屏 agent 的默认上下文只保留该群最近 **10** 条消息；私聊按 `contextGroups` 接收，默认也只留最近 10 条。
- 未配置的用户第一次私聊时，默认**不会**创建私人会话；只有显式 `autoCreatePrivate=true` 才会自动创建：
  名称 `multi-private-<用户id>`，沿用默认工具、`defaultCodex`、第一个私聊模板的 `contextGroups`，
  审核人默认是该用户；`handover_private` 只对已配置用户确保会话，不会为未配置用户建会话。
- 没有配置在 `contextGroups` 里的群：消息不会进入该会话，`group_history` 也会拒绝查询。
- 当前示例：所有私聊只配置第一个群；第二个群独立，不进入任何其他会话，也不把消息附加给别处。
- 群消息有更新时，注入的消息开头会带：
  ```xml
  <group-update group_id="群id" name="群名称" />
  ```
- 每条群消息的格式是 `<message senderName="..." senderId="..." id="...">正文</message>`：
  `senderId` 是发送者账号（NapCat 为 QQ 号，QQ 官方为群成员 `member_openid`），
  `id` 是消息 id；`group_history` 的结果同样带 `名字(账号)：内容`。
- 私人 agent 的提示词要求：除非必要，不要在私聊里查询群上下文。

## 公屏防抖

- 被 @ 时先等 **2 秒**再统一触发一次，期间再次 @ 不会重复触发；2 秒内的其它消息一起处理。
- 会话配置 `"mentionOnly": true` 时，**只有被 @ 才触发**；普通消息只进入上下文，不启动防抖、不回复。
- 其他群消息按批处理：至少累计 **10 条且间隔 5 秒**，或者距首条满 **20 秒** 才触发一次。
- 触发一次后重新计数；AI 处理的是这一批消息，不逐条回复。
- **提交 AI 请求时**：本群此前的防抖计时全部停止；本轮 `group_history` 只能查到提交时刻之前的内容。
- AI 还没完成时又有新消息：先累积，不启动计时；本轮结束后再从那一刻开始计算防抖。
- 公屏提示词要求：只有被 @、明确提问或与项目直接相关才回复；普通闲聊/无关信息保持沉默。

## 移交限制

- `handoverTargets` 控制该会话能把私聊/信息交给哪些 agent：
  - `["*"]`：任意可接手 agent（例如第一个群的公屏 agent）；
  - `[]` / 不写：不能向其他 agent 移交（例如第二个群的公屏 agent）。
- `list_agents` 和 `handover_private` 都遵守这个限制，只列出/允许被授权的目标。

## 提示词文件

提示词独立放在 `save/config/<promptDir>/`，不写在 JSON 里，方便按用户编辑：

```
save/config/multi-prompts/
├─ common.txt                 # 所有 agent 共用的总提示词（公屏/私聊、不同用户区分）
├─ public.txt                 # 公屏总览 agent 的总 prompt
├─ default.txt                # 私人 agent 的默认 prompt
└─ users/
   ├─ 345678.txt              # 某个用户的专属 prompt（NapCat QQ 号 / 官方 user_openid）
   └─ 10001.txt
```

- `common.txt`：所有 agent 都会先读的总提示词，说明 `<message>` 是公屏、`<private>` 是私聊、
  如何区分不同用户，以及隐私/渠道规则。
- `public.txt`：公屏总览 agent 的行为、发言方式、是否提交 Codex。
- `default.txt`：私人 agent 的默认行为；没有专属文件的用户用它。
- `users/<QQ>.txt`：该用户的专属设定，放称呼、身份、偏好等（**不需要再配 git 邮箱**）；文件存在时覆盖默认。
- 会话可以用 `promptFile` 指向自己的提示词文件；没写时公屏用 `public.txt`，私聊用 `users/<userId>.txt`，再退回 `default.txt`。

> 完整的 `common.txt + 角色/用户 prompt` 会在会话开始时写入一条 **系统消息**（持久化到历史），
> 之后每轮用户提示词里只带一句「先分清公屏/私聊和不同用户身份」的短提醒，避免每轮重复大段设定。

> 默认**不会**因为存在 `save/config/multi.json` 就自动开启，需要显式指定：
> `-Dmulti_agent=true` 或命令行 `--multi-agent`，并且配置里 `"enabled": true`。
> 使用 QQ 官方机器人时，再提供 `-Dqqbot_appid`/`-Dqqbot_secret`（或写进 `multi.json` 的同名字段）；
> 否则按 `napcatUrl` 走 NapCat。也可以直接运行 `com.khjxiaogu.aiwuxia.multi.MultiAgentConnector`。

## 图片与媒体

- 入站消息里的图片/媒体会先用适配器下载，再保存到对象存储（`ObjectStorageProvider`），
  消息正文写成 `<image id="..."/>`；同一 URL 只下载/上传一次（公屏广播给多个 agent 时复用）。
- 私聊 agent 的 `groupId` 内部是 `private:<用户id>`。工具产生的图片/视频/语音会通过
  `sendPrivateImage` / `sendPrivateVideo` / `sendPrivateRecord` 发送，避免把 `private:<用户id>`
  当成群 id 去走群上传/群消息接口（这是之前私聊看不到图片的原因）。
- 下载失败时退回「（图片）」占位文字，不影响文本处理。
- 缓冲区 JSONL 里保存的是图片 id，重启回放后仍可按需通过对象存储读取。

## 缓冲区与持久化

- 公屏、私聊缓冲区**默认无上限**：消息全部保留在会话队列里，不会因为超过条数被丢弃。
- 每条进入缓冲区的消息立即追加到 `save/multi/buffers/<public-群id|private-用户id>.jsonl`，
  重启时自动回放，便于序列化/恢复。
- 一轮 AI 取走缓冲区后，对应的 `.jsonl` 会被清空（已消费），不会重复回放。
- 缓冲区文件格式：每行 `{"id": "...", "text": "...", "time": 1730000000000}`。

## 发送与审核

- 公屏 agent 注册 `send_group`，并可注册 `list_agents` / `handover_private`（按 `handoverTargets` 限制）、
  `group_history`、`ask_user`；私聊 agent 注册 `send_private` 和上述查询/询问/移交工具。
  公屏 agent 不会注册 `send_private`，不会把私聊内容直接发到群里。
- 私聊 agent 的 `adminId` 写成该用户（NapCat QQ 号或官方 `user_openid`），人工审核/Codex 审批默认发给本人。
- 公屏 agent 的 `adminId` 写成配置的 `adminId`；如果配置成数组，审批会推送给每个管理员，
  任意一个先回复即生效。
- 每个私聊有独立的输出闸门（`private:<机器人>:<用户>`），同一个用户的连续回复按顺序发出。
- 私人 agent 有独立个人知识库 `save/multi/knowledge/<账号>/`，可用 `kb_write` 写入、`kb_search`/`kb_read` 读回。
- 交接前可用 `list_agents` 列举可联络的 agent；结果会带当前用户在来源群的群名片
  （通过适配器的 `getGroupMemberName` 查询），方便确认是谁。
- 需要用户确认/二选一时用 `ask_user`：发送适配器询问卡片（不支持时回退文字），
  用户点按钮或直接回复后，结果作为异步任务返回并触发下一轮 AI。
- 任意 agent 用 `send_group` 向公屏发消息后，这条消息会被追加到**其他所有 agent** 的记录/缓冲区，
  保证公屏总览和其他私人 agent 都知道群里说过什么（只记录，不额外触发 AI）。
- 私聊 agent 往公屏发消息时，正文会自动加 `用户名` 的署名：`张三的agent：内容`
  （用户名取私聊昵称/群名片，取不到时退回账号 id）。其他 agent 的上下文里这条消息以
  `senderName="张三的agent"` 表示，不会重复前缀；公屏 agent 的发言不加署名。
- 所有会话都可注册 `recall_message`：不传 `message_id` 时撤回本会话最近一条，`count` 可撤回最近
  1–5 条；只允许撤回本会话自己发出的消息。QQ 官方限制发出后 2 分钟内，超时会返回接口错误。

## 限制

- 图片/媒体在入站时同步下载并上传对象存储；大文件会占用协议读线程，后续可以改成异步队列。
- 每个会话仍是 `AIGroupSession`，`groupId` 对私聊使用 `private:<用户>` 作为内部键，
  不影响实际发送目标。
- 群消息撤回仍走原有 `handleGroupRecall`，会按 `messageId` 从各会话缓冲区删除；QQ 官方 v2 不推送撤回事件，
  官方模式下撤回不会从缓冲区移除。
