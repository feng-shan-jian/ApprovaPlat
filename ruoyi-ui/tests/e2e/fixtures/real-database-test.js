import { test as base, expect } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { connect } from 'node:net'
import { resolve } from 'node:path'
import { validateRealTarget, repository } from './real-database-target.js'

let verifiedState

function mysql(query) {
  try {
    return execFileSync(process.env.WORKFLOW_MYSQL_CLIENT || (process.platform === 'win32'
      ? 'C:/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe' : 'mysql'), [
      '--protocol=TCP', '--connect-timeout=5', '-h', '127.0.0.1', '-P', '13306',
      '-u', process.env.WORKFLOW_MYSQL_TEST_USERNAME, '--database=approvaplat',
      '--batch', '--raw', '--skip-column-names', '--default-character-set=utf8mb4'
    ], { input: query, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'],
      env: { ...process.env, MYSQL_PWD: process.env.WORKFLOW_MYSQL_TEST_PASSWORD } }).trim()
  } catch { throw new Error('Registered MySQL operation failed; SQL and credentials are withheld') }
}

export function realDatabaseSql(query) {
  if (!verifiedState) throw new Error('BrowserReal worker has not verified the owned services')
  const state = verifiedState
  validateRealTarget(process.env, state, repository)
  if (mysql('SELECT DATABASE(), @@server_uuid') !== `approvaplat\t${state.serverUuid}`) {
    throw new Error('Connected database/server UUID does not match registration')
  }
  return mysql(query)
}

function clearCaptchaCache() {
  return new Promise((resolve, reject) => {
    const socket = connect({ host: '127.0.0.1', port: Number(process.env.WORKFLOW_E2E_REDIS_PORT) })
    socket.setTimeout(3000, () => socket.destroy(new Error('Owned Redis timed out')))
    socket.on('error', reject)
    socket.on('connect', () => socket.write('*2\r\n$3\r\nDEL\r\n$37\r\nsys_config:sys.account.captchaEnabled\r\n'))
    socket.once('data', data => { socket.end(); data[0] === 45 ? reject(new Error('Captcha cache reset failed')) : resolve() })
  })
}

export const test = base.extend({
  // Automatic worker fixtures run before beforeAll hooks, including their fixture writes.
  // Playwright reads fixture dependencies from this required destructuring pattern.
  // eslint-disable-next-line no-empty-pattern
  registeredDevelopmentDatabase: [async ({}, use, workerInfo) => {
    if (workerInfo.config.workers !== 1) throw new Error('Shared development BrowserReal requires --workers=1')
    if (!process.env.WORKFLOW_VERIFICATION_STATE_PATH) throw new Error('Register the development target first')
    if (!process.env.WORKFLOW_E2E_USERNAME || !process.env.WORKFLOW_E2E_PASSWORD
      || !process.env.WORKFLOW_MYSQL_TEST_USERNAME || !process.env.WORKFLOW_MYSQL_TEST_PASSWORD) {
      throw new Error('Supply approved application and database credentials in the current process')
    }
    let state
    try {
      const evidence = execFileSync('pwsh', ['-NoProfile', '-File', resolve(repository, 'bin/workflow-test-environment.ps1'),
        '-Action', 'CheckBrowser', '-StateFile', process.env.WORKFLOW_VERIFICATION_STATE_PATH], { stdio: 'pipe', encoding: 'utf8' })
      state = JSON.parse(evidence.trim().replace(/^\uFEFF/, ''))
      validateRealTarget(process.env, state, repository)
    } catch { throw new Error('Owned frontend/backend identity check failed before BrowserReal writes') }
    verifiedState = state
    const previous = realDatabaseSql("SELECT config_value FROM sys_config WHERE config_key='sys.account.captchaEnabled'")
    if (!['true', 'false'].includes(previous)) throw new Error('Unexpected captcha setting; refusing to change it')
    try {
      realDatabaseSql("UPDATE sys_config SET config_value='false' WHERE config_key='sys.account.captchaEnabled'")
      await clearCaptchaCache()
      await use(state)
    } finally {
      try {
        realDatabaseSql(`UPDATE sys_config SET config_value='${previous}' WHERE config_key='sys.account.captchaEnabled'`)
        await clearCaptchaCache()
      } finally { verifiedState = undefined }
    }
  }, { scope: 'worker', auto: true }]
})
export { expect }
