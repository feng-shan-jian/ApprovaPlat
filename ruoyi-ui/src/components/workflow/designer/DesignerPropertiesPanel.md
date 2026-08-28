# DesignerPropertiesPanel

## 组件简介

`DesignerPropertiesPanel` 是 BPMN 设计器的受控属性编辑界面，覆盖基础信息、流程版本、正式模板与内嵌 FormData、节点字段权限、办理配置、自动抄送、活动循环与多实例、独立任务能力面板、DMN 精确版本、条件、调用活动、事件参数、异步执行标志、业务监听器和通用扩展属性。CEL、HTTP 和 SQL 使用结构化子编辑器，端点与数据源来自服务端白名单。父组件持有 Modeler 与 XML，并把面板事件统一写入 bpmn-js 命令栈。

## 使用方式

```vue
<DesignerPropertiesPanel :selected="Boolean(selectedElement)" :title="selectedTypeLabel" :state="propertyState" :flags="propertyFlags" :task-capability="selectedTaskCapability" :forms="forms" :controlled-loop-field-options="controlledLoopFieldOptions" :condition-field-options="conditionFieldOptions" :condition-context="conditionContext" :extension-options="extensionOptions" :form-field-options="formFieldOptions" :connector-endpoints="connectorEndpoints" :dmn-options="dmnOptions" :extension-loading="extensionLoading" @form-source-change="updateFormSource" @embedded-form-change="updateEmbeddedForm" @event-change="updateEventProperties" @activity-change="updateActivityProperties" @close="toggleProperties" />
```

## Props

| 属性                          | 类型      | 说明                                                                                                                |
| ----------------------------- | --------- | ------------------------------------------------------------------------------------------------------------------- |
| `selected`                    | `boolean` | 当前是否存在选中元素。                                                                                              |
| `title`                       | `string`  | 选中 BPMN 类型的业务标题。                                                                                          |
| `state`                       | `object`  | 父组件从 moddle 对象回读的响应式属性状态；表单域包含 `formSource`、`formKey` 和 `embeddedFields`。                  |
| `flags`                       | `object`  | `process`、`participant`、`activity`、`event` 等类型能力开关；Participant 需要填写 `state.processRef`。             |
| `taskCapability`              | `object`  | 当前标准任务的不可变能力条目；非任务元素为 `null`。该条目直接决定任务面板类型。                                     |
| `forms`                       | `array`   | 可选择的正式 `wf_form` 列表。                                                                                       |
| `controlledLoopFieldOptions`  | `array`   | 当前 UserTask 正式表单中的可写标量字段、静态值目录及封闭选择策略。                                                  |
| `conditionFieldOptions`       | `array`   | 当前可执行流程全部正式表单中的可写标量字段、类型和静态值目录。                                                      |
| `conditionContext`            | `object`  | 当前排他/包容网关类型及全部出线的名称、默认和配置状态。                                                             |
| `autoCopyTriggerOptions`      | `array`   | 当前 Process 或 UserTask 允许的自动抄送生命周期触发时机。                                                           |
| `autoCopyFormFieldOptions`    | `array`   | 当前节点或流程已有正式表单中的标量字段目录。                                                                        |
| `identityOptions`             | `object`  | 按正式目录和能力隔离的九个选项池；会签/或签指定用户、角色、部门分别使用 `assignees`、`activeRoles`、`activeDepts`。 |
| `identityLoading`             | `boolean` | 身份目录加载状态。                                                                                                  |
| `extensionOptions`            | `array`   | 后端扩展注册表返回的已启用 Java、CEL 和 HTTP 扩展最新版。                                                           |
| `formFieldOptions`            | `array`   | 后端 FORM_FIELD 注册表返回的已启用最新版，用于内嵌表单自定义字段。                                                  |
| `connectorEndpoints`          | `array`   | 后端返回的已启用 HTTP 端点公开元数据修订。                                                                          |
| `dmnOptions`                  | `array`   | 后端过滤冻结子部署后返回的每个决策 key 最新来源版本，值为精确 `decisionId`。                                        |
| `dmnLoading`                  | `boolean` | DMN 正式目录加载状态。                                                                                              |
| `callActivityOptions`         | `array`   | 后端按当前设计者对象权限过滤的已发布流程版本目录，包含状态及输入/输出字段。                                         |
| `callActivityLoading`         | `boolean` | 子流程正式目录加载状态。                                                                                            |
| `callActivityParentFields`    | `array`   | 当前父流程正式模板和内嵌表单合并后的可映射标量字段。                                                                |
| `extensionLoading`            | `boolean` | 扩展注册表加载状态。                                                                                                |
| `assignmentOptions` 等        | `array`   | 父组件固定的分段选项。                                                                                              |
| `participantFormFieldOptions` | `array`   | 当前单实例任务正式表单中的可用用户字段。                                                                            |

