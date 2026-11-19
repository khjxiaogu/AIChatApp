// DeepSeek Harness MCP 桥接插件。
//
// 由 Harness MCP 服务端通过 --patch 注入 web 表层。它把 DSH 的
// 工作区、聊天、任务投递、最新输出/思维链，以及 user-questions / approval
// 两个瀑布，通过本机回环 TCP 暴露给 Java 端。
//
// 注意：本文件会被 Maven 资源过滤处理，因此源码中不出现美元符与 at 符。
import { connect } from 'node:net'
import { randomUUID } from 'node:crypto'
import { mkdirSync, statSync } from 'node:fs'

export const name = 'harness-mcp-bridge'
export const inject = []

const ALLOW = 'allowed-once'
const DENY = 'rejected'

export function apply(ctx, config) {
  const cfg = config || {}
  const client = new BridgeClient(ctx, cfg)
  client.start()

  ctx.effect(() => () => client.stop(), 'harness-mcp-bridge.connection')

  // 工具审批：默认交给外部 AI 决定；allow / deny 是无人值守模式。
  ctx.on('approval/request', async (req, next) => {
    const mode = String(cfg.approvalMode || 'ask').toLowerCase()
    if (mode === 'allow') return ALLOW
    if (mode === 'deny') return DENY
    if (mode === 'off' || !client.isConnected()) return next()
    const answer = await client.ask({
      kind: 'approval',
      sessionId: sessionIdOfAgent(req && req.agent),
      toolName: String((req && req.toolName) || ''),
      reason: String((req && req.reason) || ''),
      callId: String((req && req.callId) || ''),
    }, Number(cfg.approvalTimeoutMs || 120000), req && req.signal)
    if (answer === 'allow') return ALLOW
    if (answer === 'deny') return DENY
    return next()
  }, { prepend: true })

  // ask_user_question / 计划审核：把问题转给外部 AI，返回 answers 批次。
  ctx.on('user-questions/request', async (request, next) => {
    if (!client.isConnected()) return next()
    const answer = await client.ask({
      kind: 'question',
      sessionId: sessionIdOfAgent(request && request.agent),
      questions: Array.isArray(request && request.questions) ? request.questions : [],
      wait: request && request.wait,
    }, Number(cfg.questionTimeoutMs || 120000), request && request.signal)
    if (answer !== null && typeof answer === 'object' && Array.isArray(answer.answers)) return answer
    return next()
  }, { prepend: true })

  startWatchdog(ctx, cfg)
}

/** 从 Agent 句柄取 session id。 */
function sessionIdOfAgent(agent) {
  try {
    if (agent && agent.session && agent.session.id !== undefined) return String(agent.session.id)
  } catch (error) {
    // 忽略
  }
  return ''
}

/**
 * 与 Java 端的长连接。
 * 断线后每 2 秒重连；交互请求通过通知发给 Java，Java 再用 interaction/answer 回填。
 */
class BridgeClient {
  constructor(ctx, cfg) {
    this.ctx = ctx
    this.host = String(cfg.host || '127.0.0.1')
    this.port = Number(cfg.port || 0)
    this.token = String(cfg.token || '')
    this.retryMs = Number(cfg.reconnectMs || 2000)
    this._agentCfg = {
      provider: cfg.provider,
      model: cfg.model,
      reasoning: cfg.reasoning,
      agentPreset: cfg.agentPreset,
    }
    this.socket = null
    this.buffer = ''
    this.ready = false
    this.stopped = false
    this.retryTimer = null
    this.pending = new Map()
    this.chatTitles = new Map()
    this.chatCwds = new Map()
  }

  isConnected() {
    return this.ready
  }

  start() {
    this.stopped = false
    this.connect()
  }

