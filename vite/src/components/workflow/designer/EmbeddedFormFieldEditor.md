# EmbeddedFormFieldEditor

## 组件简介

`EmbeddedFormFieldEditor` 是 Flowable BPMN 内嵌 FormData 的字段编辑器。它只编辑确定性的字段结构，不直接访问 Modeler、后端接口或浏览器存储；父组件收到完整字段列表后负责校验并通过 bpmn-js 命令栈写入 XML。

## 使用方式

```vue
<EmbeddedFormFieldEditor
  :fields="propertyState.embeddedFields"
  @change="updateEmbeddedForm"
/>
```

## Props

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `fields` | `array` | 从 `flowable:formProperty` 回读的字段列表，包含 `id`、可选 `variable`、`name`、`type`、`required`、`readable`、`writable`、`datePattern` 和 `values`。 |
| `customFieldOptions` | `array` | 正式 `/workflow/extension/options/form-field` 返回的启用最新版目录。 |
| `customFieldLoading` | `boolean` | 正式目录加载状态。 |

字段类型允许 `string`、`long`、`integer`、`boolean`、`date`、`enum`，以及正式目录返回的 `custom:<extensionKey>`。日期字段使用确定性格式文本；枚举字段只允许静态 `id` / `name` 选项。

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `change` | `fields: array` | 新增、删除或修改字段后发出一份深拷贝的完整字段列表。 |

## 公开方法

无。组件通过 Props 和 Emits 工作。

## 关键设计

- 不直接修改 `fields` Prop，避免绕过父组件校验和 bpmn-js 撤销栈。
- 输入过程中只维护组件草稿，字段失焦后一次提交完整列表；草稿不进入浏览器存储，也不替代 BPMN 正式状态。
- `id` 是稳定字段标识，`variable` 是可选提交变量名；`variable` 为空时后端按 Flowable 规则使用 `id`。
- 字段和枚举选项上限均为 500，与后端转换门禁一致。
- 不可写字段会同步清除必填状态，避免生成用户无法提交的表单约束。
- 自定义字段只能从正式扩展目录选择，服务端把稳定键解析为已安装实现并在部署表单快照中冻结版本、实现键和校验和。
- 组件不接受任意 Vue 组件名、模板、表达式、默认表达式或脚本入口。
- 变量重复、保留变量、日期格式和枚举完整性由父组件即时校验，保存与部署时后端再次执行同一业务边界。

## 最小接入示例

```js
/**
 * 将字段编辑结果写入 BPMN 命令栈。
 * @param {Array<object>} fields 组件返回的完整字段列表。
 * @returns {void} 字段非法时不修改 BPMN。
 */
function updateEmbeddedForm(fields) {
  validateEmbeddedFormFields(fields)
  propertyState.embeddedFields = fields
  syncFormDefinition()
}
```
