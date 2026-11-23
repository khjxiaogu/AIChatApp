# AIChatApp
# 通用聊天机器人框架 / AI 对话系统 (AI Chat Application)

一个基于 Java 的通用聊天机器人 / AI 对话框架：聊天机器人核心通过 `ChatAdapter` 与平台解耦，默认提供 NapCat / OneBot v11（QQ）适配器，也可接入其它聊天平台；同时支持动态加载多种 AI 应用类型（武侠、Galgame、TRPG、角色对话等），集成了大语言模型（LLM）和本地语音合成服务，提供 WebSocket 和 HTTP 接口供前端调用。系统内置会话管理、历史记录、权限控制、费用统计等功能，可快速构建个性化的 AI 交互应用。
本文档由AI生成，如有错漏以源代码为准。

---

## 特性

- **多 AI 应用支持**：通过 `AIApplication` 抽象类和工厂模式，可轻松扩展不同类型的 AI 应用。
- **通用聊天平台**：聊天机器人核心只依赖 `ChatAdapter` 接口，不绑定任何具体平台；默认提供
  NapCat / OneBot v11 适配器，接入其它平台只需新增一个适配器实现。
- **动态加载**：扫描应用目录下的 `meta.json` 配置，自动加载启用的 AI 应用，支持热重载。
- **会话管理**：每个对话拥有独立的历史记录、状态、推理内容，支持撤回、重新生成等操作。
- **流式推理**：支持 AI 模型输出推理内容（思维链）和最终内容，并实时推送到前端。
- **本地语音模型**：集成私有化部署的语音合成服务，通过 WebSocket 连接和 HTTP 回调接收音频数据。
- **权限与公测**：基于 SQLite 数据库记录用户权限，支持应用级的公测限制（每个用户仅可创建一个对话）。
- **对话导出**：可将整个对话历史导出为 UTF-8 文本文件（带 BOM 头）。
- **模型路由**：通过 `ModelProvider` 接口支持多模型提供商（Deepseek、火山引擎等），可按模型提示或
  **消费预算（budget）** 自动选择最接近的提供商。
- **网页搜索**：DeepSeek `deepseek-flash` 直接声明官方服务端联网工具 `deepseek_web_search`（需 `deepseektoken`），
  搜索在服务端完成、不按次计费；`deepseek-v4-pro` 与其它模型继续用 MCP 工具 `web_search` 接入 DeepSeek Search API，按成功请求次数计费。
- **Codex 编程代理**：`codex` 技能把本机 Codex CLI 接成 8 个 MCP 工具，`type=codex` 的 agent 应用
  负责把群里需求交给 Codex 改代码并汇总结果；按群隔离线程，审批走私聊管理员。Codex agent 默认
  **显式发送**（正文不自动发，必须用 `send_group` 工具；私聊相关工具属多 agent 架构）。
  见 [docs/codex.md](docs/codex.md)。
- **知识库 MCP**：`kb_search` 在项目根目录文件 + `design`/`docs`/`diary`/`discussion` 子目录里
  搜文件名和内容，`kb_read` 读取范围内的 `.md`；codex / 多 agent 会话按各自项目 `cwd` 自动接入。
  见 [docs/knowledge.md](docs/knowledge.md)。
- **多 agent 架构**：一个公屏总览 agent（按公开群）+ 每个私聊用户一个私人 agent；公屏内容广播到所有
  agent 历史，私聊各自隔离；缓冲区无上限并落盘 `save/multi/buffers/`，每人可配置独立提示词，私聊
  agent 默认审核人为对应用户、公屏为管理员。见 [docs/multi-agent.md](docs/multi-agent.md)。
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

## 聊天平台适配

聊天机器人部分是一个**通用聊天机器人框架**，与具体平台解耦：

- 控制器 `NapCatAIConnector` 负责会话、历史、工具、审核与回复生成，业务代码不接触任何协议字段；
- 所有平台相关的连接与收发都通过 `ChatAdapter` 完成，默认实现 `NapcatAdapter` 接入
  NapCat / OneBot v11（QQ）；
- 接入其它平台（Discord、Telegram、自建协议等）只需实现 `ChatAdapter` 并传给控制器，
  消息 id、会话 id、用户 id 全部按字符串处理，不要求是数字。

---

## 项目结构

```
├─AIAppMain.java              # 独立窗口模式入口（Swing）
├─AIChatService.java           # 核心服务类，处理HTTP/WebSocket请求，管理会话
├─AIChatWindow.java            # 测试用Swing窗口
├─AIServerMain.java            # 主服务器入口，启动Netty服务
├─NapCatAIConnector.java       # 通用聊天机器人控制器（经 ChatAdapter 接入平台，默认 NapCat / OneBot v11）
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
| `deepseektoken`   | Deepseek API 的 Token，若存在则启用 Deepseek 模型提供商，并启用联网搜索（`deepseek-flash` 走官方服务端工具 `deepseek_web_search`，`deepseek-v4-pro` 与其它模型走 MCP 工具 `web_search`） |
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
  "ws_host": "ws.example.com",
  "reader_host": "www.example.com"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `host` | 否 | 站点对外域名（网页 / HTTP）。可写裸域名 `example.com`、带端口 `example.com:8443` 或完整地址 `https://example.com`；写成 `http://...` 会得到 `ws`/`http` 地址。**留空或文件不存在时，前端自动跟随访问页面的域名**（`location.host`），Java 端回退到 `SiteConfig.DEFAULT_HOST` |
