# OpenAI 兼容 TTS 接口

本程序对外提供一套兼容 OpenAI 音频语音接口的 TTS 端点，供外部客户端（OpenAI SDK、朗读软件、
其他服务）把文本转成语音。接口只接入**本地语音后端**（`-DlocalVoiceToken` 对应的本地模型连接），
不接入 MiMo / 火山。

代码位置：

| 类 | 作用 |
| --- | --- |
| `com.khjxiaogu.aiwuxia.tts.TtsApiEndpoint` | 路由、鉴权、参数校验、异步合成与响应 |
| `com.khjxiaogu.aiwuxia.tts.TtsAudioFormat` | 音频格式规范化与 MIME 映射 |
| `AIChatService#tts` | 把 `/aichat/tts` 及其子路径转发给上面的端点 |

---

## 1. 挂载与地址

- 挂载点：`/aichat/tts`（`AIServerMain` 不需要额外注册，由 `AIChatService#tts` 转发）
- 客户端 `base_url`：`http://<host>:8999/aichat/tts/v1`
- 也接受不带 `/v1` 的写法：`http://<host>:8999/aichat/tts`，两种都能命中同一组端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/aichat/tts/v1/audio/speech` | 文本转语音（OpenAI 标准） |
| `GET` | `/aichat/tts/v1/audio/voices` | 列出可用音色（扩展端点） |
| `GET` | `/aichat/tts/v1/models` | 模型列表（OpenAI 标准格式） |
| `GET` | `/aichat/tts/v1` | 接口索引，便于确认地址是否正确 |
| `OPTIONS` | 上述路径 | CORS 预检，不需要令牌 |

---

## 2. 鉴权

复用 MCP 访问令牌，**不额外新增配置项**。

- 创建令牌：打开 `/aichat/mcptoken` 页面，或调用
  `POST /aichat/mcp/token?uid=<用户ID>`（请求体可带 `{"name":"tts","expiresInDays":30}`）。
  明文令牌只在创建时返回一次，形如 `mcp_xxxxxxxx`。
- 请求头（三选一）：
  - `Authorization: Bearer mcp_xxx`
  - `Authorization: Token mcp_xxx`
  - `X-MCP-Token: mcp_xxx`

失败返回 `401`：缺少令牌 `missing_token`、令牌无效 `invalid_token`、已停用/过期 `token_expired`。
MCP 服务端未启用（`save/mcp.json` 中 `enabled=false`）时，令牌存储不存在，接口返回 `503 tts_unavailable`。

### 2.1 计费

与 MCP 工具调用完全一致：

- **预扣**：请求开始时按固定调用费 `ttsCallCost`（默认 `100`，即 `McpServer.CALL_COST`）从令牌所属用户的
  **额外配额**中预扣，余额或令牌额度不足直接返回 `429 insufficient_quota`，不执行合成；
- **补扣**：合成成功后按实际用量合计 `Σ ceil(getEquivantTokens())` 补扣（情感标注的 LLM 用量 +
  本地语音的 `LocalVoiceUsage`），并写入 `paid_usage_record` / `daily_usage`；
- **失败**：参数/鉴权/离线等发生在扣费之前，不产生费用；标注已完成但合成失败时退回固定调用费、
  只结算已产生的标注用量；完全没有用量时全额退回；
- 每次调用都会在 `mcp_tool_call_log` 留下一条 `tts_speech` 记录（可在 `/aichat/mcptoken` 页面查看）。

计费关闭（独立进程的免费令牌库）时固定费为 0。

---

## 3. 端点

### 3.1 `POST /v1/audio/speech`

请求体与 OpenAI 官方一致（未知字段会被忽略）：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `model` | string | 否 | 默认 `gptsovits`。可传 `gptsovits`、`local`、`aiwuxia`、`tts-1`、`tts-1-hd`、`gpt-4o-mini-tts`；其它值返回 `400 model_not_found` |
| `input` | string | **是** | 要朗读的文本，不能为空；默认上限 4096 字符（`-DttsMaxInputLength` 可调） |
| `voice` | string | **是** | **必须是本地语音配置里的角色名**，见 `GET /v1/audio/voices`；否则返回 `400 voice_not_found` |
| `response_format` | string | 否 | `mp3` / `wav`（也接受 `mpeg`、`wave` 等别名）。**不传时按 mp3 声明**；服务端不做转码，该字段只决定响应头里的 `Content-Type` / `X-TTS-Format` |
| `speed` | number | 否 | 0.25–4.0；**当前只做范围校验，不会改变语速**（本地后端无变速能力） |
| `instructions` | string | 否 | 朗读风格提示（如“轻松带笑意、语速稍慢”）。会拼进 VoiceTagger 的标注提示词，**只影响逐句情感/语气选择**，不改变朗读文本，也不保证完全可控；默认上限 500 字符（`-DttsMaxInstructionsLength`） |

成功响应：

- 状态码 `200`
- `Content-Type`：`audio/mpeg` 或 `audio/wav`。本地模型立即返回时按实际格式声明；进入流式路径后按
  `response_format` 声明（未传时默认 `mp3`）
- `X-TTS-Format`：与 `Content-Type` 对应的格式名
- `X-TTS-Model`：`gptsovits`
- `Cache-Control: no-store`
- 流式路径的响应体为分块（chunked）音频，没有 `Content-Length`

> 情感标注阶段失败时返回准确的错误状态；一旦进入流式合成阶段，响应头已经发出，后续失败只能
> 表现为空音频（详见第 4、7 节）。

```bash
curl -X POST "http://127.0.0.1:8999/aichat/tts/v1/audio/speech" \
  -H "Authorization: Bearer mcp_xxxxxxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"gptsovits","input":"你好，欢迎来到江湖。","voice":"姚枫茜","response_format":"mp3"}' \
  --output speech.mp3
