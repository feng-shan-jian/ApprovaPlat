import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const componentSource = readFileSync(
  new URL('../../src/layout/components/HeaderNotice/index.vue', import.meta.url), 'utf8')
const documentationSource = readFileSync(
  new URL('../../src/layout/components/HeaderNotice/index.md', import.meta.url), 'utf8')

/**
 * 验证审批通知页签和偏好入口分别使用固定权限字符。
 * @returns {void} 权限字符、可见性或默认页签漂移时测试失败。
 */
test('HeaderNotice 使用收件箱与偏好两级权限控制用户入口', () => {
  assert.match(componentSource,
    /canReadWorkflowNotifications\s*=\s*computed\(\(\)\s*=>\s*proxy\.\$auth\.hasPermi\('workflow:notification:list'\)\)/)
  assert.match(componentSource,
    /canManageWorkflowPreference\s*=\s*computed\(\(\)\s*=>[\s\S]*?canReadWorkflowNotifications\.value\s*&&\s*proxy\.\$auth\.hasPermi\('workflow:notification:preference'\)/)
  assert.match(componentSource, /<el-tab-pane\s+v-if="canReadWorkflowNotifications"\s+name="workflow">/)
  assert.match(componentSource, /<el-tooltip\s+v-if="canManageWorkflowPreference"[^>]+content="通知偏好"/)
  assert.match(componentSource,
    /activeTab\s*=\s*ref\(canReadWorkflowNotifications\.value\s*\?\s*'workflow'\s*:\s*'announcement'\)/)
})

/**
 * 验证无收件箱权限时刷新仅查询公告，并清空已有审批通知投影。
 * @returns {void} 无权限分支可能访问收件箱或遗漏公告刷新时测试失败。
 */
test('HeaderNotice 无收件箱权限时保持审批通知零调用', () => {
  assert.match(componentSource,
    /async function loadWorkflowNotices\(\)\s*{\s*if \(!canReadWorkflowNotifications\.value\)\s*{\s*clearWorkflowNoticeState\(\)\s*return\s*}/)
  assert.match(componentSource,
    /async function refreshAll\(\)[\s\S]*?const refreshTasks = \[loadAnnouncements\(\)\][\s\S]*?if \(canReadWorkflowNotifications\.value\)\s*{\s*refreshTasks\.push\(loadWorkflowNotices\(\)\)\s*}\s*else\s*{\s*clearWorkflowNoticeState\(\)/)
  assert.match(componentSource,
    /function clearWorkflowNoticeState\(\)[\s\S]*?workflowNotices\.value\s*=\s*\[\][\s\S]*?workflowUnread\.value\s*=\s*0/)
  assert.doesNotMatch(componentSource, /onMounted\(loadWorkflowNotices\)/)
})

/**
 * 验证所有审批通知写操作在调用正式 API 前执行收件箱权限门禁。
 * @returns {void} 点击通知或全部已读可绕过权限时测试失败。
 */
test('HeaderNotice 审批通知动作在 API 调用前校验收件箱权限', () => {
  assert.match(componentSource,
    /async function openWorkflowNotice\(item\)\s*{\s*if \(!canReadWorkflowNotifications\.value\) return[\s\S]*?markWorkflowNotificationRead\(/)
  assert.match(componentSource,
    /async function markWorkflowAllRead\(\)\s*{\s*if \(!canReadWorkflowNotifications\.value\) return[\s\S]*?markAllWorkflowNotificationsRead\(/)
})

/**
 * 验证通知偏好读取和保存均在正式 API 调用前执行双权限门禁。
 * @returns {void} 任一偏好请求可绕过权限时测试失败。
 */
test('HeaderNotice 通知偏好读写在 API 调用前校验双权限', () => {
  assert.match(componentSource,
    /async function openPreference\(\)\s*{\s*if \(!canManageWorkflowPreference\.value\)[\s\S]*?return\s*}[\s\S]*?getWorkflowNotificationPreference\(/)
  assert.match(componentSource,
    /async function savePreference\(\)\s*{\s*if \(!canManageWorkflowPreference\.value\)[\s\S]*?return\s*}[\s\S]*?saveWorkflowNotificationPreference\(/)
})

/**
 * 验证权限约束已写入组件契约，且业务状态未退化为浏览器本地存储。
 * @returns {void} 文档缺失权限说明或出现本地状态替代时测试失败。
 */
test('HeaderNotice 文档声明权限边界且不使用本地业务状态', () => {
  assert.match(documentationSource, /workflow:notification:list/)
  assert.match(documentationSource, /workflow:notification:preference/)
  assert.doesNotMatch(componentSource, /localStorage|sessionStorage/)
})