| `ws_host` | 否 | **WebSocket 专用域名**，与 `host` 分开配置，例如网页走 `example.com` 而 WebSocket 走 `ws.example.com` 或独立端口。规则同 `host`（支持裸域名 / 带端口 / 完整地址；写 `ws://` 或 `http://` 得到非加密的 `ws://`）。留空时与 `host` 相同 |
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
   ├─ image-generation.json      # 图片（Seedream）/视频（Seedance 2.5）生成凭据：accessKey/secretKey + apiKey
   ├─ voice-tags.txt             # 语音标签提示词（按情感切分台词，供语音合成）
   ├─ local-voice.json           # 本地语音模型 bot 映射 {角色名: botid}
   ├─ volcano-voice.json         # 火山语音复刻 bot 映射 {角色名: voice_type}
   ├─ agents.json                # 群聊 agent 清单（name / save / voice / tools / adminId …）
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
  （内容来自 `save/site.json` 的 `host` / `ws_host`），各页面用 `aiSiteWsBase()` / `aiSiteHttpBase()`
  （见 `save/ai-protocol.js`）拼接地址；`aiSiteWsBase()` 使用 `ws_host`（未配置时与 `host` 相同），
  `aiSiteHttpBase()` 使用 `host`。两者都未配置时回退到 `location.host` / `location.protocol`，
  所以反代、换域名、本地调试都不用改 HTML。

### 让 Deepseek Harness 接管 QQ（DeepseekHarnessConnector）

`DeepseekHarnessConnector` 与 `NapCatAIConnector` 方向相反：不再由本程序的 AI 应用作答，
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
| `dsh_msg_limit`        | `600`              | 单条消息/单个转发节点的最大字数（超过才按纯字数切开，不再按行拆）      |
| `dsh_flush_line`       | `700`              | 单行文本攒到这么多字就**立刻发送**（不等整轮结束） |
| `dsh_flush_multiline`  | `400`              | 多行文本累计到这么多字就**立刻发送**           |
| `dsh_output_reasoning` | `true`             | 是否转发思维链                         |
| `dsh_reasoning_forward` | `false`           | 思维链**与工具调用**是否整轮合并成**一条转发消息**（打开后不再分段实时推送） |
| `dsh_forward_text`     | `true`             | 正文是否也用**合并转发消息**发送；转发失败会自动回退成普通消息 |
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

### 让 Codex 在群里改代码（codex 技能 + 编排 agent）

`codex` 技能把本机 Codex CLI（`codex app-server`，实验性 JSON-RPC）接成 8 个 MCP 工具；
`meta.json` 里 `"type": "codex"` 的 agent 应用负责「理解需求 → 调 Codex → 汇总结果」。
**任务在后台执行，结束后由系统触发 AI 汇报，模型不再反复轮询**。
与 `DeepseekHarnessConnector` 的区别：**不替换 AI 应用**，而是把 Codex 当工具挂进现有 QQ agent，
按群隔离线程；审批用的是 Codex 原生的审批请求，不需要 `--patch` 插件与回环 TCP。

最少两步：

1. `save/config/agents.json` 里给目标群的 `tools` 加 `"codex"`，并配置接收审批的 `adminId`；
2. `save/apps/<名字>/meta.json` 写 `{"type":"codex","name":"..."}`（可选 `rules.txt`）。

示例（bot `123456` / 群 `789012` / 管理员 `345678` / 项目 FrostedHeart）已写进
`save/config/agents.json` 与 `save/apps/frostedheartirc/`。该群默认还会把线程挂到 Codex 桌面端已有的
`Frostedheart` 项目（`codex.project`，按项目名从 Codex 状态库解析出 id），桌面端项目视图里能看到机器人会话。
工具清单、全部配置项、审批流程与安全建议见 [docs/codex.md](docs/codex.md)。

---

## 配置说明

### 网页搜索（DeepSeek 官方联网）

联网能力有两条路径，按**当前执行请求的模型**自动选择，业务代码不用关心：

| 场景 | 做法 | 计费 |
|---|---|---|
| DeepSeek `deepseek-flash` | 在 `tools` 里声明官方的服务端工具 `{"type":"deepseek_web_search"}`：搜索、打开网页、页内查找全部在服务端完成，客户端不执行任何工具，也不再下发 MCP 工具 `web_search` | **不按次计费**：官方已取消服务端搜索/抓取的按次收费，搜索结果只按模型 token 计费 |
| DeepSeek `deepseek-v4-pro`、其它模型提供商（火山方舟、Grok、SiliconFlow、MiMo），或 DeepSeek 需要 JSON 输出 / `prefix` 续写 | 使用 MCP 工具 `web_search`（`DeepseekSearchMcp`）：本进程调用 `POST https://api.deepseek.com/v1/tools/search` 取回结果后再发一轮请求（`deepseek-v4-pro` 不使用服务端联网优化） | 按**成功请求次数**计费：每 1000 次 5 元；仅 HTTP 200 且 `results` 非空才记账（每次 2500 等效 token） |

两条路径都只在会话注册了 `web_search` 工具时开启：该工具在配置了 `deepseektoken` 且工具设置里开启
`fetch` 时才注册，**未配置时不联网**。服务端路径的要点：

- 是否联网、搜几次都由模型判断；开启思考时是**深度思考搜索**（多步搜索、打开网页、页内查找，最多约 20 次调用）。
- 调用记录随回复以 `server_tool_use` 返回并存进历史（`ServerToolUseContent`）：下一轮请求把
  `server_tool_calls` **原样回传**；换成其它模型时降级成带截断的占位文本（如「[联网搜索] 冰岛人口」）。
