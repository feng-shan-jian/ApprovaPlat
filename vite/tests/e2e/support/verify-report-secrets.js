import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { unzipSync, zipSync } from 'fflate'

const projectDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')
const reportRoot = path.join(projectDirectory, 'output', 'playwright')
const jsonReportPath = path.join(reportRoot, 'results.json')
const htmlReportPath = path.join(reportRoot, 'html', 'index.html')
const credentialKeyPattern = /^FLOWABLE_RBAC_WORKFLOW_.*_USERNAME$/
const sensitiveKeyPattern = /(?:PASSWORD|SECRET|TOKEN|USERNAME)/i
const sharedTestPassword = 'wang'
const compactJwtPattern = /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{16,}/
const textReportExtensions = new Set(['.html', '.json', '.log', '.md', '.txt', '.xml'])

/**
 * 收集本轮五角色 E2E 用户名、统一测试密码与可选数据库密码，仅用于扫描报告。
 * @returns {Buffer[]} 登录和数据库验收凭据对应的 UTF-8 字节序列。
 */
function collectCredentialValues() {
  const usernames = Object.entries(process.env)
    .filter(([key, value]) => credentialKeyPattern.test(key) && typeof value === 'string' && value.length > 0)
    .map(([, value]) => Buffer.from(value, 'utf8'))
  if (usernames.length !== 5) {
    throw new Error(`报告脱敏门禁需要五个账号，当前只读取到 ${usernames.length} 项`)
  }
  const mysqlPassword = process.env.FLOWABLE_E2E_MYSQL_PASSWORD
  const databaseCredentials = typeof mysqlPassword === 'string' && mysqlPassword.length > 0
    ? [Buffer.from(mysqlPassword, 'utf8')]
    : []
  return [...usernames, Buffer.from(sharedTestPassword, 'utf8'), ...databaseCredentials]
}

/**
 * 从 Playwright HTML 报告中解压内嵌报告分片，以便扫描压缩内容中的凭据。
 * @param {string} htmlPath HTML 报告入口文件绝对路径。
 * @returns {Record<string, Uint8Array>} 以分片文件名为键的解压内容。
 */
function readHtmlReportEntries(htmlPath) {
  const html = fs.readFileSync(htmlPath, 'utf8')
  const archive = html.match(/data:application\/zip;base64,([A-Za-z0-9+/=]+)/)
  if (!archive) {
    throw new Error('Playwright HTML 报告缺少内嵌 ZIP 数据')
  }
  return unzipSync(Buffer.from(archive[1], 'base64'))
}

/**
 * 脱敏 HTML 报告内嵌 JSON 中的账号和密码，避免 Playwright 操作步骤固化真实验收凭据。
 * @param {string} htmlPath HTML 报告入口文件绝对路径。
 * @param {Buffer[]} credentials 本轮真实账号与密码字节序列。
 * @returns {void} 报告无凭据时不改写文件，有凭据时原位写回脱敏后的内嵌 ZIP。
 */
function sanitizeHtmlReportCredentials(htmlPath, credentials) {
  const html = fs.readFileSync(htmlPath, 'utf8')
  const archive = html.match(/data:application\/zip;base64,([A-Za-z0-9+/=]+)/)
  if (!archive) {
    throw new Error('Playwright HTML 报告缺少内嵌 ZIP 数据')
  }
  const entries = unzipSync(Buffer.from(archive[1], 'base64'))
  let changed = false
  for (const [name, content] of Object.entries(entries)) {
    if (!textReportExtensions.has(path.extname(name).toLowerCase())) {
      continue
    }
    let sanitized = Buffer.from(content).toString('utf8')
    for (const credential of credentials) {
      const value = credential.toString('utf8')
      if (sanitized.includes(value)) {
        sanitized = sanitized.split(value).join('[REDACTED]')
        changed = true
      }
    }
    entries[name] = Buffer.from(sanitized, 'utf8')
  }
  if (changed) {
    const encodedArchive = Buffer.from(zipSync(entries)).toString('base64')
    fs.writeFileSync(htmlPath, html.replace(archive[0], `data:application/zip;base64,${encodedArchive}`), 'utf8')
  }
}

/**
 * 判断目标内容是否包含任一真实账号或密码。
 * @param {Uint8Array} content JSON 文件或 HTML 报告分片内容。
 * @param {Buffer[]} credentials 本轮真实账号与密码字节序列。
 * @returns {boolean} 包含任一凭据时返回 true。
 */
function containsCredential(content, credentials) {
  const buffer = Buffer.from(content)
  return credentials.some(credential => buffer.includes(credential))
}

/**
 * 递归收集 Playwright 输出目录中的全部普通文件，包括 JUnit、HTML 分片和失败附件。
 * @param {string} rootPath 当前扫描目录绝对路径。
 * @returns {string[]} 目录下全部普通文件绝对路径。
 */
