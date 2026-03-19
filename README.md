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
├─NapCatAIConnector.java       # 与NapCat机器人框架的适配器
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

---

## 配置说明

### 数据库

服务启动时会自动在数据目录下创建 `messages.db`，包含三张表：

- `chats`：存储对话元数据（用户ID、应用ID、对话ID、时间、简述、属性）
- `permission`：用户权限（用户ID、应用ID、许可状态）
- `price`：费用记录（对话ID、价格字符串、时间）

### AI 应用加载

在指定的 `parent` 目录下，每个子文件夹代表一个 AI 应用，必须包含 `meta.json` 文件，格式示例：

```json
{
  "type": "talk",           // 对应注册的工厂类型
  "name": "应用名称",          // 显示名称
  "enabled": true,          // 是否启用
  "trial": true             // 是否为公测应用（每个用户仅可创建一个对话）
}
```

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
| `/applist`             | GET  | `uid` 用户ID                      | 获取用户有权使用的应用列表               |
| `/chatlist`            | GET  | `uid` 用户ID                      | 获取用户的对话列表                   |
| `/querychat`           | GET  | `uid`, `appid`                   | 查询用户在指定应用下的所有对话             |
| `/createid`            | GET  | 无                                | 生成一个可用的对话ID                  |
| `/remove`              | GET  | `uid`, `cid`                     | 隐藏指定对话（标记为 `hide`）          |
| `/exportChat`          | POST | `uid`, `chatid`                  | 导出对话历史为 UTF-8 文本文件          |
| `/kh$localModelDeploy` | WS   | 需 `Authorization: Bearer token` | 本地语音模型 WebSocket 连接端点       |
| `/kh$localModelDeployData` | POST | `reqid`, `type`, 二进制数据        | 本地语音模型回调，接收音频数据             |
| `/resource/*`          | GET  | -                                | 静态资源文件（图片、CSS 等）            |
| `/voice/*`             | GET  | -                                | 语音资源文件                       |
| `/index`, `/aichat` 等 | GET  | -                                | 返回对应的 HTML 页面                |

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

---

## 许可证

本项目基于 [MIT](LICENSE) 开源。

---

## 贡献

欢迎提交 Issue 和 Pull Request。在开发新功能前，请先阅读现有代码结构，保持风格一致。

---