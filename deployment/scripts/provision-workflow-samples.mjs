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
   * @returns {Promise<void>} 登录成功后更新客户端认证状态。
   */
  async function login(username, password) {
    const response = await request('POST', '/login', {
      username,
      password,
      code: '',
      uuid: ''
    })
    if (typeof response.token !== 'string' || !response.token) {
      throw new Error('登录响应缺少访问令牌')
    }
    token = response.token
  }

  return { login, request }
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
 * 生成带固定审计监听器、表单键和真实静态身份的串行 BPMN 2.0 XML。
 * @param {object} sample 样例模型和节点定义。
 * @param {number} formId 开始节点引用的正式表单主键。
 * @param {object[]} resolvedTasks 已解析身份编码的节点数组。
 * @returns {string} 可由模型保存接口直接校验的 BPMN XML。
 */
function createBpmnXml(sample, formId, resolvedTasks) {
  if (resolvedTasks.length < 2) {
    throw new Error('审批样例至少需要两个用户任务，才能形成真实退回路径')
  }
  const processId = escapeXml(sample.modelKey)
  const processName = escapeXml(sample.modelName)
  const elements = [
    `    <startEvent id="start" name="提交申请" flowable:formKey="key_${formId}"/>`
  ]
  const shapes = [
    '      <bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="80" y="172" width="36" height="36"/></bpmndi:BPMNShape>'
  ]
  const edges = []
  let previousId = 'start'
  let previousX = 116

  resolvedTasks.forEach((task, index) => {
    const taskId = escapeXml(task.id)
    const taskName = escapeXml(task.name)
    const identityValue = escapeXml(task.identity.value)
    const x = 190 + (180 * index)
    const flowId = `flow_${previousId}_${task.id}`
    elements.push(`    <sequenceFlow id="${flowId}" sourceRef="${previousId}" targetRef="${taskId}"/>`)
    elements.push(`    <userTask id="${taskId}" name="${taskName}" ${task.identity.attribute}="${identityValue}">`)
    elements.push('      <extensionElements>')
    elements.push('        <flowable:taskListener event="create" delegateExpression="${userTaskListener}"/>')
    elements.push('        <flowable:taskListener event="assignment" delegateExpression="${userTaskListener}"/>')
    elements.push('        <flowable:taskListener event="complete" delegateExpression="${userTaskListener}"/>')
    elements.push('      </extensionElements>')
    elements.push('    </userTask>')
    shapes.push(`      <bpmndi:BPMNShape id="${task.id}_di" bpmnElement="${taskId}"><dc:Bounds x="${x}" y="150" width="110" height="80"/></bpmndi:BPMNShape>`)
    edges.push(`      <bpmndi:BPMNEdge id="${flowId}_di" bpmnElement="${flowId}"><di:waypoint x="${previousX}" y="190"/><di:waypoint x="${x}" y="190"/></bpmndi:BPMNEdge>`)
    previousId = task.id
    previousX = x + 110
  })

  const endX = 210 + (180 * resolvedTasks.length)
  const endFlowId = `flow_${previousId}_end`
  elements.push(`    <sequenceFlow id="${endFlowId}" sourceRef="${previousId}" targetRef="end"/>`)
  elements.push('    <endEvent id="end" name="结束"/>')
  shapes.push(`      <bpmndi:BPMNShape id="end_di" bpmnElement="end"><dc:Bounds x="${endX}" y="172" width="36" height="36"/></bpmndi:BPMNShape>`)
  edges.push(`      <bpmndi:BPMNEdge id="${endFlowId}_di" bpmnElement="${endFlowId}"><di:waypoint x="${previousX}" y="190"/><di:waypoint x="${endX}" y="190"/></bpmndi:BPMNEdge>`)

  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
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
 * @param {string} expectedBpmn 目录和当前身份主数据生成的 BPMN XML。
 * @returns {Promise<string>} 已发布且内容一致的真实部署主键。
 */
async function getVerifiedDeployment(api, sample, category, formId, expectedBpmn) {
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
  if (bpmnResponse.data !== expectedBpmn) {
    throw new Error(`已部署 BPMN 内容漂移: ${sample.modelKey}`)
  }

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
 * @returns {Promise<{modelKey: string, status: string, deploymentId: string}>} 样例部署结果。
 */
async function installSample(api, sample, directory) {
  const category = await getOrCreateCategory(api, sample.category)
  const formId = await getOrCreateForm(api, sample)
  const resolvedTasks = sample.tasks.map(task => ({
    id: task.id,
    name: task.name,
    identity: resolveTaskIdentity(task, directory)
  }))
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
        expectedBpmn
      )
      return {
        modelKey: sample.modelKey,
        status: 'already-deployed',
        deploymentId
      }
    }
    if (model.bpmnXml !== expectedBpmn) {
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
    expectedBpmn
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
 * 登录真实服务、加载目录主数据并依次幂等部署全部审批样例。
 * @returns {Promise<void>} 全部样例部署成功后输出不含凭据的结果表。
 */
async function main() {
  const options = parseArguments(process.argv.slice(2))
  const password = process.env.APPROVA_SAMPLE_ADMIN_PASSWORD
  if (!password) {
    throw new Error('必须通过 APPROVA_SAMPLE_ADMIN_PASSWORD 环境变量提供管理员密码')
  }
  const catalog = JSON.parse(await fs.readFile(options.catalogPath, 'utf8'))
  if (!Array.isArray(catalog.samples) || catalog.samples.length === 0) {
    throw new Error('审批样例目录为空')
  }

  const api = createApiClient(options.baseUrl)
  await api.login(options.username, password)
  const [userResponse, roleResponse, deptResponse] = await Promise.all([
    api.request('GET', '/system/user/list?pageNum=1&pageSize=200'),
    api.request('GET', '/system/role/list?pageNum=1&pageSize=200'),
    api.request('GET', '/system/dept/list')
  ])
  const directory = {
    users: userResponse.rows || [],
    roles: roleResponse.rows || [],
    depts: deptResponse.data || []
  }
  const permissionResult = await alignParticipantRolePermissions(
    api,
    catalog,
    directory.roles
  )
  const results = []
  for (const sample of catalog.samples) {
    results.push(await installSample(api, sample, directory))
  }
  console.log(
    `参与者角色已核验: ${permissionResult.aligned}，新增菜单关联: ${permissionResult.added}`
  )
  console.table(results)
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
