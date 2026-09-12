const ALLOWED_TAGS = new Set([
  'el-input', 'el-input-number', 'el-select', 'el-cascader',
  'el-radio-group', 'el-checkbox-group', 'el-switch', 'el-slider',
  'el-time-picker', 'el-date-picker', 'el-rate', 'el-color-picker',
  'el-upload', 'tinymce', 'el-table', 'el-table-column', 'el-button'
])

const SAFE_PROP_NAMES = new Set([
  'type', 'placeholder', 'clearable', 'multiple', 'filterable', 'readonly',
  'disabled', 'maxlength', 'minlength', 'show-word-limit', 'min', 'max',
  'step', 'precision', 'controls-position', 'range', 'show-stops',
  'allow-half', 'show-alpha', 'color-format', 'format', 'value-format',
  'is-range', 'range-separator', 'start-placeholder', 'end-placeholder',
  'show-all-levels', 'collapse-tags', 'multiple-limit', 'limit', 'accept',
  'buttonText', 'showTip', 'fileSize', 'sizeUnit', 'rows', 'autosize',
  'optionType', 'border', 'height', 'stripe', 'show-overflow-tooltip'
])

/**
 * 为生成器输出创建正式权限完整的字段副本，默认字段为可见、可读、可写。
 * @param {object|object[]} source 单个生成器字段或字段数组。
 * @returns {object|object[]} 保留生成器字段并递归补齐三个布尔权限标志的新对象。
 */
export function completeWorkflowFieldPermissions(source) {
  if (Array.isArray(source)) return source.map(completeWorkflowFieldPermissions)
  if (!source || typeof source !== 'object') return source
  return {
    ...source,
    workflowHidden: typeof source.workflowHidden === 'boolean' ? source.workflowHidden : false,
    workflowReadable: typeof source.workflowReadable === 'boolean' ? source.workflowReadable : true,
    workflowWritable: typeof source.workflowWritable === 'boolean' ? source.workflowWritable : true,
    ...(Array.isArray(source.children)
      ? { children: source.children.map(completeWorkflowFieldPermissions) }
      : {})
  }
}

/** 只检查存量模板权限协议，不修改读取内容或填入默认权限。 */
export function templatePermissionIssues(content) {
  try {
    const root = typeof content === 'string' ? JSON.parse(content) : content
    if (!root || !Array.isArray(root.fields)) return ['表单 fields 必须是数组']
    const issues = []
    const visit = (fields, path) => fields.forEach((field, index) => {
      const position = `${path}[${index}]${field?.vModel ? ` (${field.vModel})` : ''}`
      const flags = ['workflowHidden', 'workflowReadable', 'workflowWritable']
      const missing = flags.filter(flag => typeof field?.[flag] !== 'boolean')
      if (missing.length) issues.push(`${position} 缺少 ${missing.join('、')}`)
      else if ((field.workflowHidden && (field.workflowReadable || field.workflowWritable || field.required))
        || (field.required && (!field.workflowReadable || !field.workflowWritable))) {
        issues.push(`${position} 节点权限组合不合法`)
      }
      if (Array.isArray(field?.children)) visit(field.children, `${path}[${index}].children`)
    })
    visit(root.fields, 'fields')
    return issues
  } catch {
    return ['表单 JSON 格式不合法']
  }
}

/**
 * 解析后端表单快照并规范为无执行逻辑的安全渲染描述。
 * @param {string|object} content 表单 JSON 文本或已解析对象。
 * @returns {{config: object, fields: object[]}} 表单级配置和字段描述。
 */
export function normalizeFormTemplate(content) {
  const root = typeof content === 'string' ? JSON.parse(content) : content
  if (!root || typeof root !== 'object' || !Array.isArray(root.fields)) {
    throw new Error('流程表单模板格式不合法')
  }
  return {
    config: {
      labelPosition: stringValue(root.labelPosition, 'right'),
      labelWidth: boundedNumber(root.labelWidth, 100, 40, 240),
      size: ['large', 'default', 'small'].includes(root.size) ? root.size : 'default',
      disabled: Boolean(root.disabled),
      gutter: boundedNumber(root.gutter, 16, 0, 40)
    },
    fields: root.fields.map(normalizeField)
  }
}

/**
 * 将单个正式 Vue 3 生成器组件规范为安全渲染描述。
 * @param {object} source 原始组件配置。
 * @returns {object} 只包含白名单属性的渲染描述。
 */
