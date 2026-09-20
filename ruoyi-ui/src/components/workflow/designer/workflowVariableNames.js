// 与服务端 WorkflowVariableNames 的业务变量用途一致；最终裁决仍由服务端执行。
const identifier = /^[A-Za-z_][A-Za-z0-9_]{0,127}$/
const reservedNames = new Set([
  'initiator', 'processStatus', 'processInstanceId', 'processDefinitionId',
  'deploymentId', 'startUserId', 'authenticatedUserId', 'businessKey', 'assignee',
  'nrOfInstances', 'nrOfActiveInstances', 'nrOfCompletedInstances', 'loopCounter',
  '_FLOWABLE_SKIP_EXPRESSION_ENABLED', '_wfMiCompletionRevision'
])
const internalPrefixes = [
  'wfMiUsers_', '_wfMiMembers_', '_wfMiRevision_', '_wfMiMode_',
  '__approva_loop_route_', '__approva_loop_iteration_', '__ruoyi_workflow_'
]
const unsafeObjectKeys = new Set(['__proto__', 'prototype', 'constructor'])

export function isBusinessVariable(name) {
  return typeof name === 'string' && identifier.test(name)
    && !reservedNames.has(name) && !internalPrefixes.some(prefix => name.startsWith(prefix))
    && !unsafeObjectKeys.has(name.toLowerCase())
}

// Java 安装包已有的额外命名空间限制，不扩大到连接器只读输入。
export function isJavaOutputVariable(name) {
  return isBusinessVariable(name) && !['ACT_', 'FLOWABLE_', '__', 'WF_']
    .some(prefix => name.toUpperCase().startsWith(prefix))
}
