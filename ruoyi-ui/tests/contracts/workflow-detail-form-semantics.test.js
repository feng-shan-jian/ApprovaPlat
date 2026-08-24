import assert from 'node:assert/strict'
import test from 'node:test'
import { normalizeDetailFormSemantics } from '../../src/views/workflow/work/detailFormSemantics.js'

const START_FORM = Object.freeze({
  taskId: null,
  sourceType: 'TEMPLATE',
  formId: 1,
  formKey: 'startForm',
  nodeKey: 'start'
})

/**
 * 创建最小详情响应，允许测试只覆盖表单业务语义。
 * @param {object} overrides 需要覆盖的响应字段。
 * @returns {object} 具有活动任务和唯一开始快照的详情响应。
 */
function detail(overrides = {}) {
  return {
    processStatus: 'running',
    currentTask: { taskId: 'task-current', active: true },
    currentTaskForm: null,
    processFormList: [START_FORM],
    ...overrides
  }
}

test('无节点表单时默认申请表单且开始快照不进入历史表单', () => {
  const result = normalizeDetailFormSemantics(detail({
    processFormList: [START_FORM, { taskId: 'task-old', nodeKey: 'oldReview' }]
  }))

  assert.equal(result.defaultTab, 'applicationForm')
  assert.equal(result.applicationForm, START_FORM)
  assert.equal(result.nodeTaskForm, null)
  assert.deepEqual(result.historyForms.map(form => form.taskId), ['task-old'])
})

test('有节点表单时保留申请表单并默认本节点办理表单', () => {
  const nodeForm = { taskId: 'task-current', nodeKey: 'review' }
  const result = normalizeDetailFormSemantics(detail({ currentTaskForm: nodeForm }))

  assert.equal(result.defaultTab, 'nodeTaskForm')
  assert.equal(result.applicationForm, START_FORM)
  assert.equal(result.nodeTaskForm, nodeForm)
})

test('退回任务使用修改申请表单且不生成本节点办理表单', () => {
  const editable = { ...START_FORM, taskId: 'task-current' }
  const result = normalizeDetailFormSemantics(detail({
    processStatus: 'returned',
    currentTaskForm: editable
  }))

  assert.equal(result.defaultTab, 'applicationForm')
  assert.equal(result.returnedApplication, true)
  assert.equal(result.applicationForm, editable)
  assert.equal(result.nodeTaskForm, null)
  assert.deepEqual(result.historyForms, [])
})

test('申请快照缺失时只允许默认流程图并显示缺失状态', () => {
  const nodeForm = { taskId: 'task-current', nodeKey: 'review' }
  const result = normalizeDetailFormSemantics(detail({
    processFormList: [],
    currentTaskForm: nodeForm
  }))

  assert.equal(result.defaultTab, 'diagram')
  assert.equal(result.applicationMissing, true)
  assert.equal(result.applicationForm, null)
  assert.equal(result.nodeTaskForm, nodeForm)
})

test('多个开始快照或退回投影串线时失败关闭', () => {
  assert.throws(() => normalizeDetailFormSemantics(detail({
    processFormList: [START_FORM, { ...START_FORM }]
  })), /申请表单快照不唯一/)
  assert.throws(() => normalizeDetailFormSemantics(detail({
    processStatus: 'returned',
    currentTaskForm: { ...START_FORM, taskId: 'task-current', nodeKey: 'other' }
  })), /退回申请表单快照关系不一致/)
})
