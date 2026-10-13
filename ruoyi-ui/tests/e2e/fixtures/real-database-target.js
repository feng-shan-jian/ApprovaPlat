import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

export const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..')

export function validateRealTarget(env, state, root, now = Date.now()) {
  const require = (condition, message) => { if (!condition) throw new Error(message) }
  require(state.project === 'ApprovaPlat' && state.mode === 'shared-development' && state.status === 'active', 'Inactive or unknown development registration')
  require(/^[a-f0-9]{24}$/.test(state.runId), 'Invalid run identifier')
  require(resolve(state.repository) === resolve(root), 'Registration belongs to another repository')
  const expires = Date.parse(state.expiresAt)
  require(expires > now && expires <= now + 8 * 3600 * 1000, 'Expired or invalid run registration')
  require(env.WORKFLOW_DEVELOPMENT_TEST_OPT_IN === `approvaplat:${state.runId}`, 'Explicit current-run development test opt-in is required')
  require(state.host === '127.0.0.1' && state.port === '13306' && state.database === 'approvaplat'
    && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(state.serverUuid), 'Unapproved development target')
  const url = env.WORKFLOW_MYSQL_TEST_URL || ''
  require(/^jdbc:mysql:\/\/127\.0\.0\.1:13306\/approvaplat(?:\?[^#]*)?$/.test(url), 'Connection target differs from registration')
  const uri = new URL(url.slice(5))
  const allowed = new Set(['useUnicode', 'characterEncoding', 'useSSL', 'allowPublicKeyRetrieval', 'serverTimezone'])
  const seen = new Set()
  for (const [key] of uri.searchParams) {
    require(allowed.has(key) && !seen.has(key), 'Unapproved JDBC connection option')
    seen.add(key)
  }
  require(state.servicesStarted === true && state.serviceDatabaseUrl === url && state.databaseUrl === url
    && state.serviceServerUuid === state.serverUuid, 'Browser backend is not bound to this database run')
  require(env.WORKFLOW_E2E_BASE_URL === `http://127.0.0.1:${state.frontendPort}`
    && env.WORKFLOW_E2E_REDIS_PORT === String(state.redisPort), 'Browser/Redis address differs from the owned service run')
  require(['backend', 'frontend', 'redis'].every(name => state.processes?.filter(entry => entry.name === name).length === 1), 'Missing owned service registration')
  return uri
}
