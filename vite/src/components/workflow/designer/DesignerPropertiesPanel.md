# DesignerPropertiesPanel

## 组件简介

`DesignerPropertiesPanel` 是 BPMN 设计器的受控属性编辑界面，覆盖基础信息、流程版本、正式模板与内嵌 FormData、节点字段权限、办理配置、活动循环与多实例、受控服务任务扩展、DMN 精确版本、条件、调用活动、事件参数、异步执行标志、业务监听器和通用扩展属性。CEL、HTTP 和 SQL 使用结构化子编辑器，端点与数据源只能来自服务端白名单。组件不直接持有 Modeler，也不写 XML；所有变更通过事件交给父组件的 bpmn-js 命令栈。

## 使用方式

```vue
<DesignerPropertiesPanel
  :selected="Boolean(selectedElement)"
  :title="selectedTypeLabel"
  :state="propertyState"
  :flags="propertyFlags"
  :forms="forms"
  :controlled-loop-field-options="controlledLoopFieldOptions"
  :condition-field-options="conditionFieldOptions"
  :condition-context="conditionContext"
  :extension-options="extensionOptions"
  :form-field-options="formFieldOptions"
  :connector-endpoints="connectorEndpoints"
  :dmn-options="dmnOptions"
  :extension-loading="extensionLoading"
  @form-source-change="updateFormSource"
  @embedded-form-change="updateEmbeddedForm"
  @event-change="updateEventProperties"
  @activity-change="updateActivityProperties"
/>
```

## Props

| 属性 | 类型 | 说明 |
| --- | --- | --- |
| `selected` | `boolean` | 当前是否存在选中元素。 |
| `title` | `string` | 选中 BPMN 类型的业务标题。 |
| `state` | `object` | 父组件从 moddle 对象回读的响应式属性状态；表单域包含 `formSource`、`formKey` 和 `embeddedFields`。 |
| `flags` | `object` | `process`、`participant`、`activity`、`event` 等类型能力开关；Participant 需要填写 `state.processRef`。 |
| `forms` | `array` | 可选择的正式 `wf_form` 列表。 |
| `controlledLoopFieldOptions` | `array` | 当前 UserTask 正式表单中的可写标量字段、静态值目录及是否禁止自由输入。 |
| `conditionFieldOptions` | `array` | 当前可执行流程全部正式表单中的可写标量字段、类型和静态值目录。 |
| `conditionContext` | `object` | 当前排他/包容网关类型及全部出线的名称、默认和配置状态。 |
| `identityOptions` | `object` | 办理人、候选用户和候选组目录。 |
| `identityLoading` | `boolean` | 身份目录加载状态。 |
| `extensionOptions` | `array` | 后端扩展注册表返回的已启用 Java、CEL 和 HTTP 扩展最新版，不接受本地伪造选项。 |
| `formFieldOptions` | `array` | 后端 FORM_FIELD 注册表返回的已启用最新版，用于内嵌表单自定义字段。 |
| `connectorEndpoints` | `array` | 后端返回的已启用 HTTP 端点修订，不包含任何密钥正文。 |
| `dmnOptions` | `array` | 后端过滤冻结子部署后返回的每个决策 key 最新来源版本，值为精确 `decisionId`。 |
| `dmnLoading` | `boolean` | DMN 正式目录加载状态。 |
| `extensionLoading` | `boolean` | 扩展注册表加载状态。 |
| `assignmentOptions` 等 | `array` | 父组件固定的分段选项。 |
| `participantFormFieldOptions` | `array` | 当前单实例任务正式表单中的可用用户字段。 |
| `identityOptions` | `object` | 按 active、approval、claim 能力隔离的正式用户、角色、部门选项池。 |
| `identityLoading` | `boolean` | 参与者目录远程检索和已选对象核验状态。 |

## Emits

组件按属性域发出 `common-change`、`id-change`、`process-change`、`participant-change`、`participant-rule-change`、`form-source-change`、`form-change`、`embedded-form-change`、`assignment-change`、`user-task-change`、`service-task-change`、`dmn-change`、`condition-change`、`condition-rule-change`、`condition-default-change`、`documentation-change`、`multi-instance-change`、`activity-change`、`call-activity-change`、`event-change`、`identity-search`、`identity-resolve`、两类业务监听器事件和 `extension-properties-change`。`identity-search` 用于正式目录分页检索，`identity-resolve` 用于重开模型时核验当前分页外的已选对象。

## 公开方法

无。属性状态由父组件在元素选择和命令栈变化时统一回读。

## 关键设计

