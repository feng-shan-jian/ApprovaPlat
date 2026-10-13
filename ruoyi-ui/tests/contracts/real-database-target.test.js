import test from 'node:test'
import assert from 'node:assert/strict'
import { validateRealTarget } from '../e2e/fixtures/real-database-target.js'

const root = process.cwd()
const now = Date.parse('2026-10-12T12:00:00Z')
function fixture() {
  const state = { project: 'ApprovaPlat', mode: 'shared-development', status: 'active', repository: root,
    runId: '0123456789abcdef01234567', host: '127.0.0.1', port: '13306', database: 'approvaplat',
    serverUuid: '00000000-1111-2222-3333-444444444444', expiresAt: '2026-10-12T13:00:00Z',
    databaseUrl: 'jdbc:mysql://127.0.0.1:13306/approvaplat', servicesStarted: true,
    frontendPort: 14234, redisPort: 14235, processes: ['backend', 'frontend', 'redis'].map(name => ({ name })) }
  state.serviceDatabaseUrl = state.databaseUrl
  state.serviceServerUuid = state.serverUuid
  const env = { WORKFLOW_DEVELOPMENT_TEST_OPT_IN: `approvaplat:${state.runId}`,
    WORKFLOW_MYSQL_TEST_URL: state.databaseUrl, WORKFLOW_E2E_BASE_URL: 'http://127.0.0.1:14234', WORKFLOW_E2E_REDIS_PORT: '14235' }
  return { state, env }
}

test('accepts the exact registered application and database run', () => {
  const { state, env } = fixture()
  assert.equal(validateRealTarget(env, state, root, now).pathname, '/approvaplat')
})
for (const [name, change] of [
  ['missing opt-in', (s, e) => { delete e.WORKFLOW_DEVELOPMENT_TEST_OPT_IN }],
  ['boolean opt-in', (s, e) => { e.WORKFLOW_DEVELOPMENT_TEST_OPT_IN = 'true' }],
  ['another run', (s, e) => { e.WORKFLOW_DEVELOPMENT_TEST_OPT_IN = 'approvaplat:other' }],
  ['stale state', s => { s.expiresAt = '2026-10-11T13:00:00Z' }],
  ['closed state', s => { s.status = 'closed' }],
  ['another project', s => { s.project = 'Other' }],
  ['wrong schema', (s, e) => { e.WORKFLOW_MYSQL_TEST_URL += '_it' }],
  ['wrong port', (s, e) => { e.WORKFLOW_MYSQL_TEST_URL = s.databaseUrl.replace('13306', '3306') }],
  ['wrong host', (s, e) => { e.WORKFLOW_MYSQL_TEST_URL = s.databaseUrl.replace('127.0.0.1', 'localhost') }],
  ['UUID drift', s => { s.serviceServerUuid = 'aaaaaaaa-1111-2222-3333-444444444444' }],
  ['another browser target', (s, e) => { e.WORKFLOW_E2E_BASE_URL = 'http://127.0.0.1:9999' }],
  ['another backend database', s => { s.serviceDatabaseUrl += '_other' }],
  ['another Redis', (s, e) => { e.WORKFLOW_E2E_REDIS_PORT = '6379' }],
  ['unowned backend', s => { s.processes.pop() }],
  ['connection override', (s, e) => { e.WORKFLOW_MYSQL_TEST_URL += '?sessionVariables=x' }]
]) {
  test(`rejects ${name} before any connection or write`, () => {
    const { state, env } = fixture()
    change(state, env)
    assert.throws(() => validateRealTarget(env, state, root, now))
  })
}
