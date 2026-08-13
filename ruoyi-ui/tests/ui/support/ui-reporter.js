import fs from 'node:fs'
import path from 'node:path'

/**
 * 将运行错误中的测试凭据替换为固定占位符。
 * @param {unknown} value Playwright 错误、步骤或附件文本。
 * @returns {string} 不包含用户名、密码和 Token 的文本。
 */
function redactSecrets(value) {
  const secrets = Object.entries(process.env)
    .filter(([name, content]) => /PASSWORD|TOKEN|SECRET|USERNAME/u.test(name) && content)
    .map(([, content]) => String(content))
    .filter(content => content.length >= 3)
  return secrets.reduce((text, secret) => text.split(secret).join('<redacted>'), String(value || ''))
}

/**
 * 从正式测试标题中提取稳定用例编号。
 * @param {string} title Playwright 完整用例标题。
 * @returns {string} 方括号中的稳定编号；缺失时返回空串并由报告标记非法。
 */
function caseIdFromTitle(title) {
  return String(title).match(/\[([A-Z][A-Z0-9-]+)\]/u)?.[1] || ''
}

/**
 * 把 Playwright 结果转换为测试规格规定的四态结果。
 * @param {import('@playwright/test/reporter').TestCase} test 当前测试定义。
 * @param {import('@playwright/test/reporter').TestResult} result 当前测试结果。
 * @returns {{status:string,reason:string}} 规范状态和可恢复原因。
 */
function normalizeStatus(test, result) {
  if (result.status !== 'skipped') {
    return {
      status: result.status === 'passed' ? 'passed' : 'failed',
      reason: redactSecrets(result.error?.message || '')
    }
  }
  const blocked = test.annotations.find(item => item.type === 'blocked')
  if (blocked) return { status: 'blocked', reason: redactSecrets(blocked.description || '环境阻塞') }
  const notExecuted = test.annotations.find(item => item.type === 'not-executed')
  if (notExecuted) return { status: 'not executed', reason: redactSecrets(notExecuted.description || '明确未执行') }
  return { status: 'failed', reason: '发现未声明原因的静默 skip' }
}

/**
 * 读取同一运行编号已有的测试账本，使定向重跑只更新本次选中的用例。
 * @param {string} outputFile 账本文件绝对或相对路径。
 * @param {string} runId 当前 `FLOWABLE_E2E_RUN_ID`。
 * @returns {object|null} 可继续合并的账本；文件不存在时返回 null。
 */
function loadExistingState(outputFile, runId) {
  if (!fs.existsSync(outputFile)) return null
  const existing = JSON.parse(fs.readFileSync(outputFile, 'utf8'))
  if (existing.runId !== runId || !existing.cases || typeof existing.cases !== 'object') {
    throw new Error(`已有 UI 测试账本与当前运行不兼容：${outputFile}`)
  }
  return existing
}

export default class UiRunStateReporter {
  /**
   * 创建可在上下文压缩或环境恢复后继续使用的运行账本。
   * @param {{outputFile?:string}} options Reporter 输出配置。
   */
  constructor(options = {}) {
    this.outputFile = options.outputFile || path.resolve('output/playwright/ui/run-state.json')
    const runId = process.env.FLOWABLE_E2E_RUN_ID || ''
    const now = new Date().toISOString()
    const existing = loadExistingState(this.outputFile, runId)
    this.state = existing
      ? { ...existing, updatedAt: now, status: 'running', summary: {} }
      : { runId, startedAt: now, updatedAt: now, status: 'running', cases: {}, summary: {} }
    delete this.state.finishedAt
  }

  /**
   * 登记全部用例，保证未运行用例也能在账本中被识别。
   * @param {import('@playwright/test/reporter').FullConfig} _config Playwright 配置。
   * @param {import('@playwright/test/reporter').Suite} suite 根测试套件。
   * @returns {void}
   */
  onBegin(_config, suite) {
    for (const test of suite.allTests()) {
      const title = test.titlePath().join(' > ')
      const caseId = caseIdFromTitle(title) || `INVALID-${test.id}`
      const existing = this.state.cases[caseId]
      this.state.cases[caseId] = {
        ...existing,
        caseId,
        title,
        file: test.location.file,
        line: test.location.line,
        status: 'pending',
        attempts: Number(existing?.attempts || 0),
        reason: '',
        durationMs: 0,
        evidence: existing?.evidence || []
      }
    }
    this.writeState()
  }

  /**
   * 每个用例结束即写盘，进程中断时保留最后成功检查点。
   * @param {import('@playwright/test/reporter').TestCase} test 当前测试定义。
   * @param {import('@playwright/test/reporter').TestResult} result 当前测试结果。
   * @returns {void}
   */
  onTestEnd(test, result) {
    const title = test.titlePath().join(' > ')
    const caseId = caseIdFromTitle(title) || `INVALID-${test.id}`
    const normalized = normalizeStatus(test, result)
    const existing = this.state.cases[caseId] || { caseId, title, attempts: 0 }
    this.state.cases[caseId] = {
      ...existing,
      status: normalized.status,
      attempts: Number(existing.attempts || 0) + 1,
      reason: normalized.reason,
      durationMs: result.duration,
      evidence: result.attachments.map(item => ({
        name: item.name,
        contentType: item.contentType,
        path: item.path || ''
      }))
    }
    this.writeState()
  }

  /**
   * 汇总四态数量并登记运行是否完整结束。
   * @param {import('@playwright/test/reporter').FullResult} result Playwright 总体结果。
   * @returns {void}
   */
  onEnd(result) {
    const values = Object.values(this.state.cases)
    this.state.finishedAt = new Date().toISOString()
    this.state.summary = Object.fromEntries(
      ['passed', 'failed', 'blocked', 'not executed', 'pending'].map(status => [
        status,
        values.filter(item => item.status === status).length
      ])
    )
    // 总状态必须包含未被本次 grep 选中的历史用例，不能只采用当前子集的 Playwright 结果。
    this.state.status = result.status === 'interrupted'
      ? 'interrupted'
      : this.state.summary.failed > 0 || this.state.summary.pending > 0
        ? 'failed'
        : 'passed'
    this.writeState()
  }

  /**
   * 使用原子替换写入账本，避免进程中断留下半份 JSON。
   * @returns {void}
   */
  writeState() {
    this.state.updatedAt = new Date().toISOString()
    fs.mkdirSync(path.dirname(this.outputFile), { recursive: true })
    const temporaryFile = `${this.outputFile}.tmp`
    fs.writeFileSync(temporaryFile, `${JSON.stringify(this.state, null, 2)}\n`, 'utf8')
    fs.renameSync(temporaryFile, this.outputFile)
  }
}
