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

- 高级任务、网关、事件、子流程、事务、调用活动、池、泳道、数据对象、分组和注释均使用标准 BPMN moddle 类型；泳道命令作用于当前选中的池或泳道。
- 边界事件携带 `eventDefinitionType` 和中断提示，最终能否附着由 bpmn-js 规则服务决定。
- “消息流 / 关联”进入 Modeler 的全局连接工具，连接类型由起点、终点和协作作用域共同决定。
- `ComplexGateway` 只解决导入、编辑和导出可发现性；后端部署门禁必须继续拒绝 Flowable 8 不执行的结构。

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
