import { ref } from 'vue'

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
 * @param {{forms:Array<object>|(()=>Array<object>),loadAuthorFields:Function}} context 模板显示内容与服务端有效字段接口。
 * @returns {object} 原始字段编辑描述及按 XML 版本维护的后端字段投影。
 */
export function createDesignerFormFieldCatalog(context = {}) {
  const { forms = [], loadAuthorFields } = context
  const snapshot = ref(null)
  const error = ref('')
  let version = 0
  let requestedXml = ''
  let pending = null

  /**
   * 按 BPMN 业务对象或 formKey 查找正式模板。
   * @param {object|string|undefined} source StartEvent、UserTask 或形如 key_1 的表单键。
   * @returns {object|undefined} formId 匹配的正式模板。
   */
  function resolveTemplateForm(source) {
    const formKey = typeof source === 'string' ? source : source?.get?.('flowable:formKey') || ''
    const match = /^key_([1-9]\d{0,18})$/.exec(String(formKey).trim())
    if (!match) return undefined
    const formId = match[1]
    return readCatalogSource(forms).find(item => String(item.formId) === formId)
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
        ...(property.systemWritable === true ? { systemWritable: true } : {}),
        datePattern: property.datePattern || '',
        values: (property.values || []).map(value => ({ id: value.id || '', name: value.name || value.id || '' }))
      }))
  }

  /**
   * 递归读取正式模板字段，不施加任何消费者类型或权限规则。
   * @param {string} content 正式 wf_form 模板字符串内容。
   * @returns {Array<{source:string,variable:string,field:object}>} 保持模板顺序且保留重名业务字段的中性描述。
   */
  function readTemplateFieldDescriptors(content) {
    try {
      const root = JSON.parse(content)
      const descriptors = []

      /**
       * 递归展开布局节点下的业务字段。
       * @param {Array<object>} fields 当前层模板字段。
       * @returns {void} 描述按原顺序写入本次调用的 descriptors。
       */
      const visit = fields => {
        for (const field of Array.isArray(fields) ? fields : []) {
          const variable = String(field?.vModel || '').trim()
          if (variable) descriptors.push({ source: 'TEMPLATE', variable, field })
          visit(field?.children)
        }
      }
      visit(root?.fields)
      return descriptors
    } catch {
      return []
    }
  }

  // 目录只接受后端按同次 XML 冻结的投影，缺失和加载中均不回退到原始模板推算。
  function owningProcess(source) {
    while (source && source.$type !== 'bpmn:Process') source = source.$parent
    return source
  }

  function nodeFields(source) {
    const processKey = owningProcess(source)?.id
    return snapshot.value?.nodes.find(node => node.processKey === processKey && node.nodeKey === source?.id)
  }

  function processFields(source) {
    const processKey = owningProcess(source)?.id
    return snapshot.value?.processes.find(process => process.processKey === processKey)
  }

  function invalidate() {
    version += 1
    requestedXml = ''
    snapshot.value = null
    error.value = ''
  }

  async function refresh(xml) {
    if (xml === requestedXml) return pending
    invalidate()
    requestedXml = xml
    const requestVersion = version
    pending = (async () => {
      try {
        const { data } = await loadAuthorFields(xml)
        if (requestVersion !== version) return
        if (!Array.isArray(data?.nodes) || !Array.isArray(data?.processes) || !Array.isArray(data?.issues)) {
          throw new Error('字段目录响应格式不正确')
        }
        snapshot.value = data
      } catch (failure) {
        if (requestVersion !== version) return
        error.value = failure?.message || '字段目录加载失败，请重新校验或编辑后重试'
        requestedXml = ''
      }
    })()
    return pending
  }

  function issueFor(source) {
    if (error.value) return error.value
    const processKey = owningProcess(source)?.id
    const nodeScope = ['bpmn:UserTask', 'bpmn:StartEvent'].includes(source?.$type)
    const issue = snapshot.value?.issues.find(item => item.processKey === processKey
      && (!nodeScope || !item.nodeKey || item.nodeKey === source?.id))
    return issue ? `${issue.nodeKey ? issue.nodeKey + '：' : ''}${issue.message}` : ''
  }

  return { resolveTemplateForm, readEmbeddedFormFields, readTemplateFieldDescriptors,
    nodeFields, processFields, refresh, invalidate, issueFor }
}

/** 内嵌字段编辑保留受控类型拼写，不参与业务资格判断。 */
function normalizeEmbeddedFormType(type) {
  const value = String(type || 'string').trim()
  const separator = value.indexOf(':')
  return separator >= 0 && value.slice(0, separator).toLowerCase() === 'custom'
    ? `custom:${value.slice(separator + 1)}` : value.toLowerCase()
}
