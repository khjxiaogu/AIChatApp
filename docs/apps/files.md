# 文件与目录：每个文件是干什么的

做 app 之前先搞清楚"东西放哪、谁在读它"。这篇不涉及代码，只讲磁盘上的文件。

---

## 1. 三层结构

```
   ┌──────────────────────────────────────────┐
   │ 类型（type）                              │  ← 一套玩法逻辑，程序内置，共 8 种
   │ talk / trpg / galgame / wuxia / …         │
   └───────────────────┬──────────────────────┘
                       │ 由 meta.json 里的 type 选择
   ┌───────────────────┴──────────────────────┐
   │ 应用（应用ID = 目录名）                    │  ← 你自己建：save/apps/<应用ID>/
   │ mywuxia / fengxitalk / …                  │     放 meta.json 和提示词
   └───────────────────┬──────────────────────┘
                       │ 用户每开一个对话
   ┌───────────────────┴──────────────────────┐
   │ 对话（对话ID）                             │  ← 程序自动建：save/saveData/<对话ID>.json
   │ 一串 32 位十六进制                        │     存历史、状态、费用
   └──────────────────────────────────────────┘
```

一个**类型**可以挂很多个**应用**（比如 `talk` 下可以有 10 个角色），
一个**应用**可以有很多个**对话**（= 很多存档）。

---

## 2. 磁盘全景

```
save/                                  ← 数据根目录（所有东西都在这下面）
├─ apps/                               ← 你的应用，一个子目录一个应用
│  └─ <应用ID>/
│     ├─ meta.json                     ★必需：类型、开关、可见性、计费…
│     ├─ role.txt                      角色口吻（放系统提示最前）
│     ├─ charaset.txt                  世界观/人物设定
│     ├─ rules.txt                     输出格式与硬性规则
│     ├─ summary.txt                   长对话压缩指令
│     ├─ prelogue.txt                  开场白
│     └─ …（可选：validator.json / chara.json / back.json / replacements.json / init.txt）
├─ saveData/                           ← 对话存档，一个对话一个 JSON
│  └─ <对话ID>.json                    历史 + 状态 + 费用（明文，可直接看）
├─ config/                             ← 站点配置（凭据、语音、生图等）
│  ├─ object-storage.json              对象存储凭据
│  ├─ image-generation.json            生图凭据
│  ├─ voice-tags.txt                   语音情感切分提示词
│  ├─ local-voice.json / volcano-voice.json  各角色的音色映射
│  ├─ agents.json                      QQ 群机器人清单
│  ├─ crontab.json                     定时任务
│  └─ sdxl/                            生图相关（tag 库、LoRA、参考图、绘画 agent 提示词）
├─ messages.db                         ← 对话索引、权限、额度、账单（SQLite）
├─ site.json                           ← 站点域名
├─ resource/                           ← 立绘、背景等图片资源（网页访问 /aichat/resource/…）
├─ voice/                              ← 生成的语音文件（网页访问 /aichat/voice/…）
├─ painter_images/ ircimages/          ← 绘画页 / QQ 群侧图片
└─ *.html *.js *.css                   ← 网页页面与公共库
```

---

## 3. 应用目录里的文件，谁在读

