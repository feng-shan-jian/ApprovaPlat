export const TASK_PANEL_TYPES = Object.freeze({
  USER: 'USER_TASK',
  SERVICE: 'SERVICE_TASK',
  SEND: 'SEND_TASK',
  RECEIVE: 'RECEIVE_TASK',
  BUSINESS_RULE: 'BUSINESS_RULE_TASK',
  MANUAL_WARNING: 'MANUAL_TASK_WARNING'
})

/**
 * 创建一个只描述平台任务能力的不可变条目。
 * @param {string} taskType 标准 BPMN 任务类型。
 * @param {boolean} creationAllowed 是否允许从设计器入口新建该任务。
 * @param {boolean} conversionAllowed 是否允许通过“更改元素”转换为该任务。
 * @param {string} panelType 属性面板使用的明确任务面板类型。
 * @param {string} runtimeSemantics 平台运行时对该任务的权威语义说明。
 * @returns {Readonly<object>} 不可变任务能力条目。
 */
function taskCapability(taskType, creationAllowed, conversionAllowed, panelType, runtimeSemantics) {
  return Object.freeze({
    taskType,
    creationAllowed,
    conversionAllowed,
    panelType,
    runtimeSemantics
  })
}

// 任务能力只表达创建、转换、面板和运行语义，不承载组件、处理器或分派行为。
export const taskCapabilityMap = Object.freeze({
  'bpmn:UserTask': taskCapability(
    'bpmn:UserTask', true, true, TASK_PANEL_TYPES.USER,
    '由平台生成可办理待办，并执行表单、参与者、会签、SLA、抄送和审计规则。'
  ),
  'bpmn:ServiceTask': taskCapability(
    'bpmn:ServiceTask', true, true, TASK_PANEL_TYPES.SERVICE,
    '进入节点时执行服务端正式扩展目录中的受控处理器；部署时冻结精确版本、配置和校验和。'
  ),
  'bpmn:SendTask': taskCapability(
    'bpmn:SendTask', true, true, TASK_PANEL_TYPES.SEND,
    '进入节点时执行正式目录中的受控发送处理器；连接 MessageFlow 时由后端强制核验事务 outbox 约束。'
  ),
  'bpmn:ReceiveTask': taskCapability(
    'bpmn:ReceiveTask', true, true, TASK_PANEL_TYPES.RECEIVE,
    '不会生成平台待办；流程在此等待通过正式运行事件接口唯一关联并触发对应 activityId。'
  ),
  'bpmn:BusinessRuleTask': taskCapability(
    'bpmn:BusinessRuleTask', true, true, TASK_PANEL_TYPES.BUSINESS_RULE,
    '执行作者选择的精确 DMN 来源版本；部署时生成同部署冻结副本，不跟随目录最新版漂移。'
  ),
  'bpmn:ManualTask': taskCapability(
    'bpmn:ManualTask', false, false, TASK_PANEL_TYPES.MANUAL_WARNING,
    '仅为历史 BPMN 兼容保留；该元素不会生成平台待办，也没有平台内办理闭环。'
  )
})

/**
 * 按标准 BPMN 类型读取不可变任务能力。
 * @param {unknown} taskType 待读取的 BPMN 类型。
 * @returns {Readonly<object>|null} 已知任务能力；非平台任务类型返回 null。
 */
export function getTaskCapability(taskType) {
  return taskCapabilityMap[String(taskType || '')] || null
}
