import assert from 'node:assert/strict'
import test from 'node:test'

/**
 * 验证 Playwright 报告配置不会序列化父进程中的账号密码等敏感环境变量。
 * @returns {Promise<void>} 校验完成时返回的 Promise；敏感变量泄漏时测试失败。
 */
test('Playwright 报告配置不得包含父进程敏感环境变量', async () => {
  const secretKey = 'RUOYI_TOKEN_SECRET'
  const previousSecret = process.env[secretKey]
  const previousStartFrontend = process.env.FLOWABLE_E2E_START_FRONTEND
  const previousBaseUrl = process.env.FLOWABLE_E2E_BASE_URL

  try {
    // 使用哨兵密钥加载真实配置，确保报告可见配置中不会出现父进程凭据。
    process.env[secretKey] = 'contract-test-secret'
    process.env.FLOWABLE_E2E_START_FRONTEND = 'true'
    process.env.FLOWABLE_E2E_BASE_URL = 'http://127.0.0.1:1024'

    const moduleUrl = new URL(`../../playwright.config.js?contract=${Date.now()}`, import.meta.url)
    const { default: config } = await import(moduleUrl.href)

    assert.equal(config.webServer.env[secretKey], undefined)
    assert.deepEqual(config.webServer.env, { VITE_OPEN_BROWSER: 'false' })
    assert.equal(config.use.screenshot, 'off')
    assert.equal(config.use.video, 'off')
  } finally {
    // 恢复父进程环境，避免该合同测试影响同一进程内的其他断言。
    if (previousSecret === undefined) delete process.env[secretKey]
    else process.env[secretKey] = previousSecret
    if (previousStartFrontend === undefined) delete process.env.FLOWABLE_E2E_START_FRONTEND
    else process.env.FLOWABLE_E2E_START_FRONTEND = previousStartFrontend
    if (previousBaseUrl === undefined) delete process.env.FLOWABLE_E2E_BASE_URL
    else process.env.FLOWABLE_E2E_BASE_URL = previousBaseUrl
  }
})
