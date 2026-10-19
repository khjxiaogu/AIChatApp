# 升级迁移指南：配置目录与站点域名

本次改动把**散落在 `save/` 根目录的配置文件**收进了 `save/config/`，并把**写死的站点域名**换成了配置项。

**好消息**：老部署不改也能继续跑——旧位置仍会被读取（只是日志提示一次）。
这篇文档给你一次搬干净的步骤、路径对照、验证清单和回滚方法。

---

## 1. 改了什么

### A. 站点域名不再写死

| 之前 | 现在 |
|---|---|
| 页面里写死域名（WebSocket 地址、图片查看链接） | 从 `save/site.json` 读取；服务端通过 `/aichat/site-config.js` 下发给页面 |
| 后端代码里写死域名 | 同上（可用启动参数临时覆盖） |
| 改域名 = 改 HTML/代码 | 改 `save/site.json` 一处，**不用重启前端**（每次开页面重读） |

`save/site.json`：

```json
{
  "host": "example.com",
  "reader_host": "www.example.com"
}
```

- `host`：站点对外域名，可写 `example.com`、`example.com:8443`、`https://example.com`；
  **留空或文件不存在 → 页面自动跟随当前访问的域名**（自建/反代最省事）；
- `reader_host`：可选，图片查看页域名，缺省与 `host` 相同；
- 临时覆盖：启动参数 `--site-host=example.com` 或 `-Dsite_host=example.com`（优先级最高）。

### B. 配置文件搬进 `save/config/`

