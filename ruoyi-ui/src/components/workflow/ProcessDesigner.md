# ProcessDesigner

## 组件简介

`ProcessDesigner` 是基于 `bpmn-js` 的 Flowable BPMN 编辑器。主组件负责 Modeler 生命周期、选择状态、命令栈、导入导出、保存时序和对外事件；页面负责加载模型、表单、身份选项，并把模型保存提交到真实后端、把非业务界面偏好写入当前用户浏览器存储。

## 职责模块

设计器按业务内聚性拆分为三个领域模块，主组件集中管理 Modeler 生命周期、目录装载与保存编排：

| 模块 | 负责范围 | 边界 |
| --- | --- | --- |
| `formParticipantDomain.js` | 表单来源与权限、参与者、任务分配、多实例 | 只读取当前 BPMN 和正式身份/表单目录；写入统一经过主组件提供的 `bpmn-js` modeling 命令栈。 |
| `routingCallActivityDomain.js` | 条件路由、DMN、CallActivity 引用与变量映射 | 目录查询函数由主组件注入；受控条件和调用映射仍使用固定技术协议。 |
| `extensionEventSlaDomain.js` | 服务扩展、业务监听器、错误/升级事件、自动抄送、SLA | 按属性键合并面板变更，并保留当前 BPMN 中的其他权威扩展属性。 |

三个模块接收主组件提供的选择状态和 Modeler 操作。所有模型变更通过 `modeling.updateProperties`、`modeling.updateModdleProperties` 或命令栈组合命令完成，因此撤销/重做语义保持一致。保存按“本地结构门禁 → 序列化一次 → 服务端权威校验 → `save` emit”执行，本地与服务端门禁共同完成校验。

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
    @identity-resolve="resolveSelectedIdentities"
    @preference-save="savePreference"
    @preference-reset="restoreDefaultPreference"
    @save="saveToServer"
    @error="showError"
  />
</template>

<script setup>
import { ElMessage } from 'element-plus'
import { listForms } from '@/api/workflow/form'
import {
  listApprovalUserOptions, listClaimableIdentityOptions,
  listIdentityOptions, resolveIdentityOptions
} from '@/api/workflow/identity'
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
const identityOptions = reactive({
  assignees: [], candidateUsers: [], candidateGroups: [], candidateRoles: [],
  activeUsers: [], activeRoles: [], activeDepts: [],
  autoCopyUsers: [], autoCopyGroups: []
})

/**
 * 从正式资格目录刷新直接办理人、候选用户或候选组选项。
 * @param {{target: string, type: 'user'|'role'|'dept'|'group', capability: ''|'approval'|'claim'|'copy', keyword?: string}} request 选项池、身份类型、资格和检索词。
 * @returns {Promise<void>} 对应选项完成刷新后结束。
 */
async function searchIdentityDirectory({ target, type, capability, keyword = '' }) {
  identityPending.value += 1
  try {
    if (target === 'assignees' && type === 'user' && capability === 'approval') {
      const response = await listApprovalUserOptions({ keyword, pageNum: 1, pageSize: 50 })
      identityOptions.assignees = response.rows || []
      return
    }
    if (target === 'candidateGroups' || target === 'autoCopyGroups') {
      const loader = target === 'candidateGroups' ? listClaimableIdentityOptions : listIdentityOptions
      const queryCapability = target === 'autoCopyGroups' ? { capability: 'copy' } : {}
      const [roles, depts] = await Promise.all([
        loader({ type: 'role', ...queryCapability, keyword, pageNum: 1, pageSize: 50 }),
        loader({ type: 'dept', ...queryCapability, keyword, pageNum: 1, pageSize: 50 })
      ])
      identityOptions[target] = [...(roles.rows || []), ...(depts.rows || [])]
      return
    }
    if (!Object.prototype.hasOwnProperty.call(identityOptions, target)) {
      throw new TypeError('身份目录目标不合法')
    }
    const loader = capability === 'claim' ? listClaimableIdentityOptions : listIdentityOptions
    const response = await loader({ type, capability, keyword, pageNum: 1, pageSize: 50 })
    identityOptions[target] = response.rows || []
  } finally {
    identityPending.value -= 1
  }
}

