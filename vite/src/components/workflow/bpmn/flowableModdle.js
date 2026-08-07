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
      extends: ['bpmn:ServiceTask', 'bpmn:SendTask'],
      properties: [
        { name: 'class', isAttr: true, type: 'String' },
        { name: 'delegateExpression', isAttr: true, type: 'String' },
        { name: 'expression', isAttr: true, type: 'String' },
        { name: 'resultVariable', isAttr: true, type: 'String' },
        { name: 'skipExpression', isAttr: true, type: 'String' }
      ]
    },
    {
      name: 'BusinessRuleTask',
      isAbstract: true,
      extends: ['bpmn:BusinessRuleTask'],
      properties: [
        { name: 'rules', isAttr: true, type: 'String' },
        { name: 'class', isAttr: true, type: 'String' },
        { name: 'ruleVariablesInput', isAttr: true, type: 'String' },
        { name: 'exclude', isAttr: true, type: 'Boolean', default: false }
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
      name: 'Activity',
      isAbstract: true,
      extends: ['bpmn:Activity'],
      properties: [
        { name: 'async', isAttr: true, type: 'Boolean', default: false },
        { name: 'asyncLeave', isAttr: true, type: 'Boolean', default: false },
        { name: 'exclusive', isAttr: true, type: 'Boolean', default: true }
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
      name: 'FormProperty',
      superClass: ['Element'],
      properties: [
        { name: 'id', isAttr: true, type: 'String' },
        { name: 'name', isAttr: true, type: 'String' },
        { name: 'type', isAttr: true, type: 'String' },
        { name: 'variable', isAttr: true, type: 'String' },
        { name: 'expression', isAttr: true, type: 'String' },
        { name: 'defaultExpression', isAttr: true, type: 'String' },
        { name: 'datePattern', isAttr: true, type: 'String' },
        { name: 'readable', isAttr: true, type: 'Boolean', default: true },
        { name: 'writable', isAttr: true, type: 'Boolean', default: true },
        { name: 'required', isAttr: true, type: 'Boolean', default: false },
        { name: 'values', type: 'Value', isMany: true }
      ]
    },
    {
      name: 'Value',
      superClass: ['Element'],
      properties: [
        { name: 'id', isAttr: true, type: 'String' },
        { name: 'name', isAttr: true, type: 'String' }
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
    },
    {
      name: 'Properties',
      superClass: ['Element'],
      properties: [{ name: 'values', type: 'Property', isMany: true }]
    },
    {
      name: 'Property',
      superClass: ['Element'],
      properties: [
        { name: 'name', isAttr: true, type: 'String' },
        { name: 'value', isAttr: true, type: 'String' }
      ]
    }
  ],
  enumerations: [],
  associations: []
}
