/**
 * Android 版 `ctx.sandbox` —— 上游 `@deepseek-ai/dsh-sandbox` 的服务实现。
 *
 * <h3>这一层在 Android 上到底是什么</h3>
 * 桌面端 Linux/macOS/Windows 各有一套**内核级**约束（bwrap / Landlock / Seatbelt / ACL 受限令牌）。
 * Android 上没有 bwrap，也没有能装进 App 的 Landlock 执行器；但 Android **本身**就是一个
 * 很强的沙箱：`untrusted_app` 这个 SELinux 域的进程只能读写自己的私有目录
 * （`/data/user/0/<pkg>/…`）与系统允许的少数位置。
 *
 * 所以这里的语义是：
 * - `workspace-write`：**由 OS 的应用沙箱满足**（App 私有目录 ⊇ workspaceRoot）。
 *   我们不改写 argv，只如实报告 `enforcement: 'android-app-sandbox'`，让上层把
 *   "这是应用级沙箱、不是 workspace 级"的差异显示给用户。
 * - `read-only`：**无法满足**。App 沙箱管不住"只能读不能写"这一维度 ——
 *   一旦放开执行，写自己私有目录是允许的。这里必须 fail closed
 *   （抛 `SandboxUnavailableError`），绝不静默放行。
 * - `danger-full-access`：上游消费者自己跳过 confine，这里不参与。
 *
 * @module dsh-android-providers/sandbox
 */
import { SandboxProvider, SandboxUnavailableError } from '@deepseek-ai/dsh-sandbox'

/** 本实现的 enforcement 标识（会上到 `result.sandbox.enforcement`）。 */
const ENFORCEMENT = 'android-app-sandbox'

/**
 * 看命令行是否只是读操作（`cat` / `ls` / `rg` …）。
 * 只用于给 read-only 模式一个**明确拒绝理由**里的细节，不参与放行判断。
 * @param {string[]} argv 待执行 argv。
 * @returns {boolean} 是否像纯读命令。
 */
function looksReadOnly(argv) {
  if (argv.length < 3 || argv[1] !== '-c') return false
  const script = argv[2]
  return !/(^|[;&|(]\s*)(rm|mv|cp|mkdir|touch|tee|dd|truncate|chmod|chown|ln|sed\s+-i|>\s*[^&])/m.test(script)
}

export default class AndroidSandboxProvider extends SandboxProvider {
  constructor(ctx) {
    super(ctx)
    this.ctx.logger?.info?.(
      'sandbox: 使用 Android 应用沙箱（SELinux untrusted_app）作为执行边界；'
      + 'workspace-write 由 OS 满足，read-only 在 shell 上 fail closed。',
    )
  }

  /**
   * 把一条命令包进请求的模式里 —— 或者明确拒绝。
   *
   * Android 没有能改写 argv 的约束器，所以本方法**要么原样返回 argv 并如实标注
   * enforcement，要么抛 `SandboxUnavailableError`**；不存在"静默不受限放行"的第三条路。
   *
   * @param {string[]} argv 待执行 argv（上游 bash 执行器给的是 `["bash","-c",cmd]`）。
   * @param {{mode: string, workspaceRoot?: string}} policy 本次调用的沙箱策略。
   * @returns {{argv: string[], enforcement: string, denialSignatures: string[], runnerFailureRules: object[]}}
   * @throws {SandboxUnavailableError} 该模式在 Android 上无法执行时。
   */
  confine(argv, policy) {
    const mode = policy?.mode ?? 'workspace-write'
    if (mode === 'danger-full-access') {
      return { argv, enforcement: ENFORCEMENT, denialSignatures: [], runnerFailureRules: [] }
    }
    if (mode === 'read-only') {
      throw new SandboxUnavailableError(
        'read-only',
        looksReadOnly(argv)
          ? 'Android 应用沙箱只能约束"能碰哪些路径"，无法约束"只能读"；这条命令看起来是只读的，但本机没有可用的只读执行后端，因此仍然拒绝执行。'
          : 'Android 应用沙箱只能约束"能碰哪些路径"，无法约束"只能读"；请切到 workspace-write 再执行，或改用 fs 只读工具。',
      )
    }
    // workspace-write：由 OS 的应用沙箱满足（App 私有目录 ⊇ workspaceRoot）。
    return { argv, enforcement: ENFORCEMENT, denialSignatures: [], runnerFailureRules: [] }
  }
}
