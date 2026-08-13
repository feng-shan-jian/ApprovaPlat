import { spawnSync } from 'node:child_process'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..')

/**
 * 在 fault 总控环境内强制重启当前仓库测试后端，并等待新进程健康。
 * @returns {{oldProcessId:number,newProcessId:number,restartedAt:string,stdout:string,stderr:string}} 不含凭据的重启证据。
 */
export function restartTestBackend() {
  if (process.platform !== 'win32') {
    throw new Error('当前 UI 故障后端重启控制器仅支持 Windows PowerShell 7')
  }
  if (process.env.FLOWABLE_E2E_FAULT_PROXY_ENABLED?.trim().toLowerCase() !== 'true') {
    throw new Error('后端重启必须通过 run-ui-tests.ps1 fault 总控执行')
  }
  const outputRoot = process.env.FLOWABLE_E2E_OUTPUT_ROOT?.trim()
  if (!outputRoot) throw new Error('缺少当前 runId 的 UI 测试输出目录')
  const restartDirectory = path.join(outputRoot, 'backend-restarts')
  const beforeEvidence = new Set(existsSync(restartDirectory)
    ? readdirSync(restartDirectory).filter(name => /^restart-\d+\.json$/u.test(name)) : [])
  const scriptPath = path.join(repositoryRoot, 'deployment', 'scripts', 'restart-ui-test-backend.ps1')
  const result = spawnSync('pwsh.exe', [
    '-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', scriptPath
  ], {
    cwd: repositoryRoot,
    env: process.env,
    timeout: 180_000,
    windowsHide: true,
    // 新 Java 后端独立存活并使用自身日志文件；忽略控制进程管道，避免等待后台进程继承的句柄关闭。
    stdio: 'ignore'
  })
  if (result.error) {
    throw new Error(`测试后端重启进程失败：${result.error.message}`)
  }
  if (result.status !== 0) {
    throw new Error(`测试后端重启失败，PowerShell 退出码 ${String(result.status)}`)
  }
  const evidenceName = readdirSync(restartDirectory)
    .filter(name => /^restart-\d+\.json$/u.test(name) && !beforeEvidence.has(name))
    .sort().at(-1)
  if (!evidenceName) throw new Error('测试后端重启未生成本次运行证据')
  const payload = JSON.parse(readFileSync(path.join(restartDirectory, evidenceName), 'utf8'))
  if (!Number.isInteger(payload.oldProcessId) || !Number.isInteger(payload.newProcessId)
    || payload.oldProcessId === payload.newProcessId) {
    throw new Error('测试后端重启未返回有效的新旧进程证据')
  }
  return payload
}