  stop() {
    this.stopped = true
    if (this.retryTimer !== null) {
      clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
    const socket = this.socket
    this.socket = null
    this.ready = false
    if (socket) {
      try {
        socket.destroy()
      } catch (error) {
        // 忽略
      }
    }
    for (const entry of this.pending.values()) {
      if (entry.timer !== null) clearTimeout(entry.timer)
      entry.resolve(null)
    }
    this.pending.clear()
  }

  connect() {
    if (this.stopped) return
    const socket = connect({ host: this.host, port: this.port })
    socket.setEncoding('utf8')
    socket.setNoDelay(true)
    this.socket = socket
    this.buffer = ''

    socket.on('connect', () => {
      this.ready = true
      this.send({
        type: 'hello',
        token: this.token,
        version: '0.1.0',
        pid: process.pid,
      })
      process.stderr.write('harness-mcp-bridge: connected to Java bridge at '
        + this.host + ':' + this.port + '\n')
    })

    socket.on('data', (chunk) => {
      this.buffer += chunk
      for (;;) {
        const nl = this.buffer.indexOf('\n')
        if (nl < 0) break
        const line = this.buffer.slice(0, nl).trim()
        this.buffer = this.buffer.slice(nl + 1)
        if (line.length === 0) continue
        let frame
        try {
          frame = JSON.parse(line)
        } catch (error) {
          continue
        }
        this.onFrame(frame)
      }
    })

    socket.on('error', () => {
      // close 会统一处理重连
    })

    socket.on('close', () => {
      this.ready = false
      if (this.socket === socket) this.socket = null
      for (const entry of this.pending.values()) {
        if (entry.timer !== null) clearTimeout(entry.timer)
        entry.resolve(null)
      }
      this.pending.clear()
      if (!this.stopped) this.scheduleReconnect()
    })
  }

  scheduleReconnect() {
    if (this.stopped || this.retryTimer !== null) return
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null
      this.connect()
    }, this.retryMs)
    if (typeof this.retryTimer.unref === 'function') this.retryTimer.unref()
  }

  onFrame(frame) {
    if (!frame || typeof frame !== 'object') return
    if (frame.type === 'hello') return
    if (frame.id !== undefined && frame.id !== null) {
      this.onRequest(frame)
      return
    }
    // 目前 Java 不主动发无 id 通知；保留扩展位
  }

  onRequest(frame) {
    const id = frame.id
    const method = String(frame.method || '')
    const params = frame.params && typeof frame.params === 'object' ? frame.params : {}
    const handler = this.handlers()[method]
    if (handler === undefined) {
      this.respondError(id, 'unknown method: ' + method)
      return
    }
    Promise.resolve()
      .then(() => handler(params))
      .then((result) => this.respond(id, result === undefined ? {} : result),
        (error) => this.respondError(id, error && error.message ? error.message : String(error)))
  }

  respond(id, result) {
    this.send({ id: id, result: result })
  }

  respondError(id, message) {
    this.send({ id: id, error: String(message) })
  }

  send(frame) {
    const socket = this.socket
    if (!socket || !this.ready) return false
    try {
      socket.write(JSON.stringify(frame) + '\n')
      return true
    } catch (error) {
      return false
    }
  }

  /** Java 调用的方法表。 */
  handlers() {
    return {
      'workspace/list': (params) => this.workspaceList(params),
      'chat/list': (params) => this.chatList(params),
      'chat/create': (params) => this.chatCreate(params),
      'chat/send': (params) => this.chatSend(params),
      'chat/latest': (params) => this.chatLatest(params),
      'interaction/answer': (params) => this.answerInteraction(params),
    }
  }

  // ==================== 工作区与聊天 ====================

  workspaceList() {
    const registry = this.ctx.get('workspaceRegistry')
    if (registry === undefined) return { workspaces: [] }
    const rows = registry.list()
    const workspaces = []
    for (const workspace of rows) {
      workspaces.push({
        id: String(workspace.id),
        title: String(workspace.title || ''),
        path: String(workspace.path || ''),
        sessionIds: Array.isArray(workspace.sessionIds) ? workspace.sessionIds.map(String) : [],
      })
    }
    return { workspaces: workspaces }
  }

  agents() {
    return this.ctx.get('agents')
  }

  sessions() {
    return this.ctx.get('sessions')
  }

  async chatList(params) {
    const controller = this.ctx.get('sessionController')
    if (controller === undefined) {
      throw new Error('this DSH deployment has no sessionController')
    }
    const listed = await controller.list({}, new AbortController().signal)
    const rows = Array.isArray(listed) ? listed : (Array.isArray(listed.items) ? listed.items : [])
    const limit = Math.max(1, Math.min(Number(params.limit || 500), 2000))
    const chats = []
    for (const row of rows) {
      if (row.parentSessionId !== undefined) continue
      const chatId = String(row.sessionId)
      const sessions = this.sessions()
      const live = sessions !== undefined ? sessions.get(chatId) : undefined
      let title = ''
      const projections = row.projections && row.projections.values
      if (projections && typeof projections.title === 'string') title = projections.title
      if (!title && live !== undefined) {
        try {
          const titles = this.ctx.get('sessionTitle')
          if (titles !== undefined) title = String(titles.get(live) || '')
        } catch (error) {
          title = ''
        }
      }
      const agent = this.agents().get(chatId)
      const cwd = row.cwd === undefined ? '' : String(row.cwd)
      if (cwd) this.chatCwds.set(chatId, cwd)
      if (title) this.chatTitles.set(chatId, title)
      chats.push({
        id: chatId,
        title: title,
        cwd: cwd,
        updatedAt: Number(row.updatedAt || 0),
        running: agent !== undefined && agent.status === 'running',
        blank: row.blank === true,
        parentSessionId: row.parentSessionId === undefined ? null : String(row.parentSessionId),
        status: agent === undefined ? 'idle' : String(agent.status || 'idle'),
      })
      if (chats.length >= limit) break
    }
    return { chats: chats }
  }

  async chatCreate(params) {
    const cwd = String(params.cwd || '')
    if (cwd.length === 0) throw new Error('chat/create requires cwd')
    ensureDirectory(cwd)
    const chatId = String(params.chatId || ('session-' + randomUUID()))
    const title = params.title === undefined ? '' : String(params.title)
    const composition = await this.presetComposition()
    const handle = await this.agents().create({
      sessionId: chatId,
      meta: {
        cwd: cwd,
        ...(composition.agentPreset === undefined ? {} : { agentPreset: composition.agentPreset }),
      },
      agentOptions: this.agentOptions(),
      ...(composition.setup === undefined ? {} : { setup: composition.setup }),
    })
    await this.attachToWorkspace(chatId, cwd)
    if (title.length > 0) {
      try {
        const titles = this.ctx.get('sessionTitle')
        if (titles !== undefined) await titles.rename(handle.agent.session, title)
      } catch (error) {
        process.stderr.write('harness-mcp-bridge: rename failed: ' + errorText(error) + '\n')
      }
    }
    if (title.length > 0) this.chatTitles.set(chatId, title)
    this.chatCwds.set(chatId, cwd)
    if (params.task !== undefined && String(params.task).trim().length > 0) {
      const message = userMessage(String(params.task))
      handle.agent.followup(message)
    }
    return { chatId: chatId, title: title, cwd: cwd }
  }

  async chatSend(params) {
    const chatId = String(params.chatId || '')
    if (chatId.length === 0) throw new Error('chat/send requires chatId')
    const text = String(params.text || '')
    if (text.trim().length === 0) throw new Error('chat/send requires text')
    let cwd = String(params.cwd || '')
    if (cwd.length === 0) cwd = await this.cwdOf(chatId)
    await this.ensureNotArchived(chatId)
    const handle = await this.openSession(chatId, cwd)
    await this.ensureNotArchived(chatId)
    const agent = handle.agent === undefined ? handle : handle.agent
    const message = userMessage(text)
    agent.followup(message)
    return { messageId: String(message.id), chatId: chatId }
  }

  async chatLatest(params) {
    const chatId = String(params.chatId || '')
    if (chatId.length === 0) throw new Error('chat/latest requires chatId')
    const includeReasoning = params.includeReasoning !== false
    const since = params.since === undefined ? -1 : Number(params.since)
    const maxMessages = Math.max(1, Math.min(Number(params.maxMessages || 20), 100))
    const events = await this.eventsOf(chatId)
    let cursor = -1
    const allItems = []
    for (const event of events) {
      if (!event || typeof event !== 'object') continue
      const item = extractItem(event, includeReasoning)
      if (item === null) continue
      allItems.push(item)
      const seq = Number(event.seq)
      if (Number.isFinite(seq) && seq > cursor) cursor = seq
    }
    const items = since >= 0
      ? allItems.filter((item) => item.seq > since).slice(-maxMessages)
      : allItems.slice(-maxMessages)
    const agent = this.agents().get(chatId)
    return {
      chatId: chatId,
      title: this.chatTitles.get(chatId) || '',
      running: agent !== undefined && agent.status === 'running',
      status: agent === undefined ? 'idle' : String(agent.status || 'idle'),
      cursor: cursor,
      items: items,
    }
  }

  // ==================== 待处理交互 ====================

  ask(payload, timeoutMs, signal) {
    if (!this.ready) return Promise.resolve(null)
    const id = String(payload.kind || 'ask') + '-' + randomUUID()
    payload.id = id
    payload.createdAt = Date.now()
    const chatId = String(payload.sessionId || '')
    payload.chatId = chatId
    payload.title = this.chatTitles.get(chatId) || ''
    return new Promise((resolve) => {
      let settled = false
      let onAbort = null
      const finish = (value) => {
        if (settled) return
        settled = true
        const entry = this.pending.get(id)
        if (entry !== undefined && entry.timer !== null) clearTimeout(entry.timer)
        this.pending.delete(id)
        if (signal !== undefined && onAbort !== null) signal.removeEventListener('abort', onAbort)
        resolve(value)
      }
      onAbort = () => {
        if (settled) return
        this.sendNotice('interaction/closed', { id: id, reason: 'aborted' })
        finish(null)
      }
      const timer = setTimeout(() => {
        this.sendNotice('interaction/closed', { id: id, reason: 'timeout' })
        finish(null)
      }, Math.max(1000, timeoutMs))
      if (timer !== null && typeof timer.unref === 'function') timer.unref()
      if (signal !== undefined) {
        if (signal.aborted) {
          finish(null)
          return
        }
        signal.addEventListener('abort', onAbort, { once: true })
      }
      this.pending.set(id, { resolve: finish, timer: timer })
      if (!this.sendNotice('interaction', payload)) finish(null)
    })
  }

  answerInteraction(params) {
    const id = String(params.id || '')
    const entry = this.pending.get(id)
    if (entry === undefined) return { accepted: false, reason: 'not-pending' }
    this.pending.delete(id)
    if (entry.timer !== null) clearTimeout(entry.timer)
    let answer = params.answer
    if (typeof answer === 'string') {
      if (answer === 'allow' || answer === 'deny') {
        // 审批决定
      } else if (answer === 'allowed-once' || answer === 'rejected') {
        answer = answer === 'allowed-once' ? 'allow' : 'deny'
      }
    }
    entry.resolve(answer)
    return { accepted: true }
  }

  sendNotice(method, params) {
    return this.send({ method: method, params: params })
  }

  // ==================== DSH 会话辅助 ====================

  agentOptions() {
    const options = {}
    if (this.cfgProvider()) options.provider = this.cfgProvider()
    if (this.cfgModel()) options.model = this.cfgModel()
    const reasoning = this.cfgReasoning()
    if (reasoning.length > 0) options.reasoningEffort = reasoning
    return options
  }

  cfgProvider() {
    return String((this._agentCfg && this._agentCfg.provider) || '')
  }

  cfgModel() {
    return String((this._agentCfg && this._agentCfg.model) || '')
  }

  cfgReasoning() {
    return String((this._agentCfg && this._agentCfg.reasoning) || '')
  }

  presetId() {
    return String((this._agentCfg && this._agentCfg.agentPreset) || 'standard')
  }

  async presetComposition() {
    const presets = this.ctx.get('agentPresets')
    if (presets === undefined) return {}
    const resolved = await presets.resolve(this.presetId())
    return {
      agentPreset: resolved.id,
      setup: async (agentCtx) => {
        await presets.mount(agentCtx, resolved.id)
      },
    }
  }

  async attachToWorkspace(sessionId, cwd) {
    const registry = this.ctx.get('workspaceRegistry')
    if (registry === undefined) return
    try {
      const workspace = (await registry.resolveByPath(cwd)) || (await registry.create(cwd))
      await workspace.attachSession(sessionId)
    } catch (error) {
      process.stderr.write('harness-mcp-bridge: attach session failed: ' + errorText(error) + '\n')
    }
  }

  async ensureNotArchived(sessionId) {
    const registry = this.ctx.get('workspaceRegistry')
    if (registry === undefined) return
    try {
      if (!registry.archivedSessionIds.includes(sessionId)) return
      await registry.unarchiveSession(sessionId)
    } catch (error) {
      process.stderr.write('harness-mcp-bridge: unarchive failed: ' + errorText(error) + '\n')
    }
  }

  async openSession(sessionId, cwd) {
    const live = this.agents().get(sessionId)
    if (live !== undefined) {
      if (cwd) await this.attachToWorkspace(sessionId, cwd)
      return { agent: live }
    }
    const composition = await this.presetComposition()
    const agentOptions = this.agentOptions()
    try {
      return await this.agents().resume({
        resumeSessionId: sessionId,
        agentOptions: agentOptions,
        ...(composition.setup === undefined ? {} : { setup: composition.setup }),
      })
    } catch (error) {
      process.stderr.write('harness-mcp-bridge: resume ' + sessionId + ' failed: '
        + errorText(error) + '\n')
      if (!cwd) throw error
      const handle = await this.agents().create({
        sessionId: sessionId,
        meta: {
          cwd: cwd,
          ...(composition.agentPreset === undefined ? {} : { agentPreset: composition.agentPreset }),
        },
        agentOptions: agentOptions,
        ...(composition.setup === undefined ? {} : { setup: composition.setup }),
      })
      await this.attachToWorkspace(sessionId, cwd)
      return handle
    }
  }

  async cwdOf(chatId) {
    const cached = this.chatCwds.get(chatId)
    if (cached) return cached
    const controller = this.ctx.get('sessionController')
    if (controller !== undefined) {
      try {
        const rows = await controller.list(new AbortController().signal)
        for (const row of rows) {
          if (String(row.sessionId) === chatId && row.cwd !== undefined) {
            this.chatCwds.set(chatId, String(row.cwd))
            return String(row.cwd)
          }
        }
      } catch (error) {
        // 忽略
      }
    }
    return ''
  }

  async eventsOf(chatId) {
    const controller = this.ctx.get('sessionController')
    if (controller !== undefined && typeof controller.inspect === 'function') {
      const observation = await controller.inspect(chatId, new AbortController().signal)
      return Array.isArray(observation && observation.events) ? observation.events : []
    }
    const sessions = this.sessions()
    const live = sessions === undefined ? undefined : sessions.get(chatId)
    if (live !== undefined && typeof live.snapshotEvents === 'function') {
      return live.snapshotEvents()
    }
    throw new Error('this DSH deployment cannot read chat history')
  }
}

