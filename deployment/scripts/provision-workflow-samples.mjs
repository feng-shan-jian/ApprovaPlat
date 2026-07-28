import fs from 'node:fs/promises'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url))
const defaultCatalogPath = path.resolve(
  scriptDirectory,
  '../samples/workflow/workflow-samples.json'
)

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
  if (!existing) {
    await api.request('POST', '/workflow/category', {
      categoryName: category.name,
      code: category.code,
      remark: 'ApprovaPlat 可验收审批样例'
    })
  }
  return category.code
}

/**
 * 按表单名称查询或创建经过服务端校验的正式流程表单。
 * @param {{request: Function}} api 已登录 API 客户端。
 * @param {object} sample 包含 formName 和 form 的样例定义。
 * @returns {Promise<number>} 正式表单主键。
 */
async function getOrCreateForm(api, sample) {
  const response = await api.request(
    'GET',
    `/workflow/form/list?pageNum=1&pageSize=50&formName=${encodeURIComponent(sample.formName)}`
  )
  const existing = (response.rows || []).find(item => item.formName === sample.formName)
  if (existing) return Number(existing.formId)
  const created = await api.request('POST', '/workflow/form', {
    formName: sample.formName,
    content: JSON.stringify(sample.form),
    remark: 'ApprovaPlat 可验收审批样例'
  })
  return Number(created.data.formId)
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
 * 创建或复用模型，保存安全 BPMN，并部署一个可直接发起的审批样例。
 * @param {{request: Function}} api 已登录 API 客户端。
 * @param {object} sample 完整样例定义。
 * @param {{users: object[], roles: object[], depts: object[]}} directory 正式身份主数据。
 * @returns {Promise<{modelKey: string, status: string, deploymentId: string}>} 样例部署结果。
 */
async function installSample(api, sample, directory) {
  const category = await getOrCreateCategory(api, sample.category)
  const formId = await getOrCreateForm(api, sample)
  const response = await api.request(
    'GET',
    `/workflow/model/list?pageNum=1&pageSize=200&modelKey=${encodeURIComponent(sample.modelKey)}`
  )
  const existing = (response.rows || []).find(item => item.modelKey === sample.modelKey)
  if (existing?.deployed) {
    return {
      modelKey: sample.modelKey,
      status: 'already-deployed',
      deploymentId: String(existing.deploymentId || '')
    }
  }

  let modelId = existing?.modelId
  const metadata = {
    modelName: sample.modelName,
    modelKey: sample.modelKey,
    category,
    description: sample.description,
    formType: 0,
    formId
  }
  if (modelId) {
    await api.request('PUT', '/workflow/model', { ...metadata, modelId })
  } else {
    const created = await api.request('POST', '/workflow/model', metadata)
    modelId = created.data.modelId
  }

  const resolvedTasks = sample.tasks.map(task => ({
    id: task.id,
    name: task.name,
    identity: resolveTaskIdentity(task, directory)
  }))
  await api.request('POST', '/workflow/model/save', {
    modelId,
    bpmnXml: createBpmnXml(sample, formId, resolvedTasks),
    newVersion: false
  })
  const deployed = await api.request(
    'POST',
    `/workflow/model/deploy?modelId=${encodeURIComponent(modelId)}`
  )
  return {
    modelKey: sample.modelKey,
    status: 'deployed',
    deploymentId: String(deployed.data.deploymentId)
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
  const results = []
  for (const sample of catalog.samples) {
    results.push(await installSample(api, sample, directory))
  }
  console.table(results)
}

main().catch(error => {
  console.error(error.message)
  process.exitCode = 1
})
