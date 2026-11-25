# config — 站点配置文件

这里放需要手工维护的站点配置。程序启动时从数据目录的 `config/` 下读取，路径统一由
`com.khjxiaogu.aiwuxia.utils.ConfigPaths` 定义，代码里不再散落文件名。
生图（SDXL / Seedream）相关的配置集中在 `config/sdxl/`。

> 从旧版本升级（文件还在 `save/` 根目录）请按 [docs/MIGRATION.md](../../docs/MIGRATION.md)
> 搬迁：旧位置仍被兼容，不搬也能跑，但启动日志会提示一次。

```
save/
├─ site.json                    # 站点域名（不在此目录，见 ../README.md）
└─ config/
   ├─ object-storage.json       # 对象存储（火山 TOS）凭据
   ├─ image-generation.json     # 图片（Seedream）/视频（Seedance 2.5）生成凭据
   ├─ tencent-docs.json         # 腾讯文档开放平台凭据（在线表格 API）
   ├─ feishu-bitable.json       # 飞书多维表格凭据（在线表格 API）
   ├─ jimeng-video.txt          # 即梦视频脚本提示词
   ├─ voice-tags.txt            # 语音标签提示词
   ├─ local-voice.json          # 本地语音 bot 映射
   ├─ volcano-voice.json        # 火山语音 bot 映射
   ├─ agents.json               # 群聊 agent 清单
   ├─ crontab.json              # 定时任务
   ├─ ticket-sheet.json         # 工单看板在线表格（供应商 / 表格 / 列）
   └─ sdxl/                     # 生图相关
      ├─ character-tags.txt     #   角色 tag 库（提示词关键词）
      ├─ loras.json             #   LoRA 配置
      ├─ reference-images.json  #   参考图
      └─ agent/                 #   绘画 agent 提示词
         ├─ role.txt            #     角色
         ├─ charaset.txt        #     角色设定（提示词用）
         ├─ character-list.txt  #     角色设定索引（作品/角色/别名/设定，工具数据源）
         └─ rules.txt           #     生成规则
   └─ h3/                       # 本地 H3 视频相关
      └─ agent/                 #   视频 agent 提示词（可缺省）
         ├─ role.txt            #     角色
         ├─ charaset.txt        #     人物设定（可选）
         └─ rules.txt           #     生成规则
```