- 不占用工具调用轮次/次数配额，也不产生「DeepSeek 网页搜索」用量。
- 官方限制：声明联网工具时 `response_format` 只能是 `text`、客户端工具不能带 `strict`（联网时自动省去该字段），
  且用过联网工具的回复不能续写（`prefix`）；JSON 输出与 `prefix` 这两种请求自动退回 MCP 工具 `web_search`。

MCP 工具参数（`deepseek-v4-pro` 与其它模型路径）：`query`（必填，≤128 字符，支持 `site:`/`-site:`/`-关键词`/`"精确短语"`/`lang:`/`after:`/`before:` 语法）、`count`（1～20，默认 10）；
返回每条结果的标题、链接、`发布时间 · 站点 · 相关度 · 权威度` 与摘要，不返回正文（需要正文用 `web_view` 打开链接）；
仅对 408 / 429 / 5xx 自动重试（最多 3 次尝试）。

```bash
java -Ddeepseektoken=your_token -jar aichat.jar   # 同时启用 Deepseek 对话与联网（flash 走服务端工具，pro 走 MCP 工具）
```

### 即梦 Seedance 2.5 视频与工具审核

`seedvideo` 工具（`jimeng_video`）已接入**火山方舟 Seedance 2.5**：视频脚本 agent 产出脚本后，
通过 `POST /api/v3/contents/generations/tasks` 提交、轮询 `/contents/generations/tasks/{id}` 取回
`content.video_url`，参考图以 `reference_image` 内容块提交。凭据仍然放在
`save/config/image-generation.json`：

```json
{
  "accessKey": "Seedream/即梦图片仍用这一对",
  "secretKey": "Seedream/即梦图片仍用这一对",
  "apiKey": "火山方舟 API Key（视频必填）",
  "videoModel": "doubao-seedance-2-5-260628",
  "videoDuration": 10,
  "videoResolution": "720p",
  "videoRatio": "9:16",
  "videoGenerateAudio": false
}
```

可选的 `videoDuration`（4-15 秒，默认 10 秒，超过 15 秒按 15 秒处理）、
`videoResolution`（480p/720p/1080p）、`videoRatio`（默认 9:16）、
`videoBaseUrl`、`videoPollIntervalMs`、`videoMaxPollAttempts` 见 `save/config/README.md`。
**所有视频生成 MCP 均禁用音频**：`videoGenerateAudio` 已强制为 `false`，配置成 `true` 也不会生效。
`videoDuration` 是默认时长；`jimeng_video` 工具支持 `duration` 参数（4-15 秒），会覆盖该默认值，
并同时写进脚本要求和提交给视频接口。未配置 `apiKey` 时视频工具返回明确错误，不影响图片工具。

视频计费按 `视频token = 输出宽 × 输出高 × 输出帧率 × 输出时长 / 1024` 估算，官方价格
**70 元/百万视频 token**。本站配额统一按 2 元/百万等效 token 折算，因此计入配额的扣减 token
为视频 token 的 **35 倍**；输出尺寸默认从 `videoResolution` + `videoRatio` 推导，帧率默认 24，
可用 `videoWidth` / `videoHeight` / `videoFps` 校准。

绘画页的图片生成工具除 SDXL 系列外，还会自动注册 Qwen-Image 2.1 文生图 `qwen_gen_image`（支持中文
提示词与 8 步极速草稿，走本地模型通道）；它和 SDXL 图片工具共享**每轮最多 5 次**生成额度，生成的
图片 id 可直接用于图生图 / 分区出图。

**绘画页（`ui: "painter"`）也会自动获得视频能力**，不需要在 `tools` 里配置：本地 H3 直连生成
`h3_gen_video` / `h3_gen_anim` 始终注册（本地模型离线时调用会返回可读错误），即梦 `jimeng_video`
在配置了 `apiKey` 时注册；绘画页不注册视频导演子 agent。两件本地 H3 工具在绘画页是**同步工具**：
本轮内等待生成完成，只把视频 id 返回给模型，不把视频附件写进回复；两者共用**每轮最多 1 次**的额度。
需要把视频展示给用户时，由模型再调用 `send_video`（传视频 id）显式发送。即梦视频仍是异步长任务，
生成期间前端显示“视频生成中”，完成后续写到本轮助手回复里。
本地 H3 视频按 id 通过 `GET /aichat/painter/video?video_id=` 取用（支持 Range），即梦返回远端地址直接播放。
绘画页也支持上传视频作为用户消息内容，详见
[docs/apps/configuration.md](docs/apps/configuration.md) 的“绘画页视频”一节。

工具调用审核写在 `save/config/agents.json` 的机器人配置里：

```json
{
  "botId": "123456",
  "groupId": "789012",
  "name": "fengxiirc",
  "adminId": "10001",
  "tools": [
    "fetch",
    {"name": "seedvideo", "approval": true}
  ],
  "approvalTimeoutMs": 120000,
  "autoReply": {
    "enabled": false,
    "intervalMs": 600000,
    "messageThreshold": 20,
    "budget": 0.6
  }
}
```

- `botId` / `groupId` / `adminId` 等标识统一按**字符串**处理，方便接入非数字 id 的平台；
  旧配置里的数字写法仍能正常读取。
- `adminId` 也可以由启动参数 `-DadminId=...` 兜底；`approvalTimeoutMs` 对应
  `-Dtool_approval_timeout_ms=...`。
