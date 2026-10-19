# AIChatApp
# AI 对话系统 (AI Chat Application)

一个基于 Java 的 AI 对话框架，支持动态加载多种 AI 应用类型（武侠、Galgame、TRPG、角色对话等），集成了大语言模型（LLM）和本地语音合成服务，提供 WebSocket 和 HTTP 接口供前端调用。系统内置会话管理、历史记录、权限控制、费用统计等功能，可快速构建个性化的 AI 交互应用。
本文档由AI生成，如有错漏以源代码为准。

---

## 特性

- **多 AI 应用支持**：通过 `AIApplication` 抽象类和工厂模式，可轻松扩展不同类型的 AI 应用。
- **动态加载**：扫描应用目录下的 `meta.json` 配置，自动加载启用的 AI 应用，支持热重载。
- **会话管理**：每个对话拥有独立的历史记录、状态、推理内容，支持撤回、重新生成等操作。
- **流式推理**：支持 AI 模型输出推理内容（思维链）和最终内容，并实时推送到前端。
- **本地语音模型**：集成私有化部署的语音合成服务，通过 WebSocket 连接和 HTTP 回调接收音频数据。
- **权限与公测**：基于 SQLite 数据库记录用户权限，支持应用级的公测限制（每个用户仅可创建一个对话）。
- **对话导出**：可将整个对话历史导出为 UTF-8 文本文件（带 BOM 头）。
- **模型路由**：通过 `ModelProvider` 接口支持多模型提供商（Deepseek、火山引擎等），根据请求类型自动路由。
- **网页搜索**：通过 MCP 工具 `web_search` 接入 DeepSeek 官方 Search API（需 `deepseektoken`），
  按成功请求次数准确计费，失败/无结果不计费。
- **前后端分离**：前端通过 WebSocket 进行实时通信，HTTP 接口用于获取应用列表、对话列表等。

---

## 技术栈

- **Java 8+**：核心开发语言
- **Netty**：WebSocket 和 HTTP 服务底层
- **SQLite**：存储对话列表、权限、费用信息
- **Gson**：JSON 序列化/反序列化
- **WebSocket**：实时双向通信
- **大语言模型 API**：Deepseek、火山引擎等
- **本地语音模型**：自定义 WebSocket 协议 + HTTP 回调

---

## 项目结构

```
├─AIAppMain.java              # 独立窗口模式入口（Swing）
├─AIChatService.java           # 核心服务类，处理HTTP/WebSocket请求，管理会话
├─AIChatWindow.java            # 测试用Swing窗口
├─AIServerMain.java            # 主服务器入口，启动Netty服务
├─NapCatAIConnector.java       # 与NapCat机器人框架的适配器（把QQ消息交给AIWuxia）
├─DeepseekHarnessConnector.java # 把 Deepseek Harness 接到 NapCat（把QQ消息交给 DSH，方向相反）
│
├─apps                         # AI应用相关
│      AIApplication.java          # 抽象AI应用基类
│      AIApplicationFactory.java   # 工厂接口
│      AIApplicationRegistry.java  # 工厂注册中心
│      AIArticleMain.java          # 文章写作应用
│      AICharaTalkMain.java        # 角色对话应用
│      AIGalgameMain.java          # Galgame风格应用
│      AIGroupApplication.java     # 群组应用
│      AISQLMain.java              # SQL查询应用
│      AITRPGSceneMain.java        # TRPG跑团应用
│      AIWuxiaMain.java            # 武侠世界应用
│
├─commands                    # 控制台命令处理（未列出具体文件）
│
├─llm                         # 大语言模型连接层
│  │  AIOutput.java                # 模型输出封装（推理流、内容流）
│  │  AIRequest.java               # 统一请求实体（Builder模式）
│  │  DefaultModelRouter.java      # 默认路由实现
│  │  LLMConnector.java            # 静态入口，初始化模型提供商
│  │  ModelProvider.java           # 模型提供商接口
│  │  ModelRouteException.java     # 路由异常
│  │  ModelRouter.java              # 路由接口
│  └─providers
│         DeepseekModelProvider.java   # Deepseek API实现
│         VolcanoModelProvider.java    # 火山引擎API实现
│
├─respscheme                  # 响应方案（AI结构化输出）
│      Choice.java
│      RespScheme.java
│      Usage.java                  # Token用量统计
│
├─scene                        # 场景选择器（用于AI决策）
│      AICharaSceneBuilder.java   # 角色场景构建器
│      Endable.java               # 构建链结束标记
│      SceneBuilder.java          # 场景构建器抽象
│      SceneSelector.java         # 场景选择器核心
│
├─state                        # 会话状态管理
│  │  ApplicationStage.java        # 应用阶段枚举
│  │  RegenerateNeededException.java
│  │  Role.java                    # 对话角色枚举
│  ├─history
│  │      HistoryHolder.java       # 历史条目容器
│  │      HistoryItem.java         # 历史条目接口
│  │      HistoryMemoryItem.java   # 内存实现条目
│  │      MemoryHistory.java       # 内存历史容器
│  ├─session
│  │      AIGroupSession.java      # 群组会话
│  │      AISession.java           # 基础会话类
│  │      AppAISession.java        # 应用级会话
│  │      WebSocketAISession.java  # WebSocket绑定的会话
│  └─status
│         ApplicationState.java    # 应用状态（界面相关）
│         AttributeSet.java        # 属性集合接口
│         AttributeValidator.java  # 属性验证器
│         MemoryAttributeSet.java  # 内存属性集
│
├─tools                        # 工具类
│      AISummary.java               # 对话摘要生成
│      InteractiveConclusionHandler.java
│      ParameterLimitGenerator.java # 参数限制生成
│      SceneGenerator.java          # 场景生成器
│
├─utils                        # 通用工具
│      BlockingReader.java          # 阻塞读取器
│      ClientTruncatedException.java
│      FileUtil.java                 # 文件读写工具
│      HttpRequestBuilder.java       # HTTP请求构造
│      JsonBuilder.java              # JSON构建器
│      ReverseIterator.java          # 反向迭代器
│      RoundRobinObjectPool.java     # 轮询对象池
│
└─voice                        # 语音模型相关
       LocalModelHandshaker.java    # 本地语音模型WebSocket握手器
       LocalVoiceModel.java         # 静态入口类
       VoiceModel.java              # 语音模型接口
       VoiceModelHandler.java       # 语音模型处理器
       VoiceModelLocalServer.java   # 本地语音模型HTTP服务器端点
       VolcanoVoiceApi.java         # 火山引擎语音API实现
       WavStreamExporter.java       # WAV流导出
```