| 文件 | 用途 | 是否入库 |
|---|---|---|
| `object-storage.json` | 对象存储凭据：`region` / `endpoint` / `bucket` / `accessKey` / `secretKey`。存在则绘画图片转存对象存储，否则只存本地 `painter_images`、`ircimages` | 忽略（含密钥） |
| `image-generation.json` | 图片/视频生成凭据。图片（Seedream/即梦）用 `accessKey` / `secretKey`；视频改用即梦 **Seedance 2.5**（火山方舟 Ark API），需要额外配置 `apiKey`，可选 `videoModel`（默认 `doubao-seedance-2-5-260628`）、`videoDuration`（默认 10 秒，最大 15 秒）、`videoResolution`（默认 480p）、`videoRatio`（默认 9:16）等，详见下方「Seedance 2.5 视频」 | 忽略（含密钥） |
| `tencent-docs.json` | 腾讯文档在线表格 API 凭据：`file_id` / `client_id` / `access_token` 等；配置 `client_secret` + `refresh_token` 后 Token 过期自动刷新并回写。字段见下方「腾讯文档表格」 | 忽略（含密钥） |
| `feishu-bitable.json` | 飞书多维表格凭据与连接标识：`app_id` / `app_secret` / `access_token` / 连接级 `app_token` / `base_url`；具体数据表 `table_id`、视图/建列行为由调用方传参。字段见下方「飞书多维表格」 | 忽略（含密钥） |
| `jimeng-video.txt` | 即梦视频脚本提示词：群聊 `seedvideo` 与绘画页（配置了视频 `apiKey` 时自动注册 `jimeng_video`）都把它作为视频脚本 agent 的系统提示词（内置参考图名字会追加在后面），agent 产出的整段脚本就是视频生成提示词。内置参考图由脚本 agent 自行挑选并附加，调用者只给想法（可选再补非内置的72位图片id，作为图1开始的外部参考图）。文件不存在时退回工具内置提示词；旧文件名 `jimeng_video.txt` 仍兼容 | 入库 |
| `voice-tags.txt` | 语音标签提示词。让模型按「谁在说话 + 情感」切分台词，供语音合成使用 | 入库 |
| `local-voice.json` | 本地语音模型 bot 映射，形如 `{"角色名": "botid"}` | 忽略（部署本地） |
| `volcano-voice.json` | 火山声音复刻 bot 映射，形如 `{"角色名": "voice_type"}` | 忽略（部署本地） |
| `agents.json` | 群聊 agent 清单：`name`（应用名）/ `save`（存档文件）/ `voice` / `tools` / `adminId` 等，`NapCatAIConnector` 启动时读取。`tools` 里可把工具写成 `{"name":"seedvideo","approval":true}` 启用工具调用审核 | 忽略（部署本地） |
| `crontab.json` | 定时任务清单 | 忽略（部署本地） |
| `ticket-sheet.json` | 工单看板同步配置：`provider`（默认 `feishu`，可选 `tencent`）、`fileId` / `sheetId` / `sheetTitle` 与展示列；字段说明见 `docs/ticket-system.md` | 忽略（部署本地） |
| `svs-voices.json` | SVS 音色角色：`{"角色名": "音频id"}`。`svs_convert` 的 `voice` 传角色名，由这里映射到 localemp.py 的 RVC 模型名/音频 ID；`list_svs_voices` 会列出可用角色。文件每次调用时重新读取 | 入库 |
| `sdxl/character-tags.txt` | 角色 tag 库（danbooru 风格 tag，一行一个）。生成提示词时按关键词检索，命中就往提示词里补 tag | 入库 |
| `sdxl/loras.json` | LoRA 配置：`{"触发词": {"key": "文件名", "weight": 0.8}}` | 忽略（部署本地） |
| `sdxl/reference-images.json` | 参考图：`{"名称": "图片地址"}`，供 Seedream 生图/生视频带参考图；生视频时这些名字会列进脚本 agent 的提示词，由 agent 自行选用 | 忽略（部署本地） |
| `sdxl/agent/` | 绘画 agent 提示词：`role.txt`（角色）、`charaset.txt`（设定）、`rules.txt`（规则） | `rules.txt` 入库，`role.txt`、`charaset.txt` 忽略（含个人信息） |
| `sdxl/agent/character-list.txt` | 角色设定索引：`[work]` 作品、`[public]` 公共设定、`[character]` 角色设定与 `别名：`（人类可读，格式见文件头注释）。角色设定工具的数据来源，不依赖 `charaset.txt` | 入库 |
| `h3/agent/` | 视频子 agent 提示词：供群聊 `h3video-agent` 使用；绘画会话不注册视频子 agent，只使用直连 H3 工具。`role.txt`（角色）、`charaset.txt`（人物设定，可选）、`rules.txt`（规则）。三个文件都不存在时使用 `H3VideoAgentMcp` 内置的“视频导演”提示词；文件存在则按 `role + charaset + rules` 拼接，并自动追加“必须等工具真实返回、必须输出视频 id、视频一律无声（禁用音频）”的硬性要求 | 按需（建议 `rules.txt` 入库） |

## 从旧位置迁移

旧版本把这些文件放在 `save/` 根目录，文件名也更随意：