- 需要审核的工具在真正执行前，把会话号（群 id）、机器人名、最近发言者、工具名、原因与参数**私聊给管理员**；
  管理员回复 `允许` / `同意` / `1` / `ok` 放行，回复 `拒绝` / `不同意` / `2` / `no` 拒绝，
  超时按拒绝处理。拒绝时可以回复 `拒绝 原因`（空格、冒号、逗号分隔均可），
  **原因会作为工具结果回传给模型**，模型据此调整后续行为。只有管理员本人的私聊答复有效。
- 旧的字符串形式 `"tools": ["fetch", "seedvideo"]` 完全兼容，只有写成对象或放进
  `toolApprovals` 的工具才需要审核；条目写技能名（`seedvideo` → `jimeng_video`）或真实工具名
  都可以。没配置 `adminId` 时按失败关闭处理，不会擅自执行。
- 审核流程复用 `ToolApprovalManager` / `ToolCallApprover`，与 WebSocket 会话的授权弹窗、
  `DeepseekHarnessConnector` 的私聊审批保持同一套语义。

### 群聊主动回复判定（autoReply）

默认关闭。开启后，没有被 @ 的群消息会先累积起来：

- 累计达到 `messageThreshold` 条（默认 20 条）时立即判定一次；
- 否则每隔 `intervalMs`（默认 10 分钟）判定一次；
- 判定时把这段时间的对话内容作为用户消息，发给**关闭思考模式**的轻量模型，让它判断当前角色
  是否需要针对这些消息主动发言；
- 需要发言时走正常回复流程，并计入该会话的配额；不需要则清空本次累计，继续等待后续消息。

| 字段 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `false` | 是否启用 |
| `intervalMs` | `600000` | 定时判定间隔（毫秒） |
| `messageThreshold` | `20` | 消息条数阈值 |
| `budget` | `0.6` | 判定请求的消费预算倍率（Mimo 档位） |

判定请求不带模型提示，因此按 `budget` 选择提供商：连接器会在支持该请求的提供商中选
`ModelProvider#budget(request)` 与本值最接近的一个（基准是 DeepSeek Flash = 1）。
明确指定模型（`modelHint`）的请求优先按模型路由、忽略 `budget`；只有按提示找不到可用提供商时
才回退到预算选择。

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
| `group` | 群聊机器人（由 `NapCatAIConnector` 拉起，具体平台由 `ChatAdapter` 决定） | — |

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
| `/kh$localModelDeploy` | WS   | 需 `Authorization: Bearer token` 或 `?token=` | 本地模型 WebSocket 连接端点（共享连接，可带 `uid` 表示仅服务该用户） |
| `/kh$localModelDeployData` | POST | `reqid`, `type`, 二进制数据        | 本地模型回调，接收音频数据             |
| `/painter/localModelDeploy` | WS | `uid`, `model_type=sdxl`           | 用户自带绘画模型的接入端点（无需 Token）     |
| `/painter/localModelResult` | POST | `reqid`, `format`，原始响应体       | 用户接入页回传生图结果（不经过 WebSocket 帧）  |
| `/painter/image`       | GET  | `image_id`                       | 按对象存储 id 取绘画图片（按内容返回 MIME） |
| `/painter/video`       | GET  | `video_id`                       | 按对象存储 id 取绘画视频；支持单段 `Range`（206/416） |
| `/painter/uploadImage` | POST | `conversation_id`, `image`（multipart） | 上传参考图到当前绘画会话（只接受图片）  |
| `/painter/uploadVideo` | POST | `conversation_id`, `video`（multipart） | 上传视频作为用户消息内容（只接受视频，≤200MB） |
| `/resource/*`          | GET  | -                                | 静态资源文件（图片、CSS 等）            |
| `/voice/*`             | GET  | -                                | 语音资源文件                       |
| `/index`, `/aichat`, `/painter`, `/archive` 等 | GET  | -                    | 返回对应的 HTML 页面                |
| `/localmodel`          | GET  | -                                | 本地模型终端页面（浏览器接入本地服务）        |
| `/painter-local`       | GET  | -                                | 用户本地绘画模型接入页（只做反向代理，无对话）   |

#### 对话列表与"当前存档"约定

- **各聊天页面只能拉取自己应用的对话**：页面调用 `/querychat?appid=<自己的应用>&uid=<用户>`，
  不接受跨应用列表；通用页面（`custom.html`）只展示 `custom` 应用的对话，绘画页面只展示 `painterirc`。
- `/querychat` 与 `/currentChat` 都只返回 `status='normal'` 的对话，因此**归档后界面不再可见**。
- 页面用 `/currentChat` 决定进入时打开哪一个存档（存档管理页选中的优先，否则取最近一条），
  不再依赖列表顺序。
- 用户ID参数兼容 `uid` 与 `userId` 两种写法；WebSocket (`/chatsocket`) 使用 `userId`。

### WebSocket 协议

前端通过 `/chatsocket` 建立连接后，与 `WebSocketAISession` 进行双向通信。
所有帧都是 JSON 对象，并统一用 **`type`** 字段区分（不再使用 `operation`）。

#### 前端 → 后端（上行帧）

| `type` | 字段 | 说明 |
|---|---|---|
| `message` | `content`, `msgId` | 用户输入文本；`msgId` 用于 ACK/重试幂等 |
| `generate` | — | 触发本轮生成（绘画页：先把输入落成待生成消息，再单独触发生成） |
| `stop` | — | 中断本轮生成 |
| `revert` / `regen` | `requestId` | 撤回最后一轮 / 重新生成最后一条回复 |
| `requestBackLog` | `before_id` | 向上翻页加载更早历史（`0` 为最新一页） |
| `model` | `model` | 切换模型 |
| `prompt` | `input`, `content` | 回答服务端的补充输入请求 |
| `toolApproval` | `id`, `approved`, `reason?` | 工具调用许可答复 |
| `ping` | `id` | 心跳，服务端回 `pong` |

