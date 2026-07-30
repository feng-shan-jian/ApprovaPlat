// 任务监听器是后端运行时身份审计的内部技术字段，持久化时只允许固定 Bean 和三个事件。
const CONTROLLED_TASK_LISTENER_EXPRESSION = '${userTaskListener}'
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
 * 移除已有的全部直接 taskListener，防止非法命名空间、实现、属性或字段进入保存结果。
 * @param {Element} extensionElements 用户任务的 BPMN 扩展节点。
 * @returns {void} 仅删除 taskListener，保留其他业务扩展元素。
 */
function removeExistingTaskListeners(extensionElements) {
  for (const listener of directElementChildren(extensionElements)
    .filter(child => child.localName === 'taskListener')) {
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
 * 将全部用户任务无条件收敛为后端固定审计监听器结构。
 * @param {string} xml bpmn-js 导出的 BPMN XML。
 * @returns {string} 已重建 create、assignment、complete 固定监听器的可持久化 XML。
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
