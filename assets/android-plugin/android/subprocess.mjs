/**
 * Android 版 `ctx.subprocess` —— 上游 `@deepseek-ai/dsh-subprocess` 的服务实现。
 *
 * <h3>为什么不用上游的 `dsh-subprocess-local`</h3>
 * 它靠 `koffi`（原生 FFI）load libc 的 `execve`、systemd scope、Windows Job Object，
 * 在 Android 上既没有这些机制也没有原生模块。本实现只保留**契约要求**的那部分：
 * `spawn` / `resolveExecutable`，输出收集（有界 + spill 文件）、SIGTERM→SIGKILL 回收。
 *
 * <h3>Android 特有的两处差异（都写在这里，不藏在别处）</h3>
 * 1. **没有 bash**：上游 `dsh-bash-local` 把 argv 写死成 `["bash","-c",cmd]`。
 *    这里在解析可执行文件时把 `bash` 落到系统的 mksh（`/system/bin/sh`）——
 *    语义上 `sh -c` 覆盖绝大多数命令，但不等于 bash（无 `[[ ]]`、无数组）。
 * 2. **进程组回收**：`detached: true` + `kill(-pid)`；失败退化为直接子进程 kill。
 *    Android 上 SELinux 限制 `untrusted_app` 只能执行系统自带的、或 App 自己带的可执行文件。
 *
 * @module dsh-android-providers/subprocess
 */
import { spawn as nodeSpawn } from 'node:child_process'
import { closeSync, openSync, statSync, unlinkSync, writeSync, promises as fsp, constants as fsConstants } from 'node:fs'
import { isAbsolute, resolve, delimiter } from 'node:path'
import { randomBytes } from 'node:crypto'
import { tmpdir } from 'node:os'
import { SubprocessRuntime, scrubbedParentEnv } from '@deepseek-ai/dsh-subprocess'

/** Node 的定时器上限（上游同样的约束）。 */
const MAX_TIMER_DELAY_MS = 2147483647

/** spill 文件名序号。 */
let spillCounter = 0

/**
 * 裸名字 → 系统里等价的可执行文件。
 * Android 没有 bash，只有 mksh；上游的 bash 执行器写死了 `bash -c`，因此在这里兜底。
 */
const SHELL_SHIMS = {
  bash: ['/system/bin/sh', '/bin/sh'],
  sh: ['/system/bin/sh', '/bin/sh'],
  zsh: ['/system/bin/sh', '/bin/sh'],
}

/** 没有 PATH 时的兜底（Android 的默认搜索路径）。 */
const DEFAULT_PATH = '/system/bin:/system/xbin:/bin:/vendor/bin:/odm/bin'

/**
 * 子进程环境：父环境去掉凭证型名字与 `DSH_*`，再叠加调用方显式给出的条目。
 * @param {object|undefined} extra 显式环境层（后写覆盖）。
 */
function childEnv(extra) {
  const base = scrubbedParentEnv()
  return extra === undefined ? base : { ...base, ...extra }
}

/**
 * 读一个环境变量的值（POSIX 大小写敏感）。
 * @param {object} env 环境映射。
 * @param {string} name 变量名。
 */
function environmentValue(env, name) {
  return env === undefined ? undefined : env[name]
}

/**
 * 同步地在 PATH 里找一个可执行文件（`spawn` 必须同步返回句柄）。
 * @param {string} command argv[0]。
 * @param {object} env 子进程环境。
 * @returns {string} 可执行文件的绝对路径。
 * @throws 找不到时抛出，带上试过哪些路径。
 */
function whichSync(command, env) {
  const candidates = searchCandidates(command, env)
  for (const candidate of candidates) {
    try {
      // 只看存在性与可执行位；真正的失败留给 spawn 的 error 事件。
      if (statSync(candidate).isFile()) return candidate
    } catch {}
  }
  throw new Error(`subprocess-android: command ${JSON.stringify(command)} was not found on PATH`)
}

/**
 * 一个裸名字可能的绝对路径候选（含 Android 的 shell 兜底）。
 * @param {string} command argv[0]。
 * @param {object} env 子进程环境。
 */
