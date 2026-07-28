import { Rate } from 'k6/metrics'

import {
  ajaxRequest,
  durationEnvironment,
  integerEnvironment,
  loginOrUseToken,
  logoutIfCreated,
  normalizeBaseUrl,
  objectEnvironment,
  parseAjaxPayload,
  validateAjaxSuccess
} from './lib/workflow-http.js'

/** 每次隔离发起、审批、终态对账和清理全部成功的比例。 */
const lifecycleIterationSuccess = new Rate('workflow_lifecycle_iteration_success')

/** 每个已创建实例最终从运行时、历史和业务引用中清理成功的比例。 */
const lifecycleCleanupSuccess = new Rate('workflow_lifecycle_cleanup_success')

const BASE_URL = normalizeBaseUrl(__ENV.FLOWABLE_K6_BASE_URL || 'http://127.0.0.1:8080')
const VUS = integerEnvironment('FLOWABLE_K6_VUS', 1, 1, 20)
const ITERATIONS_PER_VU = integerEnvironment('FLOWABLE_K6_ITERATIONS_PER_VU', 1, 1, 1000)
const LOOKUP_PAGES = integerEnvironment('FLOWABLE_K6_TASK_LOOKUP_PAGES', 3, 1, 20)
const PAGE_SIZE = integerEnvironment('FLOWABLE_K6_PAGE_SIZE', 100, 1, 200)
const P95_MS = integerEnvironment('FLOWABLE_K6_P95_MS', 3000, 1, 600000)
const P99_MS = integerEnvironment('FLOWABLE_K6_P99_MS', 6000, 1, 600000)

if (P99_MS < P95_MS) {
  throw new Error('FLOWABLE_K6_P99_MS 不能小于 FLOWABLE_K6_P95_MS')
}

