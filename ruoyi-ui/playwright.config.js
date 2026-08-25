import { defineConfig } from '@playwright/test'

// Windows 本地验收可通过 PLAYWRIGHT_CHANNEL=msedge 复用系统 Edge；CI 默认使用已安装的 Chromium。
const browserChannel = String(process.env.PLAYWRIGHT_CHANNEL || '').trim()

export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: false,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: 'line',
  use: {
    baseURL: process.env.WORKFLOW_E2E_BASE_URL || 'http://127.0.0.1:1024',
    headless: true,
    trace: 'retain-on-failure',
    ...(browserChannel ? { channel: browserChannel } : {})
  }
})
