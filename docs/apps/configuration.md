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
├─ image-generation.json    图片/视频生成（Seedream）凭据
├─ voice-tags.txt           语音情感切分提示词（决定"这句用什么情绪读"）
├─ local-voice.json         本地语音音色映射 {角色名: botid}
├─ volcano-voice.json       火山语音音色映射 {角色名: voice_type}
├─ agents.json              QQ 群机器人清单（接群时用）
├─ crontab.json             定时任务
└─ sdxl/
   ├─ character-tags.txt    角色 tag 库（一行一个，生成绘图提示词时按关键词检索）
   ├─ loras.json            LoRA 配置 {触发词: {key, weight}}
   ├─ reference-images.json 参考图 {名称: 图片地址}
   └─ agent/                绘画 agent 的提示词（role / charaset / rules）
```

逐字段说明见 [save/config/README.md](../../save/config/README.md)。

> 🚚 从旧版本升级（文件还散落在 `save/` 根目录）请按 [docs/MIGRATION.md](../MIGRATION.md) 搬迁——
> 旧位置仍被兼容，不搬也能跑，但日志会提示一次。

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
| `deepseektoken` | 启用 Deepseek（最常用），同时启用网页搜索工具 `web_search` |
| `volcmodeltoken` | 启用火山引擎 |
| `mimotoken` | 启用 Mimo（也用于 Mimo 语音） |
| `groktoken` / `silicontoken` | 启用 Grok / 硅基流动 |
| `localVoiceToken` | 本地语音模型的鉴权 token |
| `volcappid` / `volcsecret` | 火山声音复刻 |
| `sdwebuiUrl` | 本地 SD WebUI 地址 |
| `svsHost` | 音乐合成服务地址 |

> 提供商是**按 token 是否存在**决定启用的。一个都不配 → 对话会失败（模型路由错误）。

### 站点与运维

| 参数 | 作用 |
|---|---|
| `site_host` | 站点对外域名（默认读 `save/site.json`，此项用于临时覆盖） |
| `napcat_url` / `napcat_token` | QQ 群接入：NapCat 地址与 token |
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
