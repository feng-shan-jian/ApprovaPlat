# WorkflowFormField

## 组件简介

`WorkflowFormField` 是流程表单渲染器的递归字段适配组件。它把已由 `formTemplate.js` 规范化的字段描述映射为 Element Plus 控件，并负责行容器递归、共享表单值更新、附件忙碌状态透传和只读门禁。运行面固定为受支持字段配置；模板事件、脚本和远程地址在规范化阶段过滤。

## 使用方式

```vue
<WorkflowFormField
  :field="field"
  :value="formModel[field.variable]"
  :form-model="formModel"
  :readonly="readonly"
  :gutter="16"
  @update:value="value => formModel[field.variable] = value"
  @busy-change="handleBusyChange"
  @error="showError"
/>
```

## Props

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `field` | `object` | 必填 | 已规范化的字段描述，包含 `tag`、`variable`、`props`、`children` 和栅格信息。 |
| `value` | `unknown` | `undefined` | 当前叶子字段值；行容器通过 `formModel` 读取子字段值。 |
| `formModel` | `object` | 必填 | 父表单共享数据对象，递归行容器用它更新子字段。 |
| `readonly` | `boolean` | `false` | 字段与附件进入只读状态，并保留经过授权的附件下载。 |
| `gutter` | `number` | `16` | 行容器使用的 Element Plus 栅格间距。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:value` | `value` | 叶子控件值发生变化，父组件据此更新对应业务变量。 |
| `busy-change` | `fieldName, busy` | 附件上传或删除状态变化；递归时保留真实附件变量名。 |
| `error` | `Error` | 附件请求或字段处理失败。 |

## 公开方法

组件保持内部递归实现，表单级校验、取值和重置由 `ProcessFormRenderer` 统一提供。

## 关键设计

- `rowFormItem` 递归渲染子字段，并逐层透传真实的 `(fieldName, busy)`，使父表单在全部嵌套附件完成后解除提交锁。
- 叶子字段通过 `update:value` 更新；行容器子字段通过同一个 `formModel` 更新，保证递归层级共享正式提交数据。
- 控件属性来自规范化白名单；运行时 `readonly` 具有最高优先级并统一锁定模板控件。
- 附件字段复用 `WorkflowAttachmentUpload`，只保存后端安全元数据；上传、删除、绑定和对象授权仍由真实后端负责。
- 表格字段展示已规范化列，单元格渲染使用固定组件映射。
- 移动端列宽由稳定断点约束，字段树结构在动态内容更新时保持稳定。

## 最小接入示例

```js
const formModel = reactive({ applicant: '', evidenceFiles: [] })
const busyFields = reactive(new Set())

/**
 * 维护真实附件字段的提交门禁。
 * @param {string} fieldName 附件字段变量名。
 * @param {boolean} busy 是否仍有未完成写请求。
 * @returns {void} 无返回值。
 */
function handleBusyChange(fieldName, busy) {
  if (busy) busyFields.add(fieldName)
  else busyFields.delete(fieldName)
}
```

提交前必须由父级确认 `busyFields.size === 0`，再调用真实流程发起或任务完成 API。
