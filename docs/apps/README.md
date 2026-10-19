# AIWuxia App 制作文档（无需编程）

AIWuxia 里的每个「AI 应用」= **一套内置玩法（类型）+ 一个应用目录（`save/apps/<应用ID>/`）+ 若干文本文件**。
做应用就是写文本 + 改配置，**不用写代码、不用编译**。

这套文档只讲这条路。想新增一种玩法类型（而不是换皮）才需要编程，
那是另一套文档：[docs/dev/](../dev/java-api.md)。

---

## 文档地图

| 文档 | 内容 | 读它的时候 |
|---|---|---|
| [tutorial.md](tutorial.md) | **三个练习**：网页做智能体 → 复制角色对话 → 做文字冒险 | 第一次上手，跟着做 |
| [prompting.md](prompting.md) | **提示词编写指南**：五个文件怎么分工、格式契约、人设不崩、怎么迭代 | 要调 AI 的说话方式/输出格式 |
| [files.md](files.md) | **文件作用**：三层结构、磁盘全景、每个文件谁在读、存档与数据库、页面怎么对上应用 | 想知道"东西放哪、改哪个文件" |
| [configuration.md](configuration.md) | **配置指南**：`meta.json` 全字段、可见性与授权、计费、站点配置、启动参数 | 要上线、要控权限/费用 |
| [builtin-apps.md](builtin-apps.md) | **8 种内置类型的玩法与文件要求**（含各自的输出格式与坑） | 选类型、查某类型要哪些文件 |
| [troubleshooting.md](troubleshooting.md) | 排错决策树 + 常见改造 + 调试手段 | 出问题了 / 想做点小改动 |

---

## 5 分钟概念

### 三个词

| 词 | 是什么 | 谁创建 |
|---|---|---|
| **类型** | 一套玩法逻辑，共 8 种：`talk`/`galgame`/`trpg`/`group`/`wuxia`/`write`/`sql`/`custom` | 程序内置，**只能在其中选** |
| **应用 / 应用ID** | 一个目录 `save/apps/<应用ID>/`，里面是 `meta.json` + 提示词 | 你建 |
| **对话 / 对话ID** | 一次会话，存成 `save/saveData/<对话ID>.json` | 用户开新对话时自动建 |

```
   类型 talk  ──┬──  应用 fengxitalk  ──┬── 对话 a1b2c3…
    （玩法）     │      （角色+提示词）    ├── 对话 d4e5f6…
                │                        │
                └──  应用 haiyintalk   ──┴── 对话 x9y8z7…
                      （另一个角色）
```

一个类型可以挂很多应用，一个应用可以有很多对话。

### 做应用的最小步骤

```
1. 复制一个同类应用目录 → save/apps/<应用ID>/
2. 改 meta.json 的 name（type 千万别动）
3. 改几个 .txt 提示词
4. 重启服务，看到日志「AI加载成功：<应用ID>」
5. 按页面规则打开网址，用新对话验证
```

具体操作见 [tutorial.md](tutorial.md)。

### 一次对话发生了什么

```
你发消息 → 服务端拼装请求（系统提示 + 压缩过的历史 + 你的新消息）
        → 模型流式返回 → 边收边解析标记（==对话== / ==场景== …）
        → 正文逐字显示，标签用来选立绘/背景
        → 本轮结束：存盘 + 记账
```

关键结论：**每轮都会把系统提示和全部历史发给模型**（所以设定不是越长越好），
**提示词改了必须重启**，**验证提示词要用新对话**。

---

## 三条路，先选对

| 你想做 | 走哪条 | 需要重启 | 看哪篇 |
|---|---|---|---|
| 一个"人设/专家"，随时改提示词 | 网页 `agentedit` 建 **`custom` 智能体** | ❌ | [tutorial 练习一](tutorial.md#sec-ex1) |
| 一个角色/一个世界观，要现成界面 | 复制 `talk`/`galgame`/`trpg` 目录换提示词 | ✅ | [tutorial 练习二、三](tutorial.md#sec-ex2) |
| 群聊机器人 | 复制 `group` 目录 + 配 `agents.json` | ✅ | [troubleshooting §2](troubleshooting.md#sec-qq-group) |
| 全新玩法/新界面 | ❌ 配置做不到 | — | [docs/dev/](../dev/java-api.md) |

---

## 术语表

| 术语 | 含义 |
|---|---|
| **应用ID** | `save/apps/` 下的目录名，也是数据库里记录应用的值。**改名 = 换应用，旧存档会对不上** |
| **类型（type）** | 玩法逻辑，8 选 1，写在 `meta.json` 里 |
| **对话ID** | 一串 32 位十六进制，对应 `saveData/<对话ID>.json` |
| **提示词文件** | 应用目录里的 `.txt`，决定 AI 怎么说话、怎么分段 |
| **标记/格式段** | `==对话==`、`==场景==` 这类独占一行的文本，**程序靠它解析**，不只是排版 |
| **枚举值** | 标签里允许的取值集合（如 `位置=前|侧`），要和白名单一致 |
| **白名单（validator.json）** | 场景标签的允许列表；不在表里的键值会被静默丢弃 |
| **阶段** | 对话的进度状态：未开始 / 取名中 / 进行中。控制开场白是否重复 |
| **压缩/摘要** | 历史太长时把早期对话压成摘要（`summary.txt` 决定怎么压） |
| **配置型智能体** | `custom` 类型，提示词存在对话里，可随时改、不用重启 |

---

## 我想……该看哪篇？

| 我想 | 去哪 |
|---|---|
| 5 分钟做一个能聊的智能体 | [tutorial 练习一](tutorial.md#sec-ex1) |
| 加一个新角色（有立绘有语音） | [tutorial 练习二](tutorial.md#sec-ex2) |
| 做一个带数值的冒险 | [tutorial 练习三](tutorial.md#sec-ex3) |
| 让 AI 输出稳定的格式 | [prompting.md](prompting.md) |
| 修人设崩/说话太啰嗦 | [prompting.md §4、§5](prompting.md#sec-persona) |
| 查某个类型要哪些文件 | [builtin-apps.md](builtin-apps.md) |
| 搞清哪个文件是干嘛的 | [files.md](files.md) |
| 只让部分人能用 / 免费 / 计费 | [configuration.md §1、§3](configuration.md) |
| 接 QQ 群 | [troubleshooting §2](troubleshooting.md#sec-qq-group) |
| 配音色/域名/存储凭据 | [configuration.md §5、§6](configuration.md) |
| 应用不出现、没反应、格式乱 | [troubleshooting.md](troubleshooting.md) |
| 新增玩法类型 / 新页面 | [docs/dev/java-api.md](../dev/java-api.md)（需要编程） |
