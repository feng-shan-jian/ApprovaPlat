import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  APPROVAL_USER_CAPABILITY,
  CLAIM_IDENTITY_CAPABILITY,
  buildApprovalUserQuery,
  buildClaimIdentityQuery
} from '../../src/api/workflow/identityQuery.js'

// 静态契约只读取正式源码，确保页面没有绕回通用用户目录或按本地角色名猜测审批资格。
const detailSource = readFileSync(new URL('../../src/views/workflow/work/detail.vue', import.meta.url), 'utf8')
const requestSource = readFileSync(new URL('../../src/utils/request.js', import.meta.url), 'utf8')
const designPageSource = readFileSync(new URL('../../src/views/workflow/model/design.vue', import.meta.url), 'utf8')
const designerSource = readFileSync(new URL('../../src/components/workflow/ProcessDesigner.vue', import.meta.url), 'utf8')

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
 * 验证退回查询、普通动作提交和取消认领确认都冻结任务上下文，禁止晚到响应改绑其他任务。
 * @returns {void} 任一异步动作重新读取可变任务主键或缺少上下文复核时断言失败。
 */
test('任务详情异步动作冻结流程任务上下文', () => {
  assert.match(detailSource,
    /const returnContext = freezeCurrentTaskContext\(\)[\s\S]*?listReturnableTasks\(\{ taskId: returnContext\.taskId \}\)[\s\S]*?if \(!isCurrentTaskContext\(returnContext\)\) return[\s\S]*?bindActionDialogTaskContext\(returnContext\)/)
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
