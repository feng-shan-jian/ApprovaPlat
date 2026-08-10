import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

// 直接定位项目依赖内的 CLI，避免 Windows .cmd 和含空格 Node.js 路径依赖 shell 解析。
const playwrightCli = fileURLToPath(new URL('../../../node_modules/@playwright/test/cli.js', import.meta.url))

/**
 * 以继承当前终端输出的方式执行子命令，避免在 Node.js 中复制或记录敏感环境变量。
 * @param {string} command 可执行文件名称。
 * @param {string[]} args 命令参数数组。
 * @returns {number} 子进程退出码；进程无法启动时返回 1。
 */
function runCommand(command, args) {
  const result = spawnSync(command, args, {
    env: process.env,
    stdio: 'inherit',
    shell: false
  })
  return Number.isInteger(result.status) ? result.status : 1
}

/**
 * 执行真实浏览器测试并无条件执行报告保密门禁。
 * @returns {void} 最终通过 process.exitCode 返回测试或门禁失败状态。
 */
function main() {
  const playwrightExit = runCommand(process.execPath, [playwrightCli, 'test', ...process.argv.slice(2)])
  const reportGateExit = runCommand(process.execPath, ['tests/e2e/support/verify-report-secrets.js'])
  process.exitCode = playwrightExit !== 0 ? playwrightExit : reportGateExit
}

main()