---

## 快速开始

### 环境要求

- JDK 1.8 或更高版本
- SQLite（无需安装，使用 JDBC 驱动）
- Maven（可选，用于构建）

### 配置文件

系统主要通过**系统属性**配置各项 Token 和路径：

| 属性名               | 说明                             |
|-------------------|--------------------------------|
| `deepseektoken`   | Deepseek API 的 Token，若存在则启用 Deepseek 模型提供商，并启用网页搜索工具 `web_search` |
| `volcmodeltoken`  | 火山引擎模型 Token，若存在则启用火山模型提供商           |
| `localVoiceToken` | 本地语音模型的 Bearer Token，用于验证 WebSocket 连接 |
| `site_host`       | 站点对外域名的临时覆盖（一般不用传，改 `save/site.json` 即可） |

启动时通过 `-D` 参数传入，例如：
```bash
java -Ddeepseektoken=your_token -DlocalVoiceToken=voice_token -jar aichat.jar
```

#### 站点域名：`save/site.json`

站点域名不再写死在 HTML 里，默认从数据目录下的 `save/site.json` 读取：

```json
{
  "host": "example.com",
  "reader_host": "www.example.com"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `host` | 否 | 站点对外域名。可写裸域名 `example.com`、带端口 `example.com:8443` 或完整地址 `https://example.com`；写成 `http://...` 会得到 `ws`/`http` 地址。**留空或文件不存在时，前端自动跟随访问页面的域名**（`location.host`），Java 端回退到 `SiteConfig.DEFAULT_HOST` |
| `reader_host` | 否 | 图片查看页（`/works/imgView.html`）所在域名，缺省与 `host` 相同 |

优先级：启动参数 `--site-host=example.com` / `-Dsite_host=example.com` > `save/site.json` > 跟随访问域名。
需要临时覆盖时用启动参数（对 `AIServerMain` 与 `NapCatAIConnector` 都生效）：

```bash
java -jar aichat.jar --site-host=example.com
# 等价于
java -Dsite_host=example.com -jar aichat.jar
```

修改 `save/site.json` 后无需重启前端：每次打开页面都会重新拉取 `/aichat/site-config.js`。

#### 站点配置目录：`save/config/`

其余手工维护的配置统一放在 `save/config/`，文件名可读，路径由 `ConfigPaths` 统一定义；
生图（SDXL / Seedream）相关的配置集中在 `config/sdxl/`：

