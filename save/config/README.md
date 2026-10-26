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
   ├─ jimeng-video.txt          # 即梦视频脚本提示词
   ├─ voice-tags.txt            # 语音标签提示词
   ├─ local-voice.json          # 本地语音 bot 映射
   ├─ volcano-voice.json        # 火山语音 bot 映射
   ├─ agents.json               # QQ 群 agent 清单
   ├─ crontab.json              # 定时任务
   └─ sdxl/                     # 生图相关
      ├─ character-tags.txt     #   角色 tag 库（提示词关键词）
      ├─ loras.json             #   LoRA 配置
      ├─ reference-images.json  #   参考图
      └─ agent/                 #   绘画 agent 提示词
         ├─ role.txt            #     角色
         ├─ charaset.txt        #     角色设定（提示词用）
         ├─ character-list.txt  #     角色设定索引（作品/角色/别名/设定，工具数据源）
         └─ rules.txt           #     生成规则
```

| 文件 | 用途 | 是否入库 |
|---|---|---|
| `object-storage.json` | 对象存储凭据：`region` / `endpoint` / `bucket` / `accessKey` / `secretKey`。存在则绘画图片转存对象存储，否则只存本地 `painter_images`、`ircimages` | 忽略（含密钥） |
| `image-generation.json` | 图片/视频生成凭据。图片（Seedream/即梦）用 `accessKey` / `secretKey`；视频改用即梦 **Seedance 2.5**（火山方舟 Ark API），需要额外配置 `apiKey`，可选 `videoModel`（默认 `doubao-seedance-2-5-260628`）、`videoDuration`（默认 10 秒，最大 15 秒）、`videoResolution`（默认 480p）、`videoRatio`（默认 9:16）等，详见下方「Seedance 2.5 视频」 | 忽略（含密钥） |
| `jimeng-video.txt` | 即梦视频脚本提示词：`seedvideo` 工具把它作为视频脚本 agent 的系统提示词（内置参考图名字会追加在后面），agent 产出的整段脚本就是视频生成提示词。内置参考图由脚本 agent 自行挑选并附加，调用者只给想法（可选再补非内置的72位图片id，作为图1开始的外部参考图）。文件不存在时退回工具内置提示词；旧文件名 `jimeng_video.txt` 仍兼容 | 入库 |
| `voice-tags.txt` | 语音标签提示词。让模型按「谁在说话 + 情感」切分台词，供语音合成使用 | 入库 |
| `local-voice.json` | 本地语音模型 bot 映射，形如 `{"角色名": "botid"}` | 忽略（部署本地） |
| `volcano-voice.json` | 火山声音复刻 bot 映射，形如 `{"角色名": "voice_type"}` | 忽略（部署本地） |
| `agents.json` | QQ 群 agent 清单：`name`（应用名）/ `save`（存档文件）/ `voice` / `tools` / `adminId` 等，`NapCatAIConnector` 启动时读取。`tools` 里可把工具写成 `{"name":"seedvideo","approval":true}` 启用工具调用审核 | 忽略（部署本地） |
| `crontab.json` | 定时任务清单 | 忽略（部署本地） |
| `sdxl/character-tags.txt` | 角色 tag 库（danbooru 风格 tag，一行一个）。生成提示词时按关键词检索，命中就往提示词里补 tag | 入库 |
| `sdxl/loras.json` | LoRA 配置：`{"触发词": {"key": "文件名", "weight": 0.8}}` | 忽略（部署本地） |
| `sdxl/reference-images.json` | 参考图：`{"名称": "图片地址"}`，供 Seedream 生图/生视频带参考图；生视频时这些名字会列进脚本 agent 的提示词，由 agent 自行选用 | 忽略（部署本地） |
| `sdxl/agent/` | 绘画 agent 提示词：`role.txt`（角色）、`charaset.txt`（设定）、`rules.txt`（规则） | `rules.txt` 入库，`role.txt`、`charaset.txt` 忽略（含个人信息） |
| `sdxl/agent/character-list.txt` | 角色设定索引：`[work]` 作品、`[public]` 公共设定、`[character]` 角色设定与 `别名：`（人类可读，格式见文件头注释）。角色设定工具的数据来源，不依赖 `charaset.txt` | 入库 |

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
  "videoGenerateAudio": true,
  "videoWatermark": false
}
```

- `apiKey` 也可以写成 `arkApiKey`；未配置时视频工具会返回明确的失败提示，图片工具不受影响。
- `videoDuration` 支持 4-15 秒，默认 10 秒；超过 15 秒按 15 秒处理，不再使用 `-1` 自动时长。
  `jimeng_video` 工具调用时可用 `duration` 参数覆盖默认值（同样只接受 4-15 的整数），该时长会同时提供给脚本 agent 和视频接口。
- 视频计费：`视频token = 输出宽 × 输出高 × 输出帧率 × 输出时长 / 1024`，官方价 **70 元/百万视频 token**。
  本站配额按 2 元/百万等效 token 折算，因此配额扣减 token = 视频 token × **35**。
  输出尺寸默认由 `videoResolution` + `videoRatio` 推导，帧率默认 24；可用 `videoWidth` / `videoHeight` / `videoFps` 校准。
- `videoResolution` 支持 `480p` / `720p` / `1080p`。
- `videoRatio` 默认 `9:16` 竖版；需要横版时显式改 `16:9`。
- 参考图按 `reference_image` 提交，最多 14 张（工具侧上限），适合多角色参考而不是首尾帧。
- 可选 `videoBaseUrl` 切换区域/代理，`videoPollIntervalMs`、`videoMaxPollAttempts` 控制轮询。

## QQ 工具调用审核（`agents.json`）

给某个机器人配置管理员后，可以把指定工具标记为“执行前需要审核”：

```json
{
  "botId": 123456,
  "groupId": 789012,
  "name": "fengxiirc",
  "save": "savegroup4-fengxiirc.json",
  "adminId": 10001,
  "tools": [
    "fetch",
    "multimodal",
    {"name": "seedvideo", "approval": true},
    "seedimage"
  ],
  "approvalTimeoutMs": 120000
}
```

- `adminId`：接收私聊审核请求的管理员 QQ；也可以用启动参数 `-DadminId=...` 兜底。
- `approvalTimeoutMs`：等待答复的毫秒数，默认 120000；也可用 `-Dtool_approval_timeout_ms=...`。
- `tools` 的字符串条目和旧配置完全兼容；对象条目用 `{"name": "...", "approval": true}`。
- 条目名写技能名（`seedvideo`）时会展开成该技能注册的全部工具（`jimeng_video`）；
  也可以直接写真实工具名（如 `jimeng_video`）。
- 也可以集中写 `"toolApprovals": ["seedvideo"]` 或 `"toolApprovals": {"seedvideo": true}`。
  写技能名时该技能仍必须出现在 `tools` 里；直接写真实工具名时，只要该工具已由别的技能注册即可。
- 管理员私聊回复 `允许` / `同意` / `1` / `ok` 放行，回复 `拒绝` / `不同意` / `2` / `no` 拒绝；
  超时按拒绝处理。只有管理员本人私聊答复有效，其它私聊消息仍被忽略。
- 没配置 `adminId` 时失败关闭：带 `approval` 的工具一律拒绝，不会擅自执行。