| 文件 | 哪些类型会读 | 作用 | 必需性 |
|---|---|---|---|
| `meta.json` | 全部 | 应用身份与开关。**缺了整目录被忽略** | 必需 |
| `prompt.txt` | `write` / `sql` / `wuxia` / `galgame` | 系统提示主体 | 这些类型必需 |
| `role.txt` | `talk` / `trpg` / `group` | 角色身份口吻 | 这些类型必需 |
| `charaset.txt` | `talk` / `trpg` / `galgame` / `group` | 设定；压缩时也复用它 | 这些类型必需 |
| `rules.txt` | `talk` / `trpg` / `group` | **输出格式契约**（最重要） | 这些类型必需 |
| `summary.txt` | 全部 | 压缩历史的指令（玩家看不到） | 必需 |
| `prelogue.txt` | `talk` / `trpg` | 取名后的开场白 | 必需 |
| `init.txt` | `galgame` | 开局自动发送的第一句"玩家输入" | 必需 |
| `validator.json` | `talk` / `trpg` | 场景键值的**白名单** | 可选 |
| `chara.json` | `talk` / `trpg` | 立绘选择规则 | 可选（没有就没立绘） |
| `back.json` | `talk` / `trpg` | 背景选择规则 | 可选 |
| `replacements.json` | `talk` | 语音朗读前的文本替换 | 可选 |
| `name-male.txt` / `name-female.txt` | `wuxia` | 随机名字库 | 必需 |
| `rulesfull.txt` | 无人 | 样例里的人工备份，程序不读 | 无用 |

> "必需性"分两档：`galgame` / `custom` 缺文件会**加载失败**（应用直接消失）；
> `talk` / `trpg` / `wuxia` / `write` / `sql` 只会在日志里报错，然后**带病运行**
> ——表现为没立绘、没校验、没开场白，但应用还在。
> 逐类型的精确清单见 [builtin-apps.md](builtin-apps.md)。

---

## 4. `meta.json`：应用的身份牌

最小可用：

```json
{ "type": "talk", "name": "我的角色" }
```

常用字段：

```json
{
  "type": "talk",                 // ★必需，决定用哪套玩法（不能自创）
  "name": "我的角色",              // ★必需，缺了就整个不加载
  "enabled": true,                // false = 彻底停用
  "access": "public",             // public 所有人 / permission 需授权 / hidden 不出现
  "models": ["deepseek/flash"],   // 允许用的模型（不填 = 全部允许）
  "defaultModel": "deepseek/flash",
  "freeNow": true,                // true = 不扣额度
  "paidOnly": false,              // true = 只扣付费余额
  "ui": "painter"                 // 特殊形态（绘画页），一般不用
}
```