```
save/
├─ site.json                     # 站点域名（见上一节）
└─ config/
   ├─ object-storage.json        # 对象存储（火山 TOS）凭据：region / endpoint / bucket / accessKey / secretKey
   ├─ image-generation.json      # 图片/视频生成（Seedream）凭据：accessKey / secretKey
   ├─ voice-tags.txt             # 语音标签提示词（按情感切分台词，供语音合成）
   ├─ local-voice.json           # 本地语音模型 bot 映射 {角色名: botid}
   ├─ volcano-voice.json         # 火山语音复刻 bot 映射 {角色名: voice_type}
   ├─ agents.json                # QQ 群 agent 清单（name / save / voice …）
   ├─ crontab.json               # 定时任务
   └─ sdxl/                      # 生图相关
      ├─ character-tags.txt      #   角色 tag 库（一行一个，生成提示词时按关键词检索）
      ├─ loras.json              #   LoRA 配置 {触发词: {key, weight}}
      ├─ reference-images.json   #   参考图 {名称: 图片地址}
      └─ agent/                  #   绘画 agent 提示词
         ├─ role.txt             #     角色
         ├─ charaset.txt         #     角色设定
         └─ rules.txt            #     生成规则
```

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

新位置不存在时会退回旧位置并在控制台提示一次，**新位置一旦存在旧位置即被忽略**，
建议直接把文件挪进 `save/config/`。详见 `save/config/README.md`。

> 🚚 **从旧版本升级**：配置目录与站点域名（`save/site.json`）的完整迁移步骤、可重复执行的搬迁脚本、
> 验证清单与回滚方法，见 **[docs/MIGRATION.md](docs/MIGRATION.md)**。

### 运行主服务

入口类：`AIServerMain`  
启动后将监听指定端口（默认根据 Netty 配置），提供 WebSocket (`/chatsocket`) 和 HTTP 静态文件服务。

```java
public static void main(String[] args) {
    // 初始化 LLM 连接器
    LLMConnector.initDefault();
    // 启动 Netty 服务器
    // ...
}
```

### 前端访问

- 静态页面位于服务根目录下（如 `aiindex.html`, `chat.html`），通过 HTTP 访问。
- WebSocket 连接地址：`ws://host:port/chatsocket?chatid=xxx&app=xxx&userId=xxx`
- 页面里**不再写死站点域名**：服务端通过 `/aichat/site-config.js` 下发 `window.AI_SITE`
  （内容来自 `save/site.json` 的 `host`），各页面用 `aiSiteWsBase()` / `aiSiteHttpBase()`
  （见 `save/ai-protocol.js`）拼接地址；未配置时这两个函数回退到 `location.host` / `location.protocol`，
  所以反代、换域名、本地调试都不用改 HTML。

### 让 Deepseek Harness 接管 QQ（DeepseekHarnessConnector）

`DeepseekHarnessConnector` 与 `NapCatAIConnector` 方向相反：不再由 AIWuxia 的 AI 应用作答，
而是把 QQ 消息转给 **Deepseek Harness** 运行时，再把它的输出转发回 QQ。

工作方式：

1. 以 `dsh --profile web` 拉起 Deepseek Harness：这个表层会起一个**浏览器界面**，
   并通过 `--patch` 覆盖层补上 SDK 的 stdio JSON-RPC 服务端。两边在同一进程里，
   所以**界面里看到的正是连接器驱动的会话**。启动日志会打印带 token 的界面地址，
   默认还会自动打开浏览器；
2. 作为 OneBot v11 正向 WebSocket 客户端连接 NapCat；
3. **DSH 的输出先转成项目自己的消息模型**再发送：正文与思维链累积为
   `MutableMessageContents`（纯文本段落），工具调用转成 `ToolCallContent`，工具结果转成 `ToolContent`。
   发送时统一遍历 `MessageContents`，因此新增内容类型只需改一处；
4. **只处理指定机器人在指定群的群消息**：`bot` / `group` 把本实例钉死在一个机器人账号的一个群里，
   其它群、其它账号的事件直接忽略；**私聊消息一律不处理**；群消息还必须 at 机器人才投递；
