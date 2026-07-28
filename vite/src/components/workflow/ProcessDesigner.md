# ProcessDesigner

## 组件简介

`ProcessDesigner` 是基于 `bpmn-js` 的 Flowable BPMN 编辑器。组件负责画布编辑、受控 Flowable 属性、撤销/重做、XML 导出和保存前即时门禁；页面负责加载模型、表单与身份选项，并把 `save` 事件提交到真实后端。

## 使用方式

```vue
<template>
  <ProcessDesigner
    v-if="ready"
    v-model="bpmnXml"
    :model="model"
    :forms="formOptions"
    :identity-options="identityOptions"
    :identity-loading="identityLoading"
    :saving="saving"
    @identity-search="searchIdentityDirectory"
    @save="saveToServer"
    @error="showError"
  />
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { listForms } from '@/api/workflow/form'
import { listApprovalUserOptions, listClaimableIdentityOptions } from '@/api/workflow/identity'
import { getModel, getModelBpmnXml, saveModel } from '@/api/workflow/model'
import ProcessDesigner from '@/components/workflow/ProcessDesigner.vue'

const props = defineProps({
  modelId: { type: String, required: true }
})
const ready = ref(false)
const saving = ref(false)
const identityPending = ref(0)
const identityLoading = computed(() => identityPending.value > 0)
const bpmnXml = ref('')
const model = reactive({})
const formOptions = ref([])
const identityOptions = reactive({ assignees: [], candidateUsers: [], candidateGroups: [] })

/**
 * 从正式资格目录刷新直接办理人、候选用户或候选组选项。
 * @param {{type: 'user'|'group', capability: 'approval'|'claim', keyword?: string}} request 身份类型、资格和检索词。
 * @returns {Promise<void>} 对应选项完成刷新后结束。
 */
async function searchIdentityDirectory({ type, capability, keyword = '' }) {
  identityPending.value += 1
  try {
    if (type === 'user' && capability === 'approval') {
      const response = await listApprovalUserOptions({ keyword, pageNum: 1, pageSize: 50 })
      identityOptions.assignees = response.rows || []
      return
    }
    if (type === 'user' && capability === 'claim') {
      const response = await listClaimableIdentityOptions({
        type: 'user', keyword, pageNum: 1, pageSize: 50
      })
      identityOptions.candidateUsers = response.rows || []
      return
    }
    if (type !== 'group' || capability !== 'claim') throw new TypeError('身份目录请求不合法')
    const [roles, departments] = await Promise.all([
      listClaimableIdentityOptions({ type: 'role', keyword, pageNum: 1, pageSize: 50 }),
      listClaimableIdentityOptions({ type: 'dept', keyword, pageNum: 1, pageSize: 50 })
    ])
    identityOptions.candidateGroups = [...(roles.rows || []), ...(departments.rows || [])]
  } finally {
    identityPending.value -= 1
  }
}

/**
 * 将组件校验通过的完整 BPMN XML 保存到真实模型接口。
 * @param {string} xml 完整 BPMN 2.0 XML 正文。
 * @returns {Promise<void>} 后端提交完成后结束。
 */
async function saveToServer(xml) {
  saving.value = true
  try {
    await saveModel({ modelId: props.modelId, bpmnXml: xml, newVersion: false })
    ElMessage.success('流程设计保存成功')
  } finally {
    saving.value = false
  }
}

/**
 * 显示设计器导入、导出或结构校验错误。
 * @param {Error} error 设计器返回的错误对象。
 * @returns {void} 无返回值。
 */
function showError(error) {
  ElMessage.error(error?.message || '流程设计器处理失败')
}

onMounted(async () => {
  const [modelResponse, xmlResponse, formsResponse] = await Promise.all([
    getModel(props.modelId),
    getModelBpmnXml(props.modelId),
    listForms({ pageNum: 1, pageSize: 50 }),
    searchIdentityDirectory({ type: 'user', capability: 'approval' }),
    searchIdentityDirectory({ type: 'user', capability: 'claim' }),
    searchIdentityDirectory({ type: 'group', capability: 'claim' })
  ])
  Object.assign(model, modelResponse.data || {})
  bpmnXml.value = xmlResponse.data || ''
  formOptions.value = formsResponse.rows || []
  ready.value = true
})
</script>
```

