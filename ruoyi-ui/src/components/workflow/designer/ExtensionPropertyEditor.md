# ExtensionPropertyEditor

## 组件简介

`ExtensionPropertyEditor` 编辑 BPMN 元素的 `flowable:properties` 名值元数据。它提供受限名值对；实现类、Bean、脚本和表达式执行入口由专用受控编辑器管理。

## 使用方式

```vue
<ExtensionPropertyEditor
  v-model="state.extensionProperties"
  :max-items="32"
  @change="updateExtensionProperties"
/>
```

## Props

| 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `modelValue` | `array` | `[]` | `{ name, value }` 属性列表。 |
| `maxItems` | `number` | `32` | 单元素属性数量上限。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `Array<{name:string,value:string}>` | 更新双向绑定值。 |
| `change` | `Array<{name:string,value:string}>` | 新增、删除或编辑后提交完整快照。 |

## 公开方法

无。

## 关键设计

- 每行使用内部稳定键，属性重命名时其他行保持原实例。
- 组件只负责编辑，父组件通过 bpmn-js 命令栈写入 XML，后端再次校验数量、名称、重复项和值长度。
- 属性值按普通文本存储，流程表达式引擎仅处理专用受控字段。

## 最小接入示例

```js
function updateExtensionProperties(properties) {
  propertyState.extensionProperties = properties
}
```
