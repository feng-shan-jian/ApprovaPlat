import { randomUUID } from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const defaultCatalogPath = path.resolve(
  scriptDirectory,
  '../samples/workflow/workflow-samples.json'
)
const sampleAssetRemark = 'ApprovaPlat 可验收审批样例'
const formPageSize = 50
const maxFormSearchRows = 1000
const directoryPageSize = 500

/** 动态多实例办理人固定表达式，与后端白名单契约保持一致。 */
const controlledMultiInstanceAssignee = '${assignee}'
/** 动态多实例集合固定表达式，只允许服务端 handler 读取正式人员集合。 */
const controlledMultiInstanceCollection = '${multiInstanceHandler.getUserIds(execution)}'
/** 动态多实例元素变量固定名称。 */
const controlledMultiInstanceElementVariable = 'assignee'
/** 会签完成条件，要求全部实例完成。 */
const controlledMultiInstanceAllCondition = '${nrOfCompletedInstances == nrOfInstances}'
/** 或签完成条件，任一实例完成后结束多实例活动。 */
const controlledMultiInstanceAnyCondition = '${nrOfCompletedInstances > 0}'
/** 条件分支作者 BPMN 的受控规则属性，与后端部署编译契约保持一致。 */
const conditionRuleProperty = 'approva.conditionRule.config'

/**
 * 读取命令行参数并限制只接受脚本公开的配置项。
 * @param {string[]} argv Node.js 传入的命令行参数数组。
 * @returns {{baseUrl: string, username: string, catalogPath: string}} 规范化脚本配置。
 */
function parseArguments(argv) {
  const options = {
    baseUrl: 'http://127.0.0.1:8080',
    username: 'admin',
    catalogPath: defaultCatalogPath
  }
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    const value = argv[index + 1]
    if (!['--base-url', '--username', '--catalog'].includes(argument) || !value) {
      throw new Error(`不支持或缺少值的参数: ${argument}`)
    }
    if (argument === '--base-url') options.baseUrl = value
    if (argument === '--username') options.username = value
    if (argument === '--catalog') options.catalogPath = path.resolve(value)
    index += 1
  }
  options.baseUrl = options.baseUrl.replace(/\/+$/, '')
  if (!/^https?:\/\/[^\s]+$/u.test(options.baseUrl)) {
    throw new Error('服务地址必须是有效的 HTTP 或 HTTPS URL')
  }
  if (!options.username.trim()) throw new Error('管理员账号不能为空')
  return options
}

/**
 * 创建带登录状态的审批平台 API 客户端。
 * @param {string} baseUrl 服务根地址。
 * @returns {{login: Function, request: Function}} 登录和业务请求方法。
 */