## Props

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `modelValue` | `string` | `''` | 当前 BPMN XML；为空时按模型元数据创建初始流程。 |
| `model` | `object` | `{}` | `modelKey`、`modelName`、`formId` 等模型元数据。 |
| `forms` | `array` | `[]` | 正式表单选项，每项至少包含 `formId`、`formName`。 |
| `identityOptions` | `object` | `{ assignees: [], candidateUsers: [], candidateGroups: [] }` | 服务端按直接办理资格和完整候选认领资格隔离的身份选项。 |
| `height` | `string` | `calc(100vh - 128px)` | 设计器稳定高度。 |
| `saving` | `boolean` | `false` | 页面真实保存请求的加载状态。 |
| `identityLoading` | `boolean` | `false` | 用户、角色或部门远程检索的加载状态。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `xml: string` | 命令栈变化后同步 XML。 |
| `change` | `xml: string` | 用户设计发生变化。 |
| `save` | `xml: string` | 本地关键门禁通过后请求页面保存。 |
| `error` | `Error` | 导入、导出或本地校验失败。 |
| `identity-search` | `{ type: 'user' \| 'group', keyword: string, capability: 'approval' \| 'claim' }` | 请求页面检索正式资格目录；直接办理人使用 `approval`，候选用户和候选组使用 `claim`。 |

## 公开方法

| 方法 | 返回值 | 说明 |
| --- | --- | --- |
| `requestSave()` | `Promise<void>` | 执行即时门禁并触发 `save`。 |
| `getXml()` | `Promise<string>` | 获取格式化 BPMN XML。 |
| `downloadXml()` | `Promise<void>` | 下载 `.bpmn20.xml` 文件。 |
| `fitViewport()` | `void` | 将流程完整适配到画布。 |

## 关键设计

- 开始节点表单保存为 `flowable:formKey="key_正整数"`，与后端快照规则一致。
- 用户任务的办理人、候选用户、候选组互斥写入并使用独立选项池。直接办理人只来自 `capability=approval` 目录；候选用户、角色和部门只来自 `capability=claim` 目录，角色/部门还必须至少包含一名完整可认领办理成员。候选组值继续使用后端规定的 `ROLE<id>` 或 `DEPT<id>`。
- 动态多实例通过“动态 + 会签/或签”受控模式配置。组件固定写入并行循环、`${multiInstanceHandler.getUserIds(execution)}`、`assignee` 元素变量、`${assignee}` 办理人以及对应 ALL/ANY 完成条件，不提供任意方法输入。
- 从动态模式切换为串行或普通并行时会同时清理固定 handler、元素变量、完成条件和办理人，避免属性回读把静态模式错误恢复为动态模式。
- 串行和普通并行多实例保留静态集合、元素变量和完成条件编辑能力，但不能引用 `multiInstanceHandler`；最终表达式白名单由后端再次强制校验。
- 更新已导入的静态多实例时只修改面板负责的核心字段，不替换整个循环对象，因此未编辑的标准数据引用、索引变量和 `loopCardinality` 可以稳定往返。
- 新模型的默认用户任务及画布中新建用户任务会自动写入“创建、分配、完成”三个固定审计事件；导入模型缺少事件时可在属性面板补齐，存在非法实现时可恢复标准配置。组件为每个事件生成唯一 `delegateExpression="${userTaskListener}"`，保存前要求三项完整并拒绝任意 Bean、class、expression、字段注入或重复事件，与后端模型门禁保持一致。
- 切换办理方式时会立即清理旧身份属性；在新身份尚未选择前，属性面板仍保留用户刚选择的模式，避免同步回读把界面错误重置为“办理人”。
- 身份选择器禁止自由创建值，并对远程检索做 250ms 防抖；用户审批资格及身份真伪仍由保存、部署后端校验兜底。
- 新建流程只有在 `model.formId` 明确指定时才预绑定发起表单，不会隐式选择表单列表第一项。
- 服务任务只提供受控 Java 类和 `delegateExpression` 两种入口，最终安全白名单仍由后端强制执行。
- 保存事件只交付 XML，不在组件内绕过页面权限或直接调用接口。
- XML 序列化开始至后端保存结束期间锁定画布、属性面板和命令栈，阻止重复保存以及“已保存响应覆盖保存期间新修改”的竞态。
- 保存按钮要求 `workflow:model:save` 权限；`workflow:model:designer` 只负责进入设计页并读取设计上下文，后端继续独立校验保存权限。
