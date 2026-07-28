export const WORKFLOW_ROUTE_CONTRACTS = Object.freeze([
  Object.freeze({
    key: 'category', path: '/workflow/category', endpoint: '/workflow/category/list',
    roles: Object.freeze(['workflow_admin', 'workflow_designer'])
  }),
  Object.freeze({
    key: 'form', path: '/workflow/form', endpoint: '/workflow/form/list',
    roles: Object.freeze(['workflow_admin', 'workflow_designer'])
  }),
  Object.freeze({
    key: 'model', path: '/workflow/model', endpoint: '/workflow/model/list',
    roles: Object.freeze(['workflow_admin', 'workflow_designer'])
  }),
  Object.freeze({
    key: 'deploy', path: '/workflow/deploy', endpoint: '/workflow/deploy/list',
    roles: Object.freeze(['workflow_admin', 'workflow_designer'])
  }),
  Object.freeze({
    key: 'manage', path: '/workflow/instance', endpoint: '/workflow/process/manageList',
    roles: Object.freeze(['workflow_admin'])
  }),
  Object.freeze({
    key: 'start', path: '/office/create', endpoint: '/workflow/process/list',
    roles: Object.freeze(['workflow_admin', 'workflow_starter'])
  }),
  Object.freeze({
    key: 'own', path: '/office/own', endpoint: '/workflow/process/ownList',
    roles: Object.freeze(['workflow_admin', 'workflow_starter', 'workflow_auditor'])
  }),
  Object.freeze({
    key: 'todo', path: '/office/todo', endpoint: '/workflow/process/todoList',
    roles: Object.freeze(['workflow_admin', 'workflow_approver', 'workflow_auditor'])
  }),
  Object.freeze({
    key: 'claim', path: '/office/claim', endpoint: '/workflow/process/claimList',
    roles: Object.freeze(['workflow_admin', 'workflow_approver', 'workflow_auditor'])
  }),
  Object.freeze({
    key: 'finished', path: '/office/finished', endpoint: '/workflow/process/finishedList',
    roles: Object.freeze(['workflow_admin', 'workflow_approver', 'workflow_auditor'])
  }),
  Object.freeze({
    key: 'copy', path: '/office/copy', endpoint: '/workflow/process/copyList',
    roles: Object.freeze(['workflow_admin', 'workflow_starter', 'workflow_approver', 'workflow_auditor'])
  })
])