11 项，见 [§4 对照表](#4-路径对照表)。

### C. 配套变化

- 新增 `save/config/README.md`（配置目录说明）；
- 前端 5 个页面（`custom` / `galgame` / `trpg` / `ptrpg` / `painter`）引入 `site-config.js`，
  拼地址改用公共函数；
- 新增接口 `/aichat/site-config.js`；
- `.gitignore` 调整：新路径继续忽略含密钥的文件，旧路径的忽略**保留**（防止残留密钥被误提交）。

---

## 2. 我需不需要迁移？

| 你的情况 | 建议 |
|---|---|
| 只是跑服务，域名没换 | **建议搬**（不搬也能跑，日志每次会提示"仍在使用旧位置"） |
| 自建/反代/换过域名 | 搬 + 按 §5 配 `save/site.json` |
| 用过对象存储（TOS）或 Seedream 生图 | 建议搬 `object-storage.json` / `image-generation.json` |
| 用过 LoRA / 参考图 / 角色 tag 库 | 建议搬 `config/sdxl/` 下三个文件 |
| 接了 QQ 群 | 建议搬 `agents.json` |
| 用过本地语音 / 火山语音 / 定时任务 | 建议搬对应文件 |

> 迁移是**逐项独立**的：只搬一部分也完全正常，没搬的继续从旧位置读。

---

## 3. 迁移前

1. **备份**整个 `save/`（一个 5 秒的复制，能省掉后面所有麻烦）；
2. **停服务**（避免文件被占用、SQLite 被锁）；
3. 确认用的是新版程序（含 `save/config/README.md` 的那一版）。

---

## 4. 路径对照表

| # | 旧位置（`save/` 下） | 新位置 | 内容 |
|---|---|---|---|
| 1 | `tos.json` | `config/object-storage.json` | 对象存储凭据（含密钥） |
| 2 | `img.json` | `config/image-generation.json` | 生图凭据（含密钥） |
| 3 | `voicetag.txt` | `config/voice-tags.txt` | 语音情感切分提示词 |
| 4 | `localvoice.json` | `config/local-voice.json` | 本地语音音色映射 |
| 5 | `volcanovoice.json` | `config/volcano-voice.json` | 火山语音音色映射 |
| 6 | `agents.json` | `config/agents.json` | QQ 群机器人清单 |
| 7 | `crontab.json` | `config/crontab.json` | 定时任务 |
| 8 | `promptdo.txt` | `config/sdxl/character-tags.txt` | 角色 tag 库（生图提示词关键词） |
| 9 | `loras.json` | `config/sdxl/loras.json` | LoRA 配置 |
| 10 | `reference_image.json` | `config/sdxl/reference-images.json` | 参考图 |
| 11 | `painteragent/`（整个目录） | `config/sdxl/agent/` | 绘画 agent 提示词 `role.txt` / `charaset.txt` / `rules.txt` |

新增（**不需要迁移**，本来就没有）：

| 位置 | 说明 |
|---|---|
| `config/` | 配置目录本体 |
| `config/README.md` | 配置说明 |
| `config/sdxl/` | 生图配置子目录 |
| `site.json` | 站点域名（放 `save/` 根下） |

---

## 5. 迁移步骤

### 5.1 建目录

```bash
# Linux / macOS
mkdir -p save/config/sdxl
```

```powershell
# Windows PowerShell
New-Item -ItemType Directory -Force -Path save\config\sdxl | Out-Null
```

### 5.2 搬文件（脚本，安全、可重复执行）

脚本只做三件事：**有旧文件才动**、**新位置已存在就跳过**、**绝不删除任何东西**。

```powershell
# Windows PowerShell，在项目根目录执行
$save = "save"
$cfg  = Join-Path $save "config"
$sdxl = Join-Path $cfg  "sdxl"
New-Item -ItemType Directory -Force -Path $cfg, $sdxl | Out-Null

$map = [ordered]@{
  "tos.json"            = "config/object-storage.json"
  "img.json"            = "config/image-generation.json"
  "voicetag.txt"        = "config/voice-tags.txt"
  "localvoice.json"     = "config/local-voice.json"
  "volcanovoice.json"   = "config/volcano-voice.json"
  "agents.json"         = "config/agents.json"
  "crontab.json"        = "config/crontab.json"
  "promptdo.txt"        = "config/sdxl/character-tags.txt"
  "loras.json"          = "config/sdxl/loras.json"
  "reference_image.json"= "config/sdxl/reference-images.json"
}
foreach ($k in $map.Keys) {
  $src = Join-Path $save $k
  $dst = Join-Path $save $map[$k]
  if (-not (Test-Path $src)) { continue }
  if (Test-Path $dst) { Write-Host "跳过（新位置已有）: $map[$k]"; continue }
  Move-Item -LiteralPath $src -Destination $dst
  Write-Host "已迁移: $k -> $($map[$k])"
}

# 目录形式的第 11 项
$oldDir = Join-Path $save "painteragent"
$newDir = Join-Path $cfg  "sdxl\agent"
if ((Test-Path $oldDir) -and -not (Test-Path $newDir)) {
  Move-Item -LiteralPath $oldDir -Destination $newDir
  Write-Host "已迁移: painteragent/ -> config/sdxl/agent/"
}
```

```bash
# Linux / macOS，在项目根目录执行
mkdir -p save/config/sdxl
mv_if() {
  if [ -e "save/$1" ] && [ ! -e "save/$2" ]; then
    mkdir -p "$(dirname "save/$2")"
    mv "save/$1" "save/$2"
    echo "已迁移: $1 -> $2"
  fi
}

mv_if tos.json             config/object-storage.json
mv_if img.json             config/image-generation.json
mv_if voicetag.txt         config/voice-tags.txt
mv_if localvoice.json      config/local-voice.json
mv_if volcanovoice.json    config/volcano-voice.json
mv_if agents.json          config/agents.json
mv_if crontab.json         config/crontab.json
mv_if promptdo.txt         config/sdxl/character-tags.txt
mv_if loras.json           config/sdxl/loras.json
mv_if reference_image.json config/sdxl/reference-images.json
mv_if painteragent         config/sdxl/agent
```

搬完检查一眼：

```bash
ls -R save/config        # 期望看到 README.md + 若干 json/txt + sdxl/
```

### 5.3 配站点域名（可选但推荐）

在 `save/site.json` 里写你的域名：

```json
{ "host": "example.com" }
```

- **不确定就留空**（连文件都不建也行）：页面会自动跟随访问域名，行为与升级前一致；
- 如果你的站点前后端不同域名（比如页面在 `www.`、WebSocket 在裸域），
  这时必须显式配 `host`，否则会跟随页面域名。

### 5.4 重启

启动后**逐条对照日志**：

```
站点域名: wss://example.com              ← 配好了
站点域名: 未配置（跟随访问域名）           ← 没配（也正常）
[配置] 仍在使用旧位置 save\xxx ，建议移动到 …  ← 还有没搬的（见 §7）
```

---

## 6. 兼容规则（为什么可以先不搬）

程序对每个配置项都是**三层查找**：

| 顺序 | 位置 | 结果 |
|---|---|---|
| 1 | `save/config/…`（或 `config/sdxl/…`） | 有就用它 |
| 2 | `save/…`（旧位置） | 有就用它，并**提示一次**迁移建议 |
| 3 | 都没有 | 返回新位置，报错/降级信息直接指向正确路径 |

推论：

- **新旧同时存在** → 以新位置为准，旧文件被忽略（不会报错）；
- **只有旧位置** → 照旧工作，日志提示一次（同一进程只提示一次，不会刷屏）；
- **都不存在** → 与升级前"文件不存在"的表现一致（例如 TOS 凭据缺失 → 只用本地存储）。

---

## 7. 验证清单

重启后按下面逐项确认（只用**一个**新对话，不要用老存档验证提示词类改动）：

| 子系统 | 怎么验 | 期望 |
|---|---|---|
| 站点域名 | 打开任意页面，浏览器开发者工具看 `/aichat/site-config.js` | 返回 `window.AI_SITE = { host: "你的域名", secure: true }` |
| 页面连接 | 打开 `aigal?chara=<角色>&uid=test` | 能连上、能对话（不是一直"连接中"） |
| 图片查看链接（QQ 群侧） | 群里触发一次打码图片 | 链接域名正确 |
| 对象存储 | 看启动日志 | 有 `绘画图片存储: 本地+TOS`（配置生效）或 `仅本地`（没配/没读到） |
| 生图凭据 | 让机器人/绘画页生成一张图 | 能出图，不报鉴权错误 |
| 角色 tag 库 / LoRA / 参考图 | 生图时看日志或工具列表 | tag 能命中、LoRA 能列出、参考图可用 |
| 语音情感切分 | 开语音对话一轮 | 有音频产出（`save/voice/` 出现新 mp3） |
| 音色映射 | 同上 | 用的是你配的音色，而不是报"找不到音色" |
| QQ 群 | 群里 @ 机器人 | 正常回复（说明 `agents.json` 读到了） |
| 定时任务 | 等一个 cron 触发点或看日志 | 任务照常执行 |

> 任何一项异常，先回头确认 §4 表里那一项是不是**真的搬过去了**（大小写、目录层级最容易错）。

---

## 8. 回滚

### 只回滚文件布局（还留在新版程序）

新位置的手动操作反向执行即可——但**通常没必要**，因为旧位置仍然被兼容。

### 回滚到旧版程序

⚠️ **旧版程序只认旧位置**，必须把文件搬回去（`save/config/` 里的内容它看不到）：

```powershell
# Windows PowerShell：把新位置搬回旧位置
Move-Item save\config\object-storage.json      save\tos.json
Move-Item save\config\image-generation.json    save\img.json
Move-Item save\config\voice-tags.txt           save\voicetag.txt
Move-Item save\config\local-voice.json         save\localvoice.json
Move-Item save\config\volcano-voice.json       save\volcanovoice.json
Move-Item save\config\agents.json              save\agents.json
Move-Item save\config\crontab.json             save\crontab.json
Move-Item save\config\sdxl\character-tags.txt  save\promptdo.txt
Move-Item save\config\sdxl\loras.json          save\loras.json
Move-Item save\config\sdxl\reference-images.json save\reference_image.json
Move-Item save\config\sdxl\agent               save\painteragent
```

同时注意：旧版程序里域名是写死的，`save/site.json` 不会被读取（留着无害）。

---

## 9. FAQ

**Q：能不能只更新程序、不搬文件？**
能。旧位置仍然读得到，只是启动日志会提示一次。等你有空再搬。

**Q：能不能只搬一部分？**
能。11 项互相独立，各项都按 §6 的三层查找，搬了哪项哪项就"静默"了。

**Q：搬完之后旧文件能删吗？**
新位置已存在时旧文件就被忽略了，可以删。但建议先跑一天确认无异常再删。

**Q：`save/site.json` 不建会怎样？**
页面自动用当前访问的域名——绝大多数部署这样正好。

**Q：改了 `site.json` 要重启吗？**
不用。每次打开页面都会重新拉取 `/aichat/site-config.js`，刷新即可生效。

**Q：为什么 `git status` 看不到 `config/` 里的凭据文件？**
它们含密钥，被 `.gitignore` 忽略了（与 `tos.json`、`agents.json` 等旧文件同样的待遇）。
`.gitignore` 里同时保留了旧路径的忽略规则，防止残留文件被误提交。

**Q：可以只替换 jar、不替换 `save/` 下的前端文件吗？**
不行——**新页面依赖新的公共脚本**。新版页面要用到新的 `ai-protocol.js`（里面有拼地址的公共函数）
和新增的 `site-config.js` 端点，混搭会报 `aiSiteWsBase is not defined`。
升级时请把 `save/` 里的 html/js/css **整体替换**（只保留 `saveData/`、`config/`、
`messages.db` 等数据与配置）。

**Q：升级后语音不响 / 生图报鉴权错？**
99% 是该搬的文件没搬到位。看日志有没有 §5.4 里那行"仍在使用旧位置"提示，按提示路径核对一遍。

**Q：`voicetag.txt` 少了几行会导致什么？**
它是语音情感切分的系统提示词。缺失/搬错 → 语音可能不生成或情感标注异常；
但**不影响文字对话**。

---

## 10. 附：哪些文件该入库

| 文件 | 入库 | 原因 |
|---|---|---|
| `config/voice-tags.txt` | ✅ | 提示词，无敏感信息 |
| `config/sdxl/character-tags.txt` | ✅ | 词库 |
| `config/sdxl/agent/rules.txt` | ✅ | 生成规则 |
| `config/sdxl/agent/role.txt`、`charaset.txt` | ❌ | 含人设/个人信息 |
| `config/object-storage.json`、`image-generation.json` | ❌ | 含密钥 |
| `config/local-voice.json`、`volcano-voice.json` | ❌ | 部署本地数据 |
| `config/agents.json`、`crontab.json` | ❌ | 部署本地数据 |
| `config/sdxl/loras.json`、`reference-images.json` | ❌ | 部署本地数据 |
| `site.json` | ❌ | 部署本地域名 |

---

相关文档：[配置指南](apps/configuration.md) · [文件作用](apps/files.md) ·
[配置目录说明](../save/config/README.md) · 根 [README](../README.md)