```

```python
from openai import OpenAI

client = OpenAI(base_url="http://127.0.0.1:8999/aichat/tts/v1", api_key="mcp_xxxxxxxx")
with client.audio.speech.with_streaming_response.create(
    model="gptsovits", voice="姚枫茜", input="你好，欢迎来到江湖。",
) as response:
    response.stream_to_file("speech.mp3")
```

```javascript
import OpenAI from "openai";
import fs from "node:fs";

const client = new OpenAI({ baseURL: "http://127.0.0.1:8999/aichat/tts/v1", apiKey: "mcp_xxxxxxxx" });
const speech = await client.audio.speech.create({
  model: "gptsovits", voice: "姚枫茜", input: "你好，欢迎来到江湖。",
});
fs.writeFileSync("speech.mp3", Buffer.from(await speech.arrayBuffer()));
```

### 3.2 `GET /v1/audio/voices`

```json
{
  "object": "list",
  "data": [
    { "id": "姚枫茜", "object": "audio.voice", "backend": "gptsovits", "available": true }
  ]
}
```

- `id` 即 `voice` 参数要填的角色名，来自 `save/config/local-voice.json` 的键；
- `available` 表示本地语音服务当前是否在线（离线时仍会列出，但合成会返回 `503`）。

### 3.3 `GET /v1/models`

```json
{
  "object": "list",
  "data": [
    { "id": "gptsovits", "object": "model", "created": 1735689600, "owned_by": "aiwuxia" },
    { "id": "tts-1", "object": "model", "created": 1735689600, "owned_by": "aiwuxia" },
    { "id": "tts-1-hd", "object": "model", "created": 1735689600, "owned_by": "aiwuxia" },
    { "id": "gpt-4o-mini-tts", "object": "model", "created": 1735689600, "owned_by": "aiwuxia" }
  ]
}
```

`tts-1` 等 OpenAI 模型名只是别名，全部指向本地语音后端，便于现成客户端直接接入。

### 3.4 `GET /v1`

返回接口索引、`base_url`、鉴权方式，以及 `emotion_tagging: true`（表示默认走语音标注链路）。

---

## 4. 合成链路

`POST /v1/audio/speech` 默认沿用项目内朗读链路，与聊天页的朗读一致：

```
input + voice
   │
   ▼
VoiceTagger（LLM 分句 + 情感标注，按句返回 text/emotion）
   │
   ▼
本地语音模型（WebSocket audio 客户端）
   │
   ▼