| 旧位置 | 新位置 |
|---|---|
| `save/tos.json` | `save/config/object-storage.json` |
| `save/img.json` | `save/config/image-generation.json` |
| `save/voicetag.txt` | `save/config/voice-tags.txt` |
| `save/localvoice.json` | `save/config/local-voice.json` |
| `save/volcanovoice.json` | `save/config/volcano-voice.json` |
| `save/agents.json` | `save/config/agents.json` |
| `save/crontab.json` | `save/config/crontab.json` |
| `save/promptdo.txt` | `save/config/sdxl/character-tags.txt` |
| `save/loras.json` | `save/config/sdxl/loras.json` |
| `save/reference_image.json` | `save/config/sdxl/reference-images.json` |
| `save/painteragent/` | `save/config/sdxl/agent/` |

新位置不存在时程序仍会读取旧位置（控制台提示一次迁移建议），但**新位置存在后旧位置立即失效**，
建议直接把文件挪过来，保持目录干净。

## 腾讯文档表格（`tencent-docs.json`）

供 `com.khjxiaogu.aiwuxia.sheet.tencent.TencentSheetApi` 使用，接入腾讯文档开放平台 V3 表格接口
（读写范围、追加行、增删工作表）。文件不存在时在线表格功能不可用。

```json
{
	"file_id": "300000000$XXXXXXXX",
	"sheet_id": "BB08J2",
	"client_id": "应用 ID",
	"client_secret": "应用密钥",
	"open_id": "开放平台用户 ID",
	"access_token": "用户访问令牌",
	"refresh_token": "刷新令牌",
	"expires_at": 0
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `file_id` | 是 | 在线表格 ID，取自文档链接 `https://docs.qq.com/sheet/{file_id}` |
| `sheet_id` | 否 | 默认工作表 ID，取自链接 `?tab=`；调用对象方法时省略 `sheetId` 则用它 |
| `client_id` | 是 | 开放平台应用 ID（请求头 `Client-Id`） |
| `client_secret` | 自动刷新时必填 | 应用密钥，用于刷新 Token；不需要自动刷新时可不填 |
| `open_id` | 否 | 开放平台用户 ID（请求头 `Open-Id`）；留空时由刷新响应的 `user_id` 自动补全 |
| `access_token` | 二选一 | 用户访问令牌；与 `client_secret + refresh_token` 至少配置一组 |
| `refresh_token` | 自动刷新时必填 | 刷新令牌（有效期约 1 年），Token 过期后自动换取新 Token |
| `expires_at` | 否 | Access Token 过期时间（秒级时间戳），刷新成功后由程序回写；0 表示未知 |

- 配置 `client_secret` + `refresh_token` 后，Token 过期或接口返回鉴权失败会自动刷新一次，
  新 Token 与 `expires_at` 写回本文件（其他字段保持不变）；文件不可写时只记日志。
- 所有字段都可用启动参数覆盖：`-Dtencent_docs_client_id=...`、`-Dtencent_docs_client_secret=...`、
  `-Dtencent_docs_token=...`、`-Dtencent_docs_refresh_token=...`、`-Dtencent_docs_open_id=...`、
  `-Dtencent_docs_file_id=...`、`-Dtencent_docs_sheet_id=...`、`-Dtencent_docs_expires_at=...`。
- 代码入口：`TencentSheetApi.fromDataFolder(new File("save"))`；通用接口为
  `com.khjxiaogu.aiwuxia.sheet.SheetApi`。

## 飞书多维表格（`feishu-bitable.json`）

凭据文件保存鉴权信息与**连接级** `app_token`；具体操作哪个数据表由调用方在每次调用时传入
`table_id`，不在这里维护。字段行为固定为：写表头自动创建缺失字段、不自动删除旧字段、按飞书
默认记录顺序读取。