function searchCandidates(command, env) {
  if (isAbsolute(command)) return [command]
  if (command.includes('/')) throw new Error(`subprocess-android: command ${JSON.stringify(command)} is a relative path; use an absolute path or a bare PATH name`)
  const path = environmentValue(env, 'PATH') ?? DEFAULT_PATH
  const out = []
  for (const directory of path.split(delimiter)) {
    if (directory === '') continue
    out.push(resolve(directory, command))
  }
  for (const shim of SHELL_SHIMS[command] ?? []) out.push(shim)
  return out
}

/**
 * 有界输出收集器（尾保留 + 溢出落 spill 文件）—— 与上游同名类同语义：
 * 内存里只留最后 maxBytes，整条流超过 maxSpillBytes 就不再落盘。
 */
class OutputCollector {
  /**
   * @param {number} maxBytes 内存保留上限。
   * @param {number|undefined} maxSpillBytes spill 文件上限（undefined = 不落盘）。
   * @param {string} label 流标签（进文件名）。
   * @param {string} spillDir spill 目录。
   */
  constructor(maxBytes, maxSpillBytes, label, spillDir) {
    this.maxBytes = maxBytes
    this.maxSpillBytes = maxSpillBytes
    this.label = label
    this.spillDir = spillDir
    this.chunks = []
    this.bytes = 0
    this.total = 0
    this.dropped = false
    this.spillFd = undefined
    this.spillFile = undefined
    this.spillDisabled = maxSpillBytes === undefined
  }

  /** 吃进一块数据。 */
  push(chunk) {
    this.total += chunk.length
    const overflows = this.bytes + chunk.length > this.maxBytes
    if (!this.spillDisabled && (overflows || this.spillFd !== undefined)) this.spillAll(chunk)
    this.chunks.push(chunk)
    this.bytes += chunk.length
    while (this.bytes > this.maxBytes) {
      const head = this.chunks[0]
      const excess = this.bytes - this.maxBytes
      if (head.length <= excess) {
        this.chunks.shift()
        this.bytes -= head.length
      } else {
        this.chunks[0] = head.subarray(excess)
        this.bytes -= excess
      }
      this.dropped = true
    }
  }

  /** 首次溢出时开 spill 文件，并把已收集的块补写进去。 */
  spillAll(chunk) {
    if (this.maxSpillBytes !== undefined && this.total > this.maxSpillBytes) {
      this.discardSpill()
      return
    }
    if (this.spillFd === undefined) {
      this.spillFile = resolve(this.spillDir, `dsh-subprocess-${process.pid}-${++spillCounter}-${randomBytes(6).toString('hex')}-${this.label}.log`)
      this.spillFd = openSync(this.spillFile, 'wx', 0o600)
      for (const prior of this.chunks) writeSync(this.spillFd, prior)
    }
    writeSync(this.spillFd, chunk)
  }

  /** 关掉并删掉 spill 文件（流已经大到装不下了）。 */
  discardSpill() {
    const fd = this.spillFd
    const file = this.spillFile
    this.spillFd = undefined
    this.spillFile = undefined
    this.spillDisabled = true
    if (fd !== undefined) try { closeSync(fd) } catch {}
    if (file !== undefined) try { unlinkSync(file) } catch {}
  }

  /** 关掉 spill 文件（进程已退出，不再往里写）。 */
  seal() {
    if (this.spillFd === undefined) return
    try {
      closeSync(this.spillFd)
    } catch {
      this.spillFile = undefined
    }
    this.spillFd = undefined
  }

  /**
   * 增量读取（整条流的字节坐标）。
   * @param {number} fromByte 上一次读到的 nextOffset。
   */
  readFrom(fromByte) {
    const windowStart = this.total - this.bytes
    const buffer = Buffer.concat(this.chunks)
    const lossy = fromByte < windowStart
    return {
      text: (lossy ? buffer : buffer.subarray(fromByte - windowStart)).toString('utf8'),
      nextOffset: this.total,
      lossy,
      ...(this.spillFile !== undefined ? { spillPath: this.spillFile } : {}),
    }
  }

  /** 最终输出（tail 文本 + 是否截断 + spill 路径）。 */
  finalize() {
    this.seal()
    return {
      text: Buffer.concat(this.chunks).toString('utf8'),
      truncated: this.dropped,
      ...(this.spillFile !== undefined ? { spillPath: this.spillFile } : {}),
    }
  }
}

