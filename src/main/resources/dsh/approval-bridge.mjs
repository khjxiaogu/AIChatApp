// QQ 工具调用许可桥（Deepseek Harness 插件）。
//
// 由连接器以 --patch 覆盖层插入 sdk profile。它做两件事：
//
// 1. 挂在 approval/request 瀑布上，把「某个工具想提权」的请求经本机回环 TCP
//    交给连接器，由连接器在 QQ 里询问用户，再把用户的决定作为瀑布结果返回。
// 2. 看门狗：盯着连接器定时刷新的心跳文件。连接器被强杀时（Windows 上不会带走
//    子进程，JVM 的关闭钩子也不会执行），心跳就会停更，若干秒后自己结束整个运行时，
//    避免留下孤儿进程并长期占着会话写锁。
//    这里刻意不用「探测连接器端口」作为存活依据：子进程会继承父进程的监听套接字，
//    父进程死了端口照样有人 accept，探测结果永远是「活着」（实测如此）。
//
// 注意：本文件会被 Maven 资源过滤处理，因此源码中不出现美元符与 at 符。
import { connect } from 'node:net'
import { statSync } from 'node:fs'

export const name = 'qq-approval-bridge'
export const inject = []

const ALLOW = 'allowed-once'
const DENY = 'rejected'

export function apply(ctx, config) {
  const cfg = config || {}
  const mode = cfg.mode || 'ask'
  const host = cfg.host || '127.0.0.1'
  const port = Number(cfg.port || 0)
  const token = cfg.token || ''
  const timeoutMs = Number(cfg.timeoutMs || 120000)

  ctx.on('approval/request', async (req, next) => {
    // 明确放行 / 明确拒绝两种无人值守模式
    if (mode === 'allow') return ALLOW
    if (mode === 'deny') return DENY
    // 没有桥接端口说明连接器没准备好：交回后续应答者，最终按拒绝关闭
    if (!port) return next()

    let sessionId = ''
    try {
      sessionId = String(req.agent.session.id)
    } catch (error) {
      sessionId = ''
    }

    const decision = await askForwarder({
      type: 'approval',
      token: token,
      sessionId: sessionId,
      toolName: String(req.toolName || ''),
      reason: String(req.reason || ''),
      callId: String(req.callId || ''),
    }, host, port, timeoutMs, req.signal)

    if (decision === ALLOW || decision === DENY) return decision
    return next()
  })

  startWatchdog(ctx, cfg)
}

/**
 * 看门狗：心跳文件不再更新就把自己关掉。
 * 判定阈值默认 30 秒（连接器每 10 秒刷一次）。
 */
function startWatchdog(ctx, cfg) {
  if (cfg.watchdog === false) return
  const heartbeatFile = cfg.heartbeatFile || ''
  if (!heartbeatFile) return
  const intervalMs = Number(cfg.watchdogIntervalMs || 10000)
  const maxAgeMs = Number(cfg.heartbeatMaxAgeMs || 30000)

  ctx.effect(() => {
    const timer = setInterval(() => {
      let age
      try {
        age = Date.now() - statSync(heartbeatFile).mtimeMs
      } catch (error) {
        // 文件不存在或读不到：同样视为不可用
        age = Number.POSITIVE_INFINITY
      }
      if (age <= maxAgeMs) return
      process.stderr.write('qq-approval-bridge: heartbeat is stale ('
        + (age === Number.POSITIVE_INFINITY ? 'missing' : Math.round(age) + 'ms')
        + ', limit ' + maxAgeMs + 'ms), shutting the runtime down\n')
      shutdownRuntime(ctx)
    }, intervalMs)
    if (typeof timer.unref === 'function') timer.unref()
    return () => clearInterval(timer)
  }, 'qq-approval-bridge.watchdog')
}

/** 先尽量正常停掉根运行时（让持久化落盘），再兜底强退。 */
function shutdownRuntime(ctx) {
  // 若是 dispose 卡住，5 秒后无论如何退出
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

/**
 * 把一次许可请求发给连接器并等待决定。
 * 连接失败、超时、被中止都返回空字符串，交给后续应答者（最终 unavailable，按拒绝关闭）。
 */
function askForwarder(payload, host, port, timeoutMs, signal) {
  return new Promise((resolve) => {
    if (signal !== undefined && signal.aborted) {
      resolve('')
      return
    }
    let settled = false
    let buffer = ''
    const socket = connect({ host: host, port: port })
    socket.setEncoding('utf8')
    socket.setNoDelay(true)

    const finish = (value) => {
      if (settled) return
      settled = true
      try {
        socket.destroy()
      } catch (error) {
        // 忽略销毁失败
      }
      resolve(value)
    }

    const timer = setTimeout(() => finish(''), timeoutMs)
    if (signal !== undefined) {
      signal.addEventListener('abort', () => finish(''), { once: true })
    }

    socket.on('connect', () => {
      try {
        socket.write(JSON.stringify(payload) + '\n')
      } catch (error) {
        finish('')
      }
    })
    socket.on('data', (chunk) => {
      buffer += chunk
      const nl = buffer.indexOf('\n')
      if (nl < 0) return
      clearTimeout(timer)
      let decision = ''
      try {
        const answer = JSON.parse(buffer.slice(0, nl))
        decision = String(answer.decision || '')
      } catch (error) {
        decision = ''
      }
      finish(decision)
    })
    socket.on('timeout', () => finish(''))
    socket.on('error', () => finish(''))
    socket.on('close', () => finish(''))
  })
}
