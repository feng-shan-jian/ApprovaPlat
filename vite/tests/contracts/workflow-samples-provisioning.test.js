import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { DOMParser } from '@xmldom/xmldom'
import {
  createBpmnXml,
  requireIdentityCatalog,
  resolveSampleTasks
} from '../../../deployment/scripts/provision-workflow-samples.mjs'

const testDirectory = path.dirname(fileURLToPath(import.meta.url))
const catalogPath = path.resolve(
  testDirectory,
  '../../../deployment/samples/workflow/workflow-samples.json'
)
const scriptPath = path.resolve(
  testDirectory,
  '../../../deployment/scripts/provision-workflow-samples.mjs'
)

/**
 * 读取正式样例目录。
 * @returns {Promise<object>} 解析后的身份和流程样例定义。
 */
async function loadCatalog() {
  return JSON.parse(await fs.readFile(catalogPath, 'utf8'))
}

/**
 * 按目录自然键构造纯合同测试身份主数据，主键只用于验证 BPMN 解析逻辑。
 * @param {object} catalog 完整样例目录。
 * @returns {{users: object[], roles: object[], depts: object[]}} 可供身份解析函数使用的目录。
 */
function createContractDirectory(catalog) {
  const platformRoleKeys = ['workflow_starter', 'workflow_approver', 'workflow_auditor']
  return {
    users: catalog.identities.users.map((user, index) => ({
      userId: 1000 + index,
      userName: user.userName,
      status: '0'
    })),
    roles: [
      ...catalog.identities.roles.map((role, index) => ({
        roleId: 2000 + index,
        roleKey: role.roleKey,
        status: '0'
      })),
      ...platformRoleKeys.map((roleKey, index) => ({
        roleId: 3000 + index,
        roleKey,
        status: '0'
      }))
    ],
    depts: catalog.identities.departments.map((dept, index) => ({
      deptId: 4000 + index,
      deptName: dept.deptName,
      status: '0'
    }))
  }
}

/**
 * 生成指定样例的 BPMN XML。
 * @param {object} sample 流程样例定义。
 * @param {object} directory 合同测试身份主数据。
 * @returns {string} 完整 BPMN 2.0 XML。
 */
function renderSample(sample, directory) {
  return createBpmnXml(sample, 99, resolveSampleTasks(sample, directory))
}

/**
 * 解析 XML 并收集解析器错误，保证合同断言不会把字符串片段误当作合法 BPMN。
 * @param {string} xml 待解析 XML。
 * @returns {Document} 已成功解析的 XML 文档。
 */
function parseXml(xml) {
  const errors = []
  const document = new DOMParser({
    onError: (level, message) => errors.push(`${level}: ${message}`)
  }).parseFromString(xml, 'application/xml')
  assert.deepEqual(errors, [])
  return document
}

/**
 * 读取 BPMN 默认命名空间中的指定元素。
 * @param {Document} document BPMN XML 文档。
 * @param {string} localName BPMN 元素本地名称。
 * @returns {Element[]} 匹配元素数组。
 */
function bpmnElements(document, localName) {
  return Array.from(document.getElementsByTagNameNS(
    'http://www.omg.org/spec/BPMN/20100524/MODEL',
    localName
  ))
}

/**
 * 验证身份目录覆盖多部门、多角色、多账号且普通账号没有管理角色。
 * @returns {Promise<void>} 目录契约完整时正常结束。
 */
test('工作流样例内置完整测试身份且不授予管理角色', async () => {
  const catalog = await loadCatalog()
  const identities = requireIdentityCatalog(catalog)
  assert.equal(identities.departments.length, 6)
  assert.equal(identities.roles.length, 9)
  assert.equal(identities.users.length, 9)
  const availableRoleKeys = new Set([
    ...identities.roles.map(role => role.roleKey),
    'workflow_starter',
    'workflow_approver',
    'workflow_auditor'
  ])
  for (const user of identities.users) {
    assert.ok(user.roleKeys.every(roleKey => availableRoleKeys.has(roleKey)))
    assert.ok(!user.roleKeys.includes('admin'))
    assert.ok(!user.roleKeys.includes('workflow_admin'))
  }
})

/**
 * 验证所有模型标识、表单名称唯一，并覆盖关键流程模板类型。
 * @returns {Promise<void>} 样例能力矩阵完整时正常结束。
 */
test('工作流样例覆盖串行条件并行多实例和受控自动化', async () => {
  const catalog = await loadCatalog()
  assert.equal(catalog.samples.length, 11)
  assert.equal(new Set(catalog.samples.map(sample => sample.modelKey)).size, 11)
  assert.equal(new Set(catalog.samples.map(sample => sample.formName)).size, 11)
  assert.deepEqual(
    new Set(catalog.samples.map(sample => sample.template || 'serial')),
    new Set(['serial', 'conditional', 'parallel', 'multiInstance', 'service'])
  )
  assert.deepEqual(
    new Set(catalog.samples.flatMap(sample => sample.tasks
      .map(task => task.multiInstanceMode)
      .filter(Boolean))),
    new Set(['ALL', 'ANY'])
  )
})

/**
 * 验证所有目录样例都能生成结构完整、身份已解析且可在设计器显示的 BPMN XML。
 * @returns {Promise<void>} 全部 BPMN XML 合法时正常结束。
 */
