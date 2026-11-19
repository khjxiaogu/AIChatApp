# QQ 官方机器人适配器（API v2）

`com.khjxiaogu.aiwuxia.chat.QqOfficialAdapter` 按
[QQ 机器人官方文档 API v2](https://bot.q.qq.com/wiki/develop/api-v2/) 实现，把 QQ 官方机器人接入
通用控制器 `NapCatAIConnector`。它和 `NapcatAdapter`（OneBot v11）实现同一个 `ChatAdapter`
接口，切换时业务代码不需要改。

## 1. 平台准备

1. 在 [QQ 开放平台](https://q.qq.com/) 创建机器人，拿到 **AppID** 和 **AppSecret**。
2. 开通群聊能力并把机器人加入群。
3. 在群里 `@机器人` 发一条消息。启动日志会打印：

   ```
   [QQ官方] 收到群消息：B2C3D4E5F6... / 发送者 A1B2C3D4E5... / 你好
   ```

   第一个 32 位字符串就是 `group_openid`，填到 agent 配置的 `groupId`；`发送者` 后面的是
   群成员 `member_openid`（群内有效，和单聊的 `user_openid` 不是同一个）。
4. 让管理员私聊机器人一次。日志会打印：

   ```
   [QQ官方] 收到私聊消息：A1B2C3D4E5... / 允许
   ```

   这就是管理员的 `user_openid`，填到 `adminId`（工具/Codex 审批用）；如果还要用群里的
   `##清理上下文` 管理员指令，把该管理员在群里的 `member_openid` 也加进 `adminId` 数组。

> openid 是“每个 AppID 独立”的，换机器人后群/用户 openid 都会变，需要重新获取。

### 用采集器自动收集 openid

`QqOpenIdCollector` 可以独立运行，不启动 AI/控制器，只连接网关把事件里的 openid 收集成 JSON：

```bash
java -Dqqbot_appid=102xxxxxx -Dqqbot_secret=xxxxx \
     -cp "target/AIWuxia-0.0.1-SNAPSHOT.jar;target/aiwx_lib/*" \
     com.khjxiaogu.aiwuxia.chat.QqOpenIdCollector
```

- 在群里 @机器人、私聊机器人、或重新把机器人拉进群，都会记录；
- 结果默认写到 `save/config/openid-directory.json`，包含
  `groups.<group_openid>.members.<member_openid>` 与 `users.<user_openid>`；
- 按 Ctrl+C 结束时保存；下次运行会合并已有结果；
- 也可用 `--appid=... --secret=... --out=... --data=save` 显式传参。

采集后直接对照填写：

| 配置字段 | JSON 来源 |
| --- | --- |
| 公屏 `publicSessions[].groupId` | `groups` 的 key |
| 私聊 `privateSessions[].userId` / `adminId` | `users` 的 key |
| 群内管理员 `member_openid` | `groups.<gid>.members` 的 key |

> 该类也可以在自己的代码里 `new QqOpenIdCollector(outFile)`，作为 `ChatEventHandler`
> 挂到一个独立的 `QqOfficialAdapter` 上使用。

## 2. 配置

`save/config/agents.json` 里每个 agent 增加/修改以下字段：

```json
[
  {
    "name": "wuxia",
    "save": "wuxia",
    "groupId": "B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4E5",
    "botId": "102xxxxxx",
    "adminId": "A1B2C3D4E5F6A1B2C3D4E5F6A1B2C3D4",
    "autoReply": {
      "enabled": true
    }
  }
]
```

| 字段 | 说明 |
| --- | --- |
| `groupId` | QQ 群 `group_openid`（不是群号） |
| `botId` | 机器人 `AppID`，工具审核按它匹配 |
| `adminId` | 管理员 `user_openid`，可写成数组；也支持 `-DadminId=...`，多个用逗号分隔 |
| `autoReply` | 可选；全量群消息模式下让模型判断是否需要主动发言 |

其余字段（`hasReaonser`、`hasPrice`、`tools`、`emojis` 等）与 NapCat 下完全一致。

## 3. 运行

```bash
java -Dqqbot_appid=102xxxxxx -Dqqbot_secret=xxxxxxxx \
     -Ddeepseektoken=TOKEN \
     -jar target/AIWuxia-0.0.1-SNAPSHOT.jar
```

指定 `-Dqqbot_appid` 时使用官方适配器；否则仍按 `-Dnapcat_url` 使用 NapCat。

### 系统属性

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `qqbot_appid` | 空 | 设置为非空即启用官方适配器 |
| `qqbot_secret` | 空 | AppSecret |
| `qqbot_api_base` | `https://api.bot.qq.com` | OpenAPI 根地址，可指向代理/沙箱 |
| `qqbot_token_url` | `{api_base}/app/getAppAccessToken` | 获取 access_token 的地址 |
| `qqbot_intents` | `(1 << 25) \| (1 << 26)` | 事件订阅位，默认群/单聊消息 + 互动事件；自定义时必须包含 `1 << 26` 才能收到按钮点击 |

## 4. 能力与限制

| 能力 | 支持情况 |
| --- | --- |
| 群 `@机器人` 消息 `GROUP_AT_MESSAGE_CREATE` | ✅ |
| 全量群消息 `GROUP_MESSAGE_CREATE` | ✅（需平台开通“接收所有消息”） |
| 单聊消息 `C2C_MESSAGE_CREATE` | ✅（工具审核/私聊通知，也可作为私人 agent） |
| 文本、图片、视频、语音、文件 | ✅（富媒体先上传再发） |
| 表情回应 | ❌ 官方 API v2 没有该接口，`react()` 为空实现 |
| 消息撤回事件 | ❌ 官方 v2 不推送，控制器不会从待处理缓冲区移除 |
| 一对一私聊 agent 模式 | ✅（账号用 `user_openid`；Codex 审批也已支持 openid） |
| 多 agent 架构（`--multi-agent`） | ✅（公屏 `group_openid` + 私聊 `user_openid`，见 [multi-agent.md](multi-agent.md)） |
| 引用机器人消息触发回复 | ✅ 通过发送响应的 `ext_info.ref_idx` 与入站 `ref_msg_idx` 匹配 |
| 用户昵称 / 群成员名片 / 头像 | ✅ 事件里出现过的会缓存；未命中时尝试群成员信息接口（返回 `username`/`avatar`，接口需白名单，11253 表示无权限） |
| 群头像 | ✅ 事件/群信息接口有 `avatar` 时使用；群信息接口同样需要白名单 |
| 审核卡片（消息按钮） | ✅ 工具审核与 Codex 审批私聊优先发带「允许/拒绝」按钮的 Markdown 卡片；自定义按钮需平台内邀开通，发送失败自动退回文本 |
| 撤回机器人自己的消息 | ✅ `DELETE /v2/{groups\|users}/{openid}/messages/{message_id}`；官方限制发出后 **2 分钟**内 |

发送规则由适配器自动处理：

- 收到消息后 5 分钟内（单聊 60 分钟）优先作为**被动回复**，自动带上 `msg_id` 并递增 `msg_seq`；
  群聊每条消息最多回复 5 次，单聊 4 次。超过后按**主动消息**发送，可能被用户设置拒绝。
- `msg_seq` 在发送调用时预留，保证图片、说明文字与正文的顺序稳定。
- 媒体直传（`file_data`，base64）上限 5MB；超过时如果配置了 `save/config/object-storage.json`
  （远程 TOS），适配器会先把媒体转存对象存储，再用 `getHttpUrl` 返回的公网地址发送；没有远程
  存储时请直接传 http(s) 链接。图片/视频/语音按类型上传失败时会降级为普通文件发送。
- 语音：群聊支持 silk/mp3/wav，单聊仅 silk；不合适时降级为文件。
- 私聊图片/视频/语音必须走 `/v2/users/{user_openid}/files` 与 `/v2/users/{user_openid}/messages`；
  多 agent 私聊会话内部用 `private:<userid>` 作为 groupId，发送工具附件时会改用
  `sendPrivateImage`/`sendPrivateVideo`/`sendPrivateRecord`，不会把该内部 id 当群 id。

### @ 机器人的判定

- `GROUP_AT_MESSAGE_CREATE` 事件直接算“@ 了本机器人”：平台按 AppID 投递，天然只发给被 @ 的那台；
- 全量模式下的 `GROUP_MESSAGE_CREATE` 也可能出现在 @ 场景，此时看 `mentions` 里的
  `bot: true` 条目。官方给的是 openid，**不一定等于 AppID**，所以适配器只标记“@ 了机器人”，
  由 `NapCatAIConnector` 按事件的 `self_id` 归属：
  - 配置的 `botId` 等于 `self_id`：只触发这台机器人的会话，其它 botId 的会话跳过；
  - 对不上（配置写的是别的 id 形态）：无法精确归属，按本群所有会话处理，保证 @ 一定有响应；
  - 平台既不给 AT 事件、`mentions` 里也没有 `bot: true` 时无法识别，只能按正文 `@角色名` 或
    普通消息的主动回复判定处理。
- 同群存在多个机器人且都开通了全量消息时，别人 @ 另一个机器人也可能命中 `bot: true`；
  把各自的 `botId` 配成自己的 AppID 可以把影响限制在当前机器人自己的会话上。

### 用户资料与头像缓存

适配器提供统一的资料查询（NapCat 与 QQ 官方都实现）：

```java
adapter.getUserName(userId);                       // 全局昵称
adapter.getGroupMemberName(groupId, memberId);     // 群名片优先，退化昵称
adapter.getDisplayName(userId, groupId);           // 群内显示名优先，再全局昵称
adapter.getUserAvatar(userId);                     // 头像文件 byte[]
adapter.getGroupAvatar(groupId);                   // 群头像文件 byte[]
```

缓存与来源：

- 收到消息时就把 `author.username` / `author.avatar` 写入缓存；QQ 官方群成员信息接口还会返回
  `username` 和 `avatar`，未命中缓存时按需查询，**该接口只有白名单机器人可用**（错误码 11253，
  适配器会自动停止重试并退回缓存）。
- NapCat 通过 `get_stranger_info` / `get_group_member_info` 查昵称和群名片，头像用 qlogo 地址。
- 同一用户的头像默认在各群/私聊一致，因此按用户缓存；`union_openid` 可用时用它关联
  `member_openid` 与 `user_openid`。
- 缓存持久化在 `save/config/profile-cache.json`：启动时载入，运行中每 5 秒落盘，适配器关闭时
  再保存一次。QQ 账号/群数量很大时也可以直接删这个文件重建。
- QQ 官方的群成员信息接口**没有群名片字段**，群内显示名即为 `username`。

`QQMcp` 的 `avatar_fetch` / `avatar_group_recognition` 已改为通过适配器获取头像（工具名、参数、
返回值不变），因此 QQ 号与 openid 都能用；旧的直接 qlogo 工厂方法仍然保留以兼容旧调用。

### 审核卡片（消息按钮）

官方机器人下，工具审核和 Codex 审批会优先给管理员发 **Markdown + 按钮** 的审核卡片，而不是只发
一段“回复允许/拒绝”的文本：

- 工具审核按钮：`允许` / `拒绝`，按钮 data 为 `tool:<审核id>:allow|deny`；
- Codex 审批按钮：`允许` / `本会话允许` / `拒绝` / `拒绝并中断`，按钮 data 为
  `codex:<taskId>:1|2|3|4`；
- 管理员点击后，平台推送 `INTERACTION_CREATE`（`type=11`），适配器先在 3 秒内调用
  `PUT /interactions/{id}` 回应平台，再把按钮 data 交给控制器；
- 私聊审核卡片的按钮 `permission` 使用 `type=2`（所有人可操作），避免 `specify_user_ids`
  与 `user_openid` 命名空间不一致导致点击提示“无权限操作”；真正的管理员校验在
  `answerButton` / `answerApproval` 里做，非管理员点击不会生效；
- 控制器路由到 `QqToolApprovalManager.answerButton(...)` 或
  `CodexService.answerApproval(..., taskId)`，只接受该请求配置的管理员；
- 卡片发送失败（例如机器人没有自定义按钮/Markdown 权限、接口报错）会自动退回原来的文本审批。

自定义按钮属于平台“内邀开通”能力；如果账号没有权限，`keyboard.content` 会发送失败并走文本回退，
不会影响审批本身。如果卡片能显示、但点击仍提示“无权限操作”，优先检查机器人是否已开通
**自定义按钮/回调按钮**能力——消息发送和按钮点击在平台侧是两套权限；在开通前，直接回复
`允许`/`拒绝` 或 `1/2/3/4` 仍然可以完成审批。默认 intents 已包含 `INTERACTION (1 << 26)`；
用 `-Dqqbot_intents` 覆盖时记得把这一位加进去。

### 消息撤回

适配器提供：

```java
adapter.supportsRecall();
adapter.recallMessage(groupId, messageId);
adapter.recallPrivateMessage(userId, messageId);
```

官方接口：

- 群：`DELETE /v2/groups/{group_openid}/messages/{message_id}`
- 单聊：`DELETE /v2/users/{user_openid}/messages/{message_id}`
- 只能撤回机器人自己发送的消息；发送超过 **2 分钟**会返回 `40064004`。

控制器/多 agent 会自动记录每个会话最近发出的消息 id，并注册 `recall_message` 工具：

- 不传 `message_id`：撤回本会话最近一条；
- `count`：撤回最近 1–5 条；
- 只允许撤回当前会话自己发出的消息，不能借工具删除用户消息。

## 5. 实现要点

- **鉴权**：`POST {api_base}/app/getAppAccessToken`，`Authorization: QQBot {access_token}`；token 缓存，
  过期前 60 秒刷新。
- **事件**：`GET /gateway` 取 WSS 地址；`OpCode 10 Hello` → `OpCode 2 Identify`
  （`token="QQBot ..."`、`intents`、`shard=[0,1]`）；按 `heartbeat_interval` 发 `OpCode 1`；
  断线后优先 `OpCode 6 Resume`，`OpCode 9` 会话失效时重新 Identify。
- **重连**：`4099`（连接超时/网关要求重连）等瞬断不会退出程序。初次连接和运行中断开都会自动
  退避重连（2 秒起，最长 30 秒）；只有配置/权限类关闭码（4001、4002、4010–4014、4914、4915）
  会停止重试并报错。初次 `connect()` 会一直重试到连接成功（按 Ctrl+C 可终止）。
- **会话过期**：`4006`/`4007`/`4008`/`4009`（如 `4009 Session timed out`）会清空
  `session_id` 和 `seq`，下次重连改用 `Identify`；否则会一直 `Resume` 过期会话，反复 4009。
- **重连线程**：网关调度线程是非 daemon，且 `NapCatAIConnector.main` 会在连接器启动后阻塞主线程，
  避免断线后 JVM 因为只剩 daemon 线程而直接退出，导致 2 秒后的重连任务根本没执行。
- **强制重连**：`op7`、`op9`、心跳死链、`onError` 会主动标记断开并直接安排重连，不依赖
  `onClose` 一定回调；旧连接延迟到达的 close/error 也会被忽略。
- **死链检测**：如果 TCP 还连着但网关已经不发任何数据（心跳没有 ACK、事件也停了），心跳任务会在
  超过 `max(90 秒, 2×心跳间隔+10 秒)` 后主动断开并走重连；可用
  `-Dqqbot_dead_timeout_ms=毫秒` 覆盖这个阈值。重连后会在 30 秒内等待 `READY`/`RESUMED`，
  等不到就关闭连接并继续重试，避免停在“连上了但没鉴权”的半开状态。
- **接收**：正文、`mentions`、`msg_elements`、`attachments`、`message_scene.ext` 统一成
  `IncomingMessage`；`GROUP_AT_MESSAGE_CREATE` 与 `mentions` 里的 `bot=true` 都会标记
  `mentionsBot`，控制器据此触发回复。
- **发送**：`POST /v2/groups/{group_openid}/messages` 或 `POST /v2/users/{user_openid}/messages`；
  富媒体走 `/files` 上传拿 `file_info` 后用 `msg_type=7` 发送。
- **线程**：HTTP 在独立线程池执行；网关读线程上触发私聊发送时只入队，避免阻塞事件接收。
