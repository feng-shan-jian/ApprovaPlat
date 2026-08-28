# AutoCopyRuleEditor

## 组件简介与作用

`AutoCopyRuleEditor` 用于编辑流程或用户任务上的结构化自动抄送规则。组件只维护尚未应用的页面草稿；设计者点击“应用自动抄送规则”后，父级 `ProcessDesigner` 才会校验并把完整规则写入 `approva.autoCopyRules` Flowable 扩展属性。

固定身份检索通过 `identity-search` 事件交给模型设计页，由父级统一调用正式 `/workflow/identity/options?capability=copy` 身份目录。

## 使用方式

```vue
<AutoCopyRuleEditor
  v-model="state.autoCopyRules"
  :trigger-options="autoCopyTriggerOptions"
  :user-options="identityOptions.autoCopyUsers"
  :group-options="identityOptions.autoCopyGroups"
  :form-field-options="autoCopyFormFieldOptions"
  :identity-loading="identityLoading"
  @identity-search="emit('identity-search', $event)"
  @change="emit('auto-copy-change', $event)"
/>
```

## Props

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `modelValue` | `Array` | `[]` | 已写入当前 BPMN 元素的规则集合。 |
| `triggerOptions` | `Array` | `[]` | 当前元素允许的触发时机；流程仅允许 `PROCESS_COMPLETED`，用户任务允许 `NODE_ARRIVED` 和 `NODE_COMPLETED`。 |
| `userOptions` | `Array` | `[]` | 具备正式抄送资格的用户目录返回的 `{ value, label, type }` 选项。 |
| `groupOptions` | `Array` | `[]` | 具备正式抄送资格的角色、部门目录选项，值为 `ROLE<id>` 或 `DEPT<id>`。 |
| `formFieldOptions` | `Array` | `[]` | 当前流程已引用正式表单中的可写标量字段。 |
| `identityLoading` | `boolean` | `false` | 正式身份目录加载状态。 |
| `maxRules` | `number` | `10` | 单个 BPMN 元素的规则数量上限。 |
| `maxSources` | `number` | `20` | 单条规则的接收人来源数量上限。 |
| `maxValues` | `number` | `100` | 单个来源的固定身份或字段数量上限。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `rules: Array` | 显式应用后同步字段完整的规则集合。 |
| `change` | `rules: Array` | 请求父设计器原子写入完整规则；空数组表示删除属性。 |
| `identity-search` | `{ target, keyword }` | `target` 为 `autoCopyUsers` 或 `autoCopyGroups`，由父页面调用正式目录。 |

## 公开方法

规则通过 props 和 emits 的声明式契约受控管理。

## 关键设计思路

- 规则结构与后端 `WorkflowAutoCopyRuleContract` 一致：`id`、`trigger`、`recipients`；来源包含 `type` 和 `values`。
- 固定用户只接受正整数用户主键；组只接受 `ROLE<id>`、`DEPT<id>`；表单字段只接受受控变量名。
- 发起人来源的值固定为空。固定用户、角色、部门和表单字段都必须至少选择一个值。
- 编辑过程保留在组件草稿中；完整规则通过校验后才提交父级写入 BPMN，切换来源时同步清理跨类型值。
- 组件只做即时门禁。保存、部署和运行时仍由后端复核正式身份、规则位置、字段值和对象可见性。

## 最小接入示例

```vue
<script setup>
import AutoCopyRuleEditor from './AutoCopyRuleEditor.vue'

const rules = ref([])
const triggers = [{ label: '节点到达', value: 'NODE_ARRIVED' }]
const identityOptions = reactive({ autoCopyUsers: [], autoCopyGroups: [] })

function searchIdentity(request) {
  // 页面在这里调用正式身份目录并更新对应选项池。
}
</script>

<template>
  <AutoCopyRuleEditor
    v-model="rules"
    :trigger-options="triggers"
    :user-options="identityOptions.autoCopyUsers"
    :group-options="identityOptions.autoCopyGroups"
    @identity-search="searchIdentity"
  />
</template>
```
