# 开发者参考：Java 改造配方与排错

> **面向开发者**：本篇涉及 Java 代码，供**要新增 app 类型（`type`）**的人参考。
> 只想用配置和提示词做出 app，不需要写代码 —— 看 [`docs/apps/`](../apps/README.md) 那套教程。

---
每个配方都是「目标 → 改哪里 → 怎么做 → 怎么验证」。API 细节见 [java-api.md](java-api.md)，
字段含义见 [configuration.md](../apps/configuration.md)。

---

## 配方 1：加一个新角色（纯配置，5 分钟）

目标：`type: talk` 再加一个角色，不写一行 Java。

```bash
mkdir -p save/apps/myhero
cp save/apps/fengxitalk/*.txt save/apps/myhero/     # 借现有角色当模板
cp save/apps/fengxitalk/validator.json save/apps/myhero/ 2>/dev/null
```

1. 改 `save/apps/myhero/meta.json`：

   ```json
   { "type": "talk", "name": "我的角色", "enabled": true, "access": "public" }
   ```

2. 改 `role.txt`（身份口吻）、`charaset.txt`（设定）、`rules.txt`（保留 `==对话==`/`==场景==` 格式！）、
   `prelogue.txt`（开场白）。`summary.txt` 可不改。
3. 重启服务，日志应有 `AI加载成功：myhero`。
4. 打开 `aigal?chara=myhero&uid=test` —— 因为页面把 `chara` 拼成 `chara + "talk"`，
   所以 appid 必须是 `<chara>talk`。**目录名不满足这个后缀就用不了这个页面**（可改名，或改用 `custom` 页）。

验证：能取名、能收到带 `==场景==` 的回复、立绘按 `位置` 切换。

> 坑：`validator.json` 是白名单，模板里的事件键（地点/服装…）必须与你 `rules.txt` 里要求的一致，
> 否则场景标签会被整行丢弃 → 触发重试甚至"没立绘"。

---

## 配方 2：让 app 输出严格 JSON

目标：不走"标记 + 行解析"，直接要结构化数据。

```java
Builder b = AIRequest.builder(state)
        .taskType(TaskType.STORY)
        .format(ResponseFormat.JSON)      // ① 让提供商按 JSON 输出
        .temperature(0.6f)                // ② 结构化任务温度降低
        .maxTokens(4096);
b.addHistoryItem(Role.SYSTEM, systemPrompt + JSON_SCHEMA_HINT);  // ③ schema 写进提示
AIOutput resp = LLMConnector.call(b.build());
String text = resp.getContentText();      // 非流式场景一次读完

if (JSON_PARSER_FAILED) {
    // ④ 重问：把错误也带上，最多重试 N 次，全败则回滚（见 java-api.md §4）
    write.rollback();
    throw new RegenerateNeededException(snapshot);   // 内置 app 的通用重试机制
}
MyData data = GSON.fromJson(text, MyData.class);
```

要点：

- `format(ResponseFormat.JSON)` 只是**请求**，不保证模型不掺废话；`prompt` 里仍要写清"只输出 JSON，不要解释"。
- 解析失败的重试要**有上限**（内置 app 用 5~8 次），并且每次 `resp.interrupt()` + 回滚状态快照。
- 想复用"校验失败就重来"的骨架，照抄 `AICharaTalkMain` 的 `while(i<8) { try { ... } catch (RegenerateNeededException e) { ... } }`。

验证：故意让模型多写一句话，确认会重试而不是把脏文本写进历史。

---

## 配方 3：加数值状态（血量/好感/天数）

```java
// 读：按名取一组属性（不存在就建）
AttributeSet hp = state.getState().getOrCreateInterface("hp");
int cur = Integer.parseInt(hp.get("current") == null ? "100" : hp.get("current"));

// 算：你自己决定怎么变
hp.put("current", String.valueOf(Math.max(0, cur - damage)));

// 写快照：让「撤回/重新生成」能连状态一起回滚
ApplicationState snapshot = new ApplicationState(state.getState());   // 深拷贝
// ... 处理完回复之后 ...
write.setLastState(snapshot);
```

- 属性全是字符串，`AttributeSet` 只提供 `get/put/contains/clear/isEmpty/getAsMap` 与遍历；
- 要先快照再改 live state，顺序反了就没法回滚；
- 想校验模型给的值是否合法，用 `state.status.AttributeValidator`（`validator.json` 的
  `{"rules":[{"keyPattern":正则,"valuePatterns":[正则…]}]}`）：只接受白名单键值。

