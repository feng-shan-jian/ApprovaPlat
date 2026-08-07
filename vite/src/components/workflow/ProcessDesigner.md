# ProcessDesigner

## 组件简介

`ProcessDesigner` 是基于 `bpmn-js` 的 Flowable BPMN 编辑器。组件负责画布编辑、受控 Flowable 属性、导入导出、XML/JSON 预览、布局命令、Lint、Token 模拟和保存前即时门禁；页面负责加载模型、表单、身份选项与正式偏好，并把 `save`、`preference-save` 事件提交到真实后端。

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
    :preference="designerPreference"
    :preference-saving="preferenceSaving"
    @identity-search="searchIdentityDirectory"
    @preference-save="savePreference"
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
    await saveModel({
      requestId: crypto.randomUUID(),
      modelId: props.modelId,
      bpmnXml: xml,
      newVersion: false
    })
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
| `preference` | `object` | 服务端默认值 | 从 `wf_designer_preference` 回读的主题、网格、小地图、Lint、Token 模拟和属性面板状态。 |
| `preferenceSaving` | `boolean` | `false` | 偏好真实写库请求的加载状态。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `xml: string` | 命令栈变化后同步 XML。 |
| `change` | `xml: string` | 用户设计发生变化。 |
| `save` | `xml: string` | 本地关键门禁通过后请求页面保存。 |
| `error` | `Error` | 导入、导出或本地校验失败。 |
| `identity-search` | `{ type: 'user' \| 'group', keyword: string, capability: 'approval' \| 'claim' }` | 请求页面检索正式资格目录；直接办理人使用 `approval`，候选用户和候选组使用 `claim`。 |
| `preference-save` | `object` | 请求页面把字段完整的当前用户偏好写入正式数据库。 |

## 公开方法

| 方法 | 返回值 | 说明 |
| --- | --- | --- |
| `requestSave()` | `Promise<void>` | 执行即时门禁并触发 `save`。 |
| `getXml()` | `Promise<string>` | 获取已补齐内部任务审计监听器、可直接保存或部署的 BPMN XML。 |
| `downloadXml()` | `Promise<void>` | 下载 `.bpmn20.xml` 文件。 |
| `exportDiagram(format)` | `Promise<void>` | 按 `bpmn`、`xml` 或 `svg` 导出。 |
| `openPreview(format)` | `Promise<void>` | 打开 `xml` 或结构化 `json` 预览。 |
| `runServerValidation(showResult)` | `Promise<boolean>` | 调用保存/部署共同后端门禁并可显示结构化诊断。 |
| `fitViewport()` | `void` | 将流程完整适配到画布。 |

## 关键设计

- 表单来源支持正式模板与 Flowable 内嵌 FormData。正式模板保存为 `flowable:formKey="key_正整数"`；内嵌表单保存为 `flowable:formProperty`，覆盖六种内置类型及正式 FORM_FIELD 注册表返回的 `custom:<extensionKey>`，两种来源在同一 moddle 命令中互斥。
- 内嵌字段独立保存稳定 `id` 和可选 `variable`；`variable` 为空时使用 `id`。变量、保留前缀、日期格式、字段/枚举上限和重复值在前端即时校验，保存与部署时后端再次校验；自定义字段的精确版本、实现键和校验和冻结到 `wf_deploy_form` 快照。
- 表单来源切换和字段修改均进入 bpmn-js 命令栈；审计监听器等非表单 `extensionElements` 在重建 FormData 时保持不变。
- ServiceTask 不接受任意 Java 类名或 Spring Bean。设计器从正式扩展目录读取最新版，只在作者 XML 保存稳定键和 JSON 配置；部署编译器冻结精确版本并生成不可变执行快照。
- 用户任务的办理人、候选用户、候选组互斥写入并使用独立选项池。直接办理人只来自 `capability=approval` 目录；候选用户、角色和部门只来自 `capability=claim` 目录，角色/部门还必须至少包含一名完整可认领办理成员。候选组值继续使用后端规定的 `ROLE<id>` 或 `DEPT<id>`。
- 动态多实例通过“动态 + 会签/或签”受控模式配置。组件固定写入并行循环、`${multiInstanceHandler.getUserIds(execution)}`、`assignee` 元素变量、`${assignee}` 办理人以及对应 ALL/ANY 完成条件，不提供任意方法输入。
- 从动态模式切换为串行或普通并行时会同时清理固定 handler、元素变量、完成条件和办理人，避免属性回读把静态模式错误恢复为动态模式。
- 串行和普通并行多实例保留静态集合、元素变量和完成条件编辑能力，但不能引用 `multiInstanceHandler`；最终表达式白名单由后端再次强制校验。
- 更新已导入的静态多实例时只修改面板负责的核心字段，不替换整个循环对象，因此未编辑的标准数据引用、索引变量和 `loopCardinality` 可以稳定往返。
- 用户任务的“创建、分配、完成”审计监听器是后端运行时身份审计的内部技术字段，不在属性面板展示。保存、下载和 `getXml()` 会无条件重建每个用户任务的固定 `delegateExpression="${userTaskListener}"` 三项监听器，因而错误命名空间、未知属性、字段注入、重复事件和非法实现都不会进入持久化结果；其他业务扩展保持不变。
- 切换办理方式时会立即清理旧身份属性；在新身份尚未选择前，属性面板仍保留用户刚选择的模式，避免同步回读把界面错误重置为“办理人”。
- 身份选择器禁止自由创建值，并对远程检索做 250ms 防抖；用户审批资格及身份真伪仍由保存、部署后端校验兜底。
- 新建流程只有在 `model.formId` 明确指定时才预绑定发起表单，不会隐式选择表单列表第一项。
- 服务任务只提供受控扩展注册表入口，作者 XML 保存稳定扩展键和 JSON 配置，最终版本、实现和校验和由后端部署时冻结。
- 业务规则任务独立于通用服务任务，只能选择后端 DMN 来源目录中的精确 `decisionId`；作者 XML 写入 `flowable:rules`，流程部署时创建同部署冻结 DMN 副本。
- 保存事件只交付 XML，不在组件内绕过页面权限或直接调用接口。
- 显式校验直接调用无副作用 `/workflow/model/validate`；保存前必须再次通过同一服务端 BPMN、身份和表单门禁。
- BPMN/XML 导入设置 2 MiB 上限，失败不覆盖当前画布；BPMN/XML 导出继续自动重建内部审计监听器，SVG 使用 Modeler 图形输出。
- JSON 预览通过 DOM 结构递归转换，不使用字符串替换；XML、属性和文本均保持明确层级。
- 网格显示与 `gridSnapping` 同步，避免只显示网格却不吸附；小地图、Lint 和 Token 模拟使用真实扩展服务。小地图切换按钮使用 `+ / ×` 紧凑符号，原生 `title` 继续提供打开或关闭提示，避免动作文本遮挡画布。
- 偏好由页面调用正式 API 保存，服务端成功回读前不把抽屉草稿或内存状态视为已应用配置。
- 页面必须为一次用户保存意图生成 UUID `requestId`；响应丢失后的同内容重试复用该值，只有取得后端真实 `modelId` 后才清除，服务端据此返回首次落库结果而不重复建版。
- XML 序列化开始至后端保存结束期间锁定画布、属性面板和命令栈，阻止重复保存以及“已保存响应覆盖保存期间新修改”的竞态。
- 保存按钮要求 `workflow:model:save` 权限；`workflow:model:designer` 只负责进入设计页并读取设计上下文，后端继续独立校验保存权限。