5. **不再按行拆分**：换行随文本一起保留，整段文本发成一条消息（超过 `dsh_msg_limit`
   才按纯字数切开）。发送是**流式**的：单行攒到 `dsh_flush_line`（默认 700）字、或多行累计到
   `dsh_flush_multiline`（默认 400）字就立刻推送；遇到**工具调用/工具结果**时也会先把已攒文本冲掉、
   再把工具消息立刻发出，不必等整轮结束（同一次调用的多个工具合并成一条，只显示工具名）；
   投递给模型的每条消息都会重申「本群管理员是 QQ `adminId`」，非管理员的发言会带
   `untrusted="true"` 与 `[不可信]` 标记（涉及提权、删除、外发等要求先取得管理员同意）；
6. **工具调用许可**：DSH 的审批 seam 只在进程内分发、SDK 协议并不承载审批，所以连接器用
   `--patch` 覆盖层插入 `approval-bridge.mjs`，该插件把 `approval/request`
   经本机回环 TCP 转给连接器，连接器在 QQ 里问「允许/拒绝」，再把决定原路返回。

```bash
java -Dnapcat_url=127.0.0.1:3001 -Dnapcat_token=your_token \
     -Ddsh_provider=deepseek-account -Ddsh_model=deepseek-flash \
     -cp "target/AIWuxia-0.0.1-SNAPSHOT.jar;target/aiwx_lib/*" \
     com.khjxiaogu.aiwuxia.DeepseekHarnessConnector
```

| 属性名                    | 默认值                | 说明                              |
|------------------------|--------------------|---------------------------------|
| `napcat_url`           | `127.0.0.1:3001`   | NapCat 的 OneBot 正向 WS 地址 host:port |
| `napcat_token`         | 空                  | OneBot 访问令牌                     |
| `bot`                  | `0`                | 只处理这个 QQ 号（bot 账号）收到的消息，`0` = 不限制 |
| `group`                | `0`                | 只处理这个群的消息，`0` = 不限制            |
| `adminId`              | `0`                | 管理员 QQ 号；每次投递都会声明，非管理员发言标记为不可信 |
| `dsh_home`             | `~/.dsh`           | Deepseek Harness 的 DSH_HOME      |
| `dsh_profile`          | `web`              | 启动的 dsh profile：`web` 带浏览器界面，`sdk` 纯无头 |
| `dsh_web_port`         | `0`                | web 表层的端口，`0` 由系统分配（避免和桌面版 19387 冲突） |
| `dsh_open_browser`     | `true`             | 是否让 web 表层自动打开浏览器（也可用日志里的地址手动打开） |
| `dsh_agent_preset`     | `standard`         | agent preset：web 表层的**工具与提示词都由它挂载**，不设就没有工具 |
| `dsh_root`             | 自动探测               | Deepseek Harness 安装根目录          |
| `dsh_launcher`         | 自动探测               | 启动器路径（`DeepSeek Harness.exe` 或 `dsh.cmd`） |
| `dsh_provider`         | `deepseek-account` | 模型提供方路由                         |
| `dsh_model`            | `deepseek-flash`   | 模型 id                           |
| `dsh_reasoning`        | `high`             | 推理强度，留空则不指定                     |
| `dsh_cwd`              | `save/dsh-workspace` | 会话工作目录（agent 工具的活动范围）           |
| `dsh_msg_limit`        | `300`              | 单条消息的最大字数（超过才按纯字数切开，不再按行拆）      |
| `dsh_flush_line`       | `700`              | 单行文本攒到这么多字就**立刻发送**（不等整轮结束） |
| `dsh_flush_multiline`  | `400`              | 多行文本累计到这么多字就**立刻发送**           |
| `dsh_output_reasoning` | `true`             | 是否转发思维链                         |
| `dsh_reasoning_forward` | `false`           | 思维链**与工具调用**是否整轮合并成**一条转发消息**（打开后不再分段实时推送） |
| `dsh_output_tools`     | `false`            | 是否转发工具调用与工具结果（同一次调用的多个工具合并成一条，**只显示工具名、不显示参数**） |
| `dsh_prefix`           | `true`             | 是否给思维链加 💭、工具加 🔧（正文回复不加任何标签） |
| `dsh_send_delay_min_ms` | `1000`            | 每条消息发出前随机等待的下限（等上一条回执之后）      |
| `dsh_send_delay_max_ms` | `3000`            | 每条消息发出前随机等待的上限                   |
| `dsh_send_ack_timeout_ms` | `15000`          | 等上一条发送回执的超时（毫秒）                  |
| `dsh_like_emoji`       | `424`              | 被 at 时给该消息贴的表情 id，留空则关闭           |
| `dsh_turn_timeout_ms`  | `300000`           | 单轮回复超时（毫秒）                      |
| `dsh_max_messages`     | `200`              | 单轮最多发送多少条消息，防止刷屏                |
| `dsh_guard_prompt`     | `true`             | 用 `qq-message` 信封隔离不可信聊天输入       |
| `dsh_relay_context`    | `false`            | 群聊里未被 at 的消息是否作为上下文附带给模型        |
| `dsh_approval`         | `qq`               | 工具许可：`qq` 在 QQ 里问用户、`allow` 一律放行、`deny` 一律拒绝、`off` 不接审批 |
| `dsh_approval_timeout_ms` | `120000`        | 等用户答复工具许可的超时（毫秒），超时按拒绝处理        |
| `dsh_watchdog`         | `true`             | DSH 侧看门狗：连接器消失后让它自己退出           |
| `dsh_watchdog_interval_ms` | `10000`         | 心跳写入（也是看门狗检查）间隔                 |
| `dsh_watchdog_timeout_ms`  | `30000`         | 心跳多久没更新就判定连接器已死                 |