验证：跑两轮后「撤回」，面板应回到上一轮数值（`wuxia` 就是这套）。

---

## 配方 4：给 app 加一个工具（如查天气）

```java
// 1) 描述工具：名字、描述、参数（名称 → 说明），回调拿到的是参数 JSON 字符串
ToolData weather = new ToolData.Builder("get_weather", "查询城市天气")
        .putParam("city", "城市名，例如：北京")
        .tool(paramsJson -> {                          // Tool 接口：String run(String params)
            JsonObject args = JsonParser.parseString(paramsJson).getAsJsonObject();
            return fetchWeather(args.get("city").getAsString());   // 返回字符串给模型
        })
        .build();

// 2) 挂到会话（构造 app 时或 app 初始化时）
MCPTools tools = new MCPTools();
tools.register(weather);
state.registerTools(tools);      // 之后 AIRequest.builder(state) 自动带上
```

要点：

- 工具**必须**在 `AIRequest.builder(state)` 之前挂上，否则本次请求不带；
- 生产环境建议挂审批（`ManagedToolCallApprover` / `ToolCallPermission`），避免模型乱调写操作；
- 工具调用过程会用 `tool_call` 帧推给前端，`ToolCallDisplay` 控制展示；
- 计费：工具内部产生的模型调用要自己 `addUsage`，否则漏账；**按次计费**的外部接口（如
  `DeepseekSearchMcp` 的 `web_search`）要在确认成功并拿到结果之后再记账，失败、超时与空结果不要记，
  见 `DeepseekSearchUsage`。

验证：问一个需要工具的问题，日志应出现工具名与参数，前端出现工具调用卡片。

---

## 配方 5：加一个新前端页面

后端只需两步：

```java
// AIChatService
@HttpMethod("GET") @HttpPath("/myui") @Adapter
public ResultDTO myui() throws IOException {
    return new ResultDTO(200, new File(parent, "myui.html"));
}
```

前端（`save/myui.html`）**不要自己写 WebSocket 管理**，复用公共协议层：

```html
<script src="site-config.js"></script>
<script src="vue.js"></script>
<script src="theme.js"></script>
<script src="heartbeat.js"></script>
<script src="ai-protocol.js"></script>
<script>
new Vue({
  el: '#app',
  mixins: [AIProtocolMixin],          // 连接/重连/心跳/帧路由/历史分页/打字机全给你
  data: { conversationId: '', messages: [] },
  methods: {
    // 必须实现：连接地址
    aiSocketUrl() {
      return aiSiteWsBase() + '/aichat/chatsocket?chatid=' + encodeURIComponent(this.conversationId)
           + '&app=' + encodeURIComponent('myapp') + '&userId=' + encodeURIComponent(aiUid());
    },
    aiOnMessage(frame) { this.messages.push(frame); },   // 按需实现帧钩子
  },
});
</script>
```

- 页面里的站点地址一律用 `aiSiteWsBase()` / `aiSiteHttpBase()`，别写死域名；
- 让应用指向这个页面：`meta.json` 的 `url`（存档页入口）或 `ui`（会话形态）；
- 帧类型清单见 `save/ai-protocol.js` 文件头注释。

验证：刷新页面能自动重连、断网后能退避重连、历史能向上翻页。

---

## 配方 6：调上下文压缩策略

| 想达到的效果 | 改哪里 |
|---|---|
| 更早触发压缩（省 token） | 调小 `HistoryCompactor.compact(history, limit, min, ...)` 的 `limit` |
| 保留更多原文 | 调大 `min`（保留下限） |
| 换摘要提示词 | 改该 app 目录的 `summary.txt`（`HistoryCompacter` 的 system prompt） |
| 完全不做压缩 | 什么都不写（`write` / `sql` 就是这样，长文本必然撞上限） |
| 硬截断而不是摘要 | 自己 `setValidContext(hi, false)` 从最旧开始丢（`wuxia` 的做法） |

压缩是**同步 LLM 调用**，发生在生成线程上：阈值设得太小 → 每几轮就卡一次。
验证方法：把阈值临时调低（如 5000），跑几轮看日志是否出现摘要请求、前端是否明显卡顿。

---

## 配方 7：让 app 支持语音

