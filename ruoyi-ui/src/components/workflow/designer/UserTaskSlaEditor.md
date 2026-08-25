# UserTaskSlaEditor

## 组件简介

`UserTaskSlaEditor` 为 BPMN `UserTask` 提供结构化审批 SLA 配置。正式业务日历、具备审批能力的用户和启用的 BPMN Escalation 目录直接替换定时表达式、任意接收人及任意事件编码输入。

## 使用方式

```vue
<UserTaskSlaEditor
  v-model="state.sla"
  :calendars="slaCalendarOptions"
  :escalation-options="escalationEventOptions"
  :assignee-options="identityOptions.assignees"
  :loading="slaLoading"
  :identity-loading="identityLoading"
  @identity-search="searchAssignees"
  @change="updateSla"
/>
```

## Props

| 属性 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `modelValue` | `object` | 必填 | 八个 `approva.sla.*` 作者字段对应的结构化值。 |
| `calendars` | `array` | `[]` | `/workflow/sla/calendars/enabled` 返回的正式选项。 |
| `escalationOptions` | `array` | `[]` | 启用的 `ESCALATION` 编码选项。 |
| `assigneeOptions` | `array` | `[]` | 具备审批能力的正式用户选项。 |
| `loading` | `boolean` | `false` | 日历和事件目录加载状态。 |
| `identityLoading` | `boolean` | `false` | 用户目录检索状态。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `object` | 更新结构化配置。 |
| `change` | `object` | 请求父组件校验并写入 BPMN。 |
| `identity-search` | `string` | 请求父组件按审批能力检索用户。 |

## 公开方法

无。

## 关键设计

- 数值控件只表达工作分钟和次数，业务日历负责将工作分钟解析为实际到期时间。
- 升级目标通过正式用户或受控 Escalation 编码目录选择。
- 启用前加载正式日历和至少一个升级目标；首次启用自动选中首个合法目录项，确保写入完整作者配置。
- 跨字段约束、目录有效性和最终 XML 写入由 `ProcessDesigner` 统一执行，后端保存及部署时再次校验。

## 最小接入示例

```js
const sla = reactive({
  enabled: true,
  calendarKey: 'DEFAULT',
  reminderMinutes: 60,
  reminderRepeatMinutes: 60,
  maxReminders: 2,
  escalationMinutes: 240,
  escalationUserId: '1',
  escalationEventCode: ''
})
```
