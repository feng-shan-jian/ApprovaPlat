const TEMPLATE_USER_ID_TAGS = new Set([
  'el-input', 'el-input-number', 'el-radio-group', 'el-select'
])

const EMBEDDED_USER_ID_TYPES = new Set(['string', 'long', 'integer', 'enum'])
const FORM_PERMISSION_MODES = new Set(['HIDDEN', 'READONLY', 'EDITABLE', 'REQUIRED'])
const PARTICIPANT_FORM_VARIABLE_PATTERN = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/

/**
 * 规范内嵌表单字段类型，同时保留大小写敏感的正式扩展键。
 * @param {unknown} type Flowable FormProperty 原始类型。
 * @returns {string} 内置类型小写；自定义类型固定 `custom:` 前缀并保留扩展键原文。
 */
export function normalizeEmbeddedFormType(type) {
  const value = String(type || 'string').trim()
  const separator = value.indexOf(':')
  return separator >= 0 && value.slice(0, separator).toLowerCase() === 'custom'
    ? `custom:${value.slice(separator + 1)}`
    : value.toLowerCase()
}

/**
 * 合并同一作用域内的字段声明；同名字段只要有一处不合格就整体失败关闭。
 * @param {Array<{value:string,label:string,eligible:boolean,signature:string}>} declarations 字段声明、资格和值形态签名。
 * @returns {Array<{value:string,label:string,eligible:boolean,signature:string}>} 按首次出现顺序去重后的字段目录。
 */
function mergeFieldDeclarations(declarations) {
  const fields = new Map()
  for (const declaration of declarations) {
    const value = String(declaration?.value || '').trim()
    if (!value) continue
    const existing = fields.get(value)
    if (existing) {
      existing.eligible = existing.eligible
        && declaration.eligible === true
        && existing.signature === declaration.signature
      continue
    }
    fields.set(value, {
      value,
      label: String(declaration?.label || value),
      eligible: declaration?.eligible === true,
      signature: String(declaration?.signature || '')
    })
  }
  return [...fields.values()]
}

/**
 * 判断正式模板字段是否可见、可读且能无歧义承载一个用户主键。
 * @param {object} field 使用 __config__ 描述组件类型与节点权限的正式模板字段。
 * @param {string} permissionMode BPMN 节点显式覆盖的四态权限；空值保留模板原权限。
 * @returns {string} 文本、数值、单选或单值下拉字段的稳定组件签名；不合格时返回空串。
 */
function templateUserIdSignature(field, permissionMode) {
  const config = field?.__config__ || {}
  const explicitMode = FORM_PERMISSION_MODES.has(permissionMode)
  const visible = explicitMode
    ? permissionMode !== 'HIDDEN'
    : config.workflowHidden == null || config.workflowHidden === false
  const readable = explicitMode
    ? permissionMode !== 'HIDDEN'
    : config.workflowReadable == null || config.workflowReadable === true
  const tag = String(config.tag || '')
  if (!visible || !readable || !TEMPLATE_USER_ID_TAGS.has(tag)) return ''
  return tag !== 'el-select' || field?.multiple == null || field.multiple === false ? tag : ''
}

/**
 * 从正式模板 JSON 构建用户主键字段目录，保留不合格声明供流程级冲突失败关闭。
 * @param {string} content 已由正式表单接口返回的模板 JSON。
 * @param {{configured?:boolean,defaultMode?:string,permissions?:Map<string,string>}|undefined} permissionPolicy BPMN 节点权限策略。
 * @returns {Array<{value:string,label:string,eligible:boolean,signature:string}>} 去重后的全部字段声明、资格和值形态。
 */
export function createTemplateUserIdFieldCatalog(content, permissionPolicy) {
  try {
    const root = JSON.parse(content)
    const declarations = []
    const visit = fields => {
      for (const field of Array.isArray(fields) ? fields : []) {
        const value = String(field?.__vModel__ || '').trim()
        if (value) {
          const label = String(field?.__config__?.label || '').trim()
          const permissionMode = permissionPolicy?.configured === true
            ? permissionPolicy.permissions?.get(value) || permissionPolicy.defaultMode || ''
            : ''
          const signature = templateUserIdSignature(field, permissionMode)
          declarations.push({
            value,
            label: label ? `${label}（${value}）` : value,
            eligible: Boolean(signature),
            signature
          })
        }
        visit(field?.__config__?.children)
      }
    }
    visit(root?.fields)
    return mergeFieldDeclarations(declarations)
  } catch {
    // 页面目录对异常模板失败关闭，正式保存仍由后端返回精确校验错误。
    return []
  }
}

