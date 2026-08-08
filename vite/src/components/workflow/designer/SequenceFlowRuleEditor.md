# SequenceFlowRuleEditor

## 组件简介

`SequenceFlowRuleEditor` 是排他网关和包容网关出线的受控规则编辑器。组件只接收当前流程正式表单的字段目录，按字段类型提供运算符和值控件，并输出后端 `WorkflowConditionDeploymentService` 可校验的版本化规则 JSON。组件不生成或接收任意 EL。

## 使用方式

```vue
<SequenceFlowRuleEditor
  flow-id="flow_amount"
  name="大额审批"
  gateway-type="EXCLUSIVE"
  :config="conditionRule"
  :is-default="false"
  :gateway-branches="branches"
  :field-options="fields"
  @apply="updateConditionRule"
  @make-default="setDefaultBranch"
/>
```

## Props

| Prop | 类型 | 说明 |
| --- | --- | --- |
| `flowId` | `string` | 当前 SequenceFlow 的 BPMN 标识。 |
| `name` | `string` | 当前分支名称。 |
| `config` | `object \| null` | 从作者 BPMN 回读的受控规则；未配置时为空。 |
| `isDefault` | `boolean` | 当前出线是否为网关唯一默认分支。 |
| `gatewayType` | `string` | `EXCLUSIVE` 或 `INCLUSIVE`。 |
| `gatewayBranches` | `array` | 同网关全部出线的名称、默认标志和配置状态，用于冲突/遗漏提示。 |
| `fieldConflicts` | `array` | 同一流程正式表单中的同名异构字段名；非空时阻止安全选择并显示错误。 |
| `fieldOptions` | `array` | 当前流程正式表单可写标量字段，包含 `value`、`label`、`type`、`values` 和 `valueRestricted`。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `apply` | `{ name, config }` | 应用完整分支名称和版本化规则。 |
| `make-default` | 无 | 请求父设计器把当前出线设为唯一默认分支。 |

```js
function updateConditionRule({ name, config }) {
  // 父组件通过 bpmn-js modeling 命令栈同时写入名称和受控扩展属性。
}
```

## 公开方法

无。元素切换和版本回读通过 Props 驱动。

## 关键设计

- 支持规则组之间和组内分别选择 `AND` / `OR`，每分支最多 8 组、每组 8 条、总量由后端再次限制为 32 条。
- 数值、布尔、枚举和文本字段使用不同运算符和值控件；切换字段会清空旧字段值，避免跨类型复用。
- 默认分支没有可执行条件，只保存固定 `{ version: 1, default: true }` 配置；其他分支均必须完整配置。
- 同网关分支状态用于即时提示，最终字段存在性、类型、唯一默认和冲突策略仍由真实保存/部署 API 校验。

## 最小接入示例

```js
const fields = [
  { value: 'amount', label: '申请金额（amount）', type: 'NUMBER', values: [], valueRestricted: false },
  { value: 'urgent', label: '是否紧急（urgent）', type: 'BOOLEAN', values: [
    { label: '是', value: true }, { label: '否', value: false }
  ], valueRestricted: true }
]
```
