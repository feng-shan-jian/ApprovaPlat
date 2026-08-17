import assert from 'node:assert/strict'
import test from 'node:test'
import { DOMParser, XMLSerializer } from '@xmldom/xmldom'
import {
  APPROVAL_USER_CAPABILITY,
  CLAIM_IDENTITY_CAPABILITY,
  buildApprovalUserQuery,
  buildClaimIdentityQuery
} from '../../src/api/workflow/identityQuery.js'
import { normalizeSequenceFlowReferences } from '../../src/components/workflow/bpmnGraphXml.js'
import { normalizeTaskListenerXml } from '../../src/components/workflow/taskListenerXml.js'

// Node 合同测试复用浏览器同构 DOM API，直接执行设计器的正式 XML 标准化函数。
globalThis.DOMParser = DOMParser
globalThis.XMLSerializer = XMLSerializer

/**
 * 验证审批目录查询保留允许的分页和检索参数，并写入固定能力契约。
 * @returns {void} 查询对象不符合冻结契约时断言失败。
 */
test('buildApprovalUserQuery 固定审批用户目录参数', () => {
  const source = { keyword: '审批', pageNum: 2, pageSize: 30 }

  assert.deepEqual(buildApprovalUserQuery(source), {
    keyword: '审批',
    pageNum: 2,
    pageSize: 30,
    type: 'user',
    capability: APPROVAL_USER_CAPABILITY
  })
  assert.deepEqual(source, { keyword: '审批', pageNum: 2, pageSize: 30 })
})

/**
 * 验证调用方不能通过覆盖字段把审批候选检索降级为通用用户、角色或部门目录。
 * @returns {void} 固定字段可被覆盖时断言失败。
 */
test('buildApprovalUserQuery 拒绝调用方覆盖固定范围', () => {
  assert.deepEqual(buildApprovalUserQuery({
    type: 'role',
    capability: '',
    keyword: '财务'
  }), {
    type: 'user',
    capability: 'approval',
    keyword: '财务'
  })
})

/**
 * 验证异常调用值不会污染请求对象，仍生成后端可识别的最小审批查询。
 * @returns {void} 非对象输入未被安全归一化时断言失败。
 */
test('buildApprovalUserQuery 对非对象输入使用安全默认值', () => {
  assert.deepEqual(buildApprovalUserQuery(null), {
    type: 'user',
    capability: 'approval'
  })
  assert.deepEqual(buildApprovalUserQuery([]), {
    type: 'user',
    capability: 'approval'
  })
})

/**
 * 验证候选认领目录允许三种正式身份，并冻结完整认领能力范围。
 * @returns {void} 任一候选身份查询未携带 claim 能力时断言失败。
 */
test('buildClaimIdentityQuery 固定候选认领目录参数', () => {
  for (const type of ['user', 'role', 'dept']) {
    const source = { type, capability: 'approval', keyword: '候选', pageNum: 2, pageSize: 30 }
    assert.deepEqual(buildClaimIdentityQuery(source), {
      type,
      capability: CLAIM_IDENTITY_CAPABILITY,
      keyword: '候选',
      pageNum: 2,
      pageSize: 30
    })
    assert.deepEqual(source, {
      type, capability: 'approval', keyword: '候选', pageNum: 2, pageSize: 30
    })
  }
})

/**
 * 验证候选目录拒绝前端聚合类型、空类型和非对象输入，不能静默降级到通用目录。
 * @returns {void} 非法类型未失败关闭时断言失败。
 */
test('buildClaimIdentityQuery 对非法候选身份类型失败关闭', () => {
  for (const query of [{ type: 'group' }, { type: 'USER' }, {}, null, []]) {
    assert.throws(() => buildClaimIdentityQuery(query), {
      name: 'TypeError',
      message: '候选身份类型必须为 user、role 或 dept'
    })
  }
})
/**
 * 验证持久化标准化无条件重建内部监听器，清理全部非法实现并保留其他业务扩展。
 * @returns {void} 任一用户任务监听器不完整、危险属性残留或非监听扩展丢失时断言失败。
 */