## Emits

组件按属性域发出 `common-change`、`id-change`、`process-change`、`participant-change`、`participant-rule-change`、`form-source-change`、`form-change`、`embedded-form-change`、`assignment-change`、`user-task-change`、`controlled-task-config-update`、`controlled-task-change`、`dmn-change`、`condition-change`、`condition-rule-change`、`condition-default-change`、`documentation-change`、`multi-instance-change`、`activity-change`、`call-activity-change`、`event-change`、`identity-search`、`identity-resolve`、`auto-copy-change`、两类业务监听器事件和 `extension-properties-change`。`identity-search` 用于正式目录分页检索，`identity-resolve` 用于重开模型时核验当前分页外的已选对象；点击面板关闭按钮时发出 `close`。

## 公开方法

无。属性状态由父组件在元素选择和命令栈变化时统一回读。

## 关键设计

- 用户可见字段只描述业务语义；系统任务审计监听器、内部多实例 handler 等技术约束由父组件和后端自动维护。
- 面板头部和当前元素上下文固定在可视区域，长表单只在面板内部滚动。基础、业务、执行、扩展属性和监听器分区使用受控折叠状态，可逐项切换，也可一键全部展开或收起；明确任务面板类型和真实业务控件直接决定业务分区，Participant 等元素保持精确面板内容。
- 流程属性提供公开、指定用户、角色、部门四种发起范围；普通单实例 UserTask 提供八种受控办理规则。规则说明始终展示最终命中对象和固定 `FAIL` 无匹配策略。
- UserTask 将审批人设置与审批方式合并为同一个连续业务区。普通审批、会签和或签始终显式可见；当前方式通过标题标签回显，使长面板滚动后仍保留上下文。
- 普通审批使用单实例参与者规则；切换为会签或或签后，同一区域原位显示五种成员来源与真实目录选择器。两个持久化契约独立维护，切回普通审批时由父组件原子移除受控多实例属性。
- 父组件使用 `modeling.updateProperties` 或 `updateModdleProperties` 修改 BPMN moddle 对象，确保撤销、重做和保存快照一致。
- 正式模板和内嵌 FormData 使用明确的分段来源选择。内嵌字段由 `EmbeddedFormFieldEditor` 编辑；正式模板绑定 `flowable:formKey` 时，`flowable:formProperty` 只允许保存平台受控字段权限描述。
- 节点字段权限目录来自当前绑定的正式模板，支持隐藏、只读、可编辑、必填和批量默认策略；内嵌 FormData 使用自身原生字段开关。完整策略通过 `form-permission-change` 交给父组件写入 BPMN。
- 消息、信号、错误和升级引用由父组件解析为 Definitions 根元素；定时器表达式写入对应 `FormalExpression`。
- Participant 的 `processRef` 由面板显式编辑并写入标准 BPMN 属性；服务端保存/部署时再次核验流程定义存在性和可执行性，使协作池与真实运行流程保持一致。
- ServiceTask 与 SendTask 使用不同用途说明和独立面板分支，并共用 `ControlledTaskHandlerEditor` 写入同一受控作者字段。两者从正式扩展目录选择稳定实现键。
- MessageFlow 源 SendTask 选择 `COLLABORATION_OUTBOX_V1` 后使用专用编辑器，保存端点键、消息名、目标流程、关联变量和变量白名单；后端保存和部署时根据完整模型权威核验 outbox 绑定。
- ReceiveTask 面板展示真实 `POST /workflow/runtime-event/receive`、当前 `activityId`、互斥关联条件、`X-Integration-Token` RECEIVE 能力和变量白名单要求，运行状态统一由后端持久化。
- 导入的 ManualTask 显示“按 Flowable 标准语义自动通过”的明确提示，基础信息、渲染和保存保持可用。
- 异步开关只负责建模。生产能否启用仍由后端 executor、拓扑和启动门禁共同决定。
- CallActivity 通过授权发布目录选择名称、key、版本和状态，并配置版本绑定、业务键继承、变量继承、子实例名称、输入/输出映射与输出作用域；取消和终止固定按平台整棵执行树原子传播。
- 输入映射使用“父流程可读字段 -> 子流程可写开始字段”，输出映射使用“子流程可读字段 -> 父流程可写字段”。字段下拉同时展示变量名和类型，最多各 64 项；最终权限、状态、循环依赖和类型兼容仍由保存/部署后端重新校验。
- ServiceTask 只能选择正式扩展目录项并填写 JSON 对象配置。父组件写入固定调度器和作者字段；部署时由后端冻结精确版本、处理器、配置及校验和。
- BusinessRuleTask 与通用 ServiceTask 完全分离，只能选择后端 DMN 来源目录并写入单一 `flowable:rules=decisionId`；部署编译器再绑定同部署冻结副本。
- UserTask 的“审批人设置”统一提供普通审批、会签和或签，“执行配置”提供标准循环与受控整改循环。其他 BPMN Activity 可编辑标准串行或并行多实例；标准循环支持稳定导入、编辑和导出，部署门禁接受 Flowable 8 可执行的受控整改循环。
- 会签/或签在“审批人设置”下明确选择五种人员来源。“办理时选择”由唯一前驱任务在真实完成链路中提交成员；“发起时选择”由发起页根据部署 BPMN 投影必填用户字段；“指定用户”“指定角色”“指定部门”分别从 `assignees`、`activeRoles`、`activeDepts` 正式目录多选。父组件展示业务化来源选择，并为三类指定来源统一写入 `${multiInstanceHandler.getConfiguredUserIds(execution)}`、`approva.multiInstance.identityType` 和 `approva.multiInstance.identityIds`。
- 指定角色和部门在作者 BPMN 中保存正式主键，节点进入时后端按实时 RBAC 展开为真实 `assignee` 任务；会签等待全部实例完成，或签在首个实例完成后取消其余实例。运行时任务只携带 `assignee`；身份属性成对出现且与集合来源匹配，选择项及展开办理人限制为 1 至 100，保存、部署和运行时门禁统一校验重复、状态、审批资格与空目录结果。
- 切换三类指定身份来源时立即清空上一来源的目录编码，确保每类主键按正确目录解释。选中至少一个有效对象后才写入 BPMN 命令栈；远程分页外的已选值通过批量正式接口回显。指定用户配置统一迁移并保存为正式身份属性。
- 受控整改循环必须填写判断字段、再次进入值、退出值和 2 至 50 的最大轮次，并显式点击“应用整改循环配置”。完整规则写入 moddle；编辑草稿留在当前面板，静态枚举和布尔字段使用封闭目录选择。
- 排他和包容网关的多条出线使用 `SequenceFlowRuleEditor`。字段来自当前流程正式表单，分支名称、唯一默认、AND/OR 规则组和字段类型值在保存及部署 API 再次校验，作者通过结构化规则生成条件表达式。
- Process 提供“流程完成”自动抄送，UserTask 提供“节点到达/节点完成”自动抄送；固定身份使用 `capability=copy` 正式目录，表单用户来源从已有标量字段选择，完整规则才写入 moddle。
- 通用扩展属性写入受限 `flowable:properties`，后端校验数量、名称、重复项和值长度；运行时将其作为纯元数据读取。

### 错误与升级配置

- 错误和升级事件引用使用后端正式目录选项，选择结果写入 Definitions 根元素和边界事件引用；编码停用后只影响新模型，历史部署继续使用冻结快照。
- Error 使用中断附着活动语义；Escalation 提供中断/非中断选择。服务端校验唯一边界、附着范围以及产生器与捕获器匹配关系。
- `BpmnEventRaiseEditor` 的 `sourceType` 描述真实业务来源：HTTP/SQL/DMN 连接器把受控标量结果写入流程变量，再由条件变量驱动产生器；MANUAL 由人工业务路径显式配置。
- 产生器配置变更通过 bpmn-js 命令栈进入撤销/重做和 XML 保存链路；部署后端把事件名称、通知策略、扩展版本和校验和冻结，运行时只读取正式快照。

## 最小接入示例

```js
function updateActivityProperties() {
  modeler.get('modeling').updateProperties(selectedElement.value, {
    'flowable:async': propertyState.asyncBefore,
    'flowable:asyncLeave': propertyState.asyncAfter,
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
      { value: 'PASS', label: '通过' },
    ],
    valueRestricted: true,
  },
]
```
