const BPMN_NAMESPACE = 'http://www.omg.org/spec/BPMN/20100524/MODEL'

/**
 * 获取 XML 节点的直接元素子节点。
 * @param {Element} element 当前 XML 节点。
 * @returns {Element[]} 直接元素子节点数组。
 */
function directElementChildren(element) {
  return Array.from(element.childNodes).filter(child => child.nodeType === 1)
}

/**
 * 获取流程节点直接声明的 incoming 或 outgoing 顺序流主键。
 * @param {Element} element 当前 BPMN 流程节点。
 * @param {'incoming'|'outgoing'} direction 顺序流引用方向。
 * @returns {string[]} 保持 XML 顺序的顺序流主键数组。
 */
function directSequenceFlowIds(element, direction) {
  return directElementChildren(element)
    .filter(child => child.namespaceURI === BPMN_NAMESPACE && child.localName === direction)
    .map(child => String(child.textContent || '').trim())
}

/**
 * 判断两个顺序流主键数组是否完全一致。
 * @param {string[]} current 当前节点已有引用。
 * @param {string[]} expected 根据 sequenceFlow 计算出的正式引用。
 * @returns {boolean} 数量、顺序和值均一致时返回 true。
 */
function sameIds(current, expected) {
  return current.length === expected.length
    && current.every((value, index) => value === expected[index])
}

/**
 * 创建与当前 BPMN 文档前缀风格一致的 incoming 或 outgoing 节点。
 * @param {Document} document 当前 BPMN XML 文档。
 * @param {Element} owner 引用所属流程节点。
 * @param {'incoming'|'outgoing'} direction 顺序流引用方向。
 * @param {string} flowId 被引用的 sequenceFlow 主键。
 * @returns {Element} 可插入流程节点的 BPMN 引用元素。
 */
function createSequenceFlowReference(document, owner, direction, flowId) {
  const qualifiedName = owner.prefix ? `${owner.prefix}:${direction}` : direction
  const reference = document.createElementNS(BPMN_NAMESPACE, qualifiedName)
  reference.appendChild(document.createTextNode(flowId))
  return reference
}

/**
 * 按 BPMN FlowNode 子节点顺序重建 incoming 和 outgoing 引用。
 * @param {Document} document 当前 BPMN XML 文档。
 * @param {Element} flowNode 待修复的流程节点。
 * @param {string[]} incomingIds 全部进入顺序流主键。
 * @param {string[]} outgoingIds 全部离开顺序流主键。
 * @returns {void} 直接修改当前 XML 文档。
 */
function replaceSequenceFlowReferences(document, flowNode, incomingIds, outgoingIds) {
  const children = directElementChildren(flowNode)
  for (const child of children) {
    if (child.namespaceURI === BPMN_NAMESPACE
      && (child.localName === 'incoming' || child.localName === 'outgoing')) {
      flowNode.removeChild(child)
    }
  }

  // FlowNode 的引用必须位于 BaseElement/FlowElement 公共子节点之后、活动或事件专有子节点之前。
  const precedingNames = new Set([
    'documentation', 'extensionElements', 'auditing', 'monitoring', 'categoryValueRef'
  ])
  const insertionPoint = directElementChildren(flowNode)
    .find(child => !precedingNames.has(child.localName))
  const references = [
    ...incomingIds.map(flowId => createSequenceFlowReference(document, flowNode, 'incoming', flowId)),
    ...outgoingIds.map(flowId => createSequenceFlowReference(document, flowNode, 'outgoing', flowId))
  ]
  for (const reference of references) {
    if (insertionPoint) flowNode.insertBefore(reference, insertionPoint)
    else flowNode.appendChild(reference)
  }
}

/**
 * 根据全部 sequenceFlow 重建流程节点的 incoming/outgoing 反向引用。
 * @param {string} xml 待导入或持久化的 BPMN 2.0 XML。
 * @returns {string} 图引用完整的 XML；无需修改时原样返回。
 */
export function normalizeSequenceFlowReferences(xml) {
  if (!xml) return xml
  const parser = new DOMParser()
  const document = parser.parseFromString(xml, 'application/xml')
  if (document.getElementsByTagName('parsererror').length) {
    throw new Error('BPMN XML 解析失败')
  }

  // nodeStates 以流程节点主键聚合全部顺序流关系，兼容旧模型缺失或残留错误反向引用的情况。
  const nodeStates = new Map()
  const elementsById = new Map(Array.from(document.getElementsByTagNameNS(BPMN_NAMESPACE, '*'))
    .filter(element => element.hasAttribute('id'))
    .map(element => [element.getAttribute('id'), element]))
  const stateFor = element => {
    if (!nodeStates.has(element)) nodeStates.set(element, { incoming: [], outgoing: [] })
    return nodeStates.get(element)
  }

  for (const element of elementsById.values()) {
    if (directSequenceFlowIds(element, 'incoming').length
      || directSequenceFlowIds(element, 'outgoing').length) {
      stateFor(element)
    }
  }
  const sequenceFlows = Array.from(document.getElementsByTagNameNS(BPMN_NAMESPACE, 'sequenceFlow'))
  for (const sequenceFlow of sequenceFlows) {
    const flowId = String(sequenceFlow.getAttribute('id') || '').trim()
    const source = elementsById.get(sequenceFlow.getAttribute('sourceRef'))
    const target = elementsById.get(sequenceFlow.getAttribute('targetRef'))
    if (!flowId || !source || !target) continue
    stateFor(source).outgoing.push(flowId)
    stateFor(target).incoming.push(flowId)
  }

  let changed = false
  for (const [flowNode, state] of nodeStates) {
    const currentIncoming = directSequenceFlowIds(flowNode, 'incoming')
    const currentOutgoing = directSequenceFlowIds(flowNode, 'outgoing')
    if (sameIds(currentIncoming, state.incoming) && sameIds(currentOutgoing, state.outgoing)) continue
    replaceSequenceFlowReferences(document, flowNode, state.incoming, state.outgoing)
    changed = true
  }
  return changed ? new XMLSerializer().serializeToString(document) : xml
}
