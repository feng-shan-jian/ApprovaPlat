/** 将一次业务编辑的多个 moddle 更新纳入同一次撤销/重做。 */
class AtomicPropertiesHandler {
  constructor(modeling) {
    this.modeling = modeling
  }

  preExecute({ element, updates }) {
    for (const { moddleElement, properties } of updates) {
      this.modeling.updateModdleProperties(element, moddleElement, properties)
    }
  }

  execute() {}
  revert() {}
}
AtomicPropertiesHandler.$inject = ['modeling']

function registerAtomicProperties(commandStack) {
  commandStack.registerHandler('workflow.atomicProperties', AtomicPropertiesHandler)
}
registerAtomicProperties.$inject = ['commandStack']

export default {
  __init__: ['workflowAtomicProperties'],
  workflowAtomicProperties: ['type', registerAtomicProperties]
}