⚠️ **`name` 不是界面显示名**。界面显示的名字是程序里写死的（每个类型一个，见
[configuration.md](configuration.md#15-界面显示名对不上)），`name` 只是加载所必需、并且会传给部分类型当角色名。

字段全表与默认值：[configuration.md](configuration.md)。

---

## 5. 一次对话发生了什么

```
① 浏览器打开页面（网址带 ?chatid=xxx&chara/uid …）
        ↓  页面按规则算出应用ID，连上服务端
② 服务端检查：这个对话是你的吗？这个应用对你有权限吗？
        ↓
③ 初始化：解析存档（新对话则新建）
   → 首次进入/重连时会跑一次"初始化"，触发取名、开场白等
        ↓
④ 你发一句话
        ↓
⑤ 服务端按类型组装请求：
   系统提示（role + charaset + rules）+ 压缩过的历史 + 你的新消息
        ↓
⑥ 模型流式返回
   → 服务端边收边解析标记（==对话== / ==场景== …）
   → 正文逐字推给页面，场景标签拿去做选图
        ↓
⑦ 本轮结束：存盘 + 记账 + 通知页面"生成完成"
        ↓
   存档写入 save/saveData/<对话ID>.json，索引写入 messages.db
```

几个和配置直接相关的结论：

- **提示词改了必须重启**：系统提示是在启动时读一次进内存的；
- **重连会重复"初始化"**：所以取名/开场白之类只应发生一次（程序用"阶段"标记控制），
  配错文件会导致每次刷新都重新问名字；
- **每轮都会把系统提示 + 历史全量发给模型**：所以 `charaset.txt` 不是越长越好，
  它每轮都要花额度。

---

## 6. 对话存档长什么样

`save/saveData/<对话ID>.json` 是**明文**，可以直接用记事本看/改：

```json
{
  "state": {
    "intfs": { "场景": { "地点": "客栈", "时间": "夜晚" } },   // 属性组（数值、标签）
    "perks": {},                                             // 增益/标记
    "extras": ["大纲第一条", "大纲第二条"]                      // 自由条目（大纲等）
  },
  "extraData": { "brief": "我的江湖", "limit": "60000" },     // 对话级配置（全部是字符串）
  "row": 3,                                                  // 已完成回合数
  "minRow": 0,                                               // 最早可撤回的回合
  "stage": "STARTED",                                        // INITIALIZE / NAMING / STARTED
  "usage": [ { "credit": 1234 } ],                           // 用量
  "modelHint": "deepseek/flash",                             // 本对话选的模型
  "history": {
    "history": [
      {
        "identifier": 1,
        "role": "USER",                                      // USER / ASSISTANT / SYSTEM / …
        "content": ["自我介绍\n"],                             // 显示内容
        "deleted": false                                     // 撤回是"软删"标记
      }
    ]
  }
}
```

用途：

| 想看什么 | 看哪里 |
|---|---|
| AI 到底输出了什么原文（含被隐藏的格式段） | `history[].content` |
| 玩家当前数值/场景状态 | `state.intfs` |
| 这个对话用的是哪套提示词配置 | `extraData`（`custom` 智能体的核心规则就在这里） |
| 为什么撤回没效果 | `row` / `minRow` 的关系 |

> 手改存档能救急（比如卡在某个阶段、属性写坏了），改完重新打开对话即可。
> 改之前先备份。

---

## 7. 数据库里有什么

`messages.db` 是 SQLite，可以用任意工具打开：

| 表 | 内容 |
|---|---|
| `chats` | 对话索引：谁、哪个应用、什么时候、花了多少、状态（进行中/归档/移除）、名字是否被手动改过 |
| `chatdata` | 每个对话的备注、以及"哪个是该应用的当前存档" |
| `permission` | **应用授权表**：`access: permission` 的应用靠它放行 |
| `daily_usage` | 每人每天的免费额度用量 |
| `user_paid_balance` | 付费余额 |
| `user_free_limit_override` | 单独给某人调高/调低免费额度 |
| `cdk` / `redemption_log` | 兑换码与流水 |
| `daily_checkin` / `paid_usage_record` / `quota_increase_record` | 签到、付费消费明细、额度增加明细 |

> 删对话是"改状态"，不是删文件——存档 JSON 还在，方便恢复。

---

## 8. 页面 ↔ 应用怎么对上

| 页面 | 网址 | 应用ID 怎么来的 |
|---|---|---|
| `agentedit.html` | `/aichat/agentedit` | 固定操作 `custom` 类型 |
| `custom.html` | `/aichat/custom` | 固定 `custom`（对话ID 从网址读） |
| `galgame.html` | `/aichat/aigal` | 网址 `chara` + **`talk`** |
| `trpg.html` | `/aichat/aitrpg` | 网址 `chara` + **`trpg`** |
| `ptrpg.html` | `/aichat/ptrpg` | 网址 `chara` **本身** |
| `painter.html` | `/aichat/painter` | 绘画，配合 `ui: "painter"` |
| `saveman.html` | `/aichat/archive` | 存档管理，按 `meta.json` 的 `url` 跳转 |

⚠️ **文件名和类型不对应**：`galgame.html` 服务的是 `talk` 类型；
`galgame` 类型反而用 `ptrpg.html`。判断依据是上表的"应用ID 怎么来的"，
不是文件名。

⚠️ 另有两个已知问题：`/aichat` 指向的 `chat.html` 已从仓库移除，
所以 `wuxia` / `write` / `sql` **没有现成网页**（只能靠接口或自己配 `custom` 页凑合）。

---

## 9. 站点级配置

`save/config/`（凭据与全局配置）、`save/site.json`（站点域名）、
以及启动参数（模型 token 等），统一见 [configuration.md](configuration.md)。