#### 后端 → 前端（下行帧）

| `type` | 字段 | 说明 |
|---|---|---|
| `status` | `generating`, `tokens`, `price`, `conversation_id`, `isVoice*`, `models`, `modelTree`, `model`, `tasks[]`, `toolCalls[]` | 状态快照；重连后据此纠正生成状态并恢复异步任务/工具状态提示 |
| `message` | `id`, `role`, `title`, `content[]` | 整条消息（首次下发/历史重放） |
| `content` | `id`, `content[]` | 流式追加内容 |
| `remove` | `id` | 删除一条消息 |
| `batch_messages` | `messages[]`, `prepend`, `hasMore`, `oldestId`, `newestId` | 历史分页；每条另带 `token_cost` |
| `thinking` / `tool_call` | `content` / `call_id`, `tool_name`, `tool`, `content[]` | 思维链与工具调用展示；`call_id` 用于关联工具状态 |
| `tool_status` | `call_id`, `state`(`queued`/`running`/`succeeded`/`failed`/`cancelled`/`rejected`), `tool`, `name`, `time`, `startedAt`, `eta?`, `progress?`, `message?` | 工具调用状态：前端只展示 `queued`（排队中）与 `running`（执行中/进度），其余状态只用于清理显示 |
| `task` | `state`(`created`/`done`/`failed`), `id`, `tool`, `name`, `time`, `message?` | 异步长任务（视频生成等）状态变化 |
| `done` | `price`, `tokens` | 本轮结束；`tokens` 是本轮新增配额用量 |
| `notice` / `sendResult` / `sendbox` | — | 轻提示 / 发送 ACK / 有条件回填输入框 |
| `audio` / `scene` / `voice` / `model` / `pong` | — | 语音完成、场景切换、语音开关、模型回执、心跳应答 |

`content[]` 是结构化内容数组：

```json
[{"type":"text","text":"..."},
 {"type":"image","image_url":"<对象存储 id>","description":"..."},
 {"type":"video","video_url":"<对象存储 id 或远端 http 地址>","description":"..."}]
```

图片与本地视频只保存对象存储 id，由页面换成本站取用地址
（`/aichat/painter/image?image_id=`、`/aichat/painter/video?video_id=`）；
即梦等云端视频的 `video_url` 本身就是可播放的远端地址，前端直接使用。

公共前端协议层在 `save/ai-protocol.js`（Vue mixin），页面只需实现
`aiSocketUrl()` 与需要的帧钩子；视频/异步任务的展示在 `painter.html` 中实现。

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

1. 实现 `ModelProvider` 接口，实现 `supports`、`supportsHinted`、`budget` 和 `execute` 方法；
   `budget` 返回该请求在当前提供商下的消费倍率（DeepSeek Flash = 1）。
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

### 模型类型（model_type）与能力集合

连接时通过 URL 参数 `model_type` 声明该连接支持的模型类型，多个类型用逗号分隔（如
`?model_type=qwen,h3`），未指定时默认为 `audio`。`LocalModelHandshaker` 按类型维护连接池，请求时由
系统挑选支持该类型的连接。

同一条连接可以承载所有模型类型；连接建立后还可以发送能力集合变更报文动态增删类型：

```json
{"type":"capabilities","model_types":["qwen","h3"]}
```

服务器收到后更新该连接在各类型连接池里的登记，并回一条同样的报文作为确认；运行期不需要断开重连。

| 类型      | 说明   | 请求参数                                                     |
|---------|------|----------------------------------------------------------|
| `audio` | 语音合成 | `chara`, `content`                                       |
| `sdxl`  | 图像生成 | `path`, `data`                                           |
| `svs`   | 歌声合成 | `url`, `name`, `pitch`, `separate`, `timbre`, `filter`, `strength` |
| `qwen`  | Qwen-Image 2.1 生图 | `action`（`text2image` / `edit`）, `prompt`, `negative`, `width`, `height`, `steps`, `cfg`, `seed`, `fast`, `images` |
| `h3`    | MiniMax H3 生视频（无声） | `action`（`video`）, `model`（`int8` / `nvfp4`）, `steps`, `sparse`, `audio`（固定 false）, `prompt`, `width`, `height`, `seconds` / `frames`, `seed`, `first_frame`, `last_frame` |

### 独立模型服务进程（sdxl / svs / qwen / h3 / 可选 tts）

`LocalModelConnectionService` 是模型侧的独立进程：在本机执行 SDXL、SVS、Qwen-Image、MiniMax H3，
结果通过本地模型通道回传。它**同时**接入两条部署通道：

| 通道 | 地址 | 说明 |
| --- | --- | --- |
| 配置的远端 | `-DlocalModelServer=host:port`；未配置时按 `save/site.json` / `--site-host` | 可以是对外的聊天服务器 |
| 本机网关 | `ws://127.0.0.1:8997/aichat/kh$localModelDeploy` | `NapCatAIConnector` 启动时监听，端口可用 `-DlocalModelPort` 覆盖 |