function collectReportFiles(rootPath) {
  return fs.readdirSync(rootPath, { withFileTypes: true }).flatMap(entry => {
    const entryPath = path.join(rootPath, entry.name)
    return entry.isDirectory() ? collectReportFiles(entryPath) : entry.isFile() ? [entryPath] : []
  })
}

/**
 * 从根报告和嵌套批次中定位真正的 Playwright HTML 报告入口。
 * @param {string[]} reportFiles 报告根目录下的全部普通文件。
 * @returns {string[]} 每个批次 `html/index.html` 的绝对路径。
 */
function collectHtmlReportPaths(reportFiles) {
  return reportFiles.filter(filePath => (
    path.basename(filePath) === 'index.html' && path.basename(path.dirname(filePath)) === 'html'
  ))
}

/**
 * 脱敏 JSON、JUnit 和错误上下文等文本报告，避免失败页面快照固化真实账号。
 * @param {string[]} reportFiles 报告根目录下的全部普通文件。
 * @param {Buffer[]} credentials 本轮真实账号与密码字节序列。
 * @returns {void} 仅在文本文件命中凭据时原位写回脱敏内容。
 */
function sanitizeTextReportCredentials(reportFiles, credentials) {
  for (const filePath of reportFiles) {
    if (!textReportExtensions.has(path.extname(filePath).toLowerCase())) continue
    const source = fs.readFileSync(filePath, 'utf8')
    let sanitized = source
    for (const credential of credentials) {
      const value = credential.toString('utf8')
      if (sanitized.includes(value)) sanitized = sanitized.split(value).join('[REDACTED]')
    }
    if (sanitized !== source) fs.writeFileSync(filePath, sanitized, 'utf8')
  }
}

/**
 * 判断报告字节中是否出现结构完整的紧凑 JWT，避免运行时 Token 被写入文本或二进制附件。
 * @param {Uint8Array} content 任意报告文件或 HTML 压缩分片内容。
 * @returns {boolean} 发现紧凑 JWT 明文时返回 true。
 */
function containsCompactJwt(content) {
  return compactJwtPattern.test(Buffer.from(content).toString('latin1'))
}

/**
 * 校验 JSON/HTML reporter 均未持久化真实账号、密码或敏感环境变量配置。
 * @returns {void} 全部门禁通过时正常结束，发现泄漏时抛出异常。
 */
function verifyReports() {
  const credentials = collectCredentialValues()
  if (!fs.existsSync(reportRoot) || !fs.existsSync(jsonReportPath) || !fs.existsSync(htmlReportPath)) {
    throw new Error('Playwright 报告不完整，无法执行凭据门禁')
  }
  const reportFiles = collectReportFiles(reportRoot)
  const htmlReportPaths = collectHtmlReportPaths(reportFiles)
  if (htmlReportPaths.length === 0) throw new Error('Playwright HTML 报告入口缺失')
  // 每个批次先脱敏内嵌 ZIP 和文本附件，再执行递归不可绕过扫描。
  for (const reportPath of htmlReportPaths) sanitizeHtmlReportCredentials(reportPath, credentials)
  sanitizeTextReportCredentials(reportFiles, credentials)
  const jsonBytes = fs.readFileSync(jsonReportPath)
  const jsonReport = JSON.parse(jsonBytes.toString('utf8'))
  const reportEnvKeys = Object.keys(jsonReport.config?.webServer?.env ?? {})
  const sensitiveConfigKeys = reportEnvKeys.filter(key => sensitiveKeyPattern.test(key))
  const jsonContainsCredential = containsCredential(jsonBytes, credentials)
  let htmlEntryCount = 0
  const leakingHtmlEntries = []
  for (const reportPath of htmlReportPaths) {
    const entries = readHtmlReportEntries(reportPath)
    htmlEntryCount += Object.keys(entries).length
    for (const [name, content] of Object.entries(entries)) {
      if (containsCredential(content, credentials) || containsCompactJwt(content)) {
        leakingHtmlEntries.push(`${path.relative(reportRoot, reportPath)}:${name}`)
      }
    }
  }
  const leakingReportFiles = reportFiles.filter(filePath => {
    const content = fs.readFileSync(filePath)
    return containsCredential(content, credentials) || containsCompactJwt(content)
  })

  if (jsonContainsCredential || containsCompactJwt(jsonBytes) || sensitiveConfigKeys.length
      || leakingHtmlEntries.length || leakingReportFiles.length) {
    throw new Error(
      `Playwright 报告凭据门禁失败：JSON=${jsonContainsCredential ? 1 : 0}，` +
      `敏感配置键=${sensitiveConfigKeys.length}，HTML 分片=${leakingHtmlEntries.length}，` +
      `报告文件=${leakingReportFiles.length}`
    )
  }
  console.log(`Playwright 报告凭据门禁通过：文件 ${reportFiles.length}/${reportFiles.length}，` +
    `HTML ${htmlEntryCount}/${htmlEntryCount}`)
}

verifyReports()