工具许可开启时，**授权请求走私聊**：

- 触发者是管理员本人 → 直接私聊管理员（你自己发起，私聊里回 `允许` / `拒绝` 即可）；
- 触发者不是管理员 → 私聊管理员请求授权，同时私聊告知触发者「正在等待管理员批准」；
- 管理员在私聊里回复 `允许` / `同意` / `1` / `ok` 表示放行，`拒绝` / `不同意` / `2` / `no` 表示拒绝；
- 批复结果会回到群里，并私聊再通知一次非管理员触发者；
- 这些回复会被连接器消费掉，不会变成新一轮对话；非管理员的私聊消息一律忽略。

关闭行为分三层：连接器退出时关闭钩子会先发 SDK `shutdown` 再 `destroy`（实测 DSH 0.5 秒退出）；
即使钩子没执行（被强杀），DSH 也会因为 stdin EOF 自行退出（同样约 0.5 秒）；
若两者都没生效，插件的心跳看门狗会在 30 秒内把它收掉。

运行时生成的文件：会话映射 `save/dsh-sessions.json`（QQ 会话 → DSH sessionId，重启后对话不丢）、
插件副本 `save/dsh-approval-bridge.mjs`、`--patch` 覆盖层 `save/dsh-approval-patch.yml`（每次启动覆盖）、
心跳文件 `save/dsh-heartbeat`（看门狗用）。

---

## 配置说明

### 网页搜索（DeepSeek Search API）