音频字节（mp3 或 wav）→ 直接作为响应体
```

因此接口依赖以下条件，缺一不可：

1. 启动参数 `-DlocalVoiceToken=...`，且本地语音配置存在（`save/config/local-voice.json`，
   角色名 → 本地音色 ID）；
2. 本地语音服务在线（WebSocket `model_type=audio` 客户端已连接，或配置了本机 HTTP TTS）；
3. **可用的 LLM 路由**用于情感标注（通常是 `-Dmimotoken=...`，并准备
   `save/config/voice-tags.txt` 标注提示词）。

行为影响：

- 每次请求 = 一次 LLM 标注 + 一次本地合成，延迟明显高于纯 TTS；LLM 不可用时整次请求失败
  （`503 emotion_tagger_unavailable`），即使本地 TTS 本身正常；
- 同一段文本两次调用可能得到不同分段，从而音频不完全一致；如需稳定输出，请自行缓存结果；
- `VoiceTagger` 会先去掉 `（……）` 这类括号旁白再朗读，与聊天页行为一致；
- `instructions` 会作为「朗读风格要求」拼进 VoiceTagger 的提示词，只影响逐句情感标注的结果，
  不会改变要朗读的文本；标注输出仍会被校验为固定情感集合，防止自由文本破坏结构。

**执行方式**（两阶段）：

1. **情感标注阶段**：在请求线程内同步等待。此阶段的失败（LLM 不可用、超时、标注结果为空）
   都能返回准确的 HTTP 状态码；
2. **本地合成阶段**：任务提交给本地语音模型后，若已完成（成功或失败）仍按同步路径返回，状态码与
   `Content-Type` 准确；只有耗时的合成才用 `Response.openStream(200)` 接管响应并立即返回，由线程池
   等待结果、分块写出音频。流式阶段的失败无法再改状态码，客户端会收到 `200` + 空音频，原因记录在
   `latest.log`（`[TTS]` 前缀）。

并发由 `-DttsMaxConcurrent` 限制；许可从标注阶段一直持有到音频写完。

---

## 5. 错误响应

统一使用 OpenAI 错误信封：

```json
{ "error": { "message": "……", "type": "invalid_request_error", "param": "voice", "code": "voice_not_found" } }
```

| HTTP | type | code（示例） | 触发条件 |
| --- | --- | --- | --- |
| 400 | `invalid_request_error` | `input_required` / `input_too_long` | 文本缺失或超长 |
| 400 | `invalid_request_error` | `input_empty_after_cleaning` | 整段文本都是 `（……）` 旁白，去掉后没有可朗读内容 |
| 400 | `invalid_request_error` | `voice_required` / `voice_not_found` | 未传音色，或不是配置中的角色名 |
| 400 | `invalid_request_error` | `model_not_found` | `model` 不在支持列表 |
| 400 | `invalid_request_error` | `unsupported_response_format` | 请求了 mp3/wav 之外的格式 |
| 400 | `invalid_request_error` | `instructions_too_long` | `instructions` 超过长度上限 |
| 400 | `invalid_request_error` | `invalid_json` / `invalid_speed` | 请求体不是 JSON 对象 / 语速越界 |
| 401 | `authentication_error` | `missing_token` 等 | 令牌缺失、无效、过期 |
| 404 | `invalid_request_error` | `not_found` | 未知子路径 |
| 405 | `invalid_request_error` | `method_not_allowed` | 方法不对，响应带 `Allow` 头 |
| 415 | `invalid_request_error` | `unsupported_media_type` | `Content-Type` 不是 JSON |
| 429 | `rate_limit_error` | `too_many_requests` | 并发已满，响应带 `Retry-After` |
| 429 | `rate_limit_error` | `insufficient_quota` | 额外配额或令牌额度不足，未执行合成 |
| 500 | `server_error` | `billing_failed` | 预扣/结算数据库异常 |
| 500 | `server_error` | `tts_failed` / `tts_empty` | 进入流式阶段前的内部错误，或本地返回空音频 |
| 503 | `server_error` | `tts_unavailable` | 本地语音未启用 / MCP 令牌未初始化 |
| 503 | `server_error` | `local_tts_offline` | 本地语音服务不在线 |
| 503 | `server_error` | `emotion_tagger_unavailable` | 情感标注模型不可用 |
| 504 | `server_error` | `tts_timeout` | 情感标注等待超时 |

---

## 6. 配置项

全部通过 `-D` 系统属性配置，修改后需重启：

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `ttsMaxInputLength` | `4096` | 单次请求的 `input` 长度上限 |
| `ttsMaxInstructionsLength` | `500` | `instructions` 的长度上限（它会被拼进标注提示词） |
| `ttsTimeoutMs` | `120000` | 一次请求的总等待上限（情感标注 + 本地合成），标注阶段超时返回 `504` |
| `ttsMaxConcurrent` | `4` | 同时进行的合成数（许可覆盖标注与合成两个阶段），超过返回 `429` |
| `ttsCallCost` | `100` | 每次调用的固定费用（额外配额单位）；`0` 表示只按实际用量计费 |

---

## 7. 限制与注意事项

- **格式不做转码**：本地后端输出 mp3 还是 wav，取决于本地语音模型的配置
  （本机 HTTP TTS 的 `-DttsFormat`）。不传 `response_format` 时按 **mp3** 声明 `Content-Type`；
  如果本机实际输出 wav，调用方应显式传 `response_format: "wav"`，否则响应头与实际数据可能不一致
  （服务端会记录告警日志，但不会转码）。
- **不是 SSE**：响应是 HTTP 分块音频流（无 `Content-Length`），未实现 OpenAI 的 SSE 语音事件流。
- **两阶段执行**：情感标注同步完成，失败返回准确状态码；本地合成阶段已接管响应，失败只能表现为
  `200` + 空音频，原因见 `latest.log` 的 `[TTS]` 日志。最多 `ttsMaxConcurrent` 路并发，
  超出的请求立即返回 `429`（不排队）。
- **`speed` 不生效、`instructions` 只影响情感标注**：`speed` 仅做范围校验；`instructions` 会拼进
  VoiceTagger 的提示词参与情感/语气选择，输出仍被校验为固定情感集合，不改变朗读内容，也不保证效果。
- **音色只能是角色名**：不做 OpenAI 预设音色（alloy、nova 等）的映射，也不做拼写容错；
  这样音量、口音都对应配置里明确的角色。
- **并发与计费**：并发上限由 `ttsMaxConcurrent` 控制；每次调用按固定调用费 + 实际用量从额外配额扣除，
  失败规则见 2.1 节，消费记录可在 `/aichat/mcptoken` 查看。