/**
 * 校验一个 spawn 请求里必须在启动前就成立的部分。
 * @param {object} spec 已解析的 spawn 规格。
 */
function validateSpec(spec) {
  if (!Number.isFinite(spec.graceMs) || spec.graceMs <= 0 || spec.graceMs > MAX_TIMER_DELAY_MS) {
    throw new Error(`subprocess-android: graceMs must be a positive finite number no greater than ${MAX_TIMER_DELAY_MS}`)
  }
  if (spec.signal?.aborted === true) {
    let reason = 'aborted'
    try { reason = String(spec.signal.reason ?? reason) } catch {}
    throw new Error(`subprocess-android: aborted before spawn: ${reason}`)
  }
  const program = spec.argv?.[0]
  if (program === undefined || program.length === 0) throw new Error('subprocess-android: invalid argv: expected a non-empty program name at argv[0]')
}

/**
 * 收集模式：不是 `pipe` 也不是 `inherit` 就是"收集"。
 * @param {unknown} mode 某个流模式。
 */
function isCollect(mode) {
  return mode !== 'pipe' && mode !== 'inherit'
}

/**
 * 起一个受管进程并绑上生命周期。
 * @param {object} spec 已解析的 spawn 规格。
 * @param {string} spillDir spill 目录。
 */
function launch(spec, spillDir) {
  const [program, ...args] = spec.argv
  const env = childEnv(spec.env)
  const file = whichSync(program, env)
  const stdoutMode = spec.stdio.stdout
  const stderrMode = spec.stdio.stderr
  const stdinMode = spec.stdio.stdin

  const child = nodeSpawn(file, args, {
    cwd: spec.cwd,
    env,
    stdio: [
      stdinMode === 'ignore' ? 'ignore' : 'pipe',
      stdoutMode === 'inherit' ? 'inherit' : 'pipe',
      stderrMode === 'inherit' ? 'inherit' : 'pipe',
    ],
    detached: true,
  })

  const make = (mode, stream, label) => {
    if (!isCollect(mode) || stream === null || stream === undefined) return undefined
    const collector = new OutputCollector(mode.maxBytes, mode.spill?.maxBytes, label, spillDir)
    stream.on('data', (chunk) => collector.push(chunk))
    return collector
  }
  const stdoutCollector = make(stdoutMode, child.stdout, 'stdout')
  const stderrCollector = make(stderrMode, child.stderr, 'stderr')

  if (typeof stdinMode === 'object' && child.stdin !== null && child.stdin !== undefined) {
    child.stdin.on('error', () => {})
    child.stdin.end(stdinMode.data)
  }

  let exited = false
  let terminating = false
  let graceTimer
  let exitObservation

  const signalRange = (signal) => {
    if (exited) return
    const pid = child.pid
    if (pid === undefined) return
    try {
      process.kill(-pid, signal)
    } catch {
      try { child.kill(signal) } catch {}
    }
  }

  const sealCollectors = () => {
    if (stdoutCollector !== undefined) child.stdout?.destroy()
    if (stderrCollector !== undefined) child.stderr?.destroy()
    stdoutCollector?.seal()
    stderrCollector?.seal()
  }

  const done = new Promise((resolveDone, rejectDone) => {
    let settled = false
    let drainTimer
    const settle = (outcome) => {
      if (settled) return
      settled = true
      if (drainTimer !== undefined) clearTimeout(drainTimer)
      sealCollectors()
      resolveDone(outcome)
    }
    const fail = (error) => {
      if (settled) return
      settled = true
      if (drainTimer !== undefined) clearTimeout(drainTimer)
      sealCollectors()
      rejectDone(error)
    }
    child.once('error', fail)
    child.once('exit', (code, signal) => {
      exited = true
      if (graceTimer !== undefined) {
        clearTimeout(graceTimer)
        graceTimer = undefined
      }
      // exit 之后等 stdio 排空（有界：最多 graceMs），避免丢掉最后一段输出。
      drainTimer = setTimeout(() => settle({ exitCode: code, signal }), spec.graceMs)
    })
    child.once('close', (code, signal) => settle({ exitCode: code, signal }))
  })

  const waitForExit = () => {
    exitObservation ??= done.then(() => {}, () => {})
    return exitObservation
  }

  const terminate = () => {
    if (exited || terminating) return
    terminating = true
    signalRange('SIGTERM')
    graceTimer = setTimeout(() => {
      graceTimer = undefined
      if (!exited) signalRange('SIGKILL')
    }, spec.graceMs)
  }

  const terminateForHostExit = () => {
    if (exited) return
    signalRange('SIGKILL')
  }

  const onAbort = () => terminate()
  spec.signal?.addEventListener('abort', onAbort, { once: true })
  done.then(() => spec.signal?.removeEventListener('abort', onAbort), () => spec.signal?.removeEventListener('abort', onAbort))

  return {
    pid: child.pid,
    collected: {
      ...(stdoutCollector !== undefined ? { stdout: stdoutCollector } : {}),
      ...(stderrCollector !== undefined ? { stderr: stderrCollector } : {}),
    },
    done,
    terminate,
    terminateForHostExit,
    waitForExit,
  }
}