- 远端没配置时只连本机网关；两条通道都连不上时进程不退出，客户端会自动重连；
- 本机网关只监听回环地址，不需要域名、反向代理或 TLS，聊天与模型同一台机器即可；
- 模型相关系统属性：`localVoiceToken`、`sdwebuiUrl`（SDXL）、`svsHost`（localemp）、
  `qwenImageUrl` 或环境变量 `QWEN_API`（ComfyUI，默认 `http://127.0.0.1:8188`）、
  `h3VideoUrl` 或环境变量 `H3_VIDEO_API`（H3 视频，未配置时复用 `qwenImageUrl`；图与视频在同一个
  ComfyUI 进程里）、`ttsUrl` 或环境变量 `TTS_URL`（本机 TTS，默认端口 `9880`，见下）；
- 启动（与 `NapCatAIConnector` 相同的数据目录与站点参数）：
  `java -DlocalVoiceToken=... -DsdwebuiUrl=... -DsvsHost=... -DqwenImageUrl=... -Dh3VideoUrl=... -DttsUrl=http://127.0.0.1:9880 -cp "target/AIWuxia-0.0.1-SNAPSHOT.jar;target/aiwx_lib/*" com.khjxiaogu.aiwuxia.voice.LocalModelConnectionService`

**分层结构**：

| 层 | 类 | 职责 |
| --- | --- | --- |
| 传输 | `voice.transport.LocalModelTransport` / `WebSocketLocalModelTransport` | 只管连接与文本收发；换协议只需实现 `LocalModelTransport` 并提供 `LocalModelTransportFactory` |
| 协议 | `voice.protocol.LocalModelProtocol` | 定义请求/响应/进度/能力集合报文与字段，服务端与连接器共用 |
| 会话 | `voice.LocalModelClient` | 一条连接 + 一张“模型类型 → 请求处理器”表：按 `type` 分发、进度心跳、重连、按类型的健康检查 |
| 模型调用 | `vision.*LocalModelHandler` / `AudioLocalModelHandler` | 只负责把请求翻译成 SD WebUI / localemp / ComfyUI / TTS 调用 |

- 每条部署通道只建**一条连接**，所有模型共用它，不再各持一条；
- **请求支持二进制分块**：连接器在握手 URL 里用 `flags=chunked` 声明“支持分块 / 大报文”
  （也可在运行期发 `{"type":"capabilities","model_types":[...],"flags":["chunked"]}` 更新）；
  服务器遇到超过 `-DlocalModelRequestChunkSize`（默认 **1 MiB**）的请求时，按
  `[LMR1][reqidLen][reqid][formatLen][format][current][total][content]` 分块下发，连接器收齐后
  组装回原来的 JSON 再走普通处理流程。没有声明该标记的客户端仍然收到单个文本帧。
  两个方向的分块帧结构相同，只是魔数不同（结果 `LMF1`、请求 `LMR1`）。
  `flags` 是通用的协议能力声明（与 `model_type` 分开：前者说明“支持协议的哪些可选部分”，
  后者说明“能跑什么模型”），将来新增可选能力只需加一个标记，服务器据此自动降级；
- **SDXL 参考图按连接器能力选择发送方式**：图生图 / 分区图生图 / 反推提示词要把参考图交给本地模型，
  连接器声明了 `chunked` 时直接把图片文件以 Base64 放进请求报文（大请求按上面的分块下发，
  连接器与它后面 SD WebUI 不需要能访问服务器的图片链接）；没有声明该标记的连接（例如
  `/aichat/painter-local` 浏览器接入页）仍然只收到 `/painter/image?image_id=...` 或
  `/aichat/mcp/media?file_id=...` 链接。图片读不到时自动回退成链接，不会让生成直接失败；
- **结果文件走二进制分块**：连接器把图片/视频/音频原始字节按
  `[LMF1][reqidLen][reqid][formatLen][format][current][total][content]` 的二进制帧发送，
  每块内容体最大 **5 MiB**，超过自动分包；服务端收齐 `1..total` 全部块后才完成任务，
  `-DlocalModelFileTimeoutMs`（默认 60 秒）内没有新分块视为失败并丢弃已收到的块。
  图片/视频/音频不再在 JSON 里套 Base64，`format` 直接是 `png` / `mp4` / `mp3` 等；
- 进度协商：连接器在状态/进度**发生变化时立即上报**
  `{"type":"progress","reqid":"...","status":"queued|running","progress":0.35,"eta":45000,"message":"..."}`；
  与上次成功上报完全相同（状态、进度、ETA、说明都没变）的更新会被丢弃，不重复发。
  状态语义固定：`queued` = **正在等待资源锁**（排队），`running` = **请求已下发给对应的本地软件**
  （SD WebUI / localemp / ComfyUI / TTS，执行中），`progress` 为可选的执行进度；
  `eta` 是**预计总耗时（毫秒）**，用于没有真实进度的任务：前端按 10% 一档推进，到预计时间时显示 90%，
  之后保持 90% 直到任务真正结束。各模型的默认估计可用 `-DsdxlEtaMs`、`-DqwenEtaMs`、`-Dh3EtaMs`、
  `-Dh3FrameEtaMs`、`-DttsEtaMs`、`-DsvsEtaMs` 调整（配置在本地模型部署端进程上）；同一模型内不同
  API/动作还可以单独覆盖，属性名是“模型 + 动作 + `EtaMs`”，例如 `-DsdxlTxt2imgEtaMs`、
  `-DsdxlImg2imgEtaMs`、`-DsdxlInterrogateEtaMs`、`-DqwenText2imageEtaMs`、`-DqwenEditEtaMs`、
  `-Dh3VideoEtaMs`、`-Dh3ExtractFrameEtaMs`、`-Dh3VideoDurationEtaMs`、`-Dh3FoleySegmentEtaMs`；
  没有单独配置时沿用原来的 ETA 属性或内置默认值；
  服务端收到后把该任务（含 `registerTask` 注册的外部任务）的超时延长到“当前时间 + 3 分钟”
  （`-DlocalModelProgressTimeoutMs` 可调）；如果空闲超过一个心跳间隔（默认 20 秒），连接器会补一条心跳
  （`-DlocalModelProgressIntervalMs` 可调），避免服务端因为长时间没有进度而判超时；
