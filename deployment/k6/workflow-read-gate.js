import http from 'k6/http'

import {
  authenticatedParams,
  durationEnvironment,
  integerEnvironment,
  loginOrUseToken,
  logoutIfCreated,
  normalizeBaseUrl,
  validateAjaxSuccess
} from './lib/workflow-http.js'

/** 不同职责角色依法可访问的核心工作台查询，来源于正式 RBAC 矩阵。 */
const READ_PROFILES = Object.freeze({
  admin: Object.freeze([
    ['workflow_startable_list', '/workflow/process/list'],
    ['workflow_owned_list', '/workflow/process/ownList'],
    ['workflow_managed_list', '/workflow/process/manageList'],
    ['workflow_todo_list', '/workflow/process/todoList'],
    ['workflow_claimable_list', '/workflow/process/claimList'],
    ['workflow_finished_list', '/workflow/process/finishedList'],
    ['workflow_copy_list', '/workflow/process/copyList']
  ]),
  starter: Object.freeze([
    ['workflow_startable_list', '/workflow/process/list'],
    ['workflow_owned_list', '/workflow/process/ownList'],
    ['workflow_copy_list', '/workflow/process/copyList']
  ]),
  approver: Object.freeze([
    ['workflow_todo_list', '/workflow/process/todoList'],
    ['workflow_claimable_list', '/workflow/process/claimList'],
    ['workflow_finished_list', '/workflow/process/finishedList'],
    ['workflow_copy_list', '/workflow/process/copyList']
  ]),
  auditor: Object.freeze([
    ['workflow_owned_list', '/workflow/process/ownList'],
    ['workflow_todo_list', '/workflow/process/todoList'],
    ['workflow_claimable_list', '/workflow/process/claimList'],
    ['workflow_finished_list', '/workflow/process/finishedList'],
    ['workflow_copy_list', '/workflow/process/copyList']
  ])
})

const BASE_URL = normalizeBaseUrl(__ENV.FLOWABLE_K6_BASE_URL || 'http://127.0.0.1:8080')
const PROFILE = (__ENV.FLOWABLE_K6_READ_PROFILE || 'admin').trim().toLowerCase()
const VUS = integerEnvironment('FLOWABLE_K6_VUS', 5, 1, 100)
const ITERATIONS = integerEnvironment('FLOWABLE_K6_ITERATIONS', 50, 1, 100000)
const PAGE_SIZE = integerEnvironment('FLOWABLE_K6_PAGE_SIZE', 20, 1, 200)
const P95_MS = integerEnvironment('FLOWABLE_K6_P95_MS', 1500, 1, 600000)
const P99_MS = integerEnvironment('FLOWABLE_K6_P99_MS', 3000, 1, 600000)

if (!Object.prototype.hasOwnProperty.call(READ_PROFILES, PROFILE)) {
  throw new Error('FLOWABLE_K6_READ_PROFILE 仅允许 admin、starter、approver 或 auditor')
}
if (P99_MS < P95_MS) {
  throw new Error('FLOWABLE_K6_P99_MS 不能小于 FLOWABLE_K6_P95_MS')
}

export const options = {
  scenarios: {
    workflow_read_gate: {
      executor: 'shared-iterations',
      exec: 'readGate',
      vus: VUS,
      iterations: ITERATIONS,
      maxDuration: durationEnvironment('FLOWABLE_K6_MAX_DURATION', '5m'),
      gracefulStop: '0s',
      tags: { gate: 'workflow-read' }
    }
  },
  thresholds: {
    checks: ['rate==1'],
    'workflow_business_success{gate:workflow-read}': ['rate==1'],
    'http_req_failed{gate:workflow-read}': ['rate<0.01'],
    'http_req_duration{gate:workflow-read}': [
      `p(95)<${P95_MS}`,
      `p(99)<${P99_MS}`
    ]
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  userAgent: 'ruoyiflowable-k6-read-gate/1.0'
}

/**
 * 只在门禁开始前解析一次 Token 或真实登录，避免把认证成本混入每次查询。
 * @returns {{baseUrl: string, authentication: {token: string, shouldLogout: boolean}, profile: string}} VU 共享的脱敏运行配置。
 */
export function setup() {
  const authentication = loginOrUseToken(
    BASE_URL,
    {
      token: __ENV.FLOWABLE_K6_TOKEN || '',
      username: __ENV.FLOWABLE_K6_USERNAME || '',
      password: __ENV.FLOWABLE_K6_PASSWORD || ''
    },
    `workflow_${PROFILE}`
  )
  return { baseUrl: BASE_URL, authentication, profile: PROFILE }
}

/**
 * 以固定工作台集合并发读取真实 HTTP API，并校验分页业务结构。
 * @param {{baseUrl: string, authentication: {token: string}, profile: string}} data setup 返回的共享配置。
 * @returns {void} 所有结果写入 k6 指标和阈值，不保留响应正文。
 */
export function readGate(data) {
  const requests = READ_PROFILES[data.profile].map(([operation, path]) => ({
    method: 'GET',
    url: `${data.baseUrl}${path}?pageNum=1&pageSize=${PAGE_SIZE}`,
    params: authenticatedParams(data.authentication.token, 'workflow-read', operation)
  }))
  const responses = http.batch(requests)
  responses.forEach((response, index) => {
    const operation = READ_PROFILES[data.profile][index][0]
    validateAjaxSuccess(response, 'workflow-read', operation, payload => (
      Array.isArray(payload.rows)
      && Number.isFinite(Number(payload.total))
      && Number(payload.total) >= 0
    ))
  })
}

/**
 * 仅注销本轮通过用户名密码创建的 Token，外部 Token 不做任何状态修改。
 * @param {{baseUrl: string, authentication: {token: string, shouldLogout: boolean}}} data setup 返回的共享配置。
 * @returns {void} 无返回值。
 */
export function teardown(data) {
  logoutIfCreated(data.baseUrl, data.authentication)
}