```json
{
	"app_id": "cli_xxx",
	"app_secret": "应用密钥",
	"access_token": "",
	"app_token": "bascnxxx",
	"base_url": "https://open.feishu.cn"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `app_id` | 换取 tenant_access_token 时必填 | 自建应用 App ID |
| `app_secret` | 同上 | 自建应用 App Secret |
| `access_token` | 二选一 | 用户令牌；与自建应用凭据二选一，配置后不再自动换取 `tenant_access_token` |
| `app_token` | 是 | 连接级多维表格标识：`https://xxx.feishu.cn/base/{app_token}` |
| `base_url` | 否 | 开放平台域名，默认 `https://open.feishu.cn`（Lark 国际版用 `https://open.larksuite.com`） |

- 调用方只需要传业务表格 `table_id`；工单看板在 `ticket-sheet.json` 里只填 `tableId`（留空时按
  `sheetTitle` 查找/新建数据表），`app_token` 由本文件提供。
- 写 A1 表头时按名称匹配字段并记录「逻辑列名 → 实际字段」映射，之后读写自动重排，不调整字段
  物理顺序；旧字段默认保留（数据不丢），需要删除时显式调用 `deleteField(...)`。
- 表头顺序或字段集合变化时，数据行中的空单元格视为“保持原值”；表头与上次一致时按普通写入。
- 逻辑表头只保存在当前实例内存中：进程重启后、第一次写 A1 表头之前，读写退回字段物理顺序。
  建议启动时先写一次 A1 表头（工单看板就是这样做的）。
- 列改名用 `FeishuBitableApi.renameField(...)`（保留全部数据），删除用 `deleteField(...)`，
  新增用 `addField(...)` 或直接在 A1 表头里写新列名。
- 只读字段（公式、查找引用、创建/修改时间、创建/修改人、自动编号）写入时跳过；人员、附件、
  关联、群组、地理位置写入时抛错（需要飞书内部 ID）。
- 单次读写上限：5000 行、20000 单元格；批量新增/更新每批最多 1000 条。
- 凭据可用启动参数覆盖：`-Dfeishu_app_id=...`、`-Dfeishu_app_secret=...`、`-Dfeishu_token=...`、
  `-Dfeishu_app_token=...`、`-Dfeishu_base_url=...`。
- 代码入口：`FeishuBitableApi.fromDataFolder(new File("save"))` 或
  `SheetApiFactory.create(dataFolder, "feishu", null)`。

## Seedance 2.5 视频（`image-generation.json`）

`seedvideo` 工具（`jimeng_video`）已从旧的火山视觉签名接口切到**火山方舟 Seedance 2.5**。
同一个 `image-generation.json` 同时保存两套凭据：

```json
{
  "accessKey": "仍用于 Seedream/即梦图片生成",
  "secretKey": "仍用于 Seedream/即梦图片生成",
  "apiKey": "方舟 API Key，视频生成必填",
  "videoModel": "doubao-seedance-2-5-260628",
  "videoDuration": 10,
  "videoResolution": "720p",
  "videoRatio": "9:16",
  "videoGenerateAudio": false,
  "videoWatermark": false
}
```

- `apiKey` 也可以写成 `arkApiKey`；未配置时视频工具会返回明确的失败提示，图片工具不受影响。
- **所有视频生成 MCP 均禁用音频**：`videoGenerateAudio` 已强制为 `false`，配置成 `true` 也不会生效。
- `videoDuration` 支持 4-15 秒，默认 10 秒；超过 15 秒按 15 秒处理，不再使用 `-1` 自动时长。
  `jimeng_video` 工具调用时可用 `duration` 参数覆盖默认值（同样只接受 4-15 的整数），该时长会同时提供给脚本 agent 和视频接口。
- 视频计费：`视频token = 输出宽 × 输出高 × 输出帧率 × 输出时长 / 1024`，官方价 **70 元/百万视频 token**。
  本站配额按 2 元/百万等效 token 折算，因此配额扣减 token = 视频 token × **35**。
  输出尺寸默认由 `videoResolution` + `videoRatio` 推导，帧率默认 24；可用 `videoWidth` / `videoHeight` / `videoFps` 校准。
