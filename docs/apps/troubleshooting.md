# 排错与常见改造

---

## 1. 排错决策树

### 应用不出现 / 打不开

```
列表里没有这个应用
├─ 启动日志有「忽视AI：X」？
│   ├─ 后面写「出于配置原因」 → enabled=false，或缺 name 字段
│   └─ 后面写「由于找不到元数据」 → 目录里没有 meta.json
├─ 启动日志有「AI加载失败：X」？
│   └─ 该类型必需的文件缺失（galgame/custom 会直接失败），或 meta.json 格式错
├─ 日志「AI加载成功」但列表里没有？
│   └─ access = permission 但没授权（或 access = hidden）
└─ 列表里有，但点了 404
    └─ 该类型没有对应网页（wuxia / write / sql，见 builtin-apps 的已知问题）
```

### 能连上，但发消息没反应

```
输入框里的字被弹回来了
├─ 页面有提示「token限额已达到」 → 额度用完了（等明天/签到/充值）
├─ 提示「该服务暂不可用」 → 模型提供商没配好（见下一条）
└─ 没有任何提示 → 大概率是模型路由失败：启动参数里一个模型 token 都没配

一直显示"生成中"
└─ 那一轮卡在模型返回上：看 latest.log 有没有超时/报错；网络或 token 配额问题

有回复但页面不显示
└─ 提示词没按格式输出（格式段被当正文吞了）→ 看 prompting.md 的格式契约
```

### 输出格式乱 / 状态不更新

```
AI 回复里能看到 `==场景==` 这种标记（本来该被隐藏）
└─ 标记没写对：行首有空格、和正文同一行、被包在代码块里 → 见 prompting.md

立绘/背景不换
├─ rules.txt 里没让模型输出对应的键（地点/时间/表情/位置）
├─ 输出的值不在 validator.json 白名单里 → 被静默丢弃
└─ chara.json / back.json 里没有匹配这个值组合的条目

数值/天数不更新
└─ 同上：要么没输出，要么被白名单丢了

对话一轮比一轮慢
└─ 历史变长 + 压缩被触发（压缩本身要再调一次模型）。开新对话，或收紧 rules 的字数要求
```

### 语音不响

```
没有播放按钮
├─ 页面上的语音开关没打开
└─ 服务端没生成音频（见下）

有按钮但播放失败
├─ save/config/local-voice.json 或 volcano-voice.json 里没有这个角色的音色映射
└─ 语音模型没配好（启动参数 localVoiceToken / volcsecret 等）

念出了「（动作描写）」
└─ 在 save/apps/<应用ID>/replacements.json 里加一条正则把括号内容换成空
```

### 撤回 / 重新生成异常

```
点了完全没反应
└─ 只在"有过至少一轮完整对话"后才生效；新建对话立即撤回会无效

历史回滚了，但数值没回滚
└─ 该类型本身不支持状态回滚（galgame / write / sql / custom 都不支持）

对话标题变成奇怪的短串
└─ 该类型会自动取内容当标题；手动重命名一次即可永久固定
```

---

## 2. 常见改造（都不用改代码）

### 换应用名字

- `talk` / `galgame` / `trpg` / `group`：改 `meta.json` 的 `name`（它就是角色名）；
- `wuxia` / `write` / `sql` / `custom`：界面名字是程序固定的，**改不了**（除非改代码）。

### 给角色换音色

1. 打开 `save/config/local-voice.json`（本地语音）或 `volcano-voice.json`（火山）；
2. 加一行 `"角色名": "音色ID"`（键 = 你在 `meta.json` 里写的 `name`）；
3. 重启，进入对话打开语音开关。

<a id="sec-qq-group"></a>
### 接一个 QQ 群

1. 复制一个 `group` 类型的应用目录（如 `save/apps/painterirc`），改 `meta.json`：
   `type` 保持 `group`，必须写 `charaName`，`enabled` 改成 `true`；
