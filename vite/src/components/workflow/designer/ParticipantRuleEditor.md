# ParticipantRuleEditor

`ParticipantRuleEditor` 是流程设计器内的受控参与者规则编辑器。它同时支持流程级发起范围和单实例 `UserTask` 办理人规则，只接受正式身份目录与正式表单字段，不提供用户主键、组编码或表达式输入框。

## 使用方式

```vue
<ParticipantRuleEditor
  v-model="state.participantRule"
  mode="task"
  :identity-options="identityOptions"
  :form-fields="formUserFields"
  :loading="identityLoading"
  @identity-search="searchIdentity"
  @identity-resolve="resolveSelectedIdentity"
  @change="persistParticipantRule"
/>
```

## Props

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `mode` | `'start' \| 'task'` | `start` 编辑流程发起范围，`task` 编辑单实例任务办理人。 |
| `modelValue` | `object` | `{ type, targetIds, formField }` 受控规则值。 |
| `identityOptions` | `object` | 按能力隔离的正式用户、角色、部门选项池。 |
| `formFields` | `array` | 当前任务正式表单中的字段选项。 |
| `loading` | `boolean` | 身份目录加载状态。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `rule` | 发布字段完整的新规则对象。 |
| `change` | `rule` | 请求父组件把规则写入 BPMN 命令栈。 |
| `identity-search` | `{ target, keyword }` | 按规则固定的目录能力执行远程检索。 |
| `identity-resolve` | `{ target, values }` | 重开模型或远程翻页后，批量核验并回显已选正式目录对象。 |

## 公开方法

组件不公开实例方法；所有状态通过 `v-model` 和事件管理。

## 关键设计

- 角色和部门选项由正式目录返回，组件不允许输入 `ROLE`、`DEPT` 编码或表达式。
- 流程级只提供公开、指定用户、指定角色、指定部门四种范围；单实例任务只提供固定用户、候选用户、候选角色/部门、发起人本人、发起人直属上级、指定部门负责人、发起人所在部门内指定角色、表单用户字段八种规则。
- 切换规则时会清除旧目标和旧表单字段，防止无关身份残留到作者 BPMN。
- 无匹配策略固定为 `FAIL`，由后端部署快照和运行时解析共同执行。
- 单选规则在界面层保持一个目标，部署时后端仍会再次验证唯一性、有效性和审批资格。
- 已选对象不在当前远程分页时发出 `identity-resolve`；响应前显示“正在核验已选对象”，响应后保留停用/删除对象的稳定名称但禁止再次选择，不向设计者显示原始主键。
- 该组件只管理人工发起范围和单实例 `UserTask`。会签/或签成员来源继续由多实例组件和服务独立维护，不写入本组件规则。

## 最小接入示例

```js
const participantRule = ref({ type: 'STARTER_MANAGER', targetIds: [], formField: '' })
const identityOptions = reactive({
  assignees: [], candidateUsers: [], candidateGroups: [], candidateRoles: [],
  activeUsers: [], activeRoles: [], activeDepts: []
})

async function resolveSelectedIdentity({ target, values }) {
  const response = await resolveIdentityOptions({
    type: target === 'activeDepts' ? 'dept' : 'user',
    capability: target === 'assignees' ? 'approval' : '',
    values
  })
  mergeResolvedOptions(target, response.data || [])
}
```