async function resolveSelectedIdentities({ target, type, capability, values }) {
  const response = await resolveIdentityOptions({ type, capability, values })
  identityOptions[target] = mergeByValue(identityOptions[target], response.data || [])
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
      modelId: props.modelId,
      bpmnXml: xml,
      expectedRevision: props.model.revision
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
    searchIdentityDirectory({ target: 'assignees', type: 'user', capability: 'approval' }),
    searchIdentityDirectory({ target: 'candidateUsers', type: 'user', capability: 'claim' }),
    searchIdentityDirectory({ target: 'candidateGroups', type: 'group', capability: 'claim' }),
    searchIdentityDirectory({ target: 'candidateRoles', type: 'role', capability: 'claim' }),
    searchIdentityDirectory({ target: 'activeUsers', type: 'user', capability: '' }),
    searchIdentityDirectory({ target: 'activeRoles', type: 'role', capability: '' }),
    searchIdentityDirectory({ target: 'activeDepts', type: 'dept', capability: '' }),
    searchIdentityDirectory({ target: 'autoCopyUsers', type: 'user', capability: 'copy' }),
    searchIdentityDirectory({ target: 'autoCopyGroups', type: 'group', capability: 'copy' })
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
| `identityOptions` | `object` | 九个隔离选项池 | 服务端按直接办理、指定身份、完整候选认领和自动抄送资格隔离的身份选项；指定角色、部门分别使用 `activeRoles`、`activeDepts`。 |
| `height` | `string` | `calc(100vh - 128px)` | 设计器稳定高度；页面级接入推荐传入 `100%`，由可用工作区决定实际高度。 |
| `saving` | `boolean` | `false` | 页面真实保存请求的加载状态。 |
| `identityLoading` | `boolean` | `false` | 用户、角色或部门远程检索的加载状态。 |
| `preference` | `object` | 当前协议默认值 | 从当前用户版本化 `localStorage` 键回读的主题、网格、小地图、Token 模拟和属性面板状态。 |
| `preferenceSaving` | `boolean` | `false` | 页面写入浏览器存储期间的交互锁定状态。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `update:modelValue` | `xml: string` | 命令栈变化后同步 XML。 |
| `change` | `xml: string` | 用户设计发生变化。 |
| `save` | `xml: string` | 本地关键门禁通过后请求页面保存。 |
| `error` | `Error` | 导入、导出或本地校验失败。 |
| `identity-search` | `{ target, type: 'user' \| 'role' \| 'dept' \| 'group', keyword: string, capability: '' \| 'approval' \| 'claim' \| 'copy' }` | 请求页面检索正式目录；指定角色、部门使用 `activeRoles`、`activeDepts` 目标池和启用对象目录，最终办理资格及展开人数由后端校验。 |
| `identity-resolve` | `{ target, type, capability, values }` | 请求页面通过 `/workflow/identity/options/resolve` 批量核验并回显当前分页外的已选正式对象。 |
| `preference-save` | `object` | 请求页面按六字段白名单写入当前用户浏览器存储。 |
| `preference-reset` | 无 | 请求页面只删除当前用户当前协议版本的偏好键并恢复默认值。 |

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

- Participant 的 `processRef` 始终绑定真实 `bpmn:Process` 根元素；属性面板修改被引用 Process 的稳定 id，并保持该 Process 原有的 `isExecutable` 状态。异常导入缺失 Process 时补建不可执行空池；保存门禁从 `Definitions.rootElements` 读取全部流程，并递归校验子流程中的任务和边界事件。
- 表单来源支持正式模板与 Flowable 内嵌 FormData。正式模板保存为 `flowable:formKey="key_正整数"`；内嵌表单保存为 `flowable:formProperty`，覆盖六种内置类型及正式 FORM_FIELD 注册表返回的 `custom:<extensionKey>`，两种来源在同一 moddle 命令中互斥。
- 节点字段权限目录只来自当前绑定的正式模板；隐藏、只读、可编辑和必填策略使用受控 `flowable:formProperty` 随模型保存。部署时后端将其编译进当前节点不可变表单快照，模板后续新增字段按节点批量默认策略处理。
- 内嵌字段独立保存稳定 `id` 和可选 `variable`；`variable` 为空时使用 `id`。变量、保留前缀、日期格式、字段/枚举上限和重复值在前端即时校验，保存与部署时后端再次校验；自定义字段的精确版本、实现键和校验和冻结到 Flowable 业务制品 `approvaplat/forms-v1.json`。
- 表单来源切换和字段修改均进入 bpmn-js 命令栈；审计监听器等非表单 `extensionElements` 在重建 FormData 时保持不变。
- ServiceTask 从正式扩展目录选择最新版，作者 XML 只保存稳定键和 JSON 配置；部署编译器冻结精确版本并生成不可变执行快照。
- 用户任务的办理人、候选用户、候选组互斥写入并使用独立选项池。直接办理人只来自 `capability=approval` 目录；候选用户、角色和部门只来自 `capability=claim` 目录，角色/部门还必须至少包含一名完整可认领办理成员。候选组值继续使用后端规定的 `ROLE<id>` 或 `DEPT<id>`。
- 流程级发起范围固定为公开、指定用户、指定角色、指定部门四类；单实例 UserTask 固定为固定用户、候选用户、候选角色/部门、发起人本人、发起人直属上级、指定部门负责人、发起人所在部门内指定角色、表单用户字段八类。作者 BPMN 保存 `approva.startScope.*` / `approva.participant.*` 的规则版本、类型、目标、表单字段和 `FAIL` 策略，部署时后端剥离作者属性并冻结到正式快照。
- 需要目录目标或表单字段的规则支持分步编辑；切换规则后产生的暂时空值留在当前画布，正式保存门禁要求目标和字段完整。`FORM_USER` 与自动抄送 `FORM_USER_FIELD` 共用可见、可读单值字段目录：目录包含只读用户字段，隐藏字段、读取权限关闭字段、日期、布尔、附件和多值字段在构建目录时过滤；`FORM_USER` 额外遵守后端参与者变量名语法，流程级自动抄送遇到同名字段契约冲突时失败关闭。
- 重开模型时，当前远程分页外的已选身份通过 `/workflow/identity/options/resolve` 回显正式名称和实时可用状态；已删除对象使用稳定不可用文案，页面始终展示业务名称。
- 会签/或签提供“办理时选择”“发起时选择”“指定用户”“指定角色”“指定部门”五种受控来源。前两类分别固定写入 `${multiInstanceHandler.getUserIds(execution)}`、`${multiInstanceHandler.getStartUserIds(execution)}`；三类指定来源统一写入 `${multiInstanceHandler.getConfiguredUserIds(execution)}`，并在 UserTask 的 `flowable:properties` 中保存 `approva.multiInstance.identityType=USER|ROLE|DEPT` 与逗号分隔的 `approva.multiInstance.identityIds`。角色和部门只持久化正式主键，每次进入节点时由后端按实时 RBAC 展开为 1 至 100 名具备审批资格的真实用户。
- 五种来源均固定使用并行循环、`assignee` 元素变量和 `${assignee}` 办理人。会签完成条件固定为 `${nrOfCompletedInstances == nrOfInstances}`；或签固定为 `${nrOfCompletedInstances > 0}`，首名办理人完成后由 Flowable 取消其余实例。保存、部署和节点进入都会复核属性完整性、身份状态、重复值、展开人数与审批资格，生成的运行时任务仅携带真实 `assignee`。
- 指定用户统一写入 `identityType=USER` 和正式身份属性；模型回读后首次修改会把已有指定用户配置迁移到该协议。
- Process 和 UserTask 可分别配置流程完成、节点到达和节点完成自动抄送。固定用户、角色和部门仅来自 `capability=copy` 正式目录，发起人与正式表单标量字段为受控动态来源；流程级表单目录汇总顶层申请开始节点及任意层级用户任务，子流程开始事件保持作用域内部语义。规则显式应用后以 `approva.autoCopyRules` JSON 写入 BPMN，并在修改循环、SLA 或通用属性时稳定保留。
- 自动抄送属性最多 8192 个字符、10 条规则，每条最多 20 个来源、每个来源最多 100 个值。设计器保存前复核触发位置与表单字段，后端保存、部署冻结和运行时继续复核身份有效性、对象可见性及幂等触发。
- 受控整改循环面向 UserTask，判断字段目录包含该节点正式模板或内嵌 FormData 的可写标量字段。设计器固定写入 `approva.controlledLoop.*` 五项属性，达到最大轮次时由后端返回稳定业务冲突。
- 受控整改循环在画布节点上显示最大轮次徽标。面板采用“填写草稿后显式应用”，完整属性才进入 BPMN 命令栈；布尔和静态枚举值从正式目录选择。部署门禁只接受受控整改循环，标准 `standardLoopCharacteristics` 保持 XML 往返能力。
- 从动态模式切换为串行或普通并行时会同时清理固定 handler、元素变量、完成条件和办理人，确保静态模式按自身属性回读。
- 串行和普通并行多实例保留静态集合、元素变量和完成条件编辑能力，并使用后端表达式白名单校验。
- 更新已导入的静态多实例时修改面板负责的核心字段，并保留原循环对象中的标准数据引用、索引变量和 `loopCardinality`，使这些字段稳定往返。
- 用户任务的“创建、分配、完成”审计监听器由后端运行时身份审计使用。保存、下载和 `getXml()` 会重建每个用户任务固定的 `delegateExpression="${userTaskListener}"` 三项监听器，并以允许的命名空间、属性和事件生成持久化结果；属性面板只展示业务字段，其他业务扩展保持不变。
- 切换办理方式时会立即清理上一身份来源属性；在新身份完成选择前，属性面板持续显示用户刚选择的模式，保持交互上下文稳定。
- 身份选择器使用封闭目录选择并对远程检索做 250ms 防抖；保存和部署后端再次校验用户审批资格与身份真伪。
- 新建流程以显式 `model.formId` 作为预绑定发起表单的唯一依据。
- 服务任务只提供受控扩展注册表入口，作者 XML 保存稳定扩展键和 JSON 配置，最终版本、实现和校验和由后端部署时冻结。
- 简单不可变 `taskCapabilityMap` 直接替换 `serviceTaskLike` 合并类型和布尔能力推断，统一提供任务创建、转换目标、属性面板类型与运行语义。ServiceTask、SendTask、ReceiveTask、BusinessRuleTask 各自使用明确面板类型。
- 高级元素面板提供 ServiceTask、SendTask、ReceiveTask 和 BusinessRuleTask。ManualTask 作为导入、渲染、基础信息编辑、保存与导出元素保留，属性面板明确提示其 Flowable 自动通过语义。
- ReceiveTask 面板展示真实 `/workflow/runtime-event/receive` 契约；调用方使用 `X-Integration-Token`、当前 activityId、互斥实例关联条件和凭据变量白名单触发，正式状态全部由后端保存。
- 选择 HTTP 或 SQL 扩展时设计器自动开启 `flowable:async`；运行失败由 Flowable Job 按引擎/BPMN 重试配置处理，最终死信保留在 Flowable 原生表中。
- 业务规则任务独立于通用服务任务，只能选择后端 DMN 来源目录中的精确 `decisionId`；作者 XML 写入 `flowable:rules`，流程部署时创建同部署冻结 DMN 副本。
- 通过 bpmn-js“更改元素”把 UserTask 转换为业务规则任务或其他任务时，转换命令会在同一撤销单元内清理任务监听器、表单、办理规则、SLA、自动抄送、受控整改循环和受控多实例状态；普通 BPMN 循环、通用执行监听器及普通扩展属性继续保留，撤销和重做会原子恢复或再次清理。
- CallActivity 从 `/workflow/call-activity/catalog` 返回的授权已发布目录选择。作者可选择“发布时最新版”或“固定所选版本”，部署时两种策略都会冻结为不可变定义 ID，并写入 Flowable 业务制品 `approvaplat/call-activities-v1.json`。
- CallActivity 输入和输出只允许在父子流程正式表单字段间映射，分别保存为 Flowable 原生 `flowable:in` 与 `flowable:out`。保存前即时检查半成品、重复目标、64 项上限、可读写权限和标量类型，后端保存及部署再次按正式表单快照校验。
- 模型重开和复制直接回读 BPMN 的版本策略、业务键继承、变量继承、输出作用域、实例名称和原生映射；页面目录用于解析与展示，BPMN 始终是正式配置来源。
- 保存事件向页面交付 XML，由页面权限入口调用正式保存接口。
- 显式校验调用无副作用 `/workflow/model/validate`；保存时只序列化一次 XML，同一冻结快照依次通过本地结构门禁、服务端 BPMN/身份/表单门禁和真实保存。页面在服务端明确返回 `valid=true` 且 ERROR 集合为空时显示通过。
- BPMN/XML 导入设置 2 MiB 上限，导入异常时保留当前画布；BPMN/XML 导出自动重建内部审计监听器，SVG 使用 Modeler 图形输出。
- 导入时根据全部 `sequenceFlow.sourceRef/targetRef` 重建流程节点的 `incoming/outgoing` 反向引用；画布命令栈和下一次正式保存统一使用规范化后的图关系。被 `keep-alive` 缓存的设计页重新激活时检查当前画布快照，引用缺失时保留待保存编辑并重建内存图。
- JSON 预览通过 DOM 结构递归转换，XML、属性和文本保持明确层级。
- 网格显示与 `gridSnapping` 同步；小地图和 Token 模拟使用真实扩展服务。小地图切换按钮使用 `+ / ×` 紧凑符号，原生 `title` 提供打开或关闭提示并保持画布空间完整。
- 设计器使用容器响应式尺寸。属性检查器默认宽度为 368px，可通过分隔条拖拽、左右方向键调整，双击或按 `Home` 恢复默认宽度；每次尺寸变化通过 `canvas.resized()` 同步 bpmn-js 命中区域、小地图与连线视口。
- 当设计器主体不足 960px 时，属性检查器切换为工作区内浮层，面板继续支持宽度调整、滚动、折叠和关闭。可持久化的折叠状态属于当前用户的非业务界面偏好。
- 偏好键固定为 `workflow:designer:preference:v1:{userId}`，值包含 `schemaVersion: 1` 和 `theme`、`gridEnabled`、`minimapEnabled`、`tokenSimulationEnabled`、`propertiesCollapsed` 五个白名单字段。协议或字段校验失败时恢复并覆盖为默认值；登出保留偏好，恢复默认删除当前用户键。
- 模型详情返回当前 Flowable `revision`，页面保存时作为 `expectedRevision` 提交。后端返回真实 `modelId`、`version` 和新 revision；修订基线变化返回 409，相同内容直接返回当前模型并保持数据库 revision。
- XML 序列化开始至后端保存结束期间锁定画布、属性面板和命令栈，阻止重复保存以及“已保存响应覆盖保存期间新修改”的竞态。
- 保存按钮要求 `workflow:model:save` 权限；`workflow:model:designer` 只负责进入设计页并读取设计上下文，后端继续独立校验保存权限。

### BPMN 错误与升级边界

- 设计器从 `/workflow/bpmn-event/codes/options/ERROR` 和 `/workflow/bpmn-event/codes/options/ESCALATION` 读取启用目录；错误/升级边界通过正式编码选择器完成引用。
- Error 边界固定为中断语义；Escalation 边界可选择中断或非中断，画布属性与保存后的 `cancelActivity` 保持一致。
- `RAISE_BPMN_EVENT` 服务任务使用受控来源 `SERVICE_TASK`、`HTTP`、`SQL`、`DMN` 或 `MANUAL`，可绑定条件变量和消息变量。事件名称、通知策略及实现版本由部署后端冻结，页面只提交作者配置。
- 保存和部署前端校验负责即时提示；后端权威核验目录启用状态、唯一边界附着、捕获匹配和 Error 中断语义，全部核验通过后才提交 Flowable 与快照事务。