- 健康检查按模型类型分别进行：某类本地服务连续失败只把该类型从能力集合移除（发送能力集合报文），
  连接与其它模型不受影响，恢复后自动重新加入；
- **断线结果缓冲**：结果、进度与错误只回给请求来自的那个接收地址（连接器与服务器都会校验来源，
  不按 reqid 跨连接投递）。生成完成时如果该地址正好断开，结果先放进连接器里该地址的缓冲区
  （最多 8 条 / 256 MiB，超出丢最早一条），重连成功后立即补发；服务器凭连接URL里的
  `client_id` 认出“重连后还是同一个客户端”，把断线前没完成的任务保留下来并延长超时等待补发
  （没有 `client_id` 的连接，例如浏览器页面，仍在断开时立即失败）；
- 资源锁只在模型服务一侧：`LocalModelConnectionService` 持有一把锁，聊天进程的 MCP 不再加锁。
  H3 的 int8 档先等到至少 8 份，再占满当前剩余全部资源；NVFP4 档固定占用 **10 份**。
- **任务调度优先级**：连接器里多个任务同时等显存时按类型排序——SDXL / Qwen 生图这类快速任务最优先，
  SVS 其次，H3 视频最后（TTS 不占资源锁，不需要排队）；已经在执行的任务不会被抢占。
  为避免慢任务被一直插队，一个任务最多被插队 `-DlocalModelMaxOvertakes` 次（默认 **5** 次），
  达到后转为优先调度：资源够时先分给它，资源不够时其它任务也不许抢占，等资源归还。
  实现见 `tools.ResourceLock` + `tools.TaskPriority`，日志里会打印各任务的实际排队时长。

**语音合成（audio）**：只有显式配置了 `-DttsUrl`（或 `TTS_URL`）时，模型服务才会注册 `audio`
处理器，把聊天进程下发的朗读请求原样 POST 给本机 TTS（路径 `-DttsPath`，默认 `/`；回传格式
`-DttsFormat`，默认 `mp3`），响应可以是原始音频，也可以是 JSON 里的
`data` / `audio` / `audio_base64` / `audioBase64` / `base64`（支持 `data:audio/...;base64,` 前缀）。
不配置 `ttsUrl` 时没有 audio 能力，语音仍可由外部 `model_type=audio` 连接器直连本机网关或
MiMo / 火山提供；都没有时回复会自动退回文本。

`NapCatAIConnector` 侧的 `sdxl` / `sdxl-nsfw` / `sdxl-agent` / `svs` / `qwen` / `h3video` 工具都通过
`LocalModel` 通道调用这些模型，**不直接访问** SD WebUI / localemp / ComfyUI，也**不持有资源锁**；
模型服务没启动时工具会明确提示“本地模型服务未连接”。`qwen` 是新增技能：在 `save/config/agents.json`
的 `tools` 里加入 `"qwen"` 才会注册 `qwen_gen_image` / `qwen_edit_image`。

`h3video` 是另一个技能名：在 `tools` 里加入 `"h3video"` 会注册三个工具：
`h3_gen_video`（H3 视频制作）、`h3_gen_anim`（NVFP4 简单动图）和 `video_extract_frame`（视频截帧）。
生成工具只让模型给五类参数——提示词 `prompt`、参考图 `reference`（对象存储 id，1 张作首帧、
2 张用英文逗号分隔作首尾帧、留空为文生视频）、参考视频 `video`（与 `reference` 二选一；1 个视频
取首帧+末帧，2 个视频取第一个的首帧、第二个的末帧，由本地模型服务用 ComfyUI 截帧）、画面比例
`resolution`（16:9/9:16/1:1/4:3/3:4）和时长 `duration`（秒）。`video_extract_frame` 单独截取
任意一帧（`frame`：0 首帧、-1 末帧、-2 倒数第二帧），返回图片 id。其余全部由代码固定：

| 模式 | 主模型 | 步数 | 时长 | 画布（16:9） | 资源占用 |
| --- | --- | --- | --- | --- | --- |
| `h3_gen_video` | int8 | 8 | 1~15，默认 5 | 864×480 | 至少 8 份后占满剩余 |
| `h3_gen_anim` | NVFP4 | 6 | 1~8，默认 3 | 960×544（基础 640×352 的 1.5 倍宽高） | 固定 10 份 |

- **全部视频 MCP 均禁用音频**：工作流不加载音频 VAE、不做音频解码，输出无声 mp4；
- 宽高 32 的倍数、帧数按 24fps 向上吸附到 **17k+5**（5 秒→124、10 秒→243、15 秒→362 帧）、
  `euler`/`simple`、无 CFG 无负面词、随机种子、稀疏注意力默认开（`BlockSparseAttention`，
  token < 12288 自动退回稠密）、每次提交前 `/free`、跑完卸载显存；
