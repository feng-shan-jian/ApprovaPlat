import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { DOMParser, XMLSerializer } from '@xmldom/xmldom'
import {
  APPROVAL_USER_CAPABILITY,
  CLAIM_IDENTITY_CAPABILITY,
  buildApprovalUserQuery,
  buildClaimIdentityQuery
} from '../../src/api/workflow/identityQuery.js'
import { normalizeTaskListenerXml } from '../../src/components/workflow/taskListenerXml.js'

// Node 合同测试复用浏览器同构 DOM API，直接执行设计器的正式 XML 标准化函数。
globalThis.DOMParser = DOMParser
globalThis.XMLSerializer = XMLSerializer

// 静态契约只读取正式源码，确保页面没有绕回通用用户目录或按本地角色名猜测审批资格。
const detailSource = readFileSync(new URL('../../src/views/workflow/work/detail.vue', import.meta.url), 'utf8')
const requestSource = readFileSync(new URL('../../src/utils/request.js', import.meta.url), 'utf8')
const designPageSource = readFileSync(new URL('../../src/views/workflow/model/design.vue', import.meta.url), 'utf8')
const designerSource = readFileSync(new URL('../../src/components/workflow/ProcessDesigner.vue', import.meta.url), 'utf8')
const taskListenerXmlSource = readFileSync(new URL('../../src/components/workflow/taskListenerXml.js', import.meta.url), 'utf8')

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
 * 验证任务分配字段使用审批资格目录，而抄送字段仍使用通用启用用户目录。
 * @returns {void} 页面目录隔离契约漂移时断言失败。
 */
