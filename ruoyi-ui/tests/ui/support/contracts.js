import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const WORKFLOW_ROLE_KEYS = Object.freeze([
  'workflow_admin',
  'workflow_designer',
  'workflow_starter',
  'workflow_approver',
  'workflow_auditor'
])

export const WORKFLOW_ROUTE_CONTRACTS = Object.freeze([
  route('category', '/workflow/category', '/workflow/category/list', ['workflow_admin', 'workflow_designer']),
  route('form', '/workflow/form', '/workflow/form/list', ['workflow_admin', 'workflow_designer']),
  route('model', '/workflow/model', '/workflow/model/list', ['workflow_admin', 'workflow_designer']),
  route('deploy', '/workflow/deploy', '/workflow/deploy/list', ['workflow_admin', 'workflow_designer']),
  route('extension', '/workflow/extensions/extension', '/workflow/extension/list', ['workflow_admin', 'workflow_designer']),
  route('connector', '/workflow/extensions/connector', '/workflow/connector/list', ['workflow_admin', 'workflow_designer']),
  route('sqlDatasource', '/workflow/extensions/sqlDatasource', '/workflow/sql-datasource/list', ['workflow_admin', 'workflow_designer']),
  route('integrationCredential', '/workflow/extensions/integrationCredential', '/workflow/integration-credential/list', ['workflow_admin']),
  route('dmn', '/workflow/extensions/dmn', '/workflow/dmn/list', ['workflow_admin', 'workflow_designer']),
  route('runtimeEvent', '/workflow/extensions/runtimeEvent', '/workflow/runtime-event-audit/list', ['workflow_admin', 'workflow_auditor']),
  route('collaboration', '/workflow/extensions/collaboration', '/workflow/collaboration/inbound', ['workflow_admin', 'workflow_auditor']),
  route('bpmnEvent', '/workflow/extensions/bpmnEvent', '/workflow/bpmn-event/codes', ['workflow_admin', 'workflow_designer', 'workflow_auditor']),
  route('manage', '/workflow/extensions/instance', '/workflow/process/manageList', ['workflow_admin']),
  route('notification', '/workflow/notification', '/workflow/notification/policies', ['workflow_admin', 'workflow_designer']),
  route('start', '/office/create', '/workflow/process/list', ['workflow_admin', 'workflow_starter']),
  route('draft', '/office/draft', '/workflow/process/draft/list', ['workflow_admin', 'workflow_starter']),
  route('own', '/office/own', '/workflow/process/ownList', ['workflow_admin', 'workflow_starter', 'workflow_auditor']),
  route('todo', '/office/todo', '/workflow/process/todoList', ['workflow_admin', 'workflow_approver', 'workflow_auditor']),
  route('claim', '/office/claim', '/workflow/process/claimList', ['workflow_admin', 'workflow_approver', 'workflow_auditor']),
  route('finished', '/office/finished', '/workflow/process/finishedList', ['workflow_admin', 'workflow_approver', 'workflow_auditor']),
  route('copy', '/office/copy', '/workflow/process/copyList', ['workflow_admin', 'workflow_starter', 'workflow_approver', 'workflow_auditor'])
])

export const ROLE_REQUIRED_PERMISSIONS = Object.freeze({
  workflow_designer: Object.freeze([
    'workflow:category:add', 'workflow:category:edit', 'workflow:category:remove', 'workflow:category:export',
    'workflow:form:add', 'workflow:form:edit', 'workflow:form:remove', 'workflow:form:export',
    'workflow:model:add', 'workflow:model:edit', 'workflow:model:remove', 'workflow:model:export',
    'workflow:model:designer', 'workflow:model:save', 'workflow:model:deploy',
    'workflow:deploy:query', 'workflow:deploy:remove', 'workflow:deploy:state',
    'workflow:sqlDatasource:add', 'workflow:sqlDatasource:edit',
    'workflow:dmn:add', 'workflow:dmn:remove',
    'workflow:bpmnEvent:add', 'workflow:bpmnEvent:edit', 'workflow:bpmnEvent:audit',
    'workflow:sla:list', 'workflow:sla:add', 'workflow:sla:edit', 'workflow:sla:audit',
    'workflow:notification:manage', 'workflow:notification:audit'
  ]),
  workflow_starter: Object.freeze([
    'workflow:process:start', 'workflow:process:startExport', 'workflow:process:draftQuery',
    'workflow:process:draftSave', 'workflow:process:draftRemove', 'workflow:process:draftSubmit',
    'workflow:process:query', 'workflow:process:cancel', 'workflow:process:ownExport',
    'workflow:attachment:upload', 'workflow:attachment:query', 'workflow:attachment:remove',
    'workflow:notification:list', 'workflow:notification:urge'
  ]),
  workflow_approver: Object.freeze([
    'workflow:process:query', 'workflow:process:approval', 'workflow:process:claim',
    'workflow:process:revoke', 'workflow:process:todoExport', 'workflow:process:claimExport',
    'workflow:process:finishedExport', 'workflow:process:copyExport',
    'workflow:attachment:upload', 'workflow:attachment:query', 'workflow:attachment:remove',
    'workflow:notification:list', 'workflow:sla:notification'
  ]),
  workflow_auditor: Object.freeze([
    'workflow:process:query', 'workflow:process:ownExport', 'workflow:process:todoExport',
    'workflow:process:claimExport', 'workflow:process:finishedExport', 'workflow:process:copyExport',
    'workflow:attachment:query', 'workflow:collaboration:audit', 'workflow:bpmnEvent:audit',
    'workflow:bpmnEvent:notification', 'workflow:sla:list', 'workflow:sla:audit',
    'workflow:sla:notification', 'workflow:notification:list'
  ])
})

/**
 * 创建不可变页面权限契约。
 * @param {string} key 页面稳定键。
 * @param {string} pagePath 浏览器路由。
 * @param {string} endpoint 页面首个只读业务接口。
 * @param {string[]} roles 允许看到菜单并进入页面的角色。
 * @returns {Readonly<object>} 页面权限契约。
 */
function route(key, pagePath, endpoint, roles) {
  return Object.freeze({ key, path: pagePath, endpoint, roles: Object.freeze(roles) })
}

/**
 * 从正式菜单 SQL 提取全部按钮权限，作为 75 个权限点的独立源码基线。
 * @returns {string[]} 排序去重后的按钮权限字符。
 */
export function loadWorkflowButtonPermissions() {
  const currentDirectory = path.dirname(fileURLToPath(import.meta.url))
  const sqlPath = path.resolve(currentDirectory, '../../../../sql/flowable/menu/8.0.0__workflow_menu.sql')
  const source = fs.readFileSync(sqlPath, 'utf8')
  const permissions = [...source.matchAll(/'F',\s*'(workflow:[^']+)'/gu)].map(match => match[1])
  return [...new Set(permissions)].sort()
}

/**
 * 判断当前角色是否允许进入指定页面。
 * @param {{roles:ReadonlyArray<string>}} contract 页面权限契约。
 * @param {string} roleKey 当前职责角色。
 * @returns {boolean} 允许进入时返回 true。
 */
export function isRouteAllowed(contract, roleKey) {
  return contract.roles.includes(roleKey)
}
