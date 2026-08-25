# CelExpressionEditor

## 组件简介

`CelExpressionEditor` 是受控 CEL ServiceTask 的结构化配置编辑器。它编辑表达式、结果变量、结果类型和显式输入变量白名单，并向父组件输出字段顺序稳定的 JSON；父组件负责通过 bpmn-js 命令栈写入作者 XML。

## 使用方式

```vue
<CelExpressionEditor
  v-model="propertyState.extensionConfig"
  @change="updateControlledTask"
/>
```

## Props

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `modelValue` | `string` | `approvaExtensionConfig` 中保存的 CEL 配置 JSON。必须包含 `expression`、`resultVariable`、`resultType` 和 `variables`。 |

配置只允许四种确定性标量类型：`BOOL`、`INT`、`DOUBLE`、`STRING`。`variables` 最多 32 项，每项只包含 `name` 和 `type`。

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `configJson: string` | 输入发生有效结构变更后，返回紧凑且字段顺序稳定的完整 JSON。 |
| `change` | `configJson: string` | 与 `update:modelValue` 同步发出，通知父组件把配置写入 BPMN 命令栈。 |

## 公开方法

无。组件通过 Props、`v-model` 和 Emits 工作。

## 关键设计

- 组件只维护当前编辑草稿，不访问后端、Modeler、`localStorage` 或 `sessionStorage`。
- 从作者 XML 回读的 JSON 如果无法解析、字段缺失或含有额外字段，组件进入显式错误态且不会自动覆盖原 XML。
- 输入变量和结果变量使用同一标识符约束，并拒绝 Flowable 多实例变量及平台内部前缀。
- 每次提交会裁剪字段首尾空白、按变量名排序并固定 JSON 字段顺序，便于部署时与服务端规范配置比对。
- 客户端结构校验用于即时反馈；CEL 静态类型、表达式资源限制、版本校验和及运行值类型仍由服务端 `WorkflowCelSandbox` 强制执行。
- 结果变量不能覆盖输入变量，防止表达式执行后改变后续节点对输入契约的理解。

## 最小接入示例

```js
/**
 * 将 CEL 编辑器返回的配置写入当前 ServiceTask。
 * @param {string} configJson 字段和变量顺序稳定的完整 CEL 配置 JSON。
 * @returns {void} 通过既有命令栈更新作者 XML。
 */
function updateCelConfiguration(configJson) {
  propertyState.extensionConfig = configJson
  updateControlledTask()
}
```