/**
 * Android 的 `ctx.subprocess`。
 *
 * 与上游 `dsh-subprocess-local` 的差别（诚实清单）：
 * - 没有 systemd scope / Job Object / Landlock 那层"托管范围"，只有进程组；
 * - 没有 PTY（`spawnTerminal` 明确抛错，而不是假装能用）；
 * - `bash` 落到 mksh。
 */
export default class AndroidSubprocessRuntime extends SubprocessRuntime {
  /** 仍然活着的句柄（正常销毁时统一回收）。 */
  live = new Set()

  constructor(ctx) {
    super(ctx)
    ctx.effect(() => {
      const onExit = () => {
        for (const handle of this.live) {
          try { handle.terminateForHostExit() } catch {}
        }
      }
      process.prependListener('exit', onExit)
      return async () => {
        const pending = []
        for (const handle of this.live) {
          handle.terminate()
          pending.push(handle.done.catch(() => {}).then(() => this.live.delete(handle)))
        }
        await Promise.allSettled(pending)
        process.off('exit', onExit)
      }
    }, 'android subprocess teardown')
  }

  /** spill 目录：App 私有 TMPDIR。 */
  get spillDir() {
    const dir = process.env.TMPDIR
    return dir !== undefined && dir.length > 0 ? dir : tmpdir()
  }

  /**
   * 起一个受管进程（同步返回句柄）。
   * @param {object} spec 完整 spawn 规格。
   */
  spawn(spec) {
    validateSpec(spec)
    const handle = launch(spec, this.spillDir)
    this.live.add(handle)
    const release = () => handle.waitForExit().then(() => { this.live.delete(handle) })
    handle.done.then(release, release).catch(() => {})
    return handle
  }

  /**
   * 在一个执行世界（本机）里解析可执行文件。
   * @param {string} command 绝对路径或裸 PATH 名。
   * @param {object|undefined} env 解析用的环境。
   * @param {AbortSignal|undefined} signal 取消。
   * @returns {Promise<string>} 绝对路径。
   */
  async resolveExecutable(command, env, signal) {
    if (command.length === 0) throw new Error('subprocess-android: executable must be non-empty')
    signal?.throwIfAborted()
    const environment = childEnv(env)
    const candidates = searchCandidates(command, environment)
    for (const candidate of candidates) {
      signal?.throwIfAborted()
      try {
        const st = await fsp.stat(candidate)
        if (!st.isFile()) continue
        await fsp.access(candidate, fsConstants.X_OK)
        signal?.throwIfAborted()
        return candidate
      } catch {}
    }
    signal?.throwIfAborted()
    throw new Error(isAbsolute(command)
      ? `subprocess-android: command ${JSON.stringify(command)} is not an executable file`
      : `subprocess-android: command ${JSON.stringify(command)} was not found on PATH`)
  }

  /**
   * PTY 在 Android 上没有实现（上游靠 node-pty 的原生模块）。
   * @throws 明确失败，绝不假装开了终端。
   */
  async spawnTerminal() {
    throw new Error('subprocess-android: PTY 终端在 Android 上不可用（上游依赖 node-pty 原生模块）；请改用普通进程执行。')
  }
}
