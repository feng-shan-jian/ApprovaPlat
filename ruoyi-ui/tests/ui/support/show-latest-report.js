import { spawnSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const projectDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const reportRoot = path.join(projectDirectory, 'tests', 'ui', 'output')

/**
 * 选择最近一次包含 HTML 报告的 UI 测试运行目录。
 * @returns {string} 最新 HTML 报告目录。
 */
function latestReportDirectory() {
  if (!fs.existsSync(reportRoot)) throw new Error('尚未生成 UI 测试报告')
  const candidates = fs.readdirSync(reportRoot, { withFileTypes: true })
    .filter(entry => entry.isDirectory())
    .flatMap(dateEntry => {
      const dateDirectory = path.join(reportRoot, dateEntry.name)
      const directReport = path.join(dateDirectory, 'html')
      const nestedReports = fs.readdirSync(dateDirectory, { withFileTypes: true })
        .filter(entry => entry.isDirectory())
        .map(entry => path.join(dateDirectory, entry.name, 'html'))
      return [directReport, ...nestedReports]
    })
    .filter(directory => fs.existsSync(path.join(directory, 'index.html')))
    .sort((left, right) => fs.statSync(right).mtimeMs - fs.statSync(left).mtimeMs)
  if (!candidates.length) throw new Error('尚未找到可打开的 UI HTML 报告')
  return candidates[0]
}

try {
  const reportDirectory = latestReportDirectory()
  const command = process.platform === 'win32' ? 'npx.cmd' : 'npx'
  const result = spawnSync(command, ['playwright', 'show-report', reportDirectory], {
    cwd: projectDirectory,
    stdio: 'inherit'
  })
  process.exitCode = result.status ?? 1
} catch (error) {
  console.error(error.message)
  process.exitCode = 1
}