test('所有工作流样例都生成合法 BPMN XML 和设计器坐标', async () => {
  const catalog = await loadCatalog()
  const directory = createContractDirectory(catalog)
  for (const sample of catalog.samples) {
    const document = parseXml(renderSample(sample, directory))
    assert.equal(bpmnElements(document, 'process').length, 1, sample.modelKey)
    assert.equal(bpmnElements(document, 'startEvent').length, 1, sample.modelKey)
    assert.equal(bpmnElements(document, 'endEvent').length, 1, sample.modelKey)
    assert.ok(bpmnElements(document, 'userTask').length >= 1, sample.modelKey)
    assert.equal(
      document.getElementsByTagNameNS(
        'http://www.omg.org/spec/BPMN/20100524/DI',
        'BPMNPlane'
      ).length,
      1,
      sample.modelKey
    )
  }
})

/**
 * 验证复杂模板使用后端固定的网关、多实例和受控扩展协议。
 * @returns {Promise<void>} 固定执行协议完整时正常结束。
 */
test('复杂工作流样例遵守条件路由多实例和扩展白名单', async () => {
  const catalog = await loadCatalog()
  const directory = createContractDirectory(catalog)
  const byKey = new Map(catalog.samples.map(sample => [sample.modelKey, sample]))

  const conditional = parseXml(renderSample(byKey.get('sample_amount_routing'), directory))
  assert.equal(bpmnElements(conditional, 'exclusiveGateway').length, 1)
  assert.equal(bpmnElements(conditional, 'conditionExpression').length, 0)
  const conditionalFlows = new Map(bpmnElements(conditional, 'sequenceFlow')
    .map(flow => [flow.getAttribute('id'), flow]))
  /**
   * 从条件样例的指定顺序流读取受控作者规则。
   * @param {string} flowId 顺序流 BPMN 标识。
   * @returns {object} 已解析的版本化条件规则 JSON。
   */
  const readConditionRule = flowId => {
    const properties = Array.from(conditionalFlows.get(flowId)
      .getElementsByTagNameNS('http://flowable.org/bpmn', 'property'))
    const property = properties.find(item => (
      item.getAttribute('name') === 'approva.conditionRule.config'
    ))
    assert.ok(property, flowId)
    return JSON.parse(property.getAttribute('value'))
  }
  assert.deepEqual(readConditionRule('flow_amount_small'), {
    version: 1,
    default: false,
    combinator: 'AND',
    groups: [{
      combinator: 'AND',
      rules: [{ field: 'requestAmount', operator: 'LTE', value: 5000 }]
    }]
  })
  assert.deepEqual(readConditionRule('flow_amount_high'), { version: 1, default: true })
  assert.equal(conditionalFlows.get('flow_amount_small').getAttribute('name'), '小额直接通过')
  assert.equal(conditionalFlows.get('flow_amount_high').getAttribute('name'), '大额继续审批')

  const parallel = parseXml(renderSample(byKey.get('sample_onboarding_parallel'), directory))
  assert.equal(bpmnElements(parallel, 'parallelGateway').length, 2)

  const all = parseXml(renderSample(byKey.get('sample_dynamic_countersign'), directory))
  const allTask = bpmnElements(all, 'userTask').find(task => task.getAttribute('id') === 'expert_countersign')
  assert.equal(allTask.getAttributeNS('http://flowable.org/bpmn', 'assignee'), '${assignee}')
  assert.equal(bpmnElements(all, 'completionCondition')[0].textContent, '${nrOfCompletedInstances == nrOfInstances}')

  const any = parseXml(renderSample(byKey.get('sample_dynamic_anysign'), directory))
  assert.equal(bpmnElements(any, 'completionCondition')[0].textContent, '${nrOfCompletedInstances > 0}')

  const automation = renderSample(byKey.get('sample_controlled_automation'), directory)
  assert.match(automation, /delegateExpression="\$\{workflowExtensionDelegate\}"/u)
  assert.match(automation, /name="approvaExtensionKey" stringValue="approva\.set-variable"/u)
  assert.doesNotMatch(automation, /flowable:class=/u)
})

/**
 * 验证目录和脚本不固化密码、不直写数据库，并强制从环境变量取凭据。
 * @returns {Promise<void>} 安全置备约束满足时正常结束。
 */
test('工作流样例置备不包含明文密码或数据库旁路', async () => {
  const [catalogSource, scriptSource] = await Promise.all([
    fs.readFile(catalogPath, 'utf8'),
    fs.readFile(scriptPath, 'utf8')
  ])
  assert.doesNotMatch(catalogSource, /password|密码|wang/iu)
  assert.doesNotMatch(scriptSource, /['"]wang['"]/u)
  assert.doesNotMatch(scriptSource, /\b(?:INSERT|UPDATE|DELETE)\s+(?:INTO|FROM)?\s*(?:ACT_|wf_|sys_)/iu)
  assert.match(scriptSource, /APPROVA_SAMPLE_ADMIN_PASSWORD/u)
  assert.match(scriptSource, /APPROVA_SAMPLE_IDENTITY_PASSWORD/u)
  assert.match(scriptSource, /APPROVA_SAMPLE_CAPTCHA_UUID/u)
  assert.match(scriptSource, /APPROVA_SAMPLE_CAPTCHA_CODE/u)
  assert.match(scriptSource, /APPROVA_SAMPLE_TEMPORARILY_DISABLE_CAPTCHA/u)
  assert.match(scriptSource, /APPROVA_SAMPLE_REPAIR_UNDEPLOYED/u)
  assert.match(scriptSource, /Boolean\(captchaUuid\) !== Boolean\(captchaCode\)/u)
  assert.match(scriptSource, /api\.request\('GET', '\/captchaImage'\)/u)
  assert.match(scriptSource, /captchaChanged = true\s+await updateCaptchaConfig/u)
  assert.match(scriptSource, /model\.bpmnXml !== expectedBpmn && !repairUndeployed/u)
  assert.match(scriptSource, /finally \{/u)
  assert.match(scriptSource, /\/system\/user/u)
  assert.match(scriptSource, /\/workflow\/model\/deploy/u)
})