/**
 * 从 BPMN 内嵌 FormProperty 编辑值构建与后端转换结果等价的用户主键字段目录。
 * @param {Array<object>} fields 内嵌字段，包含 id、variable、type、readable 与 writable。
 * @param {Iterable<string>} supportedCustomTypes 服务端目录确认会转换为单值 el-input 的 custom: 类型。
 * @returns {Array<{value:string,label:string,eligible:boolean,signature:string}>} 去重后的全部字段声明、资格和值形态。
 */
export function createEmbeddedUserIdFieldCatalog(fields, supportedCustomTypes = []) {
  // BPMN 内嵌类型按 Flowable 约定大小写不敏感，自定义扩展键目录必须使用相同规范化口径。
  const customTypes = new Set([...supportedCustomTypes]
    .map(type => String(type || '').toLowerCase()))
  const declarations = (Array.isArray(fields) ? fields : []).map(field => {
    const value = String(field?.variable || field?.id || '').trim()
    const label = String(field?.name || '').trim()
    const type = normalizeEmbeddedFormType(field?.type)
    const comparableType = type.toLowerCase()
    const readable = field?.readable == null || field.readable === true
    const signature = !readable
      ? ''
      : comparableType === 'string' || customTypes.has(comparableType)
        ? 'el-input'
        : ['long', 'integer'].includes(comparableType)
          ? 'el-input-number'
          : comparableType === 'enum' ? 'el-select' : ''
    return {
      value,
      label: label ? `${label}（${value}）` : value,
      eligible: Boolean(signature)
        && (EMBEDDED_USER_ID_TYPES.has(comparableType) || customTypes.has(comparableType)),
      signature
    }
  })
  return mergeFieldDeclarations(declarations)
}

/**
 * 合并开始节点和用户任务字段目录，确保任一节点的不合格同名声明不会被并集放宽。
 * @param {Array<Array<{value:string,label:string,eligible:boolean,signature:string}>>} catalogs 各节点全部字段声明目录。
 * @returns {Array<{value:string,label:string,eligible:boolean,signature:string}>} 流程级去重目录。
 */
export function mergeUserIdFieldCatalogs(catalogs) {
  return mergeFieldDeclarations((Array.isArray(catalogs) ? catalogs : []).flat())
}

/**
 * 构建流程级用户主键字段目录，只纳入顶层开始节点和任意层级用户任务。
 *
 * 嵌入子流程或事件子流程的开始节点不是独立申请入口，后端不会为其冻结开始表单；
 * 因此它们不能被流程完成自动抄送误当成正式字段来源。
 *
 * @param {object} process 当前 bpmn:Process 业务对象。
 * @param {(element:object)=>Array<object>} resolveCatalog 节点到完整字段声明目录的解析器。
 * @returns {Array<{value:string,label:string,eligible:boolean,signature:string}>} 流程作用域失败关闭后的字段目录。
 */
export function createProcessUserIdFieldCatalog(process, resolveCatalog) {
  if (process?.$type !== 'bpmn:Process' || typeof resolveCatalog !== 'function') return []
  const catalogs = []
  const visit = (flowElements, includeStartEvents) => {
    for (const element of Array.isArray(flowElements) ? flowElements : []) {
      if (element?.$type === 'bpmn:UserTask'
        || (includeStartEvents && element?.$type === 'bpmn:StartEvent')) {
        catalogs.push(resolveCatalog(element) || [])
      }
      if (Array.isArray(element?.flowElements)) visit(element.flowElements, false)
    }
  }
  visit(process.flowElements, true)
  return mergeUserIdFieldCatalogs(catalogs)
}

/**
 * 从完整声明目录投影用户可选择的字段选项。
 * @param {Array<{value:string,label:string,eligible:boolean}>} catalog 全部字段声明目录。
 * @returns {Array<{value:string,label:string}>} 仅包含可见、可读单值字段的选项。
 */
export function eligibleUserIdFieldOptions(catalog) {
  return (Array.isArray(catalog) ? catalog : [])
    .filter(field => field.eligible === true)
    .map(({ value, label }) => ({ value, label }))
}

/**
 * 投影动态审批人 FORM_USER 可选择字段，并应用后端参与者变量名语法。
 * @param {Array<{value:string,label:string,eligible:boolean,signature:string}>} catalog 完整正式字段目录。
 * @returns {Array<{value:string,label:string}>} 后端可稳定写入参与者规则的字段选项。
 */
export function participantUserIdFieldOptions(catalog) {
  return eligibleUserIdFieldOptions(catalog)
    .filter(field => PARTICIPANT_FORM_VARIABLE_PATTERN.test(field.value))
}