function createApiClient(baseUrl) {
  let token = ''

  /**
   * 调用真实 HTTP API 并统一校验 HTTP 状态及若依业务响应码。
   * @param {string} method HTTP 方法。
   * @param {string} apiPath 以斜杠开头的 API 路径。
   * @param {object|undefined} body 可选 JSON 请求对象。
   * @returns {Promise<object>} 服务端解析后的响应对象。
   */
  async function request(method, apiPath, body) {
    const headers = { Accept: 'application/json' }
    if (token) headers.Authorization = `Bearer ${token}`
    if (body !== undefined) headers['Content-Type'] = 'application/json; charset=utf-8'
    const response = await fetch(`${baseUrl}${apiPath}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    })
    const payload = await response.json().catch(() => null)
    if (!response.ok || !payload || Number(payload.code) !== 200) {
      const message = payload?.msg || `HTTP ${response.status}`
      throw new Error(`API ${method} ${apiPath} 失败: ${message}`)
    }
    return payload
  }

  /**
   * 使用环境变量提供的密码登录，并仅在进程内保存 Bearer token。
   * @param {string} username 管理员账号。
   * @param {string} password 管理员密码。
   * @param {string} captchaUuid 验证码会话主键；关闭验证码时传空串。
   * @param {string} captchaCode 人工识别的验证码答案；关闭验证码时传空串。
   * @returns {Promise<void>} 登录成功后更新客户端认证状态。
   */
  async function login(username, password, captchaUuid = '', captchaCode = '') {
    const response = await request('POST', '/login', {
      username,
      password,
      code: captchaCode,
      uuid: captchaUuid
    })
    if (typeof response.token !== 'string' || !response.token) {
      throw new Error('登录响应缺少访问令牌')
    }
    token = response.token
  }

  return { login, request }
}

/**
 * 读取正式用户、角色和部门目录，并阻断超出脚本安全分页上限的未完整结果。
 * @param {{request: Function}} api 已登录 API 客户端。
 * @returns {Promise<{users: object[], roles: object[], depts: object[]}>} 完整正式身份主数据。
 */
async function loadIdentityDirectory(api) {
  const [userResponse, roleResponse, deptResponse] = await Promise.all([
    api.request('GET', `/system/user/list?pageNum=1&pageSize=${directoryPageSize}`),
    api.request('GET', `/system/role/list?pageNum=1&pageSize=${directoryPageSize}`),
    api.request('GET', '/system/dept/list')
  ])
  const users = Array.isArray(userResponse.rows) ? userResponse.rows : []
  const roles = Array.isArray(roleResponse.rows) ? roleResponse.rows : []
  if (Number(userResponse.total) !== users.length || Number(roleResponse.total) !== roles.length) {
    throw new Error(`身份目录记录超过单次安全上限 ${directoryPageSize}，拒绝使用不完整目录`)
  }
  return {
    users,
    roles,
    depts: Array.isArray(deptResponse.data) ? deptResponse.data : []
  }
}

/**
 * 校验测试身份目录的基本结构和自然键唯一性。
 * @param {object} catalog 完整审批样例目录。
 * @returns {object} 已通过结构校验的 identities 配置。
 */
function requireIdentityCatalog(catalog) {
  const identities = catalog?.identities
  if (!identities || typeof identities.rootDepartmentName !== 'string' ||
      !Array.isArray(identities.departments) || !Array.isArray(identities.roles) ||
      !Array.isArray(identities.users) || identities.departments.length === 0 ||
      identities.roles.length === 0 || identities.users.length === 0) {
    throw new Error('审批样例目录缺少完整测试身份定义')
  }
  const uniqueDefinitions = [
    ['部门名称', identities.departments.map(item => item.deptName)],
    ['角色标识', identities.roles.map(item => item.roleKey)],
    ['测试账号', identities.users.map(item => item.userName)]
  ]
  for (const [label, values] of uniqueDefinitions) {
    if (values.some(value => typeof value !== 'string' || !value.trim()) ||
        new Set(values).size !== values.length) {
      throw new Error(`${label}为空或重复`)
    }
  }
  return identities
}

/**
 * 创建或严格复用测试部门，所有部门都挂载到目录声明的正式根部门下。
 * @param {{request: Function}} api 已登录 API 客户端。
 * @param {object} identities 测试身份目录定义。
 * @returns {Promise<{created: number, reused: number}>} 部门创建和复用数量。
 */
async function provisionDepartments(api, identities) {
  let directory = await loadIdentityDirectory(api)
  const roots = directory.depts.filter(item =>
    item.deptName === identities.rootDepartmentName && Number(item.parentId) === 0
  )
  if (roots.length !== 1 || String(roots[0].status) !== '0') {
    throw new Error(`测试身份根部门缺失、重复或停用: ${identities.rootDepartmentName}`)
  }
  const root = roots[0]
  let created = 0
  let reused = 0
  for (const definition of identities.departments) {
    const matches = directory.depts.filter(item => item.deptName === definition.deptName)
    if (matches.length > 1) throw new Error(`测试部门名称重复: ${definition.deptName}`)
    const existing = matches[0]
    if (existing) {
      const matchesDefinition = Number(existing.parentId) === Number(root.deptId) &&
        Number(existing.orderNum) === Number(definition.orderNum) &&
        String(existing.status) === '0'
      if (!matchesDefinition) throw new Error(`测试部门内容漂移: ${definition.deptName}`)
      reused += 1
      continue
    }
    await api.request('POST', '/system/dept', {
      parentId: Number(root.deptId),
      deptName: definition.deptName,
      orderNum: Number(definition.orderNum),
      leader: definition.leader || '',
      phone: '',
      email: '',
      status: '0',
      remark: sampleAssetRemark
    })
    created += 1
    directory = await loadIdentityDirectory(api)
    const verified = directory.depts.filter(item => item.deptName === definition.deptName)
    if (verified.length !== 1 || Number(verified[0].parentId) !== Number(root.deptId)) {
      throw new Error(`测试部门创建后校验失败: ${definition.deptName}`)
    }
  }
  return { created, reused }
}

/**
 * 创建或严格复用测试业务角色，角色菜单稍后通过增量授权接口单独处理。
 * @param {{request: Function}} api 已登录 API 客户端。
 * @param {object} identities 测试身份目录定义。
 * @returns {Promise<{created: number, reused: number}>} 角色创建和复用数量。
 */
async function provisionRoles(api, identities) {
  let directory = await loadIdentityDirectory(api)
  let created = 0
  let reused = 0
  for (const definition of identities.roles) {
    const matches = directory.roles.filter(item => item.roleKey === definition.roleKey)
    if (matches.length > 1) throw new Error(`测试角色标识重复: ${definition.roleKey}`)
    const existing = matches[0]
    if (existing) {
      const matchesDefinition = existing.roleName === definition.roleName &&
        Number(existing.roleSort) === Number(definition.roleSort) &&
        String(existing.dataScope) === String(definition.dataScope) &&
        String(existing.status) === '0'
      if (!matchesDefinition) throw new Error(`测试角色内容漂移: ${definition.roleKey}`)
      reused += 1
      continue
    }
    await api.request('POST', '/system/role', {
      roleName: definition.roleName,
      roleKey: definition.roleKey,
      roleSort: Number(definition.roleSort),
      dataScope: String(definition.dataScope),
      menuCheckStrictly: true,
      deptCheckStrictly: true,
      status: '0',
      menuIds: [],
      remark: sampleAssetRemark
    })
    created += 1
    directory = await loadIdentityDirectory(api)
    const verified = directory.roles.filter(item => item.roleKey === definition.roleKey)
    if (verified.length !== 1 || String(verified[0].status) !== '0') {
      throw new Error(`测试角色创建后校验失败: ${definition.roleKey}`)
    }
  }
  return { created, reused }
}

/**
 * 比较两个角色主键集合是否完全一致，避免测试账号残留额外高权限角色。
 * @param {unknown[]} actual 当前正式用户角色主键集合。
 * @param {number[]} expected 样例目录要求的角色主键集合。
 * @returns {boolean} 去重并排序后完全一致时返回 true。
 */
function sameRoleIds(actual, expected) {
  const normalize = values => [...new Set((Array.isArray(values) ? values : [])
    .map(value => Number(value))
    .filter(value => Number.isSafeInteger(value) && value > 0))]
    .sort((left, right) => left - right)
  return JSON.stringify(normalize(actual)) === JSON.stringify(normalize(expected))
}

/**
 * 创建或校准一个受管测试用户的正式资料和角色绑定。
 * @param {{request: Function}} api 已登录管理员 API 客户端。
 * @param {object} definition 测试用户定义。
 * @param {{users: object[], roles: object[], depts: object[]}} directory 当前身份目录。
 * @param {string} identityPassword 仅存在于当前进程的测试用户密码。
 * @returns {Promise<{userId: number, status: string}>} 用户主键和创建或校准状态。
 */
async function provisionUser(api, definition, directory, identityPassword) {
  const deptMatches = directory.depts.filter(item =>
    item.deptName === definition.deptName && String(item.status) === '0'
  )
  if (deptMatches.length !== 1) throw new Error(`测试用户部门缺失或重复: ${definition.deptName}`)
  const roleIds = definition.roleKeys.map(roleKey => {
    const matches = directory.roles.filter(item =>
      item.roleKey === roleKey && String(item.status) === '0'
    )
    if (matches.length !== 1) throw new Error(`测试用户角色缺失或重复: ${roleKey}`)
    return Number(matches[0].roleId)
  })
  const matches = directory.users.filter(item => item.userName === definition.userName)
  if (matches.length > 1) throw new Error(`测试账号重复: ${definition.userName}`)
  let userId = matches[0] ? Number(matches[0].userId) : null
  const basePayload = {
    deptId: Number(deptMatches[0].deptId),
    userName: definition.userName,
    nickName: definition.nickName,
    email: definition.email || '',
    phonenumber: definition.phonenumber || '',
    sex: definition.sex || '2',
    status: '0',
    roleIds,
    postIds: [],
    remark: sampleAssetRemark
  }
  if (!userId) {
    await api.request('POST', '/system/user', { ...basePayload, password: identityPassword })
    const refreshed = await loadIdentityDirectory(api)
    const created = refreshed.users.filter(item => item.userName === definition.userName)
    if (created.length !== 1) throw new Error(`测试账号创建后校验失败: ${definition.userName}`)
    userId = Number(created[0].userId)
  } else if (!definition.adoptExisting && matches[0].remark !== sampleAssetRemark) {
    throw new Error(`测试账号标识已被非样例用户占用: ${definition.userName}`)
  }

  const detailResponse = await api.request('GET', `/system/user/${encodeURIComponent(userId)}`)
  const current = detailResponse.data
  const needsUpdate = Number(current?.deptId) !== basePayload.deptId ||
    current?.userName !== basePayload.userName || current?.nickName !== basePayload.nickName ||
    String(current?.status) !== '0' || String(current?.sex || '2') !== basePayload.sex ||
    String(current?.email || '') !== basePayload.email ||
    String(current?.phonenumber || '') !== basePayload.phonenumber ||
    current?.remark !== sampleAssetRemark || !sameRoleIds(detailResponse.roleIds, roleIds)
  if (needsUpdate) {
    await api.request('PUT', '/system/user', { ...basePayload, userId })
  }
  const verified = await api.request('GET', `/system/user/${encodeURIComponent(userId)}`)
  if (Number(verified.data?.deptId) !== basePayload.deptId ||
      verified.data?.nickName !== basePayload.nickName || String(verified.data?.status) !== '0' ||
      !sameRoleIds(verified.roleIds, roleIds)) {
    throw new Error(`测试账号资料或角色绑定校验失败: ${definition.userName}`)
  }
  return { userId, status: matches[0] ? (needsUpdate ? 'aligned' : 'reused') : 'created' }
}

/**
 * 验证受管测试账号密码；若密码漂移则通过正式重置接口恢复后再次登录校验。
 * @param {{request: Function}} adminApi 已登录管理员 API 客户端。
 * @param {string} baseUrl 服务根地址。
 * @param {string} username 测试账号。
 * @param {number} userId 正式用户主键。
 * @param {string} identityPassword 仅存在于当前进程的测试用户密码。
 * @returns {Promise<void>} 账号可登录时完成，否则抛出异常。
 */
async function ensureUserPassword(adminApi, baseUrl, username, userId, identityPassword) {
  const client = createApiClient(baseUrl)
  try {
    await client.login(username, identityPassword)
    return
  } catch {
    await adminApi.request('PUT', '/system/user/resetPwd', {
      userId,
      password: identityPassword
    })
  }
  const verifiedClient = createApiClient(baseUrl)
  await verifiedClient.login(username, identityPassword)
}

/**
 * 通过正式系统 API 完成测试部门、角色、用户和登录凭据置备。
 * @param {{request: Function}} api 已登录管理员 API 客户端。
 * @param {string} baseUrl 服务根地址。
 * @param {object} catalog 完整审批样例目录。
 * @param {string} identityPassword 仅存在于当前进程的测试用户密码。
 * @returns {Promise<{directory: object, departments: object, roles: object, users: object}>} 置备结果和最新身份目录。
 */
async function provisionIdentities(api, baseUrl, catalog, identityPassword) {
  const identities = requireIdentityCatalog(catalog)
  const departments = await provisionDepartments(api, identities)
  const roles = await provisionRoles(api, identities)
  let directory = await loadIdentityDirectory(api)
  const userResults = []
  for (const definition of identities.users) {
    const result = await provisionUser(api, definition, directory, identityPassword)
    await ensureUserPassword(api, baseUrl, definition.userName, result.userId, identityPassword)
    userResults.push({ userName: definition.userName, status: result.status })
    directory = await loadIdentityDirectory(api)
  }
  return {
    directory,
    departments,
    roles,
    users: {
      created: userResults.filter(item => item.status === 'created').length,
      aligned: userResults.filter(item => item.status === 'aligned').length,
      reused: userResults.filter(item => item.status === 'reused').length
    }
  }
}

/**
 * 按分类编码查询或创建正式流程分类。
 * @param {{request: Function}} api 已登录 API 客户端。
 * @param {{code: string, name: string}} category 样例分类定义。
 * @returns {Promise<string>} 正式分类编码。
 */
async function getOrCreateCategory(api, category) {
  const response = await api.request('GET', '/workflow/category/listAll')
  const existing = (response.data || []).find(item => item.code === category.code)
  if (existing) {
    if (existing.categoryName !== category.name) {
      throw new Error(`流程分类内容漂移: ${category.code}`)
    }
    return category.code
  }

  await api.request('POST', '/workflow/category', {
    categoryName: category.name,
    code: category.code,
    remark: sampleAssetRemark
  })
  return category.code
}

/**
 * 对 JSON 对象键递归排序，数组顺序保持业务语义不变。
 * @param {unknown} value 待规范化的 JSON 值。
 * @returns {unknown} 可稳定序列化比较的 JSON 值。
 */
function canonicalizeJson(value) {
  if (Array.isArray(value)) return value.map(item => canonicalizeJson(item))
  if (!value || typeof value !== 'object') return value
  return Object.fromEntries(
    Object.keys(value)
      .sort((left, right) => left.localeCompare(right))
      .map(key => [key, canonicalizeJson(value[key])])
  )
}

/**
 * 解析并稳定序列化表单 JSON，用于识别同名资产的真实内容漂移。
 * @param {string} content 待解析的表单 JSON 正文。
 * @param {string} formName 错误信息使用的表单名称。
 * @returns {string} 键顺序稳定的 JSON 字符串。
 */
function normalizeFormContent(content, formName) {
  try {
    return JSON.stringify(canonicalizeJson(JSON.parse(content)))
  } catch {
    throw new Error(`正式表单 JSON 无法解析: ${formName}`)
  }
}

/**
 * 分页读取全部名称过滤结果，再按精确名称筛选正式表单。
 * @param {{request: Function}} api 已登录 API 客户端。
 * @param {string} formName 待查询的精确表单名称。
 * @returns {Promise<object[]>} 所有精确同名的有效正式表单摘要。
 */
async function listExactForms(api, formName) {
  const matches = []
  let pageNum = 1
  let total = 0
  do {
    const response = await api.request(
      'GET',
      `/workflow/form/list?pageNum=${pageNum}&pageSize=${formPageSize}&formName=${encodeURIComponent(formName)}`
    )
    total = Number(response.total)
    if (!Number.isSafeInteger(total) || total < 0 || total > maxFormSearchRows) {
      throw new Error(`表单名称查询结果超出安全范围: ${formName}`)
    }
    const rows = Array.isArray(response.rows) ? response.rows : []
    matches.push(...rows.filter(item => item.formName === formName))
    pageNum += 1
  } while ((pageNum - 1) * formPageSize < total)
  return matches
}

/**
 * 读取正式表单详情并核验目录定义，禁止静默复用同名异内容资产。
 * @param {{request: Function}} api 已登录 API 客户端。
 * @param {number} formId 待核验的正式表单主键。
 * @param {object} sample 包含 formName 和 form 的样例定义。
 * @returns {Promise<void>} 内容一致时完成；漂移时抛出异常。
 */
async function assertFormMatches(api, formId, sample) {
  const response = await api.request('GET', `/workflow/form/${encodeURIComponent(formId)}`)
  const form = response.data
  const expectedContent = JSON.stringify(canonicalizeJson(sample.form))
  const actualContent = normalizeFormContent(form?.content, sample.formName)
  if (form?.formName !== sample.formName || actualContent !== expectedContent) {
    throw new Error(`流程表单内容漂移: ${sample.formName}`)
  }
}

/**
 * 按表单名称查询或创建经过服务端校验的正式流程表单，并拒绝重复或内容漂移。
 * @param {{request: Function}} api 已登录 API 客户端。
 * @param {object} sample 包含 formName 和 form 的样例定义。
 * @returns {Promise<number>} 正式表单主键。
 */
async function getOrCreateForm(api, sample) {
  const existingForms = await listExactForms(api, sample.formName)
  if (existingForms.length > 1) {
    throw new Error(`存在多个同名正式表单，拒绝继续部署: ${sample.formName}`)
  }
  if (existingForms.length === 1) {
    const formId = Number(existingForms[0].formId)
    await assertFormMatches(api, formId, sample)
    return formId
  }

  const created = await api.request('POST', '/workflow/form', {
    formName: sample.formName,
    content: JSON.stringify(sample.form),
    remark: sampleAssetRemark
  })
  const formId = Number(created.data?.formId)
  if (!Number.isSafeInteger(formId) || formId <= 0) {
    throw new Error(`创建表单响应缺少有效主键: ${sample.formName}`)
  }

  // 创建后重新读取名称集合，发现并发重复时停止，避免错误表单继续绑定到模型。
  const verifiedForms = await listExactForms(api, sample.formName)
  if (verifiedForms.length !== 1 || Number(verifiedForms[0].formId) !== formId) {
    throw new Error(`表单创建发生并发冲突，拒绝继续部署: ${sample.formName}`)
  }
  await assertFormMatches(api, formId, sample)
  return formId
}

/**
 * 把用户、角色或部门自然键解析为 Flowable 静态身份编码。
 * @param {object} task 包含 identityType 和 identityKey 的节点定义。
 * @param {{users: object[], roles: object[], depts: object[]}} directory 正式身份主数据。
 * @returns {{attribute: string, value: string}} BPMN 身份属性及编码。
 */
function resolveTaskIdentity(task, directory) {
  if (task.identityType === 'user') {
    const user = directory.users.find(item =>
      item.userName === task.identityKey && String(item.status) === '0'
    )
    if (!user) throw new Error(`未找到有效审批用户: ${task.identityKey}`)
    return { attribute: 'flowable:assignee', value: String(user.userId) }
  }
  if (task.identityType === 'role') {
    const role = directory.roles.find(item =>
      item.roleKey === task.identityKey && String(item.status) === '0'
    )
    if (!role) throw new Error(`未找到有效审批角色: ${task.identityKey}`)
    return { attribute: 'flowable:candidateGroups', value: `ROLE${role.roleId}` }
  }
  if (task.identityType === 'dept') {
    const dept = directory.depts.find(item =>
      item.deptName === task.identityKey && String(item.status) === '0'
    )
    if (!dept) throw new Error(`未找到有效审批部门: ${task.identityKey}`)
    return { attribute: 'flowable:candidateGroups', value: `DEPT${dept.deptId}` }
  }
  throw new Error(`不支持的审批身份类型: ${task.identityType}`)
}

/**
 * 转义 BPMN XML 属性值，避免目录名称进入 XML 结构。
 * @param {unknown} value 原始属性值。
 * @returns {string} XML 属性安全文本。
 */
function escapeXml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;')
}

/**
 * 转义正则表达式元字符，用于只读核验后端规范化后的部署 XML。
 * @param {unknown} value 待进入正则表达式的原始值。
 * @returns {string} 可安全拼入 RegExp 的文本。
 */
function escapeRegex(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')
}

/**
 * 从 BPMN XML 中读取指定类型和 ID 元素的开始标签。
 * @param {string} xml 已部署 BPMN XML。
 * @param {string} tag BPMN 元素本地名称。
 * @param {string} elementId BPMN 元素标识。
 * @returns {string} 匹配的开始标签。
 */
function requireOpeningTag(xml, tag, elementId) {
  const pattern = new RegExp(
    `<(?:[A-Za-z0-9_-]+:)?${escapeRegex(tag)}\\b[^>]*\\bid="${escapeRegex(escapeXml(elementId))}"[^>]*>`,
    'u'
  )
  const match = xml.match(pattern)
  if (!match) throw new Error(`已部署 BPMN 缺少元素: ${tag}#${elementId}`)
  return match[0]
}

/**
 * 核验 XML 开始标签包含预期属性和值。
 * @param {string} openingTag XML 开始标签。
 * @param {string} attribute 属性名，可包含命名空间前缀。
 * @param {unknown} expectedValue 预期属性值。
 * @param {string} context 错误信息使用的业务上下文。
 * @returns {void} 属性一致时完成，否则抛出异常。
 */
function assertTagAttribute(openingTag, attribute, expectedValue, context) {
  const pattern = new RegExp(
    `(?:^|\\s)${escapeRegex(attribute)}="${escapeRegex(escapeXml(expectedValue))}"(?:\\s|/?>)`,
    'u'
  )
  if (!pattern.test(openingTag)) {
    throw new Error(`已部署 BPMN 属性漂移: ${context}.${attribute}`)
  }
}

/**
 * 判断规范 XML 文本或 CDATA 是否包含同一个表达式语义。
 * @param {string} xml XML 片段。
 * @param {string} expectedText 预期表达式原文。
 * @returns {boolean} 原文或 XML 转义形式任一存在时返回 true。
 */
function includesXmlText(xml, expectedText) {
  return xml.includes(expectedText) || xml.includes(escapeXml(expectedText))
}

/**
 * 核验后端规范化或编译后的部署 BPMN 仍满足目录声明的业务拓扑和执行契约。
 * @param {string} actualBpmn 后端返回的真实部署 BPMN XML。
 * @param {object} sample 当前审批样例定义。
 * @param {number} formId 开始节点绑定的正式表单主键。
 * @param {object[]} resolvedTasks 已解析身份或多实例模式的任务数组。
 * @returns {void} 关键元素、身份、流向和受控协议一致时完成。
 */
function assertDeployedBpmnMatches(actualBpmn, sample, formId, resolvedTasks) {
  if (typeof actualBpmn !== 'string' || !actualBpmn.trim()) {
    throw new Error(`已部署 BPMN 正文为空: ${sample.modelKey}`)
  }
  const graph = createSampleGraph(sample, formId, resolvedTasks)
  const processTag = requireOpeningTag(actualBpmn, 'process', sample.modelKey)
  assertTagAttribute(processTag, 'name', sample.modelName, `${sample.modelKey}.process`)
  assertTagAttribute(processTag, 'isExecutable', 'true', `${sample.modelKey}.process`)
  for (const node of graph.nodes) {
    if (node.kind === 'start') {
      const tag = requireOpeningTag(actualBpmn, 'startEvent', node.id)
      assertTagAttribute(tag, 'flowable:formKey', `key_${formId}`, `${sample.modelKey}.${node.id}`)
      continue
    }
    if (node.kind === 'end') {
      requireOpeningTag(actualBpmn, 'endEvent', node.id)
      continue
    }
    if (node.kind === 'user') {
      const tag = requireOpeningTag(actualBpmn, 'userTask', node.id)
      assertTagAttribute(tag, 'name', node.name, `${sample.modelKey}.${node.id}`)
      if (node.multiInstanceMode) {
        assertTagAttribute(tag, 'flowable:assignee', controlledMultiInstanceAssignee, `${sample.modelKey}.${node.id}`)
        const taskPattern = new RegExp(
          `<userTask\\b[^>]*\\bid="${escapeRegex(node.id)}"[^>]*>[\\s\\S]*?</userTask>`,
          'u'
        )
        const taskXml = actualBpmn.match(taskPattern)?.[0] || ''
        if (!taskXml.includes(`flowable:collection="${escapeXml(controlledMultiInstanceCollection)}"`) ||
            !taskXml.includes(`flowable:elementVariable="${controlledMultiInstanceElementVariable}"`)) {
          throw new Error(`已部署动态多实例集合协议漂移: ${sample.modelKey}.${node.id}`)
        }
        const expectedCondition = node.multiInstanceMode === 'ALL'
          ? controlledMultiInstanceAllCondition
          : controlledMultiInstanceAnyCondition
        if (!includesXmlText(taskXml, expectedCondition)) {
          throw new Error(`已部署动态多实例完成条件漂移: ${sample.modelKey}.${node.id}`)
        }
      } else {
        assertTagAttribute(tag, node.identity.attribute, node.identity.value, `${sample.modelKey}.${node.id}`)
      }
      continue
    }
    if (node.kind === 'service') {
      const tag = requireOpeningTag(actualBpmn, 'serviceTask', node.id)
      assertTagAttribute(tag, 'flowable:delegateExpression', '${workflowExtensionDelegate}', `${sample.modelKey}.${node.id}`)
      continue
    }
    const gatewayTag = node.kind === 'exclusiveGateway' ? 'exclusiveGateway' : 'parallelGateway'
    const tag = requireOpeningTag(actualBpmn, gatewayTag, node.id)
    if (node.defaultFlow) {
      assertTagAttribute(tag, 'default', node.defaultFlow, `${sample.modelKey}.${node.id}`)
    }
  }
  for (const flow of graph.flows) {
    const tag = requireOpeningTag(actualBpmn, 'sequenceFlow', flow.id)
    assertTagAttribute(tag, 'sourceRef', flow.source, `${sample.modelKey}.${flow.id}`)
    assertTagAttribute(tag, 'targetRef', flow.target, `${sample.modelKey}.${flow.id}`)
    if (flow.name) {
      assertTagAttribute(tag, 'name', flow.name, `${sample.modelKey}.${flow.id}`)
    }
    if (flow.conditionRule) {
      const flowPattern = new RegExp(
        `<(?:[A-Za-z0-9_-]+:)?sequenceFlow\\b[^>]*\\bid="${escapeRegex(escapeXml(flow.id))}"[^>]*(?:/>|>[\\s\\S]*?</(?:[A-Za-z0-9_-]+:)?sequenceFlow>)`,
        'u'
      )
      const flowXml = actualBpmn.match(flowPattern)?.[0] || ''
      const hasRouterExpression = flowXml.includes(
        'workflowConditionRouter.matches(execution,'
      )
      if (Boolean(flow.conditionRule.default) === hasRouterExpression) {
        throw new Error(`已部署受控条件表达式漂移: ${sample.modelKey}.${flow.id}`)
      }
    }
  }
  if (graph.flows.some(flow => flow.conditionRule) &&
      actualBpmn.includes(conditionRuleProperty)) {
    throw new Error(`已部署 BPMN 仍残留条件作者属性: ${sample.modelKey}`)
  }
}

/**
 * 递归收集目标菜单及全部父菜单，确保页面路由和按钮权限同时可达。
 * @param {object} menu 需要授权的正式菜单记录。
 * @param {Map<string, object>} menusById 菜单主键到记录的映射。
 * @param {Set<number>} targetIds 待合并的菜单主键集合。
 * @returns {void} 目标集合原位增加当前菜单及其父菜单。
 */
function collectMenuAncestors(menu, menusById, targetIds) {
  let current = menu
  while (current && Number(current.menuId) > 0) {
    if (String(current.status) !== '0') {
      throw new Error(`办理权限依赖的菜单已停用: ${current.menuName || current.menuId}`)
    }
    targetIds.add(Number(current.menuId))
    const parentId = Number(current.parentId)
    if (parentId <= 0) break
    current = menusById.get(String(parentId))
  }
}

/**
 * 通过角色管理 API 合并参与者办理权限，避免真实角色被身份目录资格查询过滤。
 * @param {{request: Function}} api 已登录 API 客户端。
 * @param {object} catalog 包含角色键和办理权限的样例目录。
 * @param {object[]} roles 系统有效角色列表。
 * @returns {Promise<{aligned: number, added: number}>} 处理角色数及真实新增权限关联数。
 */
async function alignParticipantRolePermissions(api, catalog, roles) {
  const roleKeys = Array.isArray(catalog.participantRoleKeys)
    ? catalog.participantRoleKeys
    : []
  const permissions = new Set(
    Array.isArray(catalog.participantPermissions) ? catalog.participantPermissions : []
  )
  if (roleKeys.length === 0 || permissions.size === 0) {
    throw new Error('审批样例目录缺少参与者角色或办理权限契约')
  }

  const menuResponse = await api.request('GET', '/system/menu/list')
  const menus = menuResponse.data || []
  const menusById = new Map(menus.map(menu => [String(menu.menuId), menu]))
  const requiredMenuIds = new Set()
  for (const permission of permissions) {
    const matchingMenus = menus.filter(menu => menu.perms === permission)
    if (matchingMenus.length !== 1) {
      throw new Error(`办理权限菜单缺失或重复: ${permission}`)
    }
    collectMenuAncestors(matchingMenus[0], menusById, requiredMenuIds)
  }

  let added = 0
  for (const roleKey of roleKeys) {
    const roleRow = roles.find(item =>
      item.roleKey === roleKey && String(item.status) === '0'
    )
    if (!roleRow) throw new Error(`未找到有效参与者角色: ${roleKey}`)
    // 专用接口只执行 INSERT IGNORE，不读取或替换目标角色的既有菜单集合。
    const response = await api.request('PUT', `/system/role/${encodeURIComponent(roleRow.roleId)}/menus/grant`, {
      menuIds: [...requiredMenuIds].sort((left, right) => left - right)
    })
    const addedForRole = Number(response.data?.added)
    if (!Number.isSafeInteger(addedForRole) || addedForRole < 0) {
      throw new Error(`角色增量授权响应不完整: ${roleKey}`)
    }
    added += addedForRole
  }
  return { aligned: roleKeys.length, added }
}

/**
 * 核验样例引用的受控扩展已在正式注册表启用，并锁定稳定键和实现类型。
 * @param {{request: Function}} api 已登录 API 客户端。
 * @param {object} catalog 完整审批样例目录。
 * @returns {Promise<Map<string, object>>} 扩展稳定键到正式启用版本的映射。
 */
async function loadRequiredExtensions(api, catalog) {
  const requirements = Array.isArray(catalog.requiredExtensions)
    ? catalog.requiredExtensions
    : []
  const endpointByType = {
    JAVA: '/workflow/extension/options/java',
    CEL: '/workflow/extension/options/cel',
    HTTP: '/workflow/extension/options/http',
    SQL: '/workflow/extension/options/sql'
  }
  const rows = []
  for (const extensionType of new Set(requirements.map(item => item.extensionType))) {
    const endpoint = endpointByType[extensionType]
    if (!endpoint) throw new Error(`样例扩展类型不受支持: ${extensionType}`)
    const response = await api.request('GET', endpoint)
    rows.push(...(Array.isArray(response.data) ? response.data : []))
  }
  const resolved = new Map()
  for (const requirement of requirements) {
    const matches = rows.filter(item => item.extensionKey === requirement.key)
    if (matches.length !== 1) throw new Error(`样例所需受控扩展缺失或重复: ${requirement.key}`)
    const extension = matches[0]
    if (extension.extensionType !== requirement.extensionType ||
        extension.implementationKey !== requirement.implementationKey ||
        !Number.isSafeInteger(Number(extension.versionId)) || Number(extension.versionId) <= 0) {
      throw new Error(`样例所需受控扩展内容漂移: ${requirement.key}`)
    }
    resolved.set(requirement.key, extension)
  }
  return resolved
}

/**
 * 用每个测试账号重新登录并访问其正式工作流入口，验证凭据和 RBAC 真实生效。
 * @param {string} baseUrl 服务根地址。
 * @param {object} identities 测试身份目录定义。
 * @param {string} identityPassword 仅存在于当前进程的测试用户密码。
 * @returns {Promise<number>} 通过登录和权限入口校验的测试账号数量。
 */
async function verifyTestIdentityAccess(baseUrl, identities, identityPassword) {
  let verified = 0
  for (const definition of identities.users) {
    const client = createApiClient(baseUrl)
    await client.login(definition.userName, identityPassword)
    await client.request('GET', '/workflow/process/list?pageNum=1&pageSize=1')
    if (definition.roleKeys.includes('workflow_approver')) {
      await client.request('GET', '/workflow/process/todoList?pageNum=1&pageSize=1')
      await client.request('GET', '/workflow/process/claimList?pageNum=1&pageSize=1')
    }
    verified += 1
  }
  return verified
}

/**
 * 解析一个样例的静态审批身份，并保留动态多实例节点的固定完成模式。
 * @param {object} sample 样例模型和任务定义。
 * @param {{users: object[], roles: object[], depts: object[]}} directory 正式身份主数据。
 * @returns {object[]} 已解析身份或多实例模式的任务数组。
 */
function resolveSampleTasks(sample, directory) {
  if (!Array.isArray(sample.tasks) || sample.tasks.length === 0) {
    throw new Error(`审批样例缺少用户任务: ${sample.modelKey}`)
  }
  const taskIds = sample.tasks.map(task => task.id)
  if (taskIds.some(id => !/^[A-Za-z][A-Za-z0-9_-]{0,63}$/u.test(id)) ||
      new Set(taskIds).size !== taskIds.length) {
    throw new Error(`审批样例任务标识为空、重复或不符合受控语法: ${sample.modelKey}`)
  }
  return sample.tasks.map(task => {
    if (task.multiInstanceMode) {
      if (!['ALL', 'ANY'].includes(task.multiInstanceMode)) {
        throw new Error(`动态多实例完成模式不受支持: ${task.multiInstanceMode}`)
      }
      return { id: task.id, name: task.name, multiInstanceMode: task.multiInstanceMode }
    }
    return {
      id: task.id,
      name: task.name,
      identity: resolveTaskIdentity(task, directory)
    }
  })
}

/**
 * 生成所有正式用户任务统一要求的创建、分配和完成审计监听器。
 * @returns {string[]} 可直接放入 UserTask extensionElements 的 XML 行。
 */
function createUserTaskListeners() {
  return [
    '        <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>',
    '        <flowable:taskListener event="assignment" delegateExpression="${userTaskListener}"/>',
    '        <flowable:taskListener event="complete" delegateExpression="${userTaskListener}"/>'
  ]
}

/**
 * 生成静态身份或受控动态多实例 UserTask XML。
 * @param {object} task 已解析的任务定义。
 * @returns {string} 完整 UserTask XML。
 */
function createUserTaskElement(task) {
  const taskId = escapeXml(task.id)
  const taskName = escapeXml(task.name)
  const lines = []
  if (task.multiInstanceMode) {
    const condition = task.multiInstanceMode === 'ALL'
      ? controlledMultiInstanceAllCondition
      : controlledMultiInstanceAnyCondition
    lines.push(`    <userTask id="${taskId}" name="${taskName}" flowable:assignee="${escapeXml(controlledMultiInstanceAssignee)}">`)
    lines.push('      <extensionElements>')
    lines.push(...createUserTaskListeners())
    lines.push('      </extensionElements>')
    lines.push(`      <multiInstanceLoopCharacteristics isSequential="false" flowable:collection="${escapeXml(controlledMultiInstanceCollection)}" flowable:elementVariable="${controlledMultiInstanceElementVariable}">`)
    lines.push(`        <completionCondition xsi:type="tFormalExpression">${escapeXml(condition)}</completionCondition>`)
    lines.push('      </multiInstanceLoopCharacteristics>')
    lines.push('    </userTask>')
    return lines.join('\n')
  }
  lines.push(`    <userTask id="${taskId}" name="${taskName}" ${task.identity.attribute}="${escapeXml(task.identity.value)}">`)
  lines.push('      <extensionElements>')
  lines.push(...createUserTaskListeners())
  lines.push('      </extensionElements>')
  lines.push('    </userTask>')
  return lines.join('\n')
}

/**
 * 生成引用正式受控扩展注册表的 ServiceTask XML，禁止任意 Bean 或类名注入。
 * @param {object} extension 扩展稳定键和结构化配置。
 * @returns {string} 完整受控 ServiceTask XML。
 */
function createServiceTaskElement(extension) {
  if (!extension || typeof extension.key !== 'string' || !extension.key.trim() ||
      !extension.config || typeof extension.config !== 'object' || Array.isArray(extension.config)) {
    throw new Error('受控服务任务缺少扩展标识或结构化配置')
  }
  const config = escapeXml(JSON.stringify(extension.config))
  return [
    '    <serviceTask id="automation" name="写入自动化标记" flowable:delegateExpression="${workflowExtensionDelegate}">',
    '      <extensionElements>',
    `        <flowable:field name="approvaExtensionKey" stringValue="${escapeXml(extension.key)}"/>`,
    `        <flowable:field name="approvaExtensionConfig" stringValue="${config}"/>`,
    '      </extensionElements>',
    '    </serviceTask>'
  ].join('\n')
}

/**
 * 按样例模板生成可执行节点、顺序流和设计器坐标。
 * @param {object} sample 样例模型定义。
 * @param {number} formId 开始节点引用的正式表单主键。
 * @param {object[]} tasks 已解析的用户任务。
 * @returns {{nodes: object[], flows: object[]}} BPMN 图结构。
 */
function createSampleGraph(sample, formId, tasks) {
  const template = sample.template || 'serial'
  const start = { id: 'start', kind: 'start', name: '提交申请', formId, x: 80, y: 272 }
  const end = { id: 'end', kind: 'end', name: '结束', x: 0, y: 272 }
  if (template === 'serial') {
    const nodes = [start]
    const flows = []
    let previousId = start.id
    tasks.forEach((task, index) => {
      nodes.push({ ...task, kind: 'user', x: 190 + (190 * index), y: 250 })
      flows.push({ id: `flow_${previousId}_${task.id}`, source: previousId, target: task.id })
      previousId = task.id
    })
    end.x = 220 + (190 * tasks.length)
    nodes.push(end)
    flows.push({ id: `flow_${previousId}_end`, source: previousId, target: end.id })
    return { nodes, flows }
  }
  if (template === 'conditional') {
    if (tasks.length !== 3 || !sample.routing ||
        !/^[A-Za-z_][A-Za-z0-9_]{0,127}$/u.test(sample.routing.variable) ||
        !Number.isFinite(Number(sample.routing.threshold))) {
      throw new Error(`条件审批模板定义不完整: ${sample.modelKey}`)
    }
    const [initial, highFirst, highSecond] = tasks
    const threshold = Number(sample.routing.threshold)
    const gateway = {
      id: 'amount_gateway', kind: 'exclusiveGateway', name: '金额判断',
      defaultFlow: 'flow_amount_high', x: 390, y: 265
    }
    end.x = 890
    return {
      nodes: [
        start,
        { ...initial, kind: 'user', x: 190, y: 250 },
        gateway,
        { ...highFirst, kind: 'user', x: 540, y: 350 },
        { ...highSecond, kind: 'user', x: 720, y: 350 },
        end
      ],
      flows: [
        { id: 'flow_start_initial', source: 'start', target: initial.id },
        { id: 'flow_initial_gateway', source: initial.id, target: gateway.id },
        {
          id: 'flow_amount_small', name: '小额直接通过',
          source: gateway.id, target: 'end',
          conditionRule: {
            version: 1,
            default: false,
            combinator: 'AND',
            groups: [{
              combinator: 'AND',
              rules: [{
                field: sample.routing.variable,
                operator: 'LTE',
                value: threshold
              }]
            }]
          }
        },
        {
          id: 'flow_amount_high', name: '大额继续审批',
          source: gateway.id, target: highFirst.id,
          conditionRule: { version: 1, default: true }
        },
        { id: 'flow_high_first_second', source: highFirst.id, target: highSecond.id },
        { id: 'flow_high_second_end', source: highSecond.id, target: 'end' }
      ]
    }
  }
  if (template === 'parallel') {
    if (tasks.length !== 4) throw new Error(`并行审批模板必须包含四个用户任务: ${sample.modelKey}`)
    const [initial, upper, lower, final] = tasks
    const split = { id: 'parallel_split', kind: 'parallelGateway', name: '并行准备', x: 390, y: 265 }
    const join = { id: 'parallel_join', kind: 'parallelGateway', name: '汇总结果', x: 690, y: 265 }
    end.x = 1010
    return {
      nodes: [
        start,
        { ...initial, kind: 'user', x: 190, y: 250 },
        split,
        { ...upper, kind: 'user', x: 510, y: 130 },
        { ...lower, kind: 'user', x: 510, y: 370 },
        join,
        { ...final, kind: 'user', x: 810, y: 250 },
        end
      ],
      flows: [
        { id: 'flow_start_initial', source: 'start', target: initial.id },
        { id: 'flow_initial_split', source: initial.id, target: split.id },
        { id: 'flow_split_upper', source: split.id, target: upper.id },
        { id: 'flow_split_lower', source: split.id, target: lower.id },
        { id: 'flow_upper_join', source: upper.id, target: join.id },
        { id: 'flow_lower_join', source: lower.id, target: join.id },
        { id: 'flow_join_final', source: join.id, target: final.id },
        { id: 'flow_final_end', source: final.id, target: 'end' }
      ]
    }
  }
  if (template === 'multiInstance') {
    if (tasks.length !== 3 || !tasks[1].multiInstanceMode) {
      throw new Error(`动态多实例模板定义不完整: ${sample.modelKey}`)
    }
    const [initializer, multiInstance, final] = tasks
    end.x = 830
    return {
      nodes: [
        start,
        { ...initializer, kind: 'user', x: 190, y: 250 },
        { ...multiInstance, kind: 'user', x: 400, y: 250 },
        { ...final, kind: 'user', x: 610, y: 250 },
        end
      ],
      flows: [
        { id: 'flow_start_initializer', source: 'start', target: initializer.id },
        { id: 'flow_initializer_multi', source: initializer.id, target: multiInstance.id },
        { id: 'flow_multi_final', source: multiInstance.id, target: final.id },
        { id: 'flow_final_end', source: final.id, target: 'end' }
      ]
    }
  }
  if (template === 'service') {
    if (tasks.length !== 1) throw new Error(`受控自动化模板必须包含一个用户任务: ${sample.modelKey}`)
    const approval = tasks[0]
    end.x = 650
    return {
      nodes: [
        start,
        { id: 'automation', kind: 'service', extension: sample.extension, x: 190, y: 250 },
        { ...approval, kind: 'user', x: 420, y: 250 },
        end
      ],
      flows: [
        { id: 'flow_start_automation', source: 'start', target: 'automation' },
        { id: 'flow_automation_approval', source: 'automation', target: approval.id },
        { id: 'flow_approval_end', source: approval.id, target: 'end' }
      ]
    }
  }
  throw new Error(`不支持的审批样例模板: ${template}`)
}

/**
 * 把图节点转换为可执行 BPMN 元素 XML。
 * @param {object} node 样例图节点。
 * @returns {string} 节点 XML。
 */
function createNodeElement(node) {
  if (node.kind === 'start') {
    return `    <startEvent id="${node.id}" name="${escapeXml(node.name)}" flowable:formKey="key_${node.formId}"/>`
  }
  if (node.kind === 'end') return `    <endEvent id="${node.id}" name="${escapeXml(node.name)}"/>`
  if (node.kind === 'user') return createUserTaskElement(node)
  if (node.kind === 'service') return createServiceTaskElement(node.extension)
  if (node.kind === 'exclusiveGateway') {
    return `    <exclusiveGateway id="${node.id}" name="${escapeXml(node.name)}" default="${escapeXml(node.defaultFlow)}"/>`
  }
  if (node.kind === 'parallelGateway') {
    return `    <parallelGateway id="${node.id}" name="${escapeXml(node.name)}"/>`
  }
  throw new Error(`不支持的 BPMN 图节点类型: ${node.kind}`)
}

/**
 * 把顺序流转换为 BPMN XML，条件分支只写入受控作者规则属性。
 * @param {object} flow 顺序流定义。
 * @returns {string} 顺序流 XML。
 */
function createFlowElement(flow) {
  const flowName = flow.name ? ` name="${escapeXml(flow.name)}"` : ''
  const opening = `    <sequenceFlow id="${escapeXml(flow.id)}"${flowName} sourceRef="${escapeXml(flow.source)}" targetRef="${escapeXml(flow.target)}"`
  if (!flow.conditionRule) return `${opening}/>`
  const ruleJson = escapeXml(JSON.stringify(flow.conditionRule))
  return `${opening}>\n      <extensionElements>\n        <flowable:properties>\n          <flowable:property name="${conditionRuleProperty}" value="${ruleJson}"/>\n        </flowable:properties>\n      </extensionElements>\n    </sequenceFlow>`
}

/**
 * 生成 BPMN DI 节点坐标，使所有内置样例在设计器中打开即可清晰浏览。
 * @param {object} node 样例图节点。
 * @returns {string} BPMNShape XML。
 */
function createNodeShape(node) {
  const dimensions = node.kind === 'start' || node.kind === 'end'
    ? { width: 36, height: 36 }
    : node.kind.endsWith('Gateway')
      ? { width: 50, height: 50 }
      : { width: 120, height: 80 }
  return `      <bpmndi:BPMNShape id="${node.id}_di" bpmnElement="${node.id}"><dc:Bounds x="${node.x}" y="${node.y}" width="${dimensions.width}" height="${dimensions.height}"/></bpmndi:BPMNShape>`
}

/**
 * 计算 BPMN DI 连线端点，复杂分支允许直接使用斜线并保持节点关系可读。
 * @param {object} flow 顺序流定义。
 * @param {Map<string, object>} nodesById 节点标识到坐标定义的映射。
 * @returns {string} BPMNEdge XML。
 */
function createFlowEdge(flow, nodesById) {
  const source = nodesById.get(flow.source)
  const target = nodesById.get(flow.target)
  if (!source || !target) throw new Error(`顺序流引用了不存在的节点: ${flow.id}`)
  const size = node => node.kind === 'start' || node.kind === 'end'
    ? { width: 36, height: 36 }
    : node.kind.endsWith('Gateway')
      ? { width: 50, height: 50 }
      : { width: 120, height: 80 }
  const sourceSize = size(source)
  const targetSize = size(target)
  const sourceX = source.x + sourceSize.width
  const sourceY = source.y + (sourceSize.height / 2)
  const targetX = target.x
  const targetY = target.y + (targetSize.height / 2)
  return `      <bpmndi:BPMNEdge id="${flow.id}_di" bpmnElement="${flow.id}"><di:waypoint x="${sourceX}" y="${sourceY}"/><di:waypoint x="${targetX}" y="${targetY}"/></bpmndi:BPMNEdge>`
}

/**
 * 根据正式样例模板生成带表单、身份、监听器和可读坐标的 BPMN 2.0 XML。
 * @param {object} sample 样例模型和节点定义。
 * @param {number} formId 开始节点引用的正式表单主键。
 * @param {object[]} resolvedTasks 已解析身份或多实例模式的任务数组。
 * @returns {string} 可由模型保存接口直接校验的 BPMN XML。
 */
function createBpmnXml(sample, formId, resolvedTasks) {
  const processId = escapeXml(sample.modelKey)
  const processName = escapeXml(sample.modelName)
  const graph = createSampleGraph(sample, formId, resolvedTasks)
  const nodesById = new Map(graph.nodes.map(node => [node.id, node]))
  const elements = [
    ...graph.nodes.map(createNodeElement),
    ...graph.flows.map(createFlowElement)
  ]
  const shapes = graph.nodes.map(createNodeShape)
  const edges = graph.flows.map(flow => createFlowEdge(flow, nodesById))
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  xmlns:flowable="http://flowable.org/bpmn"
  targetNamespace="http://approvaplat.example/workflow/samples">
  <process id="${processId}" name="${processName}" isExecutable="true">
${elements.join('\n')}
  </process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_${processId}">
    <bpmndi:BPMNPlane id="BPMNPlane_${processId}" bpmnElement="${processId}">
${shapes.join('\n')}
${edges.join('\n')}
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</definitions>`
}

/**
 * 核验同标识模型的业务元数据和表单内容，禁止接管不属于当前目录的资产。
 * @param {object} model 模型详情接口返回的正式模型。
 * @param {object} sample 当前审批样例定义。
 * @param {string} category 已核验的正式分类编码。
 * @param {number} formId 已核验的正式表单主键。
 * @returns {void} 内容一致时完成；碰撞或漂移时抛出异常。
 */
function assertModelMetadataMatches(model, sample, category, formId) {
  const metadataMatches = model?.modelName === sample.modelName &&
    model?.modelKey === sample.modelKey &&
    model?.category === category &&
    Number(model?.formType) === 0 &&
    Number(model?.formId) === formId &&
    model?.description === sample.description
  const expectedFormContent = JSON.stringify(canonicalizeJson(sample.form))
  const actualFormContent = normalizeFormContent(model?.content, sample.formName)
  if (!metadataMatches || actualFormContent !== expectedFormContent) {
    throw new Error(`流程模型元数据或表单内容漂移: ${sample.modelKey}`)
  }
}

/**
 * 查询并核验样例当前最新部署，返回可追踪的真实部署主键。
 * @param {{request: Function}} api 已登录 API 客户端。
 * @param {object} sample 当前审批样例定义。
 * @param {string} category 已核验的正式分类编码。
 * @param {number} formId 已核验的正式表单主键。
 * @param {object[]} resolvedTasks 已解析身份或多实例模式的任务数组。
 * @returns {Promise<string>} 已发布且内容一致的真实部署主键。
 */
async function getVerifiedDeployment(api, sample, category, formId, resolvedTasks) {
  const response = await api.request(
    'GET',
    `/workflow/deploy/publishList?pageNum=1&pageSize=200&processKey=${encodeURIComponent(sample.modelKey)}`
  )
  const deployments = (response.rows || []).filter(item => item.processKey === sample.modelKey)
  if (deployments.length !== 1 || Number(response.total) !== 1) {
    throw new Error(`流程部署记录缺失或重复: ${sample.modelKey}`)
  }
  const deployment = deployments[0]
  const deploymentMatches = deployment.processName === sample.modelName &&
    deployment.category === category &&
    Number(deployment.version) === 1 &&
    Number(deployment.formId) === formId &&
    deployment.formName === sample.formName &&
    deployment.suspended === false &&
    typeof deployment.deploymentId === 'string' && deployment.deploymentId.length > 0 &&
    typeof deployment.definitionId === 'string' && deployment.definitionId.length > 0
  if (!deploymentMatches) {
    throw new Error(`流程部署元数据漂移: ${sample.modelKey}`)
  }
  const bpmnResponse = await api.request(
    'GET',
    `/workflow/deploy/bpmnXml/${encodeURIComponent(deployment.definitionId)}`
  )
  // Flowable 部署时会规范化 XML，受控扩展还会编译为固定执行 Bean，因此按业务拓扑核验而非比较格式文本。
  assertDeployedBpmnMatches(bpmnResponse.data, sample, formId, resolvedTasks)

  // 发起表单接口只读取 wf_deploy_form，不回连当前模板；据此核验不可变部署快照正文。
  const formResponse = await api.request(
    'GET',
    `/workflow/process/getProcessForm?definitionId=${encodeURIComponent(deployment.definitionId)}` +
      `&deployId=${encodeURIComponent(deployment.deploymentId)}`
  )
  const snapshot = formResponse.data
  const expectedFormContent = JSON.stringify(canonicalizeJson(sample.form))
  const actualFormContent = normalizeFormContent(snapshot?.content, sample.formName)
  const snapshotMatches = snapshot?.definitionId === deployment.definitionId &&
    snapshot?.deploymentId === deployment.deploymentId &&
    Number(snapshot?.formId) === formId &&
    snapshot?.formKey === `key_${formId}` &&
    snapshot?.nodeKey === 'start' &&
    snapshot?.formName === sample.formName &&
    actualFormContent === expectedFormContent
  if (!snapshotMatches) {
    throw new Error(`部署表单快照内容漂移: ${sample.modelKey}`)
  }
  return deployment.deploymentId
}

/**
 * 创建或复用受管样例模型，核验安全 BPMN 后部署一个可直接发起的审批样例。
 * @param {{request: Function}} api 已登录 API 客户端。
 * @param {object} sample 完整样例定义。
 * @param {{users: object[], roles: object[], depts: object[]}} directory 正式身份主数据。
 * @param {Map<string, object>} requiredExtensions 已核验的受控扩展映射。
 * @param {boolean} repairUndeployed 是否允许修复元数据和表单完全一致的未部署样例草稿。
 * @returns {Promise<{modelKey: string, status: string, deploymentId: string}>} 样例部署结果。
 */
async function installSample(
  api,
  sample,
  directory,
  requiredExtensions,
  repairUndeployed = false
) {
  const category = await getOrCreateCategory(api, sample.category)
  const formId = await getOrCreateForm(api, sample)
  if (sample.extension && !requiredExtensions.has(sample.extension.key)) {
    throw new Error(`样例引用了未核验的受控扩展: ${sample.extension.key}`)
  }
  const resolvedTasks = resolveSampleTasks(sample, directory)
  const expectedBpmn = createBpmnXml(sample, formId, resolvedTasks)
  const response = await api.request(
    'GET',
    `/workflow/model/list?pageNum=1&pageSize=200&modelKey=${encodeURIComponent(sample.modelKey)}`
  )
  const existingModels = (response.rows || []).filter(item => item.modelKey === sample.modelKey)
  if (existingModels.length > 1 || Number(response.total) !== existingModels.length) {
    throw new Error(`流程模型标识查询结果异常: ${sample.modelKey}`)
  }
  const existing = existingModels[0]
  if (existing) {
    const detailResponse = await api.request(
      'GET',
      `/workflow/model/${encodeURIComponent(existing.modelId)}`
    )
    const model = detailResponse.data
    assertModelMetadataMatches(model, sample, category, formId)
    if (model.deployed) {
      if (model.bpmnXml !== expectedBpmn) {
        throw new Error(`已部署模型 BPMN 内容漂移: ${sample.modelKey}`)
      }
      const deploymentId = await getVerifiedDeployment(
        api,
        sample,
        category,
        formId,
        resolvedTasks
      )
      return {
        modelKey: sample.modelKey,
        status: 'already-deployed',
        deploymentId
      }
    }
    if (model.bpmnXml !== expectedBpmn && !repairUndeployed) {
      throw new Error(`未部署模型 BPMN 内容漂移: ${sample.modelKey}`)
    }
  }

  const metadata = {
    modelName: sample.modelName,
    modelKey: sample.modelKey,
    category,
    description: sample.description,
    formType: 0,
    formId
  }
  let modelId = existing?.modelId
  if (!modelId) {
    const created = await api.request('POST', '/workflow/model', metadata)
    modelId = created.data?.modelId
    if (typeof modelId !== 'string' || !modelId) {
      throw new Error(`创建模型响应缺少有效主键: ${sample.modelKey}`)
    }
    const createdDetail = await api.request(
      'GET',
      `/workflow/model/${encodeURIComponent(modelId)}`
    )
    assertModelMetadataMatches(createdDetail.data, sample, category, formId)
  }

  // 只允许保存本次新建模型，或 BPMN 已与目录一致的既有未部署模型，禁止覆盖人工草稿。
  await api.request('POST', '/workflow/model/save', {
    requestId: randomUUID(),
    modelId,
    bpmnXml: expectedBpmn,
    newVersion: false
  })
  const savedDetail = await api.request(
    'GET',
    `/workflow/model/${encodeURIComponent(modelId)}`
  )
  assertModelMetadataMatches(savedDetail.data, sample, category, formId)
  if (savedDetail.data?.bpmnXml !== expectedBpmn || savedDetail.data?.deployed) {
    throw new Error(`模型保存状态不一致: ${sample.modelKey}`)
  }

  const deployed = await api.request(
    'POST',
    `/workflow/model/deploy?modelId=${encodeURIComponent(modelId)}`
  )
  const deploymentId = await getVerifiedDeployment(
    api,
    sample,
    category,
    formId,
    resolvedTasks
  )
  if (String(deployed.data?.deploymentId || '') !== deploymentId) {
    throw new Error(`模型部署主键与查询结果不一致: ${sample.modelKey}`)
  }
  return {
    modelKey: sample.modelKey,
    status: 'deployed',
    deploymentId
  }
}

/**
 * 读取并严格核验登录验证码的正式系统参数。
 * @param {{request: Function}} api 已登录且具备参数查询权限的管理员 API 客户端。
 * @returns {Promise<object>} 可原样恢复的 sys.account.captchaEnabled 参数详情。
 */
async function requireCaptchaConfig(api) {
  const response = await api.request(
    'GET',
    '/system/config/list?pageNum=1&pageSize=10&configKey=sys.account.captchaEnabled'
  )
  const rows = (response.rows || []).filter(item => (
    item.configKey === 'sys.account.captchaEnabled'
  ))
  if (rows.length !== 1 || Number(response.total) !== 1) {
    throw new Error('登录验证码参数缺失或重复，不能安全执行样例置备')
  }
  const detail = await api.request(
    'GET',
    `/system/config/${encodeURIComponent(rows[0].configId)}`
  )
  const config = detail.data
  if (!config || !['true', 'false'].includes(String(config.configValue))) {
    throw new Error('登录验证码参数值不合法，不能安全执行样例置备')
  }
  return config
}

/**
 * 通过正式参数管理接口切换验证码并立即核验缓存读取结果。
 * @param {{request: Function}} api 已登录且具备参数修改权限的管理员 API 客户端。
 * @param {object} config 已由 requireCaptchaConfig 核验的原始参数详情。
 * @param {boolean} enabled 目标验证码启用状态。
 * @returns {Promise<void>} 数据库和参数缓存均回显目标值后完成。
 */
async function updateCaptchaConfig(api, config, enabled) {
  const targetValue = String(Boolean(enabled))
  await api.request('PUT', '/system/config', {
    configId: Number(config.configId),
    configName: config.configName,
    configKey: config.configKey,
    configValue: targetValue,
    configType: config.configType,
    remark: config.remark || ''
  })
  // 直接核验匿名验证码入口的真实运行行为，避免只证明参数表或缓存中的中间值。
  const verified = await api.request('GET', '/captchaImage')
  if (Boolean(verified.captchaEnabled) !== Boolean(enabled)) {
    throw new Error(`登录验证码参数切换为 ${targetValue} 后回显不一致`)
  }
}

/**
 * 登录真实服务、加载目录主数据并依次幂等部署全部审批样例。
 * @returns {Promise<void>} 全部样例部署成功后输出不含凭据的结果表。
 */
async function main() {
  const options = parseArguments(process.argv.slice(2))
  const password = process.env.APPROVA_SAMPLE_ADMIN_PASSWORD
  const identityPassword = process.env.APPROVA_SAMPLE_IDENTITY_PASSWORD
  // captchaUuid/captchaCode 是默认初始化库开启验证码时的一次性登录凭据，必须成对提供且不会持久化。
  const captchaUuid = String(process.env.APPROVA_SAMPLE_CAPTCHA_UUID || '').trim()
  const captchaCode = String(process.env.APPROVA_SAMPLE_CAPTCHA_CODE || '').trim()
  // manageCaptcha 为 true 时仅在本次命令内暂时关闭验证码，以便逐个验证测试账号真实登录能力。
  const manageCaptchaText = String(
    process.env.APPROVA_SAMPLE_TEMPORARILY_DISABLE_CAPTCHA || 'false'
  ).trim()
  // repairUndeployed 为 true 时只允许修复经过完整元数据和表单对账的未部署样例草稿。
  const repairUndeployedText = String(
    process.env.APPROVA_SAMPLE_REPAIR_UNDEPLOYED || 'false'
  ).trim()
  if (!password) {
    throw new Error('必须通过 APPROVA_SAMPLE_ADMIN_PASSWORD 环境变量提供管理员密码')
  }
  if (!identityPassword) {
    throw new Error('必须通过 APPROVA_SAMPLE_IDENTITY_PASSWORD 环境变量提供测试身份密码')
  }
  if (Boolean(captchaUuid) !== Boolean(captchaCode)) {
    throw new Error('APPROVA_SAMPLE_CAPTCHA_UUID 与 APPROVA_SAMPLE_CAPTCHA_CODE 必须成对提供')
  }
  if (!['true', 'false'].includes(manageCaptchaText)) {
    throw new Error('APPROVA_SAMPLE_TEMPORARILY_DISABLE_CAPTCHA 只能是 true 或 false')
  }
  if (!['true', 'false'].includes(repairUndeployedText)) {
    throw new Error('APPROVA_SAMPLE_REPAIR_UNDEPLOYED 只能是 true 或 false')
  }
  const catalog = JSON.parse(await fs.readFile(options.catalogPath, 'utf8'))
  if (!Array.isArray(catalog.samples) || catalog.samples.length === 0) {
    throw new Error('审批样例目录为空')
  }

  const api = createApiClient(options.baseUrl)
  await api.login(options.username, password, captchaUuid, captchaCode)
  let captchaConfig = null
  let captchaChanged = false
  try {
    if (manageCaptchaText === 'true') {
      captchaConfig = await requireCaptchaConfig(api)
      if (String(captchaConfig.configValue) === 'true') {
        // 修改前先登记恢复责任，保证 PUT 已生效但后续回显失败时 finally 仍会恢复安全配置。
        captchaChanged = true
        await updateCaptchaConfig(api, captchaConfig, false)
      }
    }

    const identityResult = await provisionIdentities(
      api,
      options.baseUrl,
      catalog,
      identityPassword
    )
    const permissionResult = await alignParticipantRolePermissions(
      api,
      catalog,
      identityResult.directory.roles
    )
    const requiredExtensions = await loadRequiredExtensions(api, catalog)
    const results = []
    for (const sample of catalog.samples) {
      results.push(await installSample(
        api,
        sample,
        identityResult.directory,
        requiredExtensions,
        repairUndeployedText === 'true'
      ))
    }
    const verifiedUsers = await verifyTestIdentityAccess(
      options.baseUrl,
      requireIdentityCatalog(catalog),
      identityPassword
    )
    console.log(
      `测试部门已核验: 创建 ${identityResult.departments.created}，复用 ${identityResult.departments.reused}`
    )
    console.log(
      `测试角色已核验: 创建 ${identityResult.roles.created}，复用 ${identityResult.roles.reused}`
    )
    console.log(
      `测试账号已核验: 创建 ${identityResult.users.created}，校准 ${identityResult.users.aligned}，复用 ${identityResult.users.reused}，登录与权限入口通过 ${verifiedUsers}`
    )
    console.log(
      `参与者角色已核验: ${permissionResult.aligned}，新增菜单关联: ${permissionResult.added}`
    )
    console.table(results)
  } finally {
    // 即使样例创建、部署或账号验证失败，也必须通过正式接口恢复原验证码配置。
    if (captchaChanged && captchaConfig) {
      await updateCaptchaConfig(api, captchaConfig, true)
    }
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => {
    console.error(error.message)
    process.exitCode = 1
  })
}

export {
  createBpmnXml,
  createSampleGraph,
  requireIdentityCatalog,
  resolveSampleTasks
}
