import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const apiSource = readFileSync(new URL('../../src/api/workflow/draft.js', import.meta.url), 'utf8')
const listSource = readFileSync(new URL('../../src/views/workflow/work/draft.vue', import.meta.url), 'utf8')
const startSource = readFileSync(new URL('../../src/views/workflow/work/start.vue', import.meta.url), 'utf8')
const rendererSource = readFileSync(new URL('../../src/components/workflow/ProcessFormRenderer.vue', import.meta.url), 'utf8')
const attachmentSource = readFileSync(new URL('../../src/components/workflow/WorkflowAttachmentUpload.vue', import.meta.url), 'utf8')
const routerSource = readFileSync(new URL('../../src/router/index.js', import.meta.url), 'utf8')

/**
 * 验证草稿 API 使用固定正式路径、CAS 参数和提交结构。
 * @returns {void} 任一接口退化为本地状态或错误路径时测试失败。
 */
test('申请草稿使用固定后端 CRUD 和提交接口', () => {
  assert.match(apiSource, /url:\s*'\/workflow\/process\/draft\/list'/)
  assert.match(apiSource, /url:\s*'\/workflow\/process\/draft'/)
  assert.match(apiSource, /`\/workflow\/process\/draft\/\$\{encodeURIComponent\(draftId\)\}`/)
  assert.match(apiSource, /`\/workflow\/process\/draft\/\$\{encodeURIComponent\(draftId\)\}\/submit`/)
  assert.match(apiSource, /params:\s*\{\s*expectedVersion\s*\}/)
  assert.doesNotMatch(`${apiSource}\n${listSource}\n${startSource}`, /localStorage|sessionStorage/)
})

/**
 * 验证本人草稿列表提供服务端筛选、对象路由和带版本删除。
 * @returns {void} 列表缺少真实查询或 CAS 删除时测试失败。
 */
test('本人草稿列表提供流程名称时间筛选和 CAS 操作', () => {
  assert.match(listSource, /listProcessDrafts\(buildQuery\(\)\)/)
  assert.match(listSource, /updatedAfter:\s*range\[0\]/)
  assert.match(listSource, /updatedBefore:\s*range\[1\]/)
  assert.match(listSource, /deleteProcessDraft\(draftId,\s*Number\(row\.revisionNo\)\)/)
  assert.match(listSource, /name:\s*'WorkflowProcessDraftEdit'/)
})

/**
 * 验证发起页面把草稿保存与正式提交分离，并正确使用 revisionNo。
 * @returns {void} 缺少真实保存、提交、删除或并发门禁时测试失败。
 */
test('发起页面完成保存继续编辑删除提交与 CAS 闭环', () => {
  assert.match(startSource, /createProcessDraft\(\{\s*processDefinitionId:/)
  assert.match(startSource, /updateProcessDraft\(draftId\.value,\s*\{\s*expectedVersion:\s*draftState\.revisionNo/)
  assert.match(startSource, /deleteProcessDraft\(draftId\.value,\s*draftState\.revisionNo\)/)
  assert.match(startSource, /submitProcessDraft\(draftId\.value,\s*\{[\s\S]*?expectedVersion:\s*draftState\.revisionNo/)
  assert.match(startSource, /ensureAttachmentsIdle\(\)/)
  assert.match(startSource, /formRendererRef\.value\.validate\(\)/)
  assert.match(startSource, /conflict\.value\s*=\s*true/)
  assert.match(startSource, /hydrateDraftAttachments/)
  assert.match(startSource, /multiInstanceUserIds:\s*startMembers/)
  assert.match(startSource, /replaceMultiInstanceSelections\(draft\.multiInstanceUserIds\)/)
})

/**
 * 验证草稿继续编辑路由和五项前端权限使用固定契约。
 * @returns {void} 路由或按钮权限漂移时测试失败。
 */
test('草稿路由和操作使用固定权限契约', () => {
  assert.match(routerSource, /path:\s*'\/workflow\/process-draft'[\s\S]*?permissions:\s*\['workflow:process:draftQuery'\]/)
  assert.match(routerSource, /name:\s*'WorkflowProcessDraftEdit'/)
  assert.match(listSource, /workflow:process:draftQuery/)
  assert.match(listSource, /workflow:process:draftRemove/)
  assert.match(startSource, /workflow:process:draftSave/)
  assert.match(startSource, /workflow:process:draftRemove/)
  assert.match(startSource, /workflow:process:draftSubmit/)
})

/**
 * 验证表单组件支持不触发正式必填的附件门禁和 DRAFT 引用解除。
 * @returns {void} 草稿附件被直接物理删除或保存绕过忙碌门禁时测试失败。
 */
test('草稿表单复用正式附件门禁和 DRAFT 生命周期', () => {
  assert.match(rendererSource, /async function ensureAttachmentsIdle\(\)/)
  assert.match(rendererSource, /defineExpose\(\{\s*ensureAttachmentsIdle,/)
  assert.match(attachmentSource, /\['DRAFT',\s*'BOUND'\]\.includes\(attachment\.status\)/)
  assert.match(attachmentSource, /deleteWorkflowAttachment\(attachment\.attachmentId\)/)
})