export const WORKFLOW_EXPORT_CONTRACTS = Object.freeze([
  Object.freeze({
    key: 'category', roleKey: 'workflow_designer', path: '/workflow/category',
    listEndpoint: '/workflow/category/list', exportEndpoint: '/workflow/category/export',
    filenamePrefix: 'workflow_category_', filterPlaceholder: '请输入分类名称', filterField: 'categoryName',
    filterHeader: '分类名称', headers: Object.freeze(['分类ID', '分类名称', '分类编码', '备注'])
  }),
  Object.freeze({
    key: 'form', roleKey: 'workflow_designer', path: '/workflow/form',
    listEndpoint: '/workflow/form/list', exportEndpoint: '/workflow/form/export',
    filenamePrefix: 'workflow_form_', filterPlaceholder: '请输入表单名称', filterField: 'formName',
    filterHeader: '表单名称', headers: Object.freeze(['表单ID', '表单名称', '备注'])
  }),
  Object.freeze({
    key: 'model', roleKey: 'workflow_designer', path: '/workflow/model',
    listEndpoint: '/workflow/model/list', exportEndpoint: '/workflow/model/export',
    filenamePrefix: 'workflow_model_', filterPlaceholder: '请输入模型名称', filterField: 'modelName',
    filterHeader: '模型名称', headers: Object.freeze(['模型ID', '模型Key', '模型名称', '分类编码', '流程分类', '模型版本', '模型描述', '创建时间'])
  }),
  Object.freeze({
    key: 'start', roleKey: 'workflow_starter', path: '/office/create',
    listEndpoint: '/workflow/process/list', exportEndpoint: '/workflow/process/startExport',
    filenamePrefix: 'startable_processes_', filterPlaceholder: '请输入流程名称', filterField: 'processName',
    filterHeader: '流程名称', headers: Object.freeze(['流程定义ID', '流程名称', '流程Key', '分类编码', '版本', '部署ID', '部署时间'])
  }),
  Object.freeze({
    key: 'own', roleKey: 'workflow_starter', path: '/office/own',
    listEndpoint: '/workflow/process/ownList', exportEndpoint: '/workflow/process/ownExport',
    filenamePrefix: 'owned_processes_', filterPlaceholder: '请输入流程名称', filterField: 'processName',
    filterHeader: '流程名称', headers: Object.freeze(['流程实例ID', '流程名称', '分类编码', '流程版本', '提交时间', '完成时间', '流程状态', '耗时毫秒', '当前节点'])
  }),
  Object.freeze({
    key: 'manage', roleKey: 'workflow_admin', path: '/workflow/instance',
    listEndpoint: '/workflow/process/manageList', exportEndpoint: '/workflow/process/manageExport',
    filenamePrefix: 'managed_processes_', filterPlaceholder: '请输入流程名称', filterField: 'processName',
    filterHeader: '流程名称', headers: Object.freeze(['流程实例ID', '流程名称', '分类编码', '流程版本', '业务主键', '发起人ID', '发起人', '提交时间', '完成时间', '流程状态', '耗时毫秒', '当前节点'])
  }),
  Object.freeze({
    key: 'todo', roleKey: 'workflow_approver', path: '/office/todo',
    listEndpoint: '/workflow/process/todoList', exportEndpoint: '/workflow/process/todoExport',
    filenamePrefix: 'todo_tasks_', filterPlaceholder: '请输入流程名称', filterField: 'processName',
    filterHeader: '流程名称', headers: Object.freeze(['任务ID', '流程名称', '任务节点', '流程版本', '流程发起人', '接收时间', '到期时间'])
  }),
  Object.freeze({
    key: 'claim', roleKey: 'workflow_approver', path: '/office/claim',
    listEndpoint: '/workflow/process/claimList', exportEndpoint: '/workflow/process/claimExport',
    filenamePrefix: 'claimable_tasks_', filterPlaceholder: '请输入流程名称', filterField: 'processName',
    filterHeader: '流程名称', headers: Object.freeze(['任务ID', '流程名称', '任务节点', '流程版本', '流程发起人', '接收时间', '到期时间'])
  }),
  Object.freeze({
    key: 'finished', roleKey: 'workflow_approver', path: '/office/finished',
    listEndpoint: '/workflow/process/finishedList', exportEndpoint: '/workflow/process/finishedExport',
    filenamePrefix: 'finished_tasks_', filterPlaceholder: '请输入流程名称', filterField: 'processName',
    filterHeader: '流程名称', headers: Object.freeze(['任务ID', '流程名称', '任务节点', '流程版本', '流程发起人', '真实完成人ID', '接收时间', '完成时间', '耗时毫秒'])
  }),
  Object.freeze({
    key: 'copy', roleKey: 'workflow_auditor', path: '/office/copy',
    listEndpoint: '/workflow/process/copyList', exportEndpoint: '/workflow/process/copyExport',
    filenamePrefix: 'copied_processes_', filterPlaceholder: '请输入抄送标题', filterField: 'title',
    filterHeader: '抄送标题', headers: Object.freeze(['抄送ID', '抄送标题', '流程名称', '分类编码', '部署ID', '流程实例ID', '任务ID', '发起人ID', '发起人名称', '抄送时间'])
  })
])

/**
 * 判断指定职责分离角色是否允许访问页面契约。
 * @param {{roles: ReadonlyArray<string>}} contract 页面契约。
 * @param {string} roleKey 当前登录角色键。
 * @returns {boolean} true 表示菜单和动态路由都应存在。
 */
export function isRouteAllowed(contract, roleKey) {
  return contract.roles.includes(roleKey)
}
