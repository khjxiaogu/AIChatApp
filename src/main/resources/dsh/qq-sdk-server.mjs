// QQ 连接器专用的 SDK stdio 服务端。
//
// 官方 @deepseek-ai/dsh-sdk-jsonrpc-server 对每个 sessionId 只会 agents.create()，
// 一旦会话已经落盘（也就是上一次运行留下的），create 会直接报
// `session "..." already exists`，结果就是重启后要么发不出去、要么只能换个新会话。
//
// 这里实现同一套协议（initialize / session/prompt / shutdown + session.event /
// session.status 通知），但取会话时先 agents.resume()，失败才 create()，
// 因此连接器重启后仍然沿用同一个会话、同一段对话历史。
//
// 注意：本文件会被 Maven 资源过滤处理，因此源码中不出现美元符与 at 符。
import { randomUUID } from 'node:crypto'

export const name = 'qq-sdk-server'
export const inject = ['agents']

export function apply(ctx, config) {
  const input = process.stdin
  const output = process.stdout
  // 用哪个 agent preset：web 表层把基础工具行全禁了，工具是靠 preset 挂到 agent 上的，
  // 不指定 preset 的 agent 一个工具都没有（模型只能把工具调用语法当普通文本吐出来）
  const presetId = config && typeof config.agentPreset === 'string' ? config.agentPreset : 'standard'

  let cwd = process.cwd()
  let provider = 'deepseek-official'
  let model = 'deepseek-official'
  let reasoningEffort
  let maxTokens
  let initialized = false
  let closing = false

  /** sessionId -> handle，进程内复用 */
  const handles = new Map()

  const write = (frame) => {
    try {
      output.write(JSON.stringify(frame) + '\n')
    } catch (error) {
      // stdout 已关闭，忽略
    }
  }

  // 会话事件与状态，直接转成协议通知
  ctx.effect(() => {
    const offEvent = ctx.on('session/event', (session, event) => {
      write({ jsonrpc: '2.0', method: 'session.event', params: { sessionId: String(session.id), event } })
    })
    const offStatus = ctx.on('agent/status', (payload) => {
      const agent = payload && payload.agent
      if (!agent || !agent.session) return
      write({
        jsonrpc: '2.0', method: 'session.status',
        params: { sessionId: String(agent.session.id), status: payload.status },
      })
    })
    return () => {
      if (typeof offEvent === 'function') offEvent()
      if (typeof offStatus === 'function') offStatus()
    }
  }, 'qq-sdk-server.events')

  /**
   * 组装 preset 相关参数：工具与提示词段落都是 preset 挂到 agent 上的。
   * 没有 agentPresets 服务（例如 sdk profile）时返回空对象，行为与官方一致。
   */
  async function presetComposition() {
    const presets = ctx.get('agentPresets')
    if (presets === void 0) return {}
    const resolved = await presets.resolve(presetId)
    return {
      agentPreset: resolved.id,
      setup: async (agentCtx) => {
        await presets.mount(agentCtx, resolved.id)
      },
    }
  }

  /**
   * 把会话挂到它所在的工作目录上。
   * web 界面的会话列表是按 workspace 分组的：不挂上去的话，会话文件明明存在，
   * 侧边栏里却看不到（看起来就像「没有创建会话」）。
   */
  async function attachToWorkspace(sessionId) {
    // 服务名是 workspaceRegistry（不是 workspaces）
    const workspaces = ctx.get('workspaceRegistry')
    if (workspaces === void 0) return
    try {
      const workspace = (await workspaces.resolveByPath(cwd)) ?? (await workspaces.create(cwd))
      await workspace.attachSession(sessionId)
    } catch (error) {
      process.stderr.write('qq-sdk-server: attach session "' + sessionId + '" to workspace "' + cwd
        + '" failed: ' + (error && error.message ? error.message : String(error)) + '\n')
    }
  }

  /**
   * 会话被归档时，`agent/pre-step` 会直接拒掉这一步，回合以 `blocked` 收口、
   * 连模型请求都不会发出去——QQ 那边看起来就是「投递了但没回复」。
   * 所以每次投递前都先把它从归档集合里摘出来。
   */
  async function ensureNotArchived(sessionId) {
    const registry = ctx.get('workspaceRegistry')
    if (registry === void 0) return
    try {
      if (!registry.archivedSessionIds.includes(sessionId)) return
      process.stderr.write('qq-sdk-server: session "' + sessionId + '" was archived; unarchiving it\n')
      await registry.unarchiveSession(sessionId)
    } catch (error) {
      process.stderr.write('qq-sdk-server: unarchive "' + sessionId + '" failed: '
        + (error && error.message ? error.message : String(error)) + '\n')
    }
  }

  /** 取会话：已存在就 resume，否则 create。 */
  async function openSession(sessionId) {
    const cached = handles.get(sessionId)
    if (cached) return cached
    const live = ctx.agents.get(sessionId)
    if (live) {
      const rec = { agent: live }
      handles.set(sessionId, rec)
      await attachToWorkspace(sessionId)
      return rec
    }
    const agentOptions = {
      provider: provider,
      model: model,
      ...reasoningEffort === undefined ? {} : { reasoningEffort: reasoningEffort },
      ...maxTokens === undefined ? {} : { maxTokens: maxTokens },
    }
    const composition = await presetComposition()
    let handle
    try {
      handle = await ctx.agents.resume({
        resumeSessionId: sessionId,
        agentOptions: agentOptions,
        ...composition.setup === undefined ? {} : { setup: composition.setup },
      })
    } catch (error) {
      process.stderr.write('qq-sdk-server: resume failed for "' + sessionId + '" ('
        + (error && error.message ? error.message : String(error)) + '), creating a new session\n')
      handle = await ctx.agents.create({
        sessionId: sessionId,
        meta: {
          cwd: cwd,
          ...composition.agentPreset === undefined ? {} : { agentPreset: composition.agentPreset },
        },
        agentOptions: agentOptions,
        ...composition.setup === undefined ? {} : { setup: composition.setup },
      })
    }
    handles.set(sessionId, handle)
    // 让界面能在对应工作目录下看到这个会话
    await attachToWorkspace(sessionId)
    return handle
  }

  async function dispatch(method, params) {
    switch (method) {
      case 'initialize': {
        await ctx.get('loader')?.await()
        if (typeof params.cwd === 'string' && params.cwd.length > 0) cwd = params.cwd
        if (typeof params.provider === 'string') provider = params.provider
        if (typeof params.model === 'string') model = params.model
        reasoningEffort = typeof params.reasoningEffort === 'string' ? params.reasoningEffort : undefined
        maxTokens = Number.isSafeInteger(params.maxTokens) ? params.maxTokens : undefined
        initialized = true
        return { serverInfo: { name: 'deepseek-harness-sdk-runtime', version: '0.0.1' } }
      }
      case 'session/prompt': {
        if (!initialized) throw new Error('SDK server is not initialized')
        const sessionId = String(params.sessionId ?? '')
        if (sessionId.length === 0) throw new Error('session/prompt requires a sessionId')
        const blocks = Array.isArray(params.contentBlocks) ? params.contentBlocks : []
        const content = blocks
          .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
          .map((b) => ({ type: 'text', text: b.text }))
        if (content.length === 0) throw new Error('session/prompt requires text content')
        // 归档的会话会被 pre-step 拒掉（回合以 blocked 结束且不发模型请求），先解除
        await ensureNotArchived(sessionId)
        const handle = await openSession(sessionId)
        await ensureNotArchived(sessionId)
        const agent = handle.agent ?? handle
        const message = { id: randomUUID(), role: 'user', content: content, source: { kind: 'user' } }
        agent.followup(message)
        return { messageId: message.id }
      }
      case 'shutdown': {
        if (!closing) {
          closing = true
          setTimeout(() => {
            try {
              const fiber = ctx.root && ctx.root.fiber
              const done = fiber && typeof fiber.dispose === 'function' ? fiber.dispose() : Promise.resolve()
              Promise.resolve(done).finally(() => process.exit(0))
            } catch (error) {
              process.exit(0)
            }
          }, 50)
        }
        return {}
      }
      default:
        throw new Error('unknown DeepSeek Harness SDK runtime method: ' + method)
    }
  }

  function onLine(line) {
    const text = line.trim()
    if (text.length === 0) return
    let frame
    try {
      frame = JSON.parse(text)
    } catch (error) {
      return
    }
    if (!frame || typeof frame !== 'object') return
    if (typeof frame.method !== 'string') return
    if (frame.id === undefined) return
    Promise.resolve()
      .then(() => dispatch(frame.method, frame.params && typeof frame.params === 'object' ? frame.params : {}))
      .then(
        (result) => write({ jsonrpc: '2.0', id: frame.id, result: result }),
        (error) => write({
          jsonrpc: '2.0', id: frame.id,
          error: { code: -32603, message: error && error.message ? error.message : String(error) },
        }))
  }

  ctx.effect(() => {
    let buffer = ''
    const onData = (chunk) => {
      buffer += typeof chunk === 'string' ? chunk : chunk.toString('utf8')
      for (;;) {
        const nl = buffer.indexOf('\n')
        if (nl < 0) break
        const line = buffer.slice(0, nl)
        buffer = buffer.slice(nl + 1)
        onLine(line)
      }
    }
    input.setEncoding('utf8')
    input.on('data', onData)
    return () => {
      input.off('data', onData)
    }
  }, 'qq-sdk-server.stdin')
}