test('任务审计监听器按固定运行时契约重建', () => {
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:flowable="http://flowable.org/bpmn"
             xmlns:unsafe="urn:unsafe"
             xmlns:vendor="urn:vendor"
             targetNamespace="http://example.com">
  <process id="Process_1" isExecutable="true">
    <extensionElements>
      <vendor:userTask vendor:flag="keep"><vendor:payload /></vendor:userTask>
    </extensionElements>
    <userTask id="Task_1">
      <extensionElements>
        <flowable:formProperty id="keep" />
        <flowable:taskListener event="create" delegateExpression="\${userTaskListener}" onTransaction="committed" />
        <flowable:taskListener event="assignment" class="unsafe.Listener" />
        <flowable:taskListener event="delete" delegateExpression="\${workflowBusinessListener}">
          <flowable:field name="approvaExtensionKey" stringValue="approva.set-variable" />
        </flowable:taskListener>
        <unsafe:taskListener event="complete" delegateExpression="\${userTaskListener}">
          <unsafe:field />
        </unsafe:taskListener>
      </extensionElements>
    </userTask>
    <userTask id="Task_2" />
  </process>
</definitions>`

  // normalized 是所有可能送往后端的出口共用的正式持久化 XML。
  const normalized = normalizeTaskListenerXml(source)
  const document = new DOMParser().parseFromString(normalized, 'application/xml')
  const userTasks = Array.from(document.getElementsByTagNameNS(
    'http://www.omg.org/spec/BPMN/20100524/MODEL', 'userTask'))

  assert.equal(userTasks.length, 2)
  for (const userTask of userTasks) {
    const extensionElements = Array.from(userTask.childNodes).find(child =>
      child.nodeType === 1
      && child.namespaceURI === 'http://www.omg.org/spec/BPMN/20100524/MODEL'
      && child.localName === 'extensionElements')
    assert.ok(extensionElements)
    const listeners = Array.from(extensionElements.childNodes).filter(child =>
      child.nodeType === 1 && child.localName === 'taskListener')
    const businessListeners = listeners.filter(listener =>
      listener.getAttribute('delegateExpression') === '${workflowBusinessListener}')
    const systemListeners = listeners.filter(listener =>
      listener.getAttribute('delegateExpression') === '${userTaskListener}')
    assert.equal(businessListeners.length, userTask.getAttribute('id') === 'Task_1' ? 1 : 0)
    if (businessListeners.length) {
      assert.equal(businessListeners[0].getAttribute('event'), 'delete')
      assert.equal(businessListeners[0].getElementsByTagNameNS(
        'http://flowable.org/bpmn', 'field').length, 1)
    }
    assert.deepEqual(systemListeners.map(listener => listener.getAttribute('event')),
      ['create', 'assignment', 'complete'])
    for (const listener of systemListeners) {
      assert.equal(listener.namespaceURI, 'http://flowable.org/bpmn')
      assert.equal(listener.getAttribute('delegateExpression'), '${userTaskListener}')
      assert.deepEqual(Array.from(listener.attributes).map(attribute => attribute.name).sort(),
        ['delegateExpression', 'event'])
      assert.equal(Array.from(listener.childNodes).filter(child => child.nodeType === 1).length, 0)
    }
  }
  assert.equal(document.getElementsByTagNameNS('http://flowable.org/bpmn', 'formProperty').length, 1)
  // 第三方扩展中的同名 userTask 不是 BPMN 节点，标准化不得向其中注入任何内容。
  const vendorUserTask = document.getElementsByTagNameNS('urn:vendor', 'userTask')[0]
  assert.ok(vendorUserTask)
  assert.equal(vendorUserTask.getAttributeNS('urn:vendor', 'flag'), 'keep')
  assert.deepEqual(Array.from(vendorUserTask.childNodes)
    .filter(child => child.nodeType === 1)
    .map(child => [child.namespaceURI, child.localName]), [['urn:vendor', 'payload']])
  assert.equal(normalizeTaskListenerXml(normalized), normalized)
})

/**
 * 验证历史或外部 BPMN 只有 sourceRef/targetRef 时会补齐节点反向引用。
 * @returns {void} 任一引用缺失、重复或二次标准化不稳定时断言失败。
 */
test('流程图顺序流反向引用按真实 sequenceFlow 重建', () => {
  const source = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" targetNamespace="urn:test">
  <process id="approval" isExecutable="true">
    <startEvent id="start" />
    <sequenceFlow id="flow_start_review" sourceRef="start" targetRef="review" />
    <userTask id="review"><incoming>stale_flow</incoming></userTask>
    <sequenceFlow id="flow_review_end" sourceRef="review" targetRef="end" />
    <endEvent id="end" />
  </process>
</definitions>`

  const normalized = normalizeSequenceFlowReferences(source)
  const document = new DOMParser().parseFromString(normalized, 'application/xml')
  const byId = id => Array.from(document.getElementsByTagNameNS(
    'http://www.omg.org/spec/BPMN/20100524/MODEL', '*'))
    .find(element => element.getAttribute('id') === id)
  const references = (id, direction) => Array.from(byId(id).childNodes)
    .filter(child => child.nodeType === 1 && child.localName === direction)
    .map(child => child.textContent)

  assert.deepEqual(references('start', 'incoming'), [])
  assert.deepEqual(references('start', 'outgoing'), ['flow_start_review'])
  assert.deepEqual(references('review', 'incoming'), ['flow_start_review'])
  assert.deepEqual(references('review', 'outgoing'), ['flow_review_end'])
  assert.deepEqual(references('end', 'incoming'), ['flow_review_end'])
  assert.deepEqual(references('end', 'outgoing'), [])
  assert.equal(normalizeSequenceFlowReferences(normalized), normalized)
})