- `videoResolution` 支持 `480p` / `720p` / `1080p`。
- `videoRatio` 默认 `9:16` 竖版；需要横版时显式改 `16:9`。
- 参考图按 `reference_image` 提交，最多 14 张（工具侧上限），适合多角色参考而不是首尾帧。
- 可选 `videoBaseUrl` 切换区域/代理，`videoPollIntervalMs`、`videoMaxPollAttempts` 控制轮询。
- 视频脚本 agent 带**停止生成开关** `reject_request`：判断用户想法有问题（违法违规、涉及真实人物隐私、
  需求自相矛盾、参考图与需求冲突等）时调用并写明 `reason`，本次视频不再提交生成，问题原因直接作为
  `jimeng_video` 的工具结果返回给调用者转告用户。拒绝提示不要写进脚本正文（否则会被当成视频提示词提交）。

## 群聊工具调用审核（`agents.json`）

给某个机器人配置管理员后，可以把指定工具标记为“执行前需要审核”：

```json
{
  "botId": "123456",
  "groupId": "789012",
  "name": "fengxiirc",
  "save": "savegroup4-fengxiirc.json",
  "adminId": "10001",
  "tools": [
    "fetch",
    "multimodal",
    {"name": "seedvideo", "approval": true},
    "seedimage"
  ],
  "approvalTimeoutMs": 120000
}
```

- `botId` / `groupId` / `adminId` 等标识统一按字符串处理（旧配置的数字写法仍兼容）。
- `adminId`：接收私聊审核请求的管理员账号；也可以用启动参数 `-DadminId=...` 兜底。
- `approvalTimeoutMs`：等待答复的毫秒数，默认 120000；也可用 `-Dtool_approval_timeout_ms=...`。
- `tools` 的字符串条目和旧配置完全兼容；对象条目用 `{"name": "...", "approval": true}`。
- 条目名写技能名（`seedvideo`）时会展开成该技能注册的全部工具（`jimeng_video`）；
  也可以直接写真实工具名（如 `jimeng_video`）。
- 也可以集中写 `"toolApprovals": ["seedvideo"]` 或 `"toolApprovals": {"seedvideo": true}`。
  写技能名时该技能仍必须出现在 `tools` 里；直接写真实工具名时，只要该工具已由别的技能注册即可。
- 管理员私聊回复 `允许` / `同意` / `1` / `ok` 放行，回复 `拒绝` / `不同意` / `2` / `no` 拒绝；
  超时按拒绝处理。只有管理员本人私聊答复有效，其它私聊消息仍被忽略。
- 没配置 `adminId` 时失败关闭：带 `approval` 的工具一律拒绝，不会擅自执行。

## 群聊主动回复判定（`agents.json`）

默认关闭。开启后，没有被 @ 的群消息会先累积起来，按时间或条数触发一次轻量判定：

```json
"autoReply": {
  "enabled": true,
  "intervalMs": 600000,
  "messageThreshold": 20,
  "budget": 0.6
}
```

| 字段 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `false` | 是否启用 |
| `intervalMs` | `600000` | 定时判定间隔（毫秒） |
| `messageThreshold` | `20` | 消息条数阈值 |
| `budget` | `0.6` | 判定请求的消费预算倍率（Mimo 档位） |

- 累计达到 `messageThreshold` 条时立即判定；否则每隔 `intervalMs` 判定一次。
- 判定时把这段对话作为用户消息发给**关闭思考模式**的模型，让它判断当前角色是否需要主动发言；
  需要则走正常回复流程并计入会话配额，不需要则清空本次累计。
- 判定请求不带模型提示，按 `budget` 选择提供商（DeepSeek Flash 基准 1）：
  连接器会在支持该请求的提供商中选 `ModelProvider#budget(request)` 最接近的一个。
- 明确指定模型（`modelHint`）的请求优先按模型路由、忽略 `budget`；只有按提示找不到提供商时
  才回退到预算选择。
