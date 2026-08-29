import { createEmbeddedUserIdFieldCatalog, createTemplateUserIdFieldCatalog, normalizeEmbeddedFormType } from './formUserFieldCatalog.js'

/**
 * 读取调用方传入的最新目录快照，不依赖 Vue 或其他响应式框架。
 * @param {Array<object>|(()=>Array<object>)} source 固定数组或只读访问函数。
 * @returns {Array<object>} 当前目录；非法输入返回空数组。
 */
function readCatalogSource(source) {
  const value = typeof source === 'function' ? source() : source
  return Array.isArray(value) ? value : []
}

/**
 * 创建流程设计器共享表单字段目录。
 * @param {{forms:Array<object>|(()=>Array<object>),formFieldOptions:Array<object>|(()=>Array<object>)}} context 正式表单和字段扩展目录来源。
 * @returns {object} 五个不依赖 Vue、DOM、API 或 Modeler 的字段读取入口。
 */
export function createDesignerFormFieldCatalog(context = {}) {
  const { forms = [], formFieldOptions = [] } = context

  /**
   * 按 BPMN 业务对象或 formKey 查找正式模板。
   * @param {object|string|undefined} source StartEvent、UserTask 或形如 key_1 的表单键。
   * @returns {object|undefined} formId 匹配的正式模板。
   */
  function resolveTemplateForm(source) {
    const formKey = typeof source === 'string' ? source : source?.get?.('flowable:formKey') || ''
    const formId = Number(String(formKey).replace(/^key_/, ''))
    return readCatalogSource(forms).find(item => Number(item.formId) === formId)
  }

  /**
   * 从 BPMN 元素回读 Flowable 内嵌 FormProperty 字段。
   * @param {object|undefined} businessObject StartEvent 或 UserTask 业务对象。
   * @returns {Array<object>} 保持 XML 顺序的内嵌字段；正式模板权限描述不作为内嵌字段。
   */
  function readEmbeddedFormFields(businessObject) {
    const formKey = String(businessObject?.get?.('flowable:formKey') || businessObject?.formKey || '').trim()
    if (formKey) return []
    return (businessObject?.extensionElements?.values || [])
      .filter(value => value?.$type === 'flowable:FormProperty')
      .map(property => ({
        id: property.id || '',
        variable: property.variable || '',
        name: property.name || property.id || property.variable || '',
        type: normalizeEmbeddedFormType(property.type),
        required: property.required === true,
        readable: property.readable !== false,
        writable: property.writable !== false,
        datePattern: property.datePattern || '',
        values: (property.values || []).map(value => ({ id: value.id || '', name: value.name || value.id || '' }))
      }))
  }

  /**
   * 递归读取正式模板字段，不施加任何消费者类型或权限规则。
   * @param {string|object} content 正式 wf_form 模板内容。
   * @param {boolean} allowObject 是否兼容权限编辑器使用的已解析模板对象。
   * @returns {Array<{source:string,variable:string,field:object}>} 保持模板顺序且保留重名业务字段的中性描述。
   */
  function readTemplateFieldDescriptors(content, allowObject = false) {
    try {
      const root = allowObject && content && typeof content === 'object' ? content : JSON.parse(content)
      const descriptors = []

      /**
       * 递归展开布局节点下的业务字段。
       * @param {Array<object>} fields 当前层模板字段。
       * @returns {void} 描述按原顺序写入本次调用的 descriptors。
       */
      const visit = fields => {
        for (const field of Array.isArray(fields) ? fields : []) {
          const variable = String(field?.__vModel__ || '').trim()
          if (variable) descriptors.push({ source: 'TEMPLATE', variable, field })
          visit(field?.__config__?.children)
        }
      }
      visit(root?.fields)
      return descriptors
    } catch {
      return []
    }
  }

  /**
   * 解析元素当前绑定的内嵌字段或正式模板字段。
   * @param {object|undefined} businessObject StartEvent 或 UserTask 业务对象。
   * @returns {Array<{source:string,variable:string,field:object}>} 保持来源字段顺序的中性描述。
   */
  function resolveElementFieldDescriptors(businessObject) {
    const embedded = readEmbeddedFormFields(businessObject)
    if (embedded.length) {
      return embedded.map(field => ({ source: 'EMBEDDED', variable: field.variable || '', field }))
    }
    const form = resolveTemplateForm(businessObject)
    return form?.content ? readTemplateFieldDescriptors(form.content) : []
  }

  /**
   * 复用正式用户字段资格规则生成指定元素的用户主键字段目录。
   * @param {object|string|Array<object>|undefined} source BPMN 业务对象、formKey 或已编辑内嵌字段。
   * @param {{configured?:boolean,defaultMode?:string,permissions?:Map<string,string>}|undefined} permissionPolicy 消费者解析的节点字段权限。
   * @returns {Array<{value:string,label:string,eligible:boolean,signature:string}>} 保持字段顺序的完整资格目录。
   */
  function resolveUserIdFieldCatalog(source, permissionPolicy) {
    const embedded = Array.isArray(source) ? source : readEmbeddedFormFields(source)
    if (Array.isArray(source) || embedded.length) {
      const customTypes = new Set(readCatalogSource(formFieldOptions)
        .filter(option => option?.implementationKey === 'FORM_FIELD_TEXTAREA_V1')
        .map(option => `custom:${option.extensionKey}`))
      return createEmbeddedUserIdFieldCatalog(embedded, customTypes)
    }
    const form = resolveTemplateForm(source)
    return form?.content ? createTemplateUserIdFieldCatalog(form.content, permissionPolicy) : []
  }

  return { resolveTemplateForm, readEmbeddedFormFields, readTemplateFieldDescriptors,
    resolveElementFieldDescriptors, resolveUserIdFieldCatalog }
}