export const options = {
  scenarios: {
    workflow_isolated_lifecycle_gate: {
      executor: 'per-vu-iterations',
      exec: 'isolatedLifecycleGate',
      vus: VUS,
      iterations: ITERATIONS_PER_VU,
      maxDuration: durationEnvironment('FLOWABLE_K6_MAX_DURATION', '10m'),
      gracefulStop: '0s',
      tags: { gate: 'workflow-lifecycle' }
    }
  },
  thresholds: {
    checks: ['rate==1'],
    'workflow_business_success{gate:workflow-lifecycle}': ['rate==1'],
    workflow_lifecycle_iteration_success: ['rate==1'],
    workflow_lifecycle_cleanup_success: ['rate==1'],
    'http_req_failed{gate:workflow-lifecycle}': ['rate<0.01'],
    'http_req_duration{gate:workflow-lifecycle}': [
      `p(95)<${P95_MS}`,
      `p(99)<${P99_MS}`
    ]
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  userAgent: 'ruoyiflowable-k6-isolated-lifecycle-gate/1.0'
}

/**
 * 读取必填且可安全写入业务主键的标识，拒绝空值和不可追踪字符。
 * @param {string} name 环境变量名。
 * @param {number} maximum 最大字符数。
 * @returns {string} 已校验的稳定标识。
 */
function requiredIdentifier(name, maximum) {
  const value = (__ENV[name] || '').trim()
  if (value === '' || value.length > maximum || !/^[A-Za-z0-9_.:-]+$/.test(value)) {
    throw new Error(`${name} 必须是 1 到 ${maximum} 位的 ASCII 标识`)
  }
  return value
}

/**
 * 为固定角色读取 Token 或登录凭据，变量值只在 setup 内消费且从不进入日志。
 * @param {string} rolePrefix STARTER、APPROVER 或 ADMIN。
 * @param {string} label 固定脱敏角色标签。
 * @returns {{token: string, shouldLogout: boolean}} 角色认证结果。
 */
function authenticateRole(rolePrefix, label) {
  return loginOrUseToken(
    BASE_URL,
    {
      token: __ENV[`FLOWABLE_K6_${rolePrefix}_TOKEN`] || '',
      username: __ENV[`FLOWABLE_K6_${rolePrefix}_USERNAME`] || '',
      password: __ENV[`FLOWABLE_K6_${rolePrefix}_PASSWORD`] || ''
    },
    label
  )
}

/**
 * 校验隔离写门禁并一次性建立发起人、审批人和管理员会话。
 * @returns {object} 供 VU 使用的流程定义、变量和三角色认证配置。
 */
export function setup() {
  if ((__ENV.FLOWABLE_K6_ISOLATED_MUTATION_ACK || '').trim()
    !== 'isolated-cleanup-approved') {
    throw new Error('写门禁保持 blocked：FLOWABLE_K6_ISOLATED_MUTATION_ACK 未确认专用隔离资源和清理权限')
  }
  if ((__ENV.FLOWABLE_K6_ISOLATION_RESET_ACK || '').trim()
    !== 'isolated-schema-reset-approved') {
    throw new Error('写门禁保持 blocked：FLOWABLE_K6_ISOLATION_RESET_ACK 未确认隔离 schema 的审计数据清理')
  }

  const definitionId = requiredIdentifier('FLOWABLE_K6_PROCESS_DEFINITION_ID', 255)
  const processKey = requiredIdentifier('FLOWABLE_K6_PROCESS_KEY', 255)
  const taskDefinitionKey = requiredIdentifier('FLOWABLE_K6_TASK_DEFINITION_KEY', 255)
  const runId = requiredIdentifier('FLOWABLE_K6_RUN_ID', 48)
  const businessKeyPrefix = requiredIdentifier('FLOWABLE_K6_BUSINESS_KEY_PREFIX', 32)
  const startVariables = objectEnvironment('FLOWABLE_K6_START_VARIABLES_JSON')
  const completeVariables = objectEnvironment('FLOWABLE_K6_COMPLETE_VARIABLES_JSON')

  let starter
  let approver
  let admin
  try {
    // 三个会话按职责顺序创建；后续角色失败时立即注销本轮已经创建的 Token。
    starter = authenticateRole('STARTER', 'workflow_starter')
    approver = authenticateRole('APPROVER', 'workflow_approver')
    admin = authenticateRole('ADMIN', 'workflow_admin')
  } catch (error) {
    logoutIfCreated(BASE_URL, starter)
    logoutIfCreated(BASE_URL, approver)
    logoutIfCreated(BASE_URL, admin)
    throw error
  }

  return {
    baseUrl: BASE_URL,
    definitionId,
    processKey,
    taskDefinitionKey,
    runId,
    businessKeyPrefix,
    startVariables,
    completeVariables,
    starter,
    approver,
    admin
  }
}

/**
 * 要求一次 AjaxResult 调用真实成功；异常只携带操作名、HTTP 状态和业务码。
 * @param {object} response k6 HTTP 响应。
 * @param {string} operation 固定业务操作名。
 * @param {(payload: object) => boolean} shapeValidator 响应结构校验函数。
 * @returns {object} 已通过联合校验的 AjaxResult。
 */
function requireLifecycleSuccess(response, operation, shapeValidator = () => true) {
  const result = validateAjaxSuccess(
    response,
    'workflow-lifecycle',
    operation,
    shapeValidator
  )
  if (!result.ok) {
    const businessCode = result.payload && result.payload.code !== undefined
      ? Number(result.payload.code)
      : 'invalid-json'
    throw new Error(`${operation} 失败：HTTP ${response.status}，业务码 ${businessCode}`)
  }
  return result.payload
}

/**
 * 从审批人真实待办分页中定位本轮新建实例的唯一目标任务。
 * @param {object} data setup 返回的运行配置。
 * @param {string} processInstanceId 本轮真实流程实例主键。
 * @returns {object} 唯一匹配的正式待办行。
 */
function findAssignedTask(data, processInstanceId) {
  for (let pageNumber = 1; pageNumber <= LOOKUP_PAGES; pageNumber += 1) {
    const query = `processKey=${encodeURIComponent(data.processKey)}`
      + `&pageNum=${pageNumber}&pageSize=${PAGE_SIZE}`
    const response = ajaxRequest(
      'GET',
      data.baseUrl,
      `/workflow/process/todoList?${query}`,
      data.approver.token,
      'workflow-lifecycle',
      'workflow_lifecycle_todo_lookup'
    )
    const payload = requireLifecycleSuccess(
      response,
      'workflow_lifecycle_todo_lookup',
      value => Array.isArray(value.rows) && Number(value.total) >= 0
    )
    const matches = payload.rows.filter(row => (
      String(row.processInstanceId || '') === processInstanceId
      && String(row.taskDefinitionKey || '') === data.taskDefinitionKey
      && String(row.taskId || '') !== ''
    ))
    if (matches.length === 1) return matches[0]
    if (matches.length > 1) throw new Error('隔离流程产生了重复目标待办')
    if (Number(payload.total) <= pageNumber * PAGE_SIZE) break
  }
  throw new Error('隔离流程未产生审批人可见的唯一目标待办')
}

/**
 * 对本轮显式创建的实例执行终态核对、必要终止和历史删除，禁止清理任意外部实例。
 * @param {object} data setup 返回的运行配置。
 * @param {string} processInstanceId 本轮发起响应返回的实例主键。
 * @returns {boolean} 实例已不存在或已成功删除时返回 true。
 */
function cleanupCreatedInstance(data, processInstanceId) {
  const detailResponse = ajaxRequest(
    'GET',
    data.baseUrl,
    `/workflow/process/detail?procInsId=${encodeURIComponent(processInstanceId)}`,
    data.admin.token,
    'workflow-lifecycle',
    'workflow_lifecycle_cleanup_detail'
  )
  const detailPayload = parseAjaxPayload(detailResponse)
  if (detailResponse.status === 200 && detailPayload && Number(detailPayload.code) === 404) {
    return true
  }

  const processStatus = detailPayload && detailPayload.data
    ? String(detailPayload.data.processStatus || '')
    : ''
  if (processStatus === 'running' || processStatus === 'suspended') {
    const terminateResponse = ajaxRequest(
      'POST',
      data.baseUrl,
      '/workflow/instance/terminate',
      data.admin.token,
      'workflow-lifecycle',
      'workflow_lifecycle_cleanup_terminate',
      { instanceId: processInstanceId, reason: `k6 隔离门禁清理 ${data.runId}` }
    )
    const terminatePayload = parseAjaxPayload(terminateResponse)
    if (terminateResponse.status !== 200
      || !terminatePayload
      || Number(terminatePayload.code) !== 200) return false
  } else if (!['completed', 'terminated', 'canceled', 'rejected'].includes(processStatus)) {
    return false
  }

  const deleteResponse = ajaxRequest(
    'DELETE',
    data.baseUrl,
    `/workflow/process/instance/${encodeURIComponent(processInstanceId)}`,
    data.admin.token,
    'workflow-lifecycle',
    'workflow_lifecycle_cleanup_delete'
  )
  const deletePayload = parseAjaxPayload(deleteResponse)
  return deleteResponse.status === 200
    && deletePayload !== null
    && Number(deletePayload.code) === 200
}

/**
 * 每个 VU 固定执行“发起、真实待办定位、安全变量读取、审批、终态对账、清理”。
 * @param {object} data setup 返回的隔离资源和认证配置。
 * @returns {void} 结果由业务、延迟、迭代和清理阈值统一判定。
 */
export function isolatedLifecycleGate(data) {
  const businessKey = `${data.businessKeyPrefix}-${data.runId}-vu${__VU}-iter${__ITER}`
  let processInstanceId = ''
  let operationError = null

  try {
    const startResponse = ajaxRequest(
      'POST',
      data.baseUrl,
      `/workflow/process/start/${encodeURIComponent(data.definitionId)}`,
      data.starter.token,
      'workflow-lifecycle',
      'workflow_lifecycle_start',
      {
        processDefinitionId: data.definitionId,
        businessKey,
        variables: data.startVariables
      }
    )
    const startPayload = requireLifecycleSuccess(
      startResponse,
      'workflow_lifecycle_start',
      value => Boolean(value.data && (value.data.processInstanceId || value.data.procInsId))
    )
    processInstanceId = String(
      startPayload.data.processInstanceId || startPayload.data.procInsId || ''
    )
    if (String(startPayload.data.processDefinitionId || '') !== data.definitionId) {
      throw new Error('发起响应的流程定义主键与隔离定义不一致')
    }

    const task = findAssignedTask(data, processInstanceId)
    const taskId = String(task.taskId)
    const variablesResponse = ajaxRequest(
      'GET',
      data.baseUrl,
      `/workflow/task/processVariables/${encodeURIComponent(taskId)}`,
      data.approver.token,
      'workflow-lifecycle',
      'workflow_lifecycle_task_variables'
    )
    requireLifecycleSuccess(
      variablesResponse,
      'workflow_lifecycle_task_variables',
      value => value.data !== null && typeof value.data === 'object' && !Array.isArray(value.data)
    )

    const completeResponse = ajaxRequest(
      'POST',
      data.baseUrl,
      '/workflow/task/complete',
      data.approver.token,
      'workflow-lifecycle',
      'workflow_lifecycle_complete',
      {
        taskId,
        comment: `k6 隔离审批 ${data.runId}`,
        variables: data.completeVariables,
        copyUserIds: [],
        nextUserIds: [],
        expectedRevision: null
      }
    )
    requireLifecycleSuccess(completeResponse, 'workflow_lifecycle_complete')

    const terminalResponse = ajaxRequest(
      'GET',
      data.baseUrl,
      `/workflow/process/detail?procInsId=${encodeURIComponent(processInstanceId)}&taskId=${encodeURIComponent(taskId)}`,
      data.admin.token,
      'workflow-lifecycle',
      'workflow_lifecycle_terminal_detail'
    )
    requireLifecycleSuccess(
      terminalResponse,
      'workflow_lifecycle_terminal_detail',
      value => Boolean(
        value.data
        && String(value.data.processInstanceId || '') === processInstanceId
        && String(value.data.processStatus || '') === 'completed'
        && value.data.endTime
      )
    )
  } catch (error) {
    operationError = error
  }

  const cleanupOk = processInstanceId !== ''
    ? cleanupCreatedInstance(data, processInstanceId)
    : true
  lifecycleCleanupSuccess.add(cleanupOk, { gate: 'workflow-lifecycle' })
  const iterationOk = operationError === null && cleanupOk
  lifecycleIterationSuccess.add(iterationOk, { gate: 'workflow-lifecycle' })

  if (operationError !== null) throw operationError
  if (!cleanupOk) throw new Error('隔离流程实例清理失败，门禁已失败并需人工核对')
}

/**
 * 注销本轮通过用户名密码创建的三角色 Token，外部 Token 保持不变。
 * @param {object} data setup 返回的隔离资源和认证配置。
 * @returns {void} 无返回值。
 */
export function teardown(data) {
  logoutIfCreated(data.baseUrl, data.starter)
  logoutIfCreated(data.baseUrl, data.approver)
  logoutIfCreated(data.baseUrl, data.admin)
}
