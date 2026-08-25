# FormFieldPermissionEditor

## 组件简介

`FormFieldPermissionEditor` 为开始节点和 UserTask 配置字段级隐藏、只读、可编辑和必填权限。组件编辑父级传入的正式表单字段目录，每次变更都会提交完整有序策略，供 `ProcessDesigner` 原子写入 BPMN；变量名固定从目录选择。

## 使用方式

```vue
<FormFieldPermissionEditor
  :fields="state.formPermissionFields"
  :default-mode="state.formPermissionDefault"
  :disabled="saving"
  @change="updatePermissions"
/>
```

## Props

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `fields` | `Array` | `[]` | 正式表单字段，元素包含 `variable`、`label` 和 `mode`。 |
| `defaultMode` | `string` | `EDITABLE` | 批量应用及模板后续新增字段的默认权限。 |
| `disabled` | `boolean` | `false` | 将全部权限控件切换为锁定状态。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `change` | `{ defaultMode, fields }` | 单字段或批量修改后返回完整权限策略。 |

## 公开方法

无。

## 关键设计

- 权限值固定为 `HIDDEN`、`READONLY`、`EDITABLE`、`REQUIRED`，未知值在规范化时恢复为 `EDITABLE`。
- 批量策略同时更新当前全部字段，并作为正式模板后续新增字段的默认策略。
- 父级收到事件后立即通过 bpmn-js 命令栈更新模型 XML，组件状态始终由 `modelValue` 驱动。

## 最小接入示例

```js
function updatePermissions(policy) {
  state.formPermissionDefault = policy.defaultMode
  state.formPermissionFields = policy.fields
}
```