/** 把一条文本包装成 DSH 用户消息。 */
function userMessage(text) {
  return {
    id: randomUUID(),
    role: 'user',
    content: [{ type: 'text', text: text }],
    source: { kind: 'user' },
  }
}

/** 确保目录存在。 */
function ensureDirectory(path) {
  try {
    mkdirSync(path, { recursive: true })
  } catch (error) {
    throw new Error('cannot create workspace directory: ' + errorText(error))
  }
}

/** 从事件里抽取给外部 AI 的条目。 */
function extractItem(event, includeReasoning) {
  const seq = Number(event.seq)
  if (!Number.isFinite(seq)) return null
  const data = event.data && typeof event.data === 'object' ? event.data : {}
  if (event.type === 'user/message') {
    // 只保留真实用户输入；DSH 注入的 system-reminder / 上下文不算聊天内容
    const source = data.source && typeof data.source === 'object' ? data.source : {}
    if (source.kind !== 'user') return null
    const text = textOfContent(data.content)
    if (text.length === 0) return null
    return { seq: seq, role: 'user', text: text }
  }
  if (event.type === 'assistant/message') {
    const message = data.message && typeof data.message === 'object' ? data.message : {}
    const blocks = Array.isArray(message.content) ? message.content : []
    let text = ''
    let reasoning = ''
    const tools = []
    for (const block of blocks) {
      if (!block || typeof block !== 'object') continue
      if (block.type === 'text') text += String(block.text || '')
      else if (block.type === 'reasoning') reasoning += String(block.text || '')
      else if (block.type === 'tool-call') {
        tools.push({
          name: String(block.name || block.toolName || ''),
          arguments: typeof block.arguments === 'string'
            ? block.arguments
            : JSON.stringify(block.arguments || block.input || {}),
          call_id: String(block.id || block.callId || ''),
        })
      }
    }
    if (includeReasoning && reasoning.length === 0) {
      reasoning = reasoningFromStream(data.stream)
    }
    if (!includeReasoning) reasoning = ''
    if (text.length === 0 && reasoning.length === 0 && tools.length === 0) return null
    const item = { seq: seq, role: 'assistant', text: text }
    if (reasoning.length > 0) item.reasoning = reasoning
    if (tools.length > 0) item.tools = tools
    return item
  }
  if (event.type === 'tool/result') {
    const message = data.message && typeof data.message === 'object' ? data.message : {}
    const text = textOfContent(message.content)
    return {
      seq: seq,
      role: 'tool',
      tool_call_id: String(message.toolCallId || ''),
      text: text,
      is_error: message.isError === true || (data.error !== undefined),
    }
  }
  if (event.type === 'turn/end' && data.reason !== undefined && data.reason !== 'completed') {
    return { seq: seq, role: 'system', text: 'turn end: ' + String(data.reason) }
  }
  return null
}