2. 写 `role.txt` / `charaset.txt` / `rules.txt` / `summary.txt`；
3. 在 `save/config/agents.json` 里加一条：

   ```json
   { "name": "你的应用ID", "save": "qq-你的应用ID.json", "voice": "本地" }
   ```

4. 用 NapCat 的启动参数拉起（`-Dnapcat_url` / `-Dnapcat_token`），重启。
5. 验证：群里 @ 机器人有回复；`save/saveData/qq-*.json` 在增长。

### 让某个应用完全免费 / 只走付费

- 完全不扣额度：`"freeNow": true`
- 只扣付费余额：`"paidOnly": true`
- 两者都写时以 `paidOnly` 为准。详见 [configuration.md §1.1](configuration.md#sec-meta-framework)

### 只开放给部分人

1. `meta.json` 写 `"access": "permission"`；
2. 往数据库 `permission` 表插一条 `(uid, app, '1')`（SQL 见
   [configuration.md §3](configuration.md#sec-access)）；
3. 重启后该用户才能看到。

### 对话卡在奇怪的状态（一直要取名 / 一直重复开场）

1. **先开一个新对话**试试（旧存档可能带着坏状态）；
2. 还不行就备份后手动改存档 `save/saveData/<对话ID>.json`：
   把 `"stage"` 改成 `"STARTED"`，删掉多出来的开场记录；
3. 修提示词——重复开场通常是"初始化"被反复触发，而它由阶段标记控制。

### 某段剧情想从头来

- 用页面的「+ 新对话」新建一个存档（旧存档保留，可随时切回）；
- 不要在同一条对话里"删干净"——上下文和状态都会残留。

### 想让 AI 输出固定的数据格式

- 首选：选一个**已经有解析器**的类型（`talk`/`trpg`/`galgame`/`wuxia`），
  按它的标记写内容，格式有人兜底；
- `custom` 也能做到，但完全靠提示词，稳定性靠你自己测（写法见 [prompting.md](prompting.md)）。

### 想加一个全新的网页界面

这需要改代码（加一个页面端点 + 写前端页面），不在本文档范围内：
见 [docs/dev/java-api.md](../dev/java-api.md)。

---

## 3. 调试手段

| 手段 | 用途 |
|---|---|
| `latest.log` | 服务端所有输出。应用加载失败、模型报错、额度提示都先看它 |
| `save/saveData/<对话ID>.json` | **明文**存档：模型原文（含被隐藏的格式段）、状态、阶段、用量 |
| `messages.db` | 对话索引/费用/授权，任意 SQLite 工具可开 |
| 换一个 `uid` 打开 | 排除"权限/额度/历史"干扰，用干净用户复现 |
| 开新对话 | 排除旧上下文，验证提示词改动的唯一可靠方式 |
| 删 `validator.json` | 快速判断"是不是白名单把值丢了" |
| 删 `chara.json` / `back.json` | 快速判断"是不是没匹配到图" |

**定位"格式问题"的三步**：

1. 打开存档 JSON，看 `history` 里最后一条 ASSISTANT 的 `content` —— 那是模型**原文**；
2. 对比 [builtin-apps.md](builtin-apps.md) 里该类型的标记清单，看标记是否齐全/独占一行；
3. 缺什么就在 `rules.txt` 里补一句更强的约束，然后**重启 + 新对话**再验。

---

## 4. 什么时候必须改代码

| 需求 | 能只靠配置吗 |
|---|---|
| 换角色、换世界观、换文风 | ✅ 改提示词 |
| 换音色、接群、调可见性、调计费 | ✅ 改配置 |
| 用 `custom` 做出全新玩法的智能体 | ✅ 但格式稳定性自负 |
| 新的输出标记 / 新的解析规则 | ❌ 要改代码 |
| 新的网页界面 | ❌ 要改代码 |
| 新的模型提供商 | ❌ 要改代码 |

改代码相关：[docs/dev/java-api.md](../dev/java-api.md)
