/**
 * Flowable BPMN 命名空间的受控 moddle 描述。
 * 仅声明当前后端安全门禁允许读写的属性，同时保留旧流程常见的监听器、字段和表单扩展。
 */
export default {
  name: 'Flowable',
  uri: 'http://flowable.org/bpmn',
  prefix: 'flowable',
  xml: { tagAlias: 'lowerCase' },
  types: [
    {
      name: 'FormSupported',
      isAbstract: true,
      extends: ['bpmn:StartEvent', 'bpmn:UserTask'],
      properties: [
        { name: 'formKey', isAttr: true, type: 'String' },
        { name: 'formHandlerClass', isAttr: true, type: 'String' },
        { name: 'localScope', isAttr: true, type: 'Boolean', default: false }
      ]
    },
    {
      name: 'Assignable',
      isAbstract: true,
      extends: ['bpmn:UserTask'],
      properties: [
        { name: 'assignee', isAttr: true, type: 'String' },
        { name: 'candidateUsers', isAttr: true, type: 'String' },
        { name: 'candidateGroups', isAttr: true, type: 'String' },
        { name: 'owner', isAttr: true, type: 'String' },
        { name: 'dueDate', isAttr: true, type: 'String' },
        { name: 'priority', isAttr: true, type: 'String' },
        { name: 'category', isAttr: true, type: 'String' },
        { name: 'skipExpression', isAttr: true, type: 'String' }
      ]
    },
    {
      name: 'ServiceTaskLike',
      isAbstract: true,
      extends: ['bpmn:ServiceTask', 'bpmn:BusinessRuleTask', 'bpmn:SendTask'],
      properties: [
        { name: 'class', isAttr: true, type: 'String' },
        { name: 'delegateExpression', isAttr: true, type: 'String' },
        { name: 'expression', isAttr: true, type: 'String' },
        { name: 'resultVariable', isAttr: true, type: 'String' },
        { name: 'skipExpression', isAttr: true, type: 'String' }
      ]
    },
    {
      name: 'Process',
      isAbstract: true,
      extends: ['bpmn:Process'],
      properties: [
        { name: 'candidateStarterUsers', isAttr: true, type: 'String' },
        { name: 'candidateStarterGroups', isAttr: true, type: 'String' },
        { name: 'processCategory', isAttr: true, type: 'String' },
        { name: 'versionTag', isAttr: true, type: 'String' }
      ]
    },
    {
      name: 'Initiator',
      isAbstract: true,
      extends: ['bpmn:StartEvent'],
      properties: [{ name: 'initiator', isAttr: true, type: 'String' }]
    },
    {
      name: 'CallActivity',
      isAbstract: true,
      extends: ['bpmn:CallActivity'],
      properties: [
        { name: 'calledElementType', isAttr: true, type: 'String' },
        { name: 'calledElement', isAttr: true, type: 'String' },
        { name: 'businessKey', isAttr: true, type: 'String' },
        { name: 'processInstanceName', isAttr: true, type: 'String' }
      ]
    },
    {
      name: 'Collectable',
      isAbstract: true,
      extends: ['bpmn:MultiInstanceLoopCharacteristics'],
      properties: [
        { name: 'collection', isAttr: true, type: 'String' },
        { name: 'elementVariable', isAttr: true, type: 'String' }
      ]
    },
    {
      name: 'Field',
      superClass: ['Element'],
      properties: [
        { name: 'name', isAttr: true, type: 'String' },
        { name: 'stringValue', isAttr: true, type: 'String' },
        { name: 'expression', isAttr: true, type: 'String' }
      ]
    },
    {
      name: 'ExecutionListener',
      superClass: ['Element'],
      properties: [
        { name: 'event', isAttr: true, type: 'String' },
        { name: 'class', isAttr: true, type: 'String' },
        { name: 'delegateExpression', isAttr: true, type: 'String' },
        { name: 'expression', isAttr: true, type: 'String' },
        { name: 'fields', type: 'Field', isMany: true }
      ]
    },
    {
      name: 'TaskListener',
      superClass: ['Element'],
      properties: [
        { name: 'event', isAttr: true, type: 'String' },
        { name: 'class', isAttr: true, type: 'String' },
        { name: 'delegateExpression', isAttr: true, type: 'String' },
        { name: 'expression', isAttr: true, type: 'String' },
        { name: 'fields', type: 'Field', isMany: true }
      ]
    },
    {
      name: 'FailedJobRetryTimeCycle',
      superClass: ['Element'],
      properties: [{ name: 'body', isBody: true, type: 'String' }]
    }
  ],
  enumerations: [],
  associations: []
}