- 用户可见字段只描述业务语义；系统任务审计监听器、内部多实例 handler 等技术约束不在面板中出现。
- 流程属性提供公开、指定用户、角色、部门四种发起范围；普通单实例 UserTask 提供八种受控办理规则。规则说明始终展示最终命中对象和固定 `FAIL` 无匹配策略。
- 单实例参与者编辑器在多实例开启时隐藏。会签/或签成员来源仍由“签署规则”独立维护，两个契约不会互相读写。
- 组件不直接修改 BPMN moddle 对象，父组件必须使用 `modeling.updateProperties` 或 `updateModdleProperties`，确保撤销、重做和保存快照一致。
- 正式模板和内嵌 FormData 使用明确的分段来源选择。内嵌字段由 `EmbeddedFormFieldEditor` 编辑；正式模板绑定 `flowable:formKey` 时，`flowable:formProperty` 只允许保存平台受控字段权限描述。
- 节点字段权限目录只来自当前绑定的正式模板，支持隐藏、只读、可编辑、必填和批量默认策略；内嵌 FormData 继续使用自身原生字段开关，不进入本权限面板。完整策略通过 `form-permission-change` 交给父组件写入 BPMN。
- 消息、信号、错误和升级引用由父组件解析为 Definitions 根元素；定时器表达式写入对应 `FormalExpression`。
- Participant 的 `processRef` 由面板显式编辑并写入标准 BPMN 属性；服务端保存/部署时再次核验流程定义存在性和可执行性，避免只绘制池而形成假运行能力。
- MessageFlow 源 SendTask 选择 `COLLABORATION_OUTBOX_V1` 后使用专用编辑器，只保存端点键、消息名、目标流程、关联变量和变量白名单；部署冻结认证端点，运行时事务登记 outbox。
- 异步开关只负责建模。生产能否启用仍由后端 executor、拓扑和启动门禁共同决定。
- ServiceTask 只能选择正式扩展目录项并填写 JSON 对象配置。父组件写入固定调度器和作者字段；部署时由后端冻结精确版本、处理器、配置及校验和。
- BusinessRuleTask 与通用 ServiceTask 完全分离，只能选择后端 DMN 来源目录并写入单一 `flowable:rules=decisionId`；部署编译器再绑定同部署冻结副本。
- UserTask 的循环方式只提供受控会签/或签和受控整改循环，不展示要求手写集合表达式、元素变量或完成条件的串行/并行多实例入口。其他 BPMN Activity 仍可编辑标准串行或并行多实例；标准循环可稳定导入、编辑和导出，但 Flowable 8 模型不提供对应执行类型，因此服务端明确禁止部署。
- 会签/或签在“签署规则”下明确选择人员来源。“办理时选择”由唯一前驱任务在真实完成链路中提交成员；“发起时选择”由发起页根据部署 BPMN 投影必填用户字段；“固定人员”仅能从审批资格目录多选。父组件只写入三种固定白名单表达式，不向用户显示 collection、elementVariable 或 completionCondition。三种来源进入节点后共用正式成员快照、revision/CAS、加减签和完成审计。
- 切换到“固定人员”时先保留页面编辑状态并显示正式审批用户目录；选择首名成员后才写入 BPMN 命令栈。若未选择成员直接保存，父组件在发起服务端校验和保存请求前明确拒绝，确保不会把旧人员来源误保存为固定配置。
- 受控整改循环必须填写判断字段、再次进入值、退出值和 2 至 50 的最大轮次，并显式点击“应用整改循环配置”。半成品只保留在当前面板草稿中，不写入 moddle；静态枚举和布尔字段禁止自由创建条件值。
- 排他和包容网关的多条出线使用 `SequenceFlowRuleEditor`，普通设计者不能输入任意 EL。字段来自当前流程正式表单，分支名称、唯一默认、AND/OR 规则组和字段类型值在保存及部署 API 再次校验。
- 通用扩展属性写入受限 `flowable:properties`，后端校验数量、名称、重复项和值长度；它们只是元数据，不作为表达式或实现入口执行。

### 错误与升级配置

- 错误和升级事件引用使用后端正式目录选项，选择结果写入 Definitions 根元素和边界事件引用；编码停用后只影响新模型，历史部署继续使用冻结快照。
- Error 只能中断附着活动；Escalation 提供中断/非中断选择。重复边界、跨活动猜测、未附着产生器和未匹配产生器均由服务端拒绝。
- `BpmnEventRaiseEditor` 的 `sourceType` 只描述真实业务来源，不执行表达式或脚本：HTTP/SQL/DMN 连接器把受控标量结果写入流程变量，再由条件变量驱动产生器；MANUAL 由人工业务路径显式配置。
- 产生器配置变更通过 bpmn-js 命令栈进入撤销/重做和 XML 保存链路；部署后端把事件名称、通知策略、扩展版本和校验和冻结，运行时只读取正式快照。

## 最小接入示例

```js
function updateActivityProperties() {
  modeler.get('modeling').updateProperties(selectedElement.value, {
    'flowable:async': propertyState.asyncBefore,
    'flowable:asyncLeave': propertyState.asyncAfter
  })
}
```

受控整改循环字段选项示例：

```js
const controlledLoopFieldOptions = [
  {
    value: 'reviewResult',
    label: '审核结论（reviewResult）',
    values: [
      { value: 'RECTIFY', label: '退回整改' },
      { value: 'PASS', label: '通过' }
    ],
    valueRestricted: true
  }
]
```
