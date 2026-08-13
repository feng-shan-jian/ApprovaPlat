import { defineConfig, devices } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const projectDirectory = path.dirname(fileURLToPath(import.meta.url))
const baseURL = process.env.FLOWABLE_E2E_BASE_URL?.trim() || 'http://127.0.0.1:1024'
const runId = process.env.FLOWABLE_E2E_RUN_ID?.trim() || `manual_${new Date().toISOString().replace(/[^0-9]/g, '').slice(0, 14)}`
const runDate = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Shanghai' })
const outputRoot = path.join(projectDirectory, 'tests', 'ui', 'output', runDate, runId)
const startFrontend = process.env.FLOWABLE_E2E_START_FRONTEND?.trim().toLowerCase() === 'true'

/**
 * 校验并解析 Playwright 可自动启动的本机前端地址。
 * @param {string} urlValue `FLOWABLE_E2E_BASE_URL` 配置值。
 * @returns {{host:string,port:number,url:string}} Vite 启动参数和健康检查地址。
 */
function resolveLocalFrontend(urlValue) {
  const parsed = new URL(urlValue)
  const allowedHosts = new Set(['127.0.0.1', 'localhost'])
  const port = Number(parsed.port || 80)
  if (parsed.protocol !== 'http:' || !allowedHosts.has(parsed.hostname) || parsed.pathname !== '/' || parsed.search || parsed.hash) {
    throw new Error('自动启动前端时 FLOWABLE_E2E_BASE_URL 必须是本机 HTTP 根地址')
  }
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('FLOWABLE_E2E_BASE_URL 端口必须在 1-65535 之间')
  }
  return { host: parsed.hostname, port, url: `${parsed.origin}/login` }
}

const localFrontend = startFrontend ? resolveLocalFrontend(baseURL) : null

export default defineConfig({
  testDir: './tests/ui',
  testMatch: '**/*.spec.js',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  outputDir: path.join(outputRoot, 'test-results'),
  reporter: [
    ['list'],
    ['./tests/ui/support/ui-reporter.js', { outputFile: path.join(outputRoot, 'run-state.json') }],
    ['html', { outputFolder: path.join(outputRoot, 'html'), open: 'never' }],
    ['junit', { outputFile: path.join(outputRoot, 'junit.xml') }],
    ['json', { outputFile: path.join(outputRoot, 'results.json') }]
  ],
  use: {
    ...devices['Desktop Chrome'],
    baseURL,
    viewport: { width: 1440, height: 960 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    acceptDownloads: true,
    actionTimeout: 20_000,
    navigationTimeout: 45_000,
    screenshot: 'off',
    video: 'off',
    trace: 'off'
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: startFrontend
    ? {
        command: `npm run dev -- --host ${localFrontend.host} --port ${localFrontend.port} --strictPort`,
        url: localFrontend.url,
        timeout: 120_000,
        reuseExistingServer: !process.env.CI,
        env: { VITE_OPEN_BROWSER: 'false' }
      }
    : undefined
})
