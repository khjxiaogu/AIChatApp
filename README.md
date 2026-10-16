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
| `deepseektoken`   | Deepseek API 的 Token，若存在则启用 Deepseek 模型提供商 |
| `volcmodeltoken`  | 火山引擎模型 Token，若存在则启用火山模型提供商           |
| `localVoiceToken` | 本地语音模型的 Bearer Token，用于验证 WebSocket 连接 |

启动时通过 `-D` 参数传入，例如：
```bash
java -Ddeepseektoken=your_token -DlocalVoiceToken=voice_token -jar aichat.jar
```

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

### 让 Deepseek Harness 接管 QQ（DeepseekHarnessConnector）

`DeepseekHarnessConnector` 与 `NapCatAIConnector` 方向相反：不再由 AIWuxia 的 AI 应用作答，
而是把 QQ 消息转给 **Deepseek Harness** 运行时，再把它的正文与思维链转发回 QQ。

工作方式：

1. 以 `dsh --profile sdk` 拉起 Deepseek Harness，通过官方 SDK 协议（stdio 上按换行分帧的 JSON-RPC 2.0）
   握手、投递消息、接收 `session.event` / `session.status` 通知；
2. 作为 OneBot v11 正向 WebSocket 客户端连接 NapCat；
3. **只转发两样东西**：助手正文（`content[].type == "text"`）与思维链（`content[].type == "reasoning"`），
   工具调用、系统提示、运行上下文等一律丢弃；
4. 私聊消息直接视为发消息，群聊消息必须 **at 机器人** 才投递；
5. 转发时按行切分，**每超过 20 个字的一行作为一条消息发送**（可用 `dsh_msg_limit` 调整）。

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
| `dsh_home`             | `~/.dsh`           | Deepseek Harness 的 DSH_HOME      |
| `dsh_root`             | 自动探测               | Deepseek Harness 安装根目录          |
| `dsh_launcher`         | 自动探测               | 启动器路径（`DeepSeek Harness.exe` 或 `dsh.cmd`） |
| `dsh_provider`         | `deepseek-account` | 模型提供方路由                         |
| `dsh_model`            | `deepseek-flash`   | 模型 id                           |
| `dsh_reasoning`        | `high`             | 推理强度，留空则不指定                     |
| `dsh_cwd`              | `save/dsh-workspace` | 会话工作目录（agent 工具的活动范围）           |
| `dsh_msg_limit`        | `20`               | 每条消息的字数上限                       |
| `dsh_output_reasoning` | `true`             | 是否转发思维链                         |
| `dsh_prefix`           | `true`             | 是否给消息加【思考】/【回复】前缀               |
| `dsh_interval_ms`      | `200`              | 连续发送消息之间的间隔（毫秒）                 |
| `dsh_turn_timeout_ms`  | `300000`           | 单轮回复超时（毫秒）                      |
| `dsh_max_messages`     | `200`              | 单轮最多发送多少条消息，防止刷屏                |
| `dsh_guard_prompt`     | `true`             | 用 `qq-message` 信封隔离不可信聊天输入       |
| `dsh_relay_context`    | `false`            | 群聊里未被 at 的消息是否作为上下文附带给模型        |

会话映射保存在 `save/dsh-sessions.json`（QQ 会话 → DSH sessionId），重启后对话不丢。

---

## 配置说明

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

`type` 字段决定了使用哪个 `AIApplicationFactory` 创建实例。内置类型：
- `wuxia`：武侠世界
- `article`：文章写作
- `talk`：角色对话
- `trpg`：TRPG 跑团
- 等等（可在 `AIChatService.reload()` 中查看）

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

### 添加新的 AI 应用类型

1. 实现 `AIApplication` 抽象类，重写核心方法（`getName`, `constructSystem`, `getBrief`, `provideInitial` 等）。
2. 实现 `AIApplicationFactory` 接口，在 `createInstance` 中实例化上述应用。
3. 在应用启动时（或静态代码块中）调用 `AIApplicationRegistry.register("type", factory)` 注册工厂。
4. 在应用目录下创建对应的 `meta.json`，`type` 字段与注册的名称一致。

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