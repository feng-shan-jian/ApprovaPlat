// 系统审计监听器是后端运行时内部字段；业务监听器只能使用固定注册表入口并与其并存。
const CONTROLLED_TASK_LISTENER_EXPRESSION = '${userTaskListener}'
const BUSINESS_TASK_LISTENER_EXPRESSION = '${workflowBusinessListener}'
const APPROVED_TASK_LISTENER_EVENTS = Object.freeze(['create', 'assignment', 'complete'])
const BPMN_NAMESPACE = 'http://www.omg.org/spec/BPMN/20100524/MODEL'
const FLOWABLE_NAMESPACE = 'http://flowable.org/bpmn'
const XMLNS_NAMESPACE = 'http://www.w3.org/2000/xmlns/'

/**
 * 获取 XML 节点的直接元素子节点。
 * @param {Element} element 当前 XML 节点。
 * @returns {Element[]} 直接元素子节点数组。
 */
function directElementChildren(element) {
  return Array.from(element.childNodes).filter(child => child.nodeType === 1)
}

/**
 * 查找用户任务的 BPMN extensionElements 直接子节点。
 * @param {Element} userTask BPMN 用户任务 XML 节点。
 * @returns {Element|undefined} 合法 BPMN 扩展节点，不存在时返回 undefined。
 */
function findExtensionElements(userTask) {
  return directElementChildren(userTask).find(child =>
    child.namespaceURI === BPMN_NAMESPACE && child.localName === 'extensionElements')
}

/**
 * 按 BPMN BaseElement 顺序插入 extensionElements，保证位于 documentation 之后、其他业务子节点之前。
 * @param {Document} document 当前 BPMN XML 文档。
 * @param {Element} userTask BPMN 用户任务 XML 节点。
 * @returns {Element} 新建并插入的 BPMN 扩展节点。
 */
function insertExtensionElements(document, userTask) {
  const extensionElements = document.createElementNS(BPMN_NAMESPACE, 'extensionElements')
  const insertionPoint = directElementChildren(userTask)
    .find(child => child.localName !== 'documentation')
  if (insertionPoint) {
    userTask.insertBefore(extensionElements, insertionPoint)
  } else {
    userTask.appendChild(extensionElements)
  }
  return extensionElements
}

/**
 * 移除已有的系统审计 taskListener，保留受控业务监听器供服务端继续校验和冻结。
 * @param {Element} extensionElements 用户任务的 BPMN 扩展节点。
 * @returns {void} 仅删除 taskListener，保留其他业务扩展元素。
 */
function removeExistingTaskListeners(extensionElements) {
  for (const listener of directElementChildren(extensionElements).filter(child => {
    if (child.localName !== 'taskListener') return false
    return child.getAttribute('delegateExpression') !== BUSINESS_TASK_LISTENER_EXPRESSION
  })) {
    extensionElements.removeChild(listener)
  }
}

/**
 * 写入后端允许的三个固定任务监听器。
 * @param {Document} document 当前 BPMN XML 文档。
 * @param {Element} extensionElements 用户任务的 BPMN 扩展节点。
 * @returns {void} 按固定顺序追加 create、assignment、complete 监听器。
 */
function appendApprovedTaskListeners(document, extensionElements) {
  for (const event of APPROVED_TASK_LISTENER_EVENTS) {
    const listener = document.createElementNS(FLOWABLE_NAMESPACE, 'flowable:taskListener')
    listener.setAttribute('event', event)
    listener.setAttribute('delegateExpression', CONTROLLED_TASK_LISTENER_EXPRESSION)
    extensionElements.appendChild(listener)
  }
}

/**
 * 将全部用户任务收敛为固定审计监听器，并保留固定业务监听器入口。
 * @param {string} xml bpmn-js 导出的 BPMN XML。
 * @returns {string} 已重建系统监听器且保留业务监听器的可持久化 XML。
 */
export function normalizeTaskListenerXml(xml) {
  if (!xml) return xml
  const parser = new DOMParser()
  const document = parser.parseFromString(xml, 'application/xml')
  if (document.getElementsByTagName('parsererror').length) {
    throw new Error('BPMN XML 解析失败')
  }
  // userTasks 是本次持久化必须统一补齐内部运行时监听器的全部用户任务节点。
  const userTasks = Array.from(document.getElementsByTagNameNS(BPMN_NAMESPACE, 'userTask'))
  if (!userTasks.length) return xml
  for (const userTask of userTasks) {
    const extensionElements = findExtensionElements(userTask)
      || insertExtensionElements(document, userTask)
    removeExistingTaskListeners(extensionElements)
    appendApprovedTaskListeners(document, extensionElements)
  }
  document.documentElement.setAttributeNS(XMLNS_NAMESPACE, 'xmlns:flowable', FLOWABLE_NAMESPACE)
  return new XMLSerializer().serializeToString(document)
}
