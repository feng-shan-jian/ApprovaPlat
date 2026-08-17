import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createEmbeddedUserIdFieldCatalog,
  createProcessUserIdFieldCatalog,
  createTemplateUserIdFieldCatalog,
  eligibleUserIdFieldOptions,
  mergeUserIdFieldCatalogs,
  normalizeEmbeddedFormType,
  participantUserIdFieldOptions
} from '../../src/components/workflow/designer/formUserFieldCatalog.js'
/**
 * 创建正式模板字段。
 * @param {string} variable 业务变量名。
 * @param {string} tag Element Plus 组件标签。
 * @param {object} options 权限及组件形态补丁。
 * @returns {object} 可用于字段目录测试的正式模板组件。
 */
function templateField(variable, tag, options = {}) {
  const { config = {}, ...component } = options
  return {
    __vModel__: variable,
    __config__: {
      label: variable,
      tag,
      workflowReadable: true,
      workflowWritable: true,
      ...config
    },
    ...component
  }
}

/**
 * 验证正式模板目录允许只读单值字段，并排除隐藏、不可读、集合、日期和附件。
 * @returns {void} 前后端字段资格发生漂移时断言失败。
 */
test('正式模板表单用户字段与后端可见可读单值契约一致', () => {
  const content = JSON.stringify({ fields: [
    templateField('hiddenUser', 'el-input', { config: { workflowHidden: true } }),
    templateField('unreadableUser', 'el-input', { config: { workflowReadable: false } }),
    templateField('readonlyUser', 'el-input', { config: { workflowWritable: false } }),
    templateField('numberUser', 'el-input-number'),
    templateField('radioUser', 'el-radio-group'),
    templateField('selectUser', 'el-select'),
    templateField('multiUser', 'el-select', { multiple: true }),
    templateField('attachmentUser', 'el-upload'),
    templateField('dateUser', 'el-date-picker')
  ] })

  assert.deepEqual(eligibleUserIdFieldOptions(createTemplateUserIdFieldCatalog(content))
    .map(field => field.value), [
    'readonlyUser', 'numberUser', 'radioUser', 'selectUser'
  ])
})

/**
 * 验证 BPMN 节点四态权限覆盖模板原权限，与后端权限化快照保持一致。
 * @returns {void} 节点隐藏未生效或节点只读未恢复可读资格时断言失败。
 */
test('节点字段权限覆盖模板原始可见性', () => {
  const content = JSON.stringify({ fields: [
    templateField('hiddenByNode', 'el-input'),
    templateField('readonlyByNode', 'el-input', { config: { workflowHidden: true } })
  ] })
  const policy = {
    configured: true,
    defaultMode: 'EDITABLE',
    permissions: new Map([
      ['hiddenByNode', 'HIDDEN'],
      ['readonlyByNode', 'READONLY']
    ])
  }

  assert.deepEqual(eligibleUserIdFieldOptions(
    createTemplateUserIdFieldCatalog(content, policy)).map(field => field.value), ['readonlyByNode'])
})

/**
 * 验证内嵌 FormProperty 使用 readable 和 field.type 等价收窄，不再依赖 writable。
 * @returns {void} 只读字段遗漏或布尔、日期字段被错误放行时断言失败。
 */
test('内嵌表单用户字段按可读性和单值类型收窄', () => {
  assert.equal(normalizeEmbeddedFormType('CuStOm:ManagerDirectory'), 'custom:ManagerDirectory')
  const catalog = createEmbeddedUserIdFieldCatalog([
    { id: 'readonly', name: '只读用户', type: 'string', readable: true, writable: false },
    { id: 'unreadable', type: 'string', readable: false, writable: true },
    { id: 'longUser', type: 'long', readable: true },
    { id: 'integerUser', type: 'integer', readable: true },
    { id: 'enumUser', type: 'enum', readable: true },
    { id: 'booleanUser', type: 'boolean', readable: true },
    { id: 'dateUser', type: 'date', readable: true },
    { id: 'customUser', type: 'custom:textarea', readable: true },
    { id: 'upperCustomUser', type: 'custom:ManagerDirectory', readable: true }
  ], ['custom:textarea', 'custom:ManagerDirectory'])

  assert.deepEqual(eligibleUserIdFieldOptions(catalog).map(field => field.value), [
    'readonly', 'longUser', 'integerUser', 'enumUser', 'customUser', 'upperCustomUser'
  ])
})

/**
 * 验证流程级自动抄送不会用其他节点的合格同名字段放宽隐藏或复合声明。
 * @returns {void} 同名冲突未失败关闭时断言失败。
 */
test('流程级字段并集对任一节点不合格同名声明失败关闭', () => {
  const readable = createTemplateUserIdFieldCatalog(JSON.stringify({ fields: [
    templateField('managerId', 'el-input'),
    templateField('ownerId', 'el-input')
  ] }))
  const hiddenConflict = createTemplateUserIdFieldCatalog(JSON.stringify({ fields: [
    templateField('managerId', 'el-input', { config: { workflowHidden: true } })
  ] }))
  const typeConflict = createTemplateUserIdFieldCatalog(JSON.stringify({ fields: [
    templateField('ownerId', 'el-input-number')
  ] }))

  assert.deepEqual(eligibleUserIdFieldOptions(
    mergeUserIdFieldCatalogs([readable, hiddenConflict, typeConflict]))
    .map(field => field.value), [])
})

/**
 * 验证流程级目录只收集顶层开始表单和所有用户任务，不放宽嵌入子流程开始节点。
 * @returns {void} 前端再次展示后端不冻结的子流程开始字段时断言失败。
 */
test('流程级目录排除子流程开始节点并保留子流程用户任务', () => {
  const field = (value, signature = 'el-input') => [{ value, label: value, eligible: true, signature }]
  const process = {
    $type: 'bpmn:Process',
    flowElements: [
      { $type: 'bpmn:StartEvent', fieldCatalog: field('topStarterId') },
      { $type: 'bpmn:UserTask', fieldCatalog: field('topReviewerId') },
      {
        $type: 'bpmn:SubProcess',
        flowElements: [
          { $type: 'bpmn:StartEvent', fieldCatalog: field('nestedStarterId') },
          { $type: 'bpmn:UserTask', fieldCatalog: field('nestedReviewerId') }
        ]
      }
    ]
  }

  assert.deepEqual(eligibleUserIdFieldOptions(
    createProcessUserIdFieldCatalog(process, element => element.fieldCatalog))
    .map(option => option.value), ['topStarterId', 'topReviewerId', 'nestedReviewerId'])
})

/**
 * 验证 FORM_USER 选项额外应用参与者后端变量名语法，而不是接受任意模板变量。
 * @returns {void} 中文、点号、连字符或超长变量进入动态审批人目录时断言失败。
 */
test('动态审批人目录使用后端参与者变量名语法', () => {
  const fields = [
    templateField('_reviewer_1', 'el-input'),
    templateField('reviewer.id', 'el-input'),
    templateField('reviewer-id', 'el-input'),
    templateField('审批人', 'el-input'),
    templateField(`u${'a'.repeat(128)}`, 'el-input')
  ]

  assert.deepEqual(participantUserIdFieldOptions(
    createTemplateUserIdFieldCatalog(JSON.stringify({ fields })))
    .map(option => option.value), ['_reviewer_1'])
})