`DeepseekSearchMcp` 按官方
[网页搜索与抓取](https://api-docs.deepseek.com/zh-cn/guides/search) 文档接入
`POST https://api.deepseek.com/v1/tools/search`：对话模型通过工具 `web_search` 搜索实时网页，
拿到标题、链接、发布时间、权威度与长摘要。鉴权复用 `deepseektoken`，**未配置时不注册该工具**。

| 事项 | 说明 |
|---|---|
| 挂载位置 | QQ 群 agent 的 `tools` 含 `fetch` 时注册；绘画会话与绘画 subagent 也注册 |
| 请求参数 | `query`（必填，≤128 字符，支持 `site:`/`-site:`/`-关键词`/`"精确短语"`/`lang:`/`after:`/`before:` 语法）、`count`（1～20，默认 10） |
| 返回内容 | 每条结果的标题、链接、`发布时间 · 站点 · 相关度 · 权威度` 与摘要；不返回正文（需要正文用 `web_view` 打开链接） |
| 计费口径 | 官方按**成功请求次数**计费：每 1000 次 5 元（每次 0.005 元），与 `count`、token 无关；仅 HTTP 200 且 `results` 非空才计费 |
| 系统记账 | 按 1 元 = 500000 等效 token 折算，每次成功搜索记 2500 等效 token（0.005 元），明细类型为「DeepSeek 网页搜索」；失败、超时、空结果一律不记账 |
| 重试 | 仅对 408 / 429 / 5xx 自动重试（最多 3 次尝试），其它 4xx 直接返回错误说明给模型 |

```bash
java -Ddeepseektoken=your_token -jar aichat.jar   # 同时启用 Deepseek 对话与 web_search
```

### 数据库

服务启动时会自动在数据目录下创建 `messages.db`。核心表：

- `chats`：对话主表，存储用户ID、应用ID、对话ID、时间、名称、**累计消耗（price）**、
  **存档状态（status）** 以及 `name_fixed`（名称是否被用户手动重命名）
- `chatdata`：对话备注（`remark`）与"当前存档"标记（`selected`）
- `permission`：用户权限（用户ID、应用ID、许可状态），可见性为 `permission` 的应用据此授权

`chats.status` 的取值：

| 取值         | 含义                                   | 是否出现在聊天页 | 是否出现在存档管理页 |
|------------|--------------------------------------|----------|------------|
| `normal`   | 进行中（默认）                              | 是        | 是（"进行中"标签） |
| `archived` | 已归档                                  | 否        | 是（"已归档"标签，可恢复） |
| `removed`  | 已移除（软删除，由 `/remove` 设置）               | 否        | 否          |

启动时会自动升级旧库：把原 `price` 表的费用并入 `chats.price`，并把历史的
`chats.attribute` 标记迁移为 `status`（`hide` → `archived`，`removed`/`deleted` → `removed`，
其余 → `normal`）。迁移是幂等的；`price` 表升级后不再被读写，仅作为历史数据保留。

### AI 应用加载

在指定的 `parent` 目录下，每个子文件夹代表一个 AI 应用，必须包含 `meta.json` 文件，格式示例：

```json
{
  "type": "talk",              // 对应注册的工厂类型
  "name": "应用名称",             // 显示名称
  "enabled": true,             // 是否启用
  "access": "permission"       // 可见性：hidden / permission / public
}
```

`access` 控制应用的可见性与可用性：

| 取值           | 含义                                                     |
|--------------|--------------------------------------------------------|
| `hidden`     | 不可见：不出现在任何应用/存档列表中，也不允许新建对话（已存在的对话仍可按对话ID打开）           |
| `permission` | 仅权限可见（默认）：只有在 `permission` 表中 `state='1'` 的用户可见、可新建对话     |
| `public`     | 公开可见：所有用户都可见、可新建对话                                     |

大小写不敏感，也接受 `none`/`invisible`/`private`（→ hidden）、`open`/`all`/`trial`（→ public）、
`limited`/`auth`（→ permission）等写法。**已弃用的 `trial` 字段仍然兼容**：未配置 `access` 时，
`trial: true` 等价于 `access: "public"`，`trial: false` 等价于 `access: "permission"`。

`type` 字段决定了使用哪个 `AIApplicationFactory` 创建实例。内置类型（见 `AIApplicationRegistry`）：

| `type` | 说明 | 前端页面 |
|---|---|---|
| `talk` | 角色对话（立绘 + 场景标签 + 语音） | `galgame.html` |
| `galgame` | Galgame 风格多幕叙事 | `ptrpg.html` |
| `trpg` | TRPG 跑团（多角色立绘） | `trpg.html` |
| `wuxia` | 武侠/修仙模拟（属性面板） | —（`chat.html` 已移除） |
| `write` | 写作辅助（大纲 + 正文） | — |
| `sql` | 根据表定义生成 SQL | — |
| `custom` | 配置型智能体（提示词存在会话里） | `custom.html` |
| `group` | QQ 群聊机器人（由 `NapCatAIConnector` 拉起） | — |

> ⚠️ 注意：`meta.json` 的 `name` **不是界面显示名**，只用于加载门槛与传给工厂；
> 界面显示的是各 app 类 `getName()` 的返回值（所以 `wuxia` 目录写"修仙模拟器"，界面显示"武侠模拟器"）。

各应用的玩法、必需文件、输出格式与坑位，见 **[docs/apps/builtin-apps.md](docs/apps/builtin-apps.md)**。

---

## API 文档

### HTTP 接口

| 路径                     | 方法   | 参数                               | 说明                             |
|------------------------|------|----------------------------------|--------------------------------|
| `/applist`             | GET  | `uid` 用户ID                      | 获取用户可见的应用列表（公开可见 + 已授权）        |
| `/chatlist`            | GET  | `uid` 用户ID                      | 获取用户全部进行中的对话（跨应用，兼容接口）         |
| `/querychat`           | GET  | `uid`, `appid`, `status?`        | 按应用分类查询对话（各聊天页面专用，只返回该应用）      |
| `/currentChat`         | GET  | `uid`, `appid`                   | 查询该应用下的"当前存档"（选中的优先，否则最近一条）    |
| `/createid`            | GET  | 无                                | 生成一个可用的对话ID                  |
| `/renameChat`          | POST | `uid`, `chatid`, `name`          | 重命名对话（写入主表，之后不再被AI自动改写）        |
| `/archiveChat`         | POST | `uid`, `chatid`                  | 归档对话（聊天页不再显示，可在存档管理页恢复）        |
| `/unarchiveChat`       | POST | `uid`, `chatid`                  | 取消归档，恢复为进行中                    |
| `/remove`              | GET  | `uid`, `cid`                     | 移除对话（软删除，标记为 `removed`）        |
| `/archiveApplist`      | GET  | `uid` 用户ID                      | 存档管理页应用列表（可见应用 ∪ 已有存档的应用 + 计数） |
| `/queryArchives`       | GET  | `uid`, `appid`, `archived?`      | 存档管理页存档明细（含消耗、备注、选中标记）         |
| `/updateRemark`        | POST | `uid`, `chatid`, `remark`        | 更新存档备注                         |
| `/updateSelection`     | POST | `uid`, `chatid`, `appid`         | 设置该应用的"当前存档"                   |
| `/exportChat`          | POST | `uid`, `chatid`                  | 导出对话历史为 UTF-8 文本文件          |
| `/exportMemory`        | POST | `uid`, `chatid`                  | 导出对话记忆为 UTF-8 文本文件          |
| `/useToday`, `/useHistory`, `/checkin`, `/redeem` 等 | GET | `uid` 等 | 额度查询、签到与 CDK 兑换             |
| `/kh$localModelDeploy` | WS   | 需 `Authorization: Bearer token` | 本地语音模型 WebSocket 连接端点       |
| `/kh$localModelDeployData` | POST | `reqid`, `type`, 二进制数据        | 本地语音模型回调，接收音频数据             |
| `/resource/*`          | GET  | -                                | 静态资源文件（图片、CSS 等）            |
| `/voice/*`             | GET  | -                                | 语音资源文件                       |
| `/index`, `/aichat`, `/painter`, `/archive` 等 | GET  | -                    | 返回对应的 HTML 页面                |

#### 对话列表与"当前存档"约定

- **各聊天页面只能拉取自己应用的对话**：页面调用 `/querychat?appid=<自己的应用>&uid=<用户>`，
  不接受跨应用列表；通用页面（`custom.html`）只展示 `custom` 应用的对话，绘画页面只展示 `painterirc`。
- `/querychat` 与 `/currentChat` 都只返回 `status='normal'` 的对话，因此**归档后界面不再可见**。
- 页面用 `/currentChat` 决定进入时打开哪一个存档（存档管理页选中的优先，否则取最近一条），
  不再依赖列表顺序。
- 用户ID参数兼容 `uid` 与 `userId` 两种写法；WebSocket (`/chatsocket`) 使用 `userId`。

### WebSocket 协议

前端通过 `/chatsocket` 建立连接后，与 `WebSocketAISession` 进行双向通信。

#### 前端 → 后端（客户端消息）

- **发送用户消息**：纯文本字符串，表示用户输入。
- **特殊指令**：某些应用支持“重新生成”、“撤回”等指令。

#### 后端 → 前端（服务端推送）

- **新消息推送**：`postMessage(id, role, message)` 方法会触发推送。
- **追加内容**：`appendMessage(id, content)` 用于流式追加当前消息内容。
- **删除消息**：`delMessage(id)` 通知前端移除某条消息。
- **音频完成**：`postAudioComplete(id, audioId)` 通知语音生成完成。

消息格式为 JSON，具体字段由前端约定（可在 `chat.js` 中查看）。

---

## 扩展开发

### 不写代码做应用（推荐先看这个）

做应用 = 建一个目录 + 写 `meta.json` + 写几个提示词 txt + 重启服务，**不需要编译**。
完整教程见 **[docs/apps/](docs/apps/README.md)**：

| 文档 | 内容 |
|---|---|
| [tutorial.md](docs/apps/tutorial.md) | 三个练习：网页做智能体 → 复制角色对话 → 做文字冒险 |
| [prompting.md](docs/apps/prompting.md) | 提示词编写：五个文件分工、格式契约、人设不崩、迭代 |
| [files.md](docs/apps/files.md) | 文件作用：三层结构、每个文件谁在读、存档与数据库 |
| [configuration.md](docs/apps/configuration.md) | 配置：`meta.json` 全字段、可见性/授权、计费、站点配置 |
| [builtin-apps.md](docs/apps/builtin-apps.md) | 8 种内置类型的玩法、必需文件、输出格式、坑 |
| [troubleshooting.md](docs/apps/troubleshooting.md) | 排错决策树、常见改造、调试手段 |

### 新增一种玩法类型（需要写 Java）

仅当"换提示词 + 换配置"表达不了新玩法时才需要：

1. 继承 `AIApplication`，实现 `getName` / `constructSystem(ApplicationState)` / `getBrief` /
   `getMemory` / `provideInitial`（详见[开发者参考](docs/dev/java-api.md)）。
2. 注册工厂：在 `AIApplicationRegistry` 的静态块里
   `register("yourtype", (base, folder, name, meta) -> new YourApp(folder))`。
3. 建目录 `save/apps/<appid>/`，写 `meta.json`（`type` 与注册名一致）与该类型需要的提示词文件。
4. 重启服务，看日志出现 `AI加载成功：<appid>`。

文档：[docs/dev/java-api.md](docs/dev/java-api.md)（API 参考）、
[docs/dev/java-recipes.md](docs/dev/java-recipes.md)（改造配方与排错）。

### 添加新的模型提供商

1. 实现 `ModelProvider` 接口，实现 `supports` 和 `execute` 方法。
2. 在 `LLMConnector.initDefault()` 中根据条件（如系统属性）将提供商加入列表。

### 自定义场景选择器

`SceneSelector` 用于根据当前状态（如季节、时间、地点）选择不同的场景文件。可通过 `AICharaSceneBuilder` 流式构建：

```java
AICharaSceneBuilder<Endable> builder = AICharaSceneBuilder.builder()
    .withLocation("客厅")
    .season().spring().end()
    .time().morning().end()
    .withScene("日常.txt");
SceneSelector selector = builder.build();
```

---

## 本地语音模型部署

系统支持与私有化语音合成服务对接，通信流程：

1. 语音模型服务端主动连接 `/kh$localModelDeploy` WebSocket 端点，携带 Bearer Token 认证。
2. 当需要合成语音时，`LocalModelHandshaker` 从连接池中选取一个连接，发送 JSON 请求：
   ```json
   {
     "chara": "角色名",
     "emote": "表情",
     "reqid": "请求ID",
     "text": "要合成的文本",
     "type": "audiorequest"
   }
   ```
3. 语音服务生成完成后，通过 HTTP POST 请求 `/kh$localModelDeployData?reqid=xxx&type=audio`，将音频二进制数据（Base64 编码后放在 JSON 中，或直接作为原始数据）回传。
4. `LocalModelHandshaker` 唤醒等待的线程，将音频数据返回给调用方。

系统属性 `localVoiceToken` 用于验证 WebSocket 和 HTTP 回调的 Bearer Token。

### 模型类型（model_type）

连接时通过 URL 参数 `model_type` 声明该连接支持的模型类型，多个类型用逗号分隔（如 `?model_type=audio,svs`），
未指定时默认为 `audio`。`LocalModelHandshaker` 按类型维护连接池，请求时由系统挑选支持该类型的连接。

| 类型      | 说明   | 请求参数                                                     |
|---------|------|----------------------------------------------------------|
| `audio` | 语音合成 | `chara`, `content`                                       |
| `sdxl`  | 图像生成 | `path`, `data`                                           |
| `svs`   | 歌声合成 | `url`, `name`, `pitch`, `separate`, `timbre`, `filter`, `strength` |

### SVS 歌声合成

SVS 请求在音源（`url`、`name`）之外需要五个参数：

| 参数         | 类型      | 说明                |
|------------|---------|-------------------|
| `pitch`    | int     | 音高                |
| `separate` | bool    | 是否分离人声            |
| `timbre`   | string  | 音色标识              |
| `filter`   | int     | 滤波强度，取值 0-7       |
| `strength` | double  | 合成强度，取值 0.00-1.00 |

```json
{
  "url": "音源地址",
  "name": "音源名称",
  "pitch": 2,
  "separate": true,
  "timbre": "音色",
  "filter": 3,
  "strength": 0.75,
  "reqid": "请求ID",
  "type": "svs"
}
```

Java 调用：

```java
LocalModel.requireSvs(reqid, url, LocalModelHandshaker.SvsOptions.builder()
        .pitch(2).separate(true).timbre("音色").filter(3).strength(0.75))
    .thenAccept(result -> { /* result.bodyData, result.format */ });
```

`filter`、`strength` 超出范围，或 `timbre` 传入空字符串时会立即抛出 `IllegalArgumentException`；
未设置 `timbre` 时返回失败的 Future。可用 `LocalModel.hasOnlineSvsService()` 判断是否有可用的 SVS 连接。

---

## 许可证

本项目基于 [MIT](LICENSE) 开源。

---

## 贡献

欢迎提交 Issue 和 Pull Request。在开发新功能前，请先阅读现有代码结构，保持风格一致。

---