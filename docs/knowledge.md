# 知识库 MCP

`KnowledgeBaseMcp` 给 Codex / 多 agent 会话提供项目知识库检索：按关键字搜文件名和文件内容，
并读取 Markdown。

## 搜索范围

以项目 `cwd` 为根：

- 项目根目录下的**文件**（不递归，根目录的子文件夹不直接算）；
- 项目根目录的 `design/`、`docs/`、`diary/`、`discussion/` 及它们的**所有子文件夹**；
- 每个**一级子文件夹**下的 `design/`、`docs/`、`diary/`、`discussion/` 及其子文件夹
  （例如 `moduleA/docs/design.md`、`client/design/api.md`）；
- 目录列表会缓存，默认每 5 分钟按需刷新一次；新建目录后最多等一个刷新周期即可搜到。

会跳过 `.git`、`node_modules`、`build`、`target`、`out`、`.gradle` 等构建/依赖目录；
单个文件超过 2MB 不参与内容搜索；二进制文件只按文件名匹配。

## 工具

| 工具 | 参数 | 说明 |
|---|---|---|
| `kb_search` | `keyword` 必填，`max_results` 可选（默认 20，最多 50） | 搜文件名和内容，返回相对路径 + 匹配片段；多个中英文关键词用 `\|` 分隔一起搜 |
| `kb_read` | `path` 必填（相对路径），`max_chars` 可选（默认 12000，最多 40000） | 只允许 `.md`；`personal/` 开头读个人知识库 |
| `kb_write` | `path`、`content` 必填，`mode` 可选 | **仅私人 agent**：写入/创建个人知识库 `.md`；`append`（默认）/`overwrite`/`create`/`patch` |

示例（中英文一起搜）：

```
kb_search(keyword="天气|weather")
→ 中文/英文文档里任一关键词命中都会返回
```

示例：

```
kb_search(keyword="天气系统")
→ 1. docs/design/weather.md（文件名+内容匹配）
    片段：天气系统由 ... 控制

kb_read(path="docs/design/weather.md")
```

## 接入

- 知识库是**可配置工具**：工具名 `knowledge`，在 `defaultTools` 或会话 `extraTools` 里加上才会注册。
  示例：`"defaultTools": ["fetch", "qq", "knowledge"]`。
- codex / 多 agent 会话按各自项目 `cwd` 建库；多 agent 每个私人会话用自己 `codex.cwd`。
- 私人 agent 额外有一个**可写个人知识库**：`save/multi/knowledge/<用户id>/`，只能通过
  `kb_write` 写入 `.md`；搜索结果里显示为 `personal/...`，用 `kb_read` 读回。
- `kb_write` 的 `mode=patch` 支持两种格式：统一 diff（`@@ -1,3 +1,4 @@` + `-`/`+` 行），
  或 `<<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE` 搜索/替换块；找不到上下文会报错且不写文件。
- 公屏 agent 不会注册 `kb_write`，不能写个人库。
- 没有单独的配置项；知识库范围固定为上面的目录约定。