- 计费：生成成功后按算力时长上报配额，**每 1 小时计算时间 = 1,000,000 配额**。估算公式为
  `F=17k+5 网格、T_lat=((F-5)/17)*5+2、n=T_lat*(W/32)*(H/32)+2*round(40*sec)+T_text`，
  `t_step=a*n+b*n^2`（INT8：a=4.94e-4、b=3.86e-8；NVFP4：a=6.10e-4、b=4.80e-8），
  再加冷加载、文本编码与 VAE 解码时间；`-Dh3DiskMBps` 可校准磁盘读吞吐
  （默认 1000 MB/s）。见 `H3VideoUsage`；
- 生成结果上传对象存储后作为视频附件返回，聊天侧会把对象存储 id 换成公网地址（或 `base64://`）
  再交给协议层发送；H3 工作流还会额外输出首帧、末帧 png（节点 16~19），API 里可通过
  `H3Output.firstFrame/lastFrame` 取用。

`h3video-agent` 是视频导演**子 agent**（与 `sdxl-agent` 同构）：主模型只给需求与参考图/参考视频，
子 agent 负责扩写 H3 提示词、决定画面比例与时长，并在 `h3_gen_video`（完整视频）与
`h3_gen_anim`（NVFP4 简单动图）之间选择，每种模式最多生成 2 次视频；参考视频可用 `video` 参数
（1 个取首末帧、2 个取首/末），需要单独某一帧时用子 agent 的 `video_extract_frame`。没有合适参考图
时可以先让 Qwen 生成一张首帧，再做图生视频。子 agent 的提示词放在 `config/h3/agent/`
（`role.txt` / `charaset.txt` / `rules.txt`，可缺省，缺省时用内置提示词），生成的视频会作为外层
工具附件自动回传；子 agent 内部视频的算力用量也会补记到外层任务。视频排队与显存由其本地模型
处理器（`H3LocalModelHandler`）统一处理。

### 本地模型终端页面

除了让独立进程连接 WebSocket，也可以用浏览器直接充当本地模型客户端：打开
`/aichat/localmodel`。页面提供：

- 服务器 WebSocket 地址与 Token 配置；
- 语音 / 绘画 / 歌声三个服务的开关、本地地址、健康检查路径、请求路径与返回格式；
- 每个服务**上线前先做健康检查**，上线后按设定间隔**定期复查**，连续失败自动从服务器下线，
  恢复后重新上线（可在设置里关闭自动重连）；
- 运行日志。

浏览器无法为 WebSocket 设置 `Authorization` 请求头，因此页面把 Token 作为 `?token=`
查询参数发送，服务端同时接受请求头与查询参数两种鉴权方式。健康检查使用 `no-cors` 请求，
只能验证本地端口可达；请求转发要求本地服务允许跨域（例如 SD WebUI 启动参数
`--cors-allow-origins=*`）。

### 用户自带绘画模型（painter-local）

`/aichat/painter-local` 是面向普通用户的接入页：它不再提供对话，只负责把用户本机的
Stable Diffusion WebUI 反向代理到服务器。页面不向用户收集 uid，固定发送 `uid=undefined`
作为占位参数；生产环境由服务器注入对应参数（例如替换页面里的占位值）。占位值会被服务器当作
共享接入（任何没有自己本地模型的会话都可以回退使用），注入真实 uid 后才按用户隔离。页面会先检查本地服务，
通过后用浏览器 WebSocket 连接 `/painter/localModelDeploy`（查询参数 `uid` 与 `model_type=sdxl`），
把服务器下发的生图请求转发到本地。生图结果不再走 WebSocket 文本帧，而是把本地服务的原始响应体
直接 POST 到 `/painter/localModelResult?reqid=<请求ID>&format=json`，由服务器用 reqid 唤醒等待中的
请求；这样大图 JSON 不会受 WebSocket 帧大小或压缩链路影响。要点：

- 该用户接入本地模型后，其**所有绘画会话**（包括已经打开的会话）都优先使用这台机器上的模型；
- 本地服务离线、断开本页或关闭浏览器时，服务器自动回退到共享的本地模型池，生成不中断；
- 带真实 uid 的连接只进入该用户的连接池，不会被其他用户取用；`undefined` 占位连接按共享接入处理；
- 接入页提供 **25%–125% 的生图尺寸比例**滑条：只在本页把发给本地模型的 `width` / `height`
  按比例缩放（按 8 的倍数向下取整），用于适配显存；服务器侧参数与对话记录不受影响；
- 服务器接入地址按当前页面推断，连续失败次数固定为 2，均不向用户开放修改；
- 该端点面向普通用户，不要求 `localVoiceToken`，并且只接受 `model_type=sdxl`。

运维如果想给所有用户提供共享模型，仍使用 `/aichat/localmodel`（`/kh$localModelDeploy`，需 Token）。
共享连接也可以带 `uid` 参数接入，此时该连接只服务指定用户。

排查接入问题时，服务端日志会打印实际接入的 uid：

```
[LocalModel] connected from ... uid=<客户端发送的值> (shared|user-bound) client=<client_id> flags=[chunked] provider: [sdxl]
[Painter] local SDXL unavailable for session user=<session 用户>
```

`shared` 表示占位值/未提供 uid，按共享连接处理；`user-bound` 表示已绑定到该用户。
`client=<client_id>` 是连接器自己的稳定标识（没有时为 `(none)`）；断线重连后该值不变，
服务器据此把重连前后的连接认成同一个客户端，接续断线期间暂存的结果。
`flags=[chunked]`（为空时是 `flags=[]`）是该连接声明的协议能力：`chunked` 表示支持分块/大报文，
服务器才会对过大的请求分块下发，也才会把多媒体内容直接放进报文而不是只给链接。

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
