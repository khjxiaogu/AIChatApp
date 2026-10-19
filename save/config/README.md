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
   ├─ image-generation.json     # 图片生成（Seedream）凭据
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
         ├─ charaset.txt        #     角色设定
         └─ rules.txt           #     生成规则
```

| 文件 | 用途 | 是否入库 |
|---|---|---|
| `object-storage.json` | 对象存储凭据：`region` / `endpoint` / `bucket` / `accessKey` / `secretKey`。存在则绘画图片转存对象存储，否则只存本地 `painter_images`、`ircimages` | 忽略（含密钥） |
| `image-generation.json` | 图片/视频生成服务（Seedream）凭据：`accessKey` / `secretKey` | 忽略（含密钥） |
| `voice-tags.txt` | 语音标签提示词。让模型按「谁在说话 + 情感」切分台词，供语音合成使用 | 入库 |
| `local-voice.json` | 本地语音模型 bot 映射，形如 `{"角色名": "botid"}` | 忽略（部署本地） |
| `volcano-voice.json` | 火山声音复刻 bot 映射，形如 `{"角色名": "voice_type"}` | 忽略（部署本地） |
| `agents.json` | QQ 群 agent 清单：`name`（应用名）/ `save`（存档文件）/ `voice` 等，`NapCatAIConnector` 启动时读取 | 忽略（部署本地） |
| `crontab.json` | 定时任务清单 | 忽略（部署本地） |
| `sdxl/character-tags.txt` | 角色 tag 库（danbooru 风格 tag，一行一个）。生成提示词时按关键词检索，命中就往提示词里补 tag | 入库 |
| `sdxl/loras.json` | LoRA 配置：`{"触发词": {"key": "文件名", "weight": 0.8}}` | 忽略（部署本地） |
| `sdxl/reference-images.json` | 参考图：`{"名称": "图片地址"}`，供 Seedream 生图/生视频带参考图 | 忽略（部署本地） |
| `sdxl/agent/` | 绘画 agent 提示词：`role.txt`（角色）、`charaset.txt`（设定）、`rules.txt`（规则） | `rules.txt` 入库，`role.txt`、`charaset.txt` 忽略（含个人信息） |

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
