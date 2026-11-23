# 配置指南

配置分三层，改之前先确认该改哪一层：

| 层次 | 位置 | 影响 | 生效时机 |
|---|---|---|---|
| **应用配置** | `save/apps/<应用ID>/meta.json` + 同目录提示词 | 单个应用 | **重启服务** |
| **站点配置** | `save/config/`、`save/site.json` | 全站 | 多数随启动；域名每次开页面重读 |
| **启动参数** | `-D参数=值` | 整个服务 | 启动时 |

---

## 1. `meta.json`：应用配置

路径 `save/apps/<应用ID>/meta.json`。**应用ID 就是目录名**，也是数据库里记录应用的值——
**改目录名等于换应用ID，已有存档会与新应用对不上，别随便改**。

<a id="sec-meta-framework"></a>
### 1.1 框架字段（服务启动时读取）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `type` | 字符串 | **无（必需）** | 玩法类型：`write`/`talk`/`galgame`/`group`/`sql`/`trpg`/`wuxia`/`custom`，**不能自创** |
| `name` | 字符串 | **无（必需）** | 缺这个字段目录**直接不加载**。⚠️ 它不是界面显示名，见 §1.5 |
| `enabled` | 布尔 | `true` | `false` 则完全停用（日志："忽视AI：X 出于配置原因"）。写 `"true"`/`"false"` 字符串也生效 |
| `access` | 字符串 | `permission` | 可见性，见 [§3](#sec-access) |
| `models` | 字符串数组 | 空 = 不限 | 允许使用的模型白名单，如 `["deepseek/pro","deepseek/flash"]`；网页上只能在这些里选 |
| `defaultModel` | 字符串 | 白名单第一个 | 新对话默认模型 |
| `paidOnly` | 布尔 | `false` | 只扣付费余额；余额不足不能新建对话 |
| `freeNow` | 布尔 | `false` | 完全不扣额度（体验/内部用） |
| `ui` | 字符串 | 无 | 填 `painter` 时该应用走**绘画页**形态（图片会话），一般应用不填 |
| `url` | 字符串 | 无 | 存档管理页"进入应用"的跳转地址；配置型智能体会自动写成对话页地址 |

**计费三态**：

| `paidOnly` | `freeNow` | 扣哪份额度 | 粒度 |
|---|---|---|---|
| `true` | 任意 | 付费余额 | 按用量向上取整 |
| `false` | `true` | 不扣 | — |
| `false` | `false` | 每日免费额度 | 按用量向下取整 |

### 1.2 按类型的私有字段

这些字段只有对应类型会读：

| 类型 | 字段 | 说明 |
|---|---|---|
| `group` | `charaName` | **必需**。机器人在群里的自称，缺失 → 加载失败 |
| `galgame` | `background` | 默认背景图；不填则进入时不显示背景 |
| `talk` | `volcappid` / `localChara` / `localEmote` | ⚠️ **历史遗留字段，写了不生效**（音色实际在 `save/config/*-voice.json` 里配） |
| `trpg` / `wuxia` / `write` / `sql` / `custom` | — | 不读任何私有字段 |

> 看到老配置里的 `volcappid`、`localEmote` 别去改它调语音——按本文档实测，它们是死字段。

### 1.3 一个完整例子

```json
{
  "type": "group",
  "name": "绘图助手群聊",
  "enabled": true,
  "access": "public",
  "freeNow": true,
  "ui": "painter",
  "models": ["deepseek/pro", "deepseek/flash"],
  "charaName": "绘图助手"
}
```

### 1.4 排查：应用没出现

按顺序查：

1. `enabled` 是不是 `false`？
2. `name` 有没有缺？
3. `type` 拼对了没有？（只有 §1.1 那 8 个值）
4. 该类型必需的文件齐全吗？（`galgame`/`custom` 缺文件会直接加载失败）
5. 看启动日志：`AI加载失败：<应用ID>` 后面通常跟着原因。

<a id="sec-display-name"></a>
### 1.5 界面显示名对不上

界面显示的名字是**程序里为每种类型固定好的**，不是 `meta.json` 的 `name`：

| 类型 | 界面显示的名字 |
|---|---|
| `wuxia` | 武侠模拟器 |
| `write` | 写作 |
| `sql` | sql |
| `custom` | 自定义智能体 |
| `talk` / `galgame` / `trpg` / `group` | 你在 `meta.json` 里写的 `name`（会被当作角色名） |

所以：改 `wuxia` 的 `meta.name` 不会改界面显示；而 `talk` 换 `name` 就等于换角色名。

---

## 2. 应用目录里的提示词

文件分工、格式契约、枚举一致性这些写作技巧，见 [prompting.md](prompting.md)；
每个文件被哪个类型读取，见 [files.md](files.md#sec-file-usage)。

三条硬规矩：

1. **UTF-8 无 BOM**（带 BOM 会把乱码塞进提示词开头）；
2. **改完要重启**（提示词在启动时读入内存）；
3. **换角色/换剧情要开新对话验证**（旧对话带着旧上下文）。

---

<a id="sec-access"></a>
## 3. 可见性与授权

`access` 三个取值：

| 值 | 效果 |
|---|---|
| `public` | 所有用户可见、可新建对话 |
| `permission`（默认） | 只有 `permission` 表里被授权的用户可见 |
| `hidden` | 不出现在任何列表，也不能新建；**已有对话仍可按对话ID打开** |

也接受同义词：`open`/`all`/`trial` → public，`none`/`invisible`/`private` → hidden，
`limited`/`auth` → permission，大小写不敏感。旧字段 `trial` 仍兼容（`true`=public，`false`=permission），
但启动日志会提示已弃用，新配置请直接用 `access`。

**授权没有网页接口**，需要直接在数据库里加记录（先停服务或确保数据库没被占用）：

```sql
-- 授权用户 12345 使用应用 mywuxia
INSERT INTO permission(uid, app, state) VALUES('12345', 'mywuxia', '1');

-- 撤销
DELETE FROM permission WHERE uid='12345' AND app='mywuxia';
```

---

<a id="sec-custom-agent"></a>
## 4. 配置型智能体（不改代码、不重启）

在 `agentedit` 页面填表单，保存即用。它**不需要应用目录**——提示词存在**每个对话的存档**里，
所以每个智能体天然隔离，可以随时改。

| 表单 | 限制 | 存在哪 | 用途 |
|---|---|---|---|
| 名称 | ≤ 20 字 | 对话的 `brief` | 对话标题 |
| 核心规则 | ≤ 5000 字 | 对话的 `rules` | 系统提示的「核心规则」段 |
| 游戏设定 | ≤ 8000 字 | 对话的 `charaset` | 系统提示的「游戏设定」段 |
| 上下文上限 | 30000~100000 | 对话的 `limit` | 触发长对话压缩的阈值 |

- 有效系统提示 = `【核心规则】\n<核心规则>\n【游戏设定】\n<游戏设定>`；
- 输出格式**不由程序解析**，完全靠你的核心规则约束（写法见 [prompting.md](prompting.md)）；
- 想再编辑：回到 `agentedit?chatid=<对话ID>`；
- 该功能依赖 `save/apps/custom/` 下的两个文件，**不要删**：
  `summary.txt`（压缩指令，缺了应用加载失败）和 `prompt.txt`（新建智能体的默认核心规则，
  缺了整个服务起不来）。

---

## 5. 站点配置：`save/config/`

需要手工维护的站点级配置。含密钥的文件已被 `.gitignore` 忽略。

```
save/config/
├─ object-storage.json      火山 TOS 对象存储凭据（有则绘画图片转存对象存储，否则只存本地）
├─ image-generation.json    图片（Seedream）/视频（Seedance 2.5）生成凭据
├─ voice-tags.txt           语音情感切分提示词（决定"这句用什么情绪读"）
├─ local-voice.json         本地语音音色映射 {角色名: botid}
├─ volcano-voice.json       火山语音音色映射 {角色名: voice_type}
├─ agents.json              群聊机器人清单（含 tools / adminId，接群时用）
├─ crontab.json             定时任务
└─ sdxl/
   ├─ character-tags.txt    角色 tag 库（一行一个，生成绘图提示词时按关键词检索）
   ├─ loras.json            LoRA 配置 {触发词: {key, weight}}
   ├─ reference-images.json 参考图 {名称: 图片地址}
   └─ agent/                绘画 agent 的提示词（role / charaset / rules）
└─ h3/
   └─ agent/                本地视频子 agent 的提示词（role / charaset / rules，可缺省）
```

逐字段说明见 [save/config/README.md](../../save/config/README.md)。

### 5.1 Seedance 2.5 视频

群聊 agent 的 `tools` 含 `seedvideo` 时注册 `jimeng_video` 工具，后端使用**火山方舟 Seedance 2.5**。
`save/config/image-generation.json` 除图片的 `accessKey` / `secretKey` 外，还需要视频的 `apiKey`：

```json
{
  "accessKey": "...",
  "secretKey": "...",
  "apiKey": "火山方舟 API Key",
  "videoModel": "doubao-seedance-2-5-260628",
  "videoDuration": 10,
  "videoResolution": "720p",
  "videoRatio": "9:16",
  "videoGenerateAudio": false
}
```

`videoDuration` 支持 4-15 秒，默认 10 秒（超过 15 秒按 15 秒处理）；`videoRatio` 默认 `9:16`；
`videoResolution` 支持 480p/720p/1080p。**所有视频生成 MCP 均禁用音频**：
`videoGenerateAudio` 已强制为 `false`，配置成 `true` 也不会生效。`jimeng_video` 工具还支持 `duration` 参数（4-15 的整数），
会覆盖配置默认值，并同时写进脚本要求、提交给视频接口。未配置 `apiKey` 时只有视频工具失败，
图片工具不受影响。

视频脚本 agent 还带一个**停止生成开关** `reject_request`：它发现用户想法本身有问题（违法违规、
涉及真实人物或隐私、需求自相矛盾、参考图与需求明显冲突、信息少到写不出脚本等）时调用该工具并写明
问题原因，本次视频**不再提交生成**，脚本 agent 也随之停下，问题原因直接作为 `jimeng_video` 的
工具结果返回给调用它的 agent，由它转告用户。只是不够具体的信息仍由脚本 agent 自行推测补全，不触发开关；
拒绝提示不要再写进脚本正文，否则会被当成视频提示词提交。

视频计费按 `视频token = 输出宽 × 输出高 × 输出帧率 × 输出时长 / 1024` 估算，价格
**70 元/百万视频 token**；本站配额按 2 元/百万等效 token 折算，所以配额扣减 token = 视频 token × 35。
输出尺寸默认由 `videoResolution` + `videoRatio` 推导，帧率默认 24，可用 `videoWidth` /
`videoHeight` / `videoFps` 校准。

#### 本地 H3 视频（`h3video` / `h3video-agent`）

模型服务（`LocalModelConnectionService`）在本机跑 ComfyUI + MiniMax H3 时，可以完全绕开火山方舟，
不需要视频 `apiKey`：

- `tools` 里加 `"h3video"` 会注册三个工具：
  - `h3_gen_video`（H3 视频制作，int8，8 步，1~15 秒，默认 5，16:9=864×480）；
  - `h3_gen_anim`（NVFP4 简单动图，6 步，1~8 秒，默认 3，16:9=960×544；画布为基础 640×352 的 1.5 倍宽高）；
  - `video_extract_frame`（视频截帧，`frame` 0 首帧、-1 末帧、-2 倒数第二帧，返回图片 id）。
  生成工具参数为提示词、参考图（1 张首帧 / 2 张首尾帧）、参考视频（1 个视频取首末帧，2 个取首/末，
  与参考图二选一）、画面比例（16:9/9:16/1:1/4:3/3:4）、时长（秒）；尺寸、帧数、步数、采样、种子、
  模型与显存释放全部由代码固定，且**都不生成音频**；
- 计费：生成成功后按算力时长扣配额，每 1 小时 = 1,000,000 配额；`-Dh3DiskMBps` 可校准磁盘读吞吐
  （默认 1000 MB/s）；
- `tools` 里加 `"h3video-agent"` 注册视频导演子 agent `h3_video_agent`：主模型只给需求与参考图/视频，
  子 agent 负责扩写提示词、决定比例与时长，并在两种视频模式间选择；提示词文件放在
  `config/h3/agent/`（可缺省，缺省时用内置提示词）；
- 模型地址沿用 `-DqwenImageUrl`（图与视频共用同一个 ComfyUI），确实需要分开时用 `-Dh3VideoUrl`；
  NVFP4 档需要 `ComfyUI/models/diffusion_models/10Eros_Max_H3_FL2VA-NVFP4.safetensors` 与 torch ≥ 2.8。

#### 绘画页图片（`PainterSession`）

绘画会话（`ui: "painter"` 的应用）默认注册图片工具，不需要在 `tools` 里勾选：

- SDXL 系列：`sdxl_gen_image`、`regional_image`、`image_to_image`、`image_interrogate`，
  优先使用该用户通过 `/painter-local` 接入的本地模型，未接入或离线时回退共享模型池；
- `qwen_gen_image`：Qwen-Image 2.1 文生图（走本地模型通道的 `qwen` 客户端），支持中文提示词与
  8 步极速草稿；结果写入绘画图片存储，返回的图片 id 可直接作为图生图/分区出图的参考图；
- 以上图片生成工具共享**每轮最多 5 次**调用额度。

#### 绘画页视频（`PainterSession`，无需额外开关）

绘画会话（`ui: "painter"` 的应用）默认注册视频工具，不需要像群聊那样在 `tools` 里勾选：

- `h3_gen_video`：本地 H3 直连生成（int8，完整视频，1~15 秒，默认 5），**同步**；
- `h3_gen_anim`：本地 H3 直连生成（NVFP4，简单动图，1~8 秒，默认 3），**同步**；
- `send_video`：把已生成/上传的视频 id 作为附件写回当前回复；绘画页**不注册**视频导演子 agent；
- `jimeng_video`：即梦 Seedance 2.5，仅在 `config/image-generation.json` 配置了 `apiKey` 时注册，
  参数与群聊侧完全一致（想法 / 参考图 / 4-15 秒时长），仍是异步长任务。

两件本地 H3 直连工具绑定为**同一个共享额度：每轮最多调用 1 次**。模型在同一轮里用掉其中
任意一个之后，另一个会被 `ToolCallManager` 拒绝，避免反复启动耗时数分钟的视频任务；下一轮重新计数。

绘画页的本地 H3 视频工具是**同步工具**：本轮内等待生成完成，工具结果只把视频 id 返回给模型，
**不会把视频附件直接写进助手回复**。模型需要把视频展示给用户时，再调用 `send_video`（`video_id` 传
刚拿到的视频 id）显式发送；也可以只在正文里给出视频 id。视频文件仍保存在对象存储，可按 id 通过
`GET /aichat/painter/video?video_id=<id>` 取用。这样视频生成也会在同一轮内结算配额，不会出现
“任务完成得比本轮 `done` 还晚”的情况。

即梦 `jimeng_video` 仍是**异步长任务**（5 秒视频约 2~5 分钟），绘画前端据此做了三件事：

1. 生成期间在输入框上方常驻“视频生成中 · 已等待 X 秒”的提示（协议帧 `task`，
   重连后由 `status.tasks` 恢复）；本轮回复先结束也不会丢失提示；
2. 任务完成后把视频**续写到本轮助手回复**里（本轮之后已经开了新对话、最后一条不是助手回复时
   才新起一条），历史重载后同样能看到；撤回/重新生成仍按“助手回复 ↔ 用户请求”配对处理；
3. 视频可播放、全屏、下载；图片安全模式对视频同样生效。

视频的 `video_url` 有两种来源，前端会自动区分：

| 来源 | `video_url` | 播放方式 |
|---|---|---|
| 本地 H3 | 对象存储 id（72 位哈希） | `GET /aichat/painter/video?video_id=<id>`，支持 Range 拖动 |
| 即梦（云端） | 远端 `https://...mp4` | 浏览器直接播放 |

绘画页还支持上传视频作为用户消息内容：

```text
POST /aichat/painter/uploadVideo      multipart/form-data
  conversation_id: 当前对话 id
  video:           视频文件（按内容校验，只接受视频；上限 200MB）
→ {"code":0,"message":"success","videoId":"..."}
```

图片上传（`POST /aichat/painter/uploadImage`）也改为按文件内容校验只接受图片，
并在响应里补上 `imageId`。两个上传接口都只写入待生成的用户消息，不立即触发生成。

### 5.2 工具调用审核

在 `save/config/agents.json` 里给机器人配置 `adminId`，并把需要审核的工具写成对象：

```json
{
  "botId": "123456",
  "groupId": "789012",
  "name": "fengxiirc",
  "save": "savegroup4-fengxiirc.json",
  "adminId": "10001",
  "tools": [
    "fetch",
    {"name": "seedvideo", "approval": true}
  ],
  "approvalTimeoutMs": 120000
}
```

`botId` / `groupId` / `adminId` 等标识统一按字符串处理（旧配置的数字写法仍兼容），
方便接入非数字 id 的聊天平台。

工具执行前会把会话号（群 id）、机器人名、最近发言者、工具名、原因与参数私聊给管理员；管理员回复
`允许`/`同意`/`1`/`ok` 放行，回复 `拒绝`/`不同意`/`2`/`no` 拒绝，超时按拒绝处理。
拒绝时可以回复 `拒绝 原因`（空格、冒号、逗号分隔均可），原因会作为工具结果回传给模型，
让模型知道具体的拒绝理由。
旧的字符串工具项完全兼容；支持 `toolApprovals` 数组/对象集中声明，条目可写技能名
（`seedvideo` 会展开为 `jimeng_video`）或真实工具名。未配置 `adminId` 时失败关闭，
需要审核的工具一律拒绝。`adminId` 与超时也可以分别用 `-DadminId=...`、
`-Dtool_approval_timeout_ms=...` 兜底。

> 🚚 从旧版本升级（文件还散落在 `save/` 根目录）请按 [docs/MIGRATION.md](../MIGRATION.md) 搬迁——
> 旧位置仍被兼容，不搬也能跑，但日志会提示一次。

### 5.3 子 agent 单回合限制

群聊侧启用 `sdxl-agent` / `sdxl-agent-nsfw` 或 `music` 时，对应子 agent 工具
（`sdxl_agent`、`find_music`）在**同一回合最多调用一次**，由 `ToolCallManager` 的
单工具次数限制实现，跨回合自动重新计数。

群聊侧还会限制每个 AI 最多持有 **2 个未完成异步任务**。同一批工具调用中的多个异步工具
一起放行；当已持有任务数达到上限后，后续批次不再允许启动新的异步工具，直到有任务完成。

### 5.4 主动回复判定

默认关闭。在 `agents.json` 的机器人配置里加 `autoReply` 段即可开启：

```json
"autoReply": {
  "enabled": true,
  "intervalMs": 600000,
  "messageThreshold": 20,
  "budget": 0.6
}
```

- 未被 @ 的群消息先累积：累计到 `messageThreshold` 条（默认 20）立即判定一次，否则每隔
  `intervalMs`（默认 10 分钟）判定一次；
- 判定把这段对话作为用户消息发给**关闭思考模式**的模型，让它判断当前角色是否需要主动发言；
  需要则走正常回复流程并计入配额，不需要则清空本次累计；
- 判定请求不带模型提示，按 `budget`（默认 Mimo 档位 0.6，DeepSeek Flash 基准 1）选择提供商；
- 明确指定模型（`modelHint`）的请求优先按模型路由、忽略 `budget`，只有按提示找不到提供商时
  才回退到预算选择。

**站点域名**单独放在 `save/site.json`：

```json
{ "host": "example.com", "ws_host": "ws.example.com", "reader_host": "www.example.com" }
```

- `host` 留空或文件不存在 → 网页自动跟随当前访问的域名（自建部署最省事）；
- `ws_host` 可选，**WebSocket 专用域名**，与网页的 `host` 分开（例如网页 `example.com`、WebSocket `ws.example.com`）；
  留空时与 `host` 相同；
- `reader_host` 可选，用于图片查看页；
- 详见根 [README.md](../../README.md)。

---

<a id="sec-startup-args"></a>
## 6. 启动参数

服务是 `java -D参数=值 …` 启动的。常用参数：

### 模型与语音

| 参数 | 作用 |
|---|---|
| `deepseektoken` | 启用 Deepseek（最常用），同时启用联网搜索：`deepseek-flash` 走官方服务端工具 `deepseek_web_search`（不按次计费），`deepseek-v4-pro` 与其它模型走 MCP 工具 `web_search` |
| `volcmodeltoken` | 启用火山引擎 |
| `mimotoken` | 启用 Mimo（也用于 Mimo 语音） |
| `groktoken` / `silicontoken` | 启用 Grok / 硅基流动 |
| `localVoiceToken` | 本地语音模型的鉴权 token |
| `volcappid` / `volcsecret` | 火山声音复刻 |
| `sdwebuiUrl` | 本地 SD WebUI 地址 |
| `svsHost` | 音乐合成服务地址 |
| `musicBaseUrl` | 音乐文件公开地址前缀，群聊模式默认 `http://127.0.0.1:8998/aichat` |

> 提供商是**按 token 是否存在**决定启用的。一个都不配 → 对话会失败（模型路由错误）。

### 站点与运维

| 参数 | 作用 |
|---|---|
| `site_host` | 站点对外域名（默认读 `save/site.json`，此项用于临时覆盖） |
| `napcat_url` / `napcat_token` | 默认 NapCat 适配器的接入地址与 token（QQ 群） |
| `adminId` | 群聊工具审核的管理员账号（agent 配置里没写时兜底） |
| `tool_approval_timeout_ms` | 群聊工具审核等待答复的超时毫秒，默认 120000 |
| `cdk_secret` | 兑换码签名密钥 |

---

## 7. 额度与计费默认值

| 项 | 值 | 含义 |
|---|---|---|
| 每日免费额度 | 500,000 | 每人每天的免费 token |
| 签到奖励 | 50,000 | 每日签到补充 |
| 新用户赠送 | 100,000 | 首次获取额度时静默赠送 |

- 免费额度按天记在 `daily_usage`，付费余额在 `user_paid_balance`；
- `paidOnly` 应用不碰免费额度，`freeNow` 应用不碰任何额度；
- 单独调某人的免费额度：`user_free_limit_override(user_id, free_limit)`。

---

## 8. 上线检查清单

- [ ] `meta.json` 有 `type`（8 个合法值之一）+ `name`，JSON 格式正确
- [ ] 该类型必需的文件都在，UTF-8 无 BOM
- [ ] `access` 设对；`permission` 的话已往 `permission` 表加过授权
- [ ] `models` 里的模型确实有可用提供商（对应 token 已配）
- [ ] 想清楚计费口径（`freeNow` / `paidOnly` / 默认免费额度）
- [ ] `ui` 只在要用绘画页时才填 `painter`
- [ ] 重启后日志有 `AI加载成功：<应用ID>`
- [ ] 用**新对话**验证过一轮完整流程
