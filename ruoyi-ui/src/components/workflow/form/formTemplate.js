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
 * 将单个旧版或 Vue 3 生成器组件规范为统一字段描述。
 * @param {object} source 原始组件配置。
 * @returns {object} 只包含白名单属性的渲染描述。
 */
function normalizeField(source) {
  if (!source || typeof source !== 'object') throw new Error('流程表单字段格式不合法')
  const legacyConfig = source.__config__ && typeof source.__config__ === 'object'
    ? source.__config__
    : source
  const tag = stringValue(legacyConfig.tag || source.tag, '')
  const layout = stringValue(legacyConfig.layout || source.layout, 'colFormItem')
  if (!['colFormItem', 'rowFormItem', 'raw'].includes(layout)) {
    throw new Error('流程表单布局不受支持')
  }
  if (layout !== 'rowFormItem' && !ALLOWED_TAGS.has(tag)) {
    throw new Error('流程表单组件不受支持')
  }
  const children = Array.isArray(legacyConfig.children)
    ? legacyConfig.children.map(normalizeField)
    : []
  const props = {}
  Object.entries(source).forEach(([key, value]) => {
    if (SAFE_PROP_NAMES.has(key) && isSafeValue(value)) props[key] = value
  })
  const options = source.__slot__?.options || source.options || []
  return {
    layout,
    tag,
    variable: stringValue(source.__vModel__ || source.vModel, ''),
    label: stringValue(legacyConfig.label || source.label, ''),
    span: boundedNumber(legacyConfig.span ?? source.span, 24, 1, 24),
    labelWidth: boundedNumber(legacyConfig.labelWidth ?? source.labelWidth, 0, 0, 240),
    required: Boolean(legacyConfig.required ?? source.required),
    hidden: legacyConfig.workflowHidden === true,
    readable: legacyConfig.workflowReadable !== false,
    writable: legacyConfig.workflowWritable !== false,
    defaultValue: cloneJsonValue(legacyConfig.defaultValue ?? source.defaultValue),
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

/**
 * 将旧项目正式表单模板转换为当前 Vue 3 生成器的平面组件结构。
 * @param {string|object} content 旧版表单 JSON 文本或对象。
 * @returns {object} 可直接交给现有拖拽生成器的表单配置。
 */
export function legacyTemplateToBuilder(content) {
  const root = typeof content === 'string' ? JSON.parse(content) : cloneJsonValue(content)
  if (!root || typeof root !== 'object' || !Array.isArray(root.fields)) {
    throw new Error('流程表单模板格式不合法')
  }
  return {
    ...root,
    fields: root.fields.map(legacyFieldToBuilder)
  }
}

/**
 * 将当前 Vue 3 生成器配置转换为后端和旧流程兼容的正式模板。
 * @param {object} builderTemplate 生成器表单配置和 fields。
 * @returns {object} 使用 __config__/__vModel__ 的可持久化模板。
 */
export function builderTemplateToLegacy(builderTemplate) {
  if (!builderTemplate || typeof builderTemplate !== 'object'
      || !Array.isArray(builderTemplate.fields)) {
    throw new Error('流程表单设计数据不完整')
  }
  const root = cloneJsonValue(builderTemplate)
  root.fields = builderTemplate.fields.map(builderFieldToLegacy)
  return root
}

/**
 * 转换单个旧版字段为生成器字段，并递归处理行容器。
 * @param {object} source 旧版字段。
 * @returns {object} Vue 3 生成器字段。
 */
function legacyFieldToBuilder(source) {
  const config = source.__config__ || {}
  const slot = source.__slot__ || {}
  const result = {}
  Object.entries(source).forEach(([key, value]) => {
    if (!['__config__', '__slot__', '__vModel__'].includes(key)) result[key] = cloneJsonValue(value)
  })
  Object.assign(result, cloneJsonValue(config))
  if (source.__vModel__) result.vModel = source.__vModel__
  if (Array.isArray(slot.options)) result.options = cloneJsonValue(slot.options)
  if (Array.isArray(config.children)) result.children = config.children.map(legacyFieldToBuilder)
  return result
}

/**
 * 转换单个生成器字段为旧版正式字段，并递归处理行容器。
 * @param {object} source Vue 3 生成器字段。
 * @returns {object} 后端允许持久化的旧版字段。
 */
function builderFieldToLegacy(source) {
  if (!source || typeof source !== 'object') throw new Error('流程表单字段格式不合法')
  const configKeys = new Set([
    'label', 'labelWidth', 'showLabel', 'changeTag', 'tag', 'tagIcon',
    'defaultValue', 'required', 'layout', 'span', 'document', 'regList',
    'formId', 'renderKey', 'componentName', 'gutter', 'justify', 'align', 'children'
  ])
  const config = {}
  const field = {}
  Object.entries(source).forEach(([key, value]) => {
    if (key === 'vModel' || key === 'options') return
    if (configKeys.has(key)) config[key] = cloneJsonValue(value)
    else field[key] = cloneJsonValue(value)
  })
  config.layout = config.layout || 'colFormItem'
  if (config.layout !== 'rowFormItem' && !config.tag) {
    throw new Error('流程表单字段组件类型不能为空')
  }
  if (Array.isArray(source.children)) config.children = source.children.map(builderFieldToLegacy)
  field.__config__ = config
  if (source.vModel) field.__vModel__ = source.vModel
  if (Array.isArray(source.options)) field.__slot__ = { options: cloneJsonValue(source.options) }
  return field
}
