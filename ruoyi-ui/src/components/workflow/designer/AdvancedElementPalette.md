# AdvancedElementPalette

## 组件简介

`AdvancedElementPalette` 为审批流程设计器补充原生 Palette 未直接展示的标准 BPMN 元素。组件只维护可见目录和受控创建提示，真实业务对象、BPMN DI、命令栈及连接规则仍由父级 `bpmn-js` Modeler 执行。

## 使用方式

```vue
<AdvancedElementPalette
  :disabled="designerLocked"
  @create="createAdvancedElement"
/>
```

## Props

| 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `disabled` | `boolean` | `false` | 保存、导入或其他互斥操作期间禁止开始新的创建命令。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `create` | `(definition, mouseEvent)` | 选择元素后发出；`definition` 只包含标准 BPMN 类型和受控提示，`mouseEvent` 是 Modeler 创建手势起点。 |

## 公开方法

无。菜单开关由组件内部维护。

## 关键设计

- ServiceTask、SendTask、ReceiveTask 和 BusinessRuleTask 使用明确创建入口，并通过唯一不可变 `taskCapabilityMap` 判断是否允许创建；泳道命令作用于当前选中的池或泳道。
- ManualTask 不进入高级面板，也会从 bpmn-js“更改元素”目标中移除。历史 BPMN 的 ManualTask 仍由 Modeler 正常导入、渲染和导出。
- Popover 的 reference 使用稳定原生元素承载 Tooltip 和触发器，避免运行时指令落到非元素根节点并导致点击入口失效。
- 边界事件携带 `eventDefinitionType` 和中断提示，最终能否附着由 bpmn-js 规则服务决定。
- “协作消息流 / 关联”进入 Modeler 的全局连接工具；只有连接不同池中 SendTask/消息抛出事件与 ReceiveTask/消息捕获事件，且消息名称与消息定义完整时，后端才允许部署。
- 复杂网关不进入正式工具入口。协作图形或 XML 往返不会自动获得运行语义，运行时必须通过协作消息 API、关联键和正式消息台账完成真实投递。
- Popover 引用使用真实内联 DOM 包装 Tooltip，确保 Element Plus 的触发、焦点和点击外部指令稳定绑定，不产生非元素根节点告警。

## 最小接入示例

```js
function createAdvancedElement(definition, event) {
  const shape = modeler.get('elementFactory').createShape({
    type: definition.type,
    eventDefinitionType: definition.eventDefinitionType
  })
  modeler.get('create').start(event, shape)
}
```