function normalizeField(source) {
  if (!source || typeof source !== 'object') throw new Error('流程表单字段格式不合法')
  if (Object.keys(source).some(key => key.startsWith('__'))) {
    throw new Error('流程表单包含已废止的内部生成器字段')
  }
  const tag = stringValue(source.tag, '')
  const layout = stringValue(source.layout, 'colFormItem')
  if (!['colFormItem', 'rowFormItem', 'raw'].includes(layout)) {
    throw new Error('流程表单布局不受支持')
  }
  if (layout !== 'rowFormItem' && !ALLOWED_TAGS.has(tag)) {
    throw new Error('流程表单组件不受支持')
  }
  const children = Array.isArray(source.children)
    ? source.children.map(normalizeField)
    : []
  const props = {}
  Object.entries(source).forEach(([key, value]) => {
    if (SAFE_PROP_NAMES.has(key) && isSafeValue(value)) props[key] = value
  })
  const options = source.options || []
  return {
    layout,
    tag,
    variable: stringValue(source.vModel, ''),
    label: stringValue(source.label, ''),
    span: boundedNumber(source.span, 24, 1, 24),
    labelWidth: boundedNumber(source.labelWidth, 0, 0, 240),
    required: source.required === true,
    hidden: source.workflowHidden === true,
    readable: source.workflowReadable === true,
    writable: source.workflowWritable === true,
    defaultValue: cloneJsonValue(source.defaultValue),
    props,
    options: normalizeOptions(options),
    children
  }
}

/**
 * 规范静态选项，拒绝函数、原型对象和任意动态请求配置。
 * @param {unknown} options 原始选项数组。
 * @returns {object[]} label/value/children 安全选项树。
 */
function normalizeOptions(options) {
  if (!Array.isArray(options)) return []
  return options.slice(0, 500).map(option => ({
    label: stringValue(option?.label, ''),
    value: cloneJsonValue(option?.value),
    children: normalizeOptions(option?.children)
  }))
}

/**
 * 判断属性值是否可以作为无执行能力的 Vue prop 使用。
 * @param {unknown} value 待检查值。
 * @returns {boolean} JSON 标量、数组或普通对象返回 true。
 */
function isSafeValue(value) {
  if (value === null || ['string', 'number', 'boolean', 'undefined'].includes(typeof value)) return true
  if (Array.isArray(value)) return value.every(isSafeValue)
  if (typeof value !== 'object' || Object.getPrototypeOf(value) !== Object.prototype) return false
  return Object.entries(value).every(([key, item]) => !['__proto__', 'prototype', 'constructor'].includes(key) && isSafeValue(item))
}

/**
 * 通过 JSON 复制字段默认值并拒绝非 JSON 类型。
 * @param {unknown} value 原始默认值。
 * @returns {unknown} 可安全进入响应式表单的数据副本。
 */
function cloneJsonValue(value) {
  if (value === undefined) return undefined
  if (!isSafeValue(value)) throw new Error('流程表单字段默认值不合法')
  return JSON.parse(JSON.stringify(value))
}

/**
 * 返回受边界限制的有限数字。
 * @param {unknown} value 原始数值。
 * @param {number} fallback 非法时默认值。
 * @param {number} minimum 最小值。
 * @param {number} maximum 最大值。
 * @returns {number} 边界内数字。
 */
function boundedNumber(value, fallback, minimum, maximum) {
  const number = Number(value)
  return Number.isFinite(number) ? Math.min(maximum, Math.max(minimum, number)) : fallback
}

/**
 * 将可选值规范为有长度上限的字符串。
 * @param {unknown} value 原始值。
 * @param {string} fallback 非字符串时默认值。
 * @returns {string} 最多 512 个字符的文本。
 */
function stringValue(value, fallback) {
  return typeof value === 'string' ? value.slice(0, 512) : fallback
}

/**
 * 递归收集所有具备变量名的字段描述。
 * @param {object[]} fields 已规范字段树。
 * @returns {object[]} 按模板顺序排列的业务字段。
 */
export function flattenFormFields(fields) {
  const result = []

  /**
   * 按模板顺序深度遍历字段树，并把具备业务变量名的节点加入结果。
   * @param {object[]} nodes 当前层已经过规范化的字段节点。
   * @returns {void} 遍历结果直接写入外层 result 数组。
   */
  function visit(nodes) {
    nodes.forEach(node => {
      if (node.variable) result.push(node)
      if (node.children.length) visit(node.children)
    })
  }

  visit(fields || [])
  return result
}