test('任务详情页隔离审批对象与抄送对象目录', () => {
  assert.match(detailSource,
    /async function searchApprovalUsers\(keyword\)[\s\S]*?listApprovalUserOptions\(\{/)
  assert.match(detailSource,
    /async function searchMultiInstanceUsers\(keyword\)[\s\S]*?listApprovalUserOptions\(\{/)
  assert.match(detailSource,
    /async function searchCopyUsers\(keyword\)[\s\S]*?listIdentityOptions\(\{[\s\S]*?type: 'user'/)
  assert.match(detailSource,
    /validSelectedUsers\(actionDialog\.nextUserIds,[\s\S]*?verifiedApprovalUserIds\)/)
  assert.match(detailSource,
    /validSelectedUsers\(actionDialog\.copyUserIds,[\s\S]*?verifiedCopyUserIds\)/)
})

/**
 * 验证直接退回、普通动作提交和取消认领确认都冻结任务上下文，禁止晚到响应改绑其他任务。
 * @returns {void} 任一异步动作重新读取可变任务主键或缺少上下文复核时断言失败。
 */
test('任务详情异步动作冻结流程任务上下文', () => {
  assert.match(detailSource,
    /const returnContext = freezeCurrentTaskContext\(\)[\s\S]*?if \(!isCurrentTaskContext\(returnContext\)\) return[\s\S]*?bindActionDialogTaskContext\(returnContext\)/)
  assert.match(detailSource,
    /const actionContext = currentActionDialogTaskContext\(\)[\s\S]*?const taskId = actionContext\.taskId[\s\S]*?if \(!currentActionDialogTaskContext\(\)[\s\S]*?returnTask\(\{ taskId,/)
  assert.match(detailSource,
    /async function confirmUnclaim\(\)[\s\S]*?const taskContext = freezeCurrentTaskContext\(\)[\s\S]*?actionBusy\.value = true[\s\S]*?await proxy\.\$modal\.confirm[\s\S]*?isCurrentTaskContext\(taskContext\)[\s\S]*?unclaimTask\(taskContext\.taskId\)/)
  assert.match(detailSource,
    /openActionDialog\('resolve'\)[\s\S]*?const actionContext = currentActionDialogTaskContext\(\)[\s\S]*?const taskId = actionContext\.taskId[\s\S]*?if \(!currentActionDialogTaskContext\(\)[\s\S]*?resolveTask\(\{ taskId, comment, copyUserIds \}\)/)
})

/**
 * 验证普通动作弹窗关闭动画只能清理仍处于关闭状态的草稿。
 * @returns {void} 模板重新直接绑定重置函数或缺少 visible 守卫时断言失败。
 */
test('普通动作弹窗关闭动画不清理新一轮草稿', () => {
  assert.match(detailSource, /@closed="handleActionDialogClosed"/)
  assert.match(detailSource,
    /function handleActionDialogClosed\(\) \{\s*if \(actionDialog\.visible\) return\s*resetActionDialog\(\)\s*\}/)
  assert.doesNotMatch(detailSource, /@closed="resetActionDialog"/)
})

/**
 * 验证下一办理人字段只由服务端枚举策略驱动，缺少策略时不会继续展示不确定的可选能力。
 * @returns {void} 页面回退为所有完成任务展示选择器或自行推断 BPMN 时断言失败。
 */
test('下一办理人字段遵循服务端策略并失败关闭', () => {
  assert.match(detailSource,
    /NEXT_USER_ASSIGNMENT_POLICIES = Object\.freeze\(\[[\s\S]*?'DISABLED'[\s\S]*?'OPTIONAL'[\s\S]*?'REQUIRED_ALL'[\s\S]*?'REQUIRED_ANY'/)
  assert.match(detailSource,
    /function normalizeNextUserAssignmentPolicy\(payload\)[\s\S]*?return legacyRequired \? `REQUIRED_\$\{legacyMode\}` : 'DISABLED'/)
  assert.match(detailSource,
    /v-if="actionDialog\.type === 'complete' && nextUserSelectionEnabled"/)
  assert.match(detailSource,
    /type === 'complete' && !nextUserSelectionEnabled\.value && actionDialog\.nextUserIds\.length > 0/)
})

/**
 * 验证 revision 重试只消费受控机器子码，普通 409 不得冒充成员版本变化。
 * @returns {void} 请求层丢失子码或详情页继续按全部 409 刷新草稿时断言失败。
 */
test('动态多实例只按机器子码处理 revision 冲突', () => {
  assert.match(requestSource,
    /function createBusinessError\(code, message, subCode\)[\s\S]*?error\.subCode = normalizedSubCode/)
  assert.match(requestSource, /createBusinessError\(code, msg, res\.data\.subCode\)/)
  assert.match(detailSource,
    /MULTI_INSTANCE_REVISION_CONFLICT_SUBCODE = 'WORKFLOW_MULTI_INSTANCE_REVISION_CONFLICT'/)
  assert.match(detailSource,
    /function isMultiInstanceRevisionConflict\(error\)[\s\S]*?isConflictResponse\(error\)[\s\S]*?MULTI_INSTANCE_REVISION_CONFLICT_SUBCODE/)
  assert.match(detailSource,
    /type === 'complete' && expectedRevision !== null && isMultiInstanceRevisionConflict\(error\)/)
  assert.match(detailSource,
    /catch \(error\) \{\s*if \(isMultiInstanceRevisionConflict\(error\)\)/)
})

/**
 * 验证时间线只渲染后端用户可见 opinion，并在页面状态写入前删除内部审计载体。
 * @returns {void} 模板或脚本重新读取 comment.message/audit 时断言失败。
 */
test('流程时间线不渲染原始审计 JSON', () => {
  assert.match(detailSource,
    /v-if="comment\.opinion" class="workflow-detail__comment-message">\{\{ comment\.opinion \}\}/)
  assert.match(detailSource,
    /function normalizeTimelineComments\(value\)[\s\S]*?delete visibleComment\.message[\s\S]*?delete visibleComment\.audit/)
  assert.match(detailSource, /MAX_TIMELINE_OPINION_BYTES = 8 \* 1024/)
  assert.match(detailSource,
    /new TextEncoder\(\)\.encode\(opinion\)\.byteLength > MAX_TIMELINE_OPINION_BYTES/)
  assert.doesNotMatch(detailSource, /\{\{\s*comment\.message\s*\}\}/)
  assert.doesNotMatch(detailSource, /JSON\.parse\([^)]*comment\.(?:message|audit)/)
})

/**
 * 验证设计器按办理方式隔离直接办理和候选认领资格目录。
 * @returns {void} 三类选项池、能力契约或请求版本隔离出现漂移时断言失败。
 */
test('流程设计器按办理方式隔离身份资格目录', () => {
  assert.match(designPageSource,
    /identityOptions = reactive\(\{ assignees: \[\], candidateUsers: \[\], candidateGroups: \[\] \}\)/)
  assert.match(designPageSource,
    /identityRequestVersion = \{ assignees: 0, candidateUsers: 0, candidateGroups: 0 \}/)
  assert.match(designPageSource,
    /target === 'assignees'[\s\S]*?listApprovalUserOptions\(\{ keyword, pageNum: 1, pageSize: 50 \}\)/)
  assert.match(designPageSource,
    /target === 'candidateUsers'[\s\S]*?listClaimableIdentityOptions\(\{[\s\S]*?type: 'user'/)
  assert.match(designPageSource,
    /listClaimableIdentityOptions\(\{ type: 'role'[\s\S]*?listClaimableIdentityOptions\(\{ type: 'dept'/)
  assert.match(designerSource,
    /assignees: Object\.freeze\(\{ type: 'user', capability: 'approval' \}\)[\s\S]*?candidateUsers: Object\.freeze\(\{ type: 'user', capability: 'claim' \}\)[\s\S]*?candidateGroups: Object\.freeze\(\{ type: 'group', capability: 'claim' \}\)/)
  assert.match(designerSource,
    /:remote-method="searchAssignees"[\s\S]*?identityOptions\.assignees[\s\S]*?:remote-method="searchCandidateUsers"[\s\S]*?identityOptions\.candidateUsers[\s\S]*?:remote-method="searchCandidateGroups"[\s\S]*?identityOptions\.candidateGroups/)
  assert.doesNotMatch(designPageSource, /listIdentityOptions/)
  assert.doesNotMatch(designerSource, /identityOptions\.(?:users|groups)/)
  assert.doesNotMatch(`${detailSource}\n${designPageSource}\n${designerSource}`,
    /workflow_approver|workflow:process:approval.*(?:option|filter)/i)
})

/**
 * 验证内部任务审计监听器不再作为设计者可编辑字段暴露，全部持久化出口由组件自动补齐。
 * @returns {void} 页面重新出现审计面板、嵌套 shape 命令或任一持久化出口缺少标准化时断言失败。
 */
test('流程设计器隐藏内部任务审计监听器并自动标准化', () => {
  assert.doesNotMatch(designerSource, /任务审计事件|恢复标准任务审计/)
  assert.doesNotMatch(designerSource,
    /restoreTaskListeners|updateTaskListeners|initializeCreatedUserTask/)
  assert.doesNotMatch(designerSource, /eventBus\.on\('shape\.added'/)
  assert.match(designerSource,
    /import \{ normalizeTaskListenerXml \} from '\.\/taskListenerXml'/)
  assert.match(taskListenerXmlSource,
    /export function normalizeTaskListenerXml\(xml\)[\s\S]*?appendApprovedTaskListeners\(document, extensionElements\)/)
  assert.match(designerSource,
    /async function emitPersistedXml\(\)[\s\S]*?normalizeTaskListenerXml\(rawXml\)/)
  assert.match(designerSource,
    /async function requestSave\(\)[\s\S]*?emit\('save', await emitPersistedXml\(\)\)/)
  assert.match(designerSource,
    /async function downloadXml\(\)[\s\S]*?const xml = await emitPersistedXml\(\)/)
  assert.match(designerSource,
    /defineExpose\(\{[\s\S]*?getXml: \(\) => emitPersistedXml\(\)[\s\S]*?\}\)/)
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
    assert.deepEqual(listeners.map(listener => listener.getAttribute('event')),
      ['create', 'assignment', 'complete'])
    for (const listener of listeners) {
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
 * 验证模型设计页不再依赖人工“保存为新版本”开关，并对缺失的新版本主键失败关闭。
 * @returns {void} 手动版本开关回归、保存参数漂移或响应主键缺失被静默忽略时断言失败。
 */
test('模型设计保存不再暴露手动新版本开关', () => {
  assert.doesNotMatch(designPageSource, /saveAsNewVersion|保存为新版本/)
  assert.match(designPageSource,
    /const requestId = resolveSaveRequestId\(sourceModelId, xml\)[\s\S]*?saveModel\(\{\s*requestId,\s*modelId: sourceModelId,\s*bpmnXml: xml,[\s\S]*?newVersion: false\s*\}\)/)
  assert.match(designPageSource,
    /pendingSaveRequest\?\.modelId === modelId[\s\S]*?pendingSaveRequest\?\.xml === xml[\s\S]*?return pendingSaveRequest\.requestId/)
  assert.match(designPageSource, /globalThis\.crypto\.randomUUID\(\)/)
  assert.match(designPageSource,
    /pendingSaveRequest = undefined\s*proxy\.\$modal\.msgSuccess\('流程设计保存成功'\)/)
  assert.match(designPageSource,
    /const savedModelId = String\(response\.data\?\.modelId \|\| ''\)\.trim\(\)/)
  assert.match(designPageSource,
    /if \(!savedModelId\) \{\s*proxy\.\$modal\.msgError\('流程模型保存结果不完整'\)\s*return\s*\}/)
  assert.doesNotMatch(designPageSource,
    /response\.data\?\.modelId \|\| currentModelId\(\)/)
  assert.match(designPageSource,
    /if \(savedModelId !== currentModelId\(\)\)[\s\S]*?router\.replace/)
})