```java
@Override
public CompletableFuture<Boolean> generateVoice(AISession state, String text, String audioId) {
    return CompletableFuture.supplyAsync(() -> {
        try {
            MediaResult r = VoiceModelHandler.getAudioData(
                    state.getExtraData().voiceModel,   // 会话选定的语音模型
                    charaName,                          // 音色键，对应 save/config/*-voice.json
                    state.getUser(), text, audioId, state::addUsage);
            writeMp3(audioId, r);                       // 落到 save/voice/<audioId>.mp3
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    });
}
```

- 音色映射在 `save/config/local-voice.json`（`{角色名: botid}`）或 `volcano-voice.json`；
- 前端 `genVoice` 帧或自动语音（`isAudioSession`）触发；成功后要 `write.setAudioId` / `postAudioComplete` 才会出现播放按钮；
- 语音文本先过一遍替换（`talk` 的 `replacements.json`）再送去 TTS，避免把 `（动作描写）` 念出来。

验证：开启语音后回复出现播放按钮，`save/voice/` 下有新 mp3。

---

## 配方 8：把 app 搬到 QQ 群

`group` 类型由 `NapCatAIConnector` 拉起，走 `save/config/agents.json`：

```json
[
  { "name": "myhero", "save": "qq-myhero.json", "voice": "本地" }
]
```

- `name` = `save/apps/` 下的 appid；`save` = 该群会话的存档文件名（在 `save/saveData/` 下）；
- app 目录必须有 `meta.json`（`type: "group"`）和 `role/charaset/rules/summary` 四个 txt；
- `meta.charaName` **必需**，是机器人在群里的自称；
- 群侧工具（生图/搜索/QQ 操作）由 NapCat 按 skill 注册，和网页侧是两套配置。

启动：`java -Dnapcat_url=127.0.0.1:3001 -Dnapcat_token=xxx -cp ... com.khjxiaogu.aiwuxia.NapCatAIConnector`。

验证：群里 @ 机器人能收到回复；日志有 `发送消息成功`；`saveSaveData/qq-myhero.json` 在增长。

---

## 排错决策树

```
问题：应用不出现 / 打不开
├─ 启动日志有「忽视AI：X」？
│   ├─ 「出于配置原因」 → enabled=false 或缺 name
│   └─ 「由于找不到元数据」 → 没有 meta.json
├─ 启动日志有「AI加载失败：X」？
│   └─ 往上翻堆栈：多为必需 txt 缺失（galgame/custom 直接抛异常）
├─ 日志「AI加载成功」但列表里没有？
│   └─ access=permission 且 permission 表没授权（或 access=hidden）
└─ 列表里有但页面 404？
    └─ 该 type 没有对应页面（wuxia/write/sql 的 chat.html 已删除）
```

```
问题：能连上但发消息没反应
├─ 输入框内容被弹回？
│   ├─ 有 notice 提示"token限额已达到" → 配额（checkTokenUse）
│   └─ 无提示 → 模型路由失败（没配 -Dxxxxtoken，看日志 ModelRouteException）
├─ 一直"生成中"？
│   └─ handler 里阻塞在流读取，或没读完 reasoner
└─ 有回复但不显示？
    └─ append(x, false) 只写上下文；或 shouldSend=false
```

```
问题：回复格式乱 / 状态不更新
├─ 模型没按标记输出？
│   └─ 看 saveData/<chatid>.json 里 content 原文（存档是明文的）
├─ 标记在，但属性没变？
│   └─ validator.json 白名单把它丢了（未列举的键一律丢弃）
└─ 重试很多次、很贵？
    └─ 截断导致 RegenerateNeededException：调大 maxTokens，或把标记段挪到正文前
```

```
问题：撤回/重新生成异常
├─ 完全没反应 → doRevert 要求 dialogRow > minDialogRow
├─ 历史回滚了但数值没回滚 → 写记录时没 setLastState
└─ 会话名变成奇怪的短串 → getBrief 的截取逻辑（sql 恒 "QL"，write 吞首字）
```

---

## 调试手段速查

| 手段 | 用途 |
|---|---|
| `latest.log` | 服务端 stdout/stderr 全在这（`AIServerMain` 重定向），加载失败、路由异常都先看它 |
| `save/saveData/<chatid>.json` | 会话明文存档：历史、`extraData`、`state`、`stage` 一目了然 |
| `messages.db` | 对话索引/费用/权限，`sqlite3` 直接查 |
| `printReasonerContent(resp)` | 临时把模型思维链打到控制台 |
| 把阈值调小 | 快速验证压缩路径（配方 6） |
| `?uid=test` 多开窗口 | 多用户/多会话隔离测试 |