/** 拼接内容块中的文本。 */
function textOfContent(content) {
  if (!Array.isArray(content)) return ''
  let text = ''
  for (const block of content) {
    if (block && typeof block === 'object' && block.type === 'text') {
      text += String(block.text || '')
    }
  }
  return text
}

/** 紧凑流里的思维链增量。 */
function reasoningFromStream(stream) {
  if (!Array.isArray(stream)) return ''
  let text = ''
  for (const record of stream) {
    if (!record || typeof record !== 'object') continue
    if (record.type !== 'reasoning-chunks' || !Array.isArray(record.texts)) continue
    for (const part of record.texts) text += String(part || '')
  }
  return text
}

/** 错误文本。 */
function errorText(error) {
  if (error === null || error === undefined) return 'unknown error'
  return error.message ? String(error.message) : String(error)
}

/** 看门狗：Java 端不再刷新心跳时让 DSH 自己退出。 */
function startWatchdog(ctx, cfg) {
  if (cfg.watchdog === false) return
  const heartbeatFile = String(cfg.heartbeatFile || '')
  if (heartbeatFile.length === 0) return
  const intervalMs = Number(cfg.watchdogIntervalMs || 10000)
  const maxAgeMs = Number(cfg.heartbeatMaxAgeMs || 30000)
  ctx.effect(() => {
    const timer = setInterval(() => {
      let age
      try {
        age = Date.now() - statSync(heartbeatFile).mtimeMs
      } catch (error) {
        age = Number.POSITIVE_INFINITY
      }
      if (age <= maxAgeMs) return
      process.stderr.write('harness-mcp-bridge: heartbeat is stale, shutting down\n')
      shutdownRuntime(ctx)
    }, intervalMs)
    if (typeof timer.unref === 'function') timer.unref()
    return () => clearInterval(timer)
  }, 'harness-mcp-bridge.watchdog')
}

/** 先正常 dispose 根运行时，再兜底强退。 */
function shutdownRuntime(ctx) {
  setTimeout(() => process.exit(0), 5000)
  try {
    const fiber = ctx.root && ctx.root.fiber
    if (fiber && typeof fiber.dispose === 'function') {
      Promise.resolve(fiber.dispose()).then(
        () => process.exit(0),
        () => process.exit(0))
      return
    }
  } catch (error) {
    // 落到强退
  }
  process.exit(0)
}
