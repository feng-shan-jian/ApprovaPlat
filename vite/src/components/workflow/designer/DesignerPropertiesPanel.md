# DesignerPropertiesPanel

## 组件简介

`DesignerPropertiesPanel` 是 BPMN 设计器的受控属性编辑界面，覆盖基础信息、流程版本、正式模板与内嵌 FormData、办理配置、活动循环与多实例、受控服务任务扩展、DMN 精确版本、条件、调用活动、事件参数、异步执行标志、业务监听器和通用扩展属性。CEL、HTTP 和 SQL 使用结构化子编辑器，端点与数据源只能来自服务端白名单。组件不直接持有 Modeler，也不写 XML；所有变更通过事件交给父组件的 bpmn-js 命令栈。

## 使用方式

```vue
<DesignerPropertiesPanel
  :selected="Boolean(selectedElement)"
  :title="selectedTypeLabel"
  :state="propertyState"
  :flags="propertyFlags"
  :forms="forms"
  :controlled-loop-field-options="controlledLoopFieldOptions"
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
| `identityOptions` | `object` | 办理人、候选用户和候选组目录。 |
| `identityLoading` | `boolean` | 身份目录加载状态。 |
| `extensionOptions` | `array` | 后端扩展注册表返回的已启用 Java、CEL 和 HTTP 扩展最新版，不接受本地伪造选项。 |
| `formFieldOptions` | `array` | 后端 FORM_FIELD 注册表返回的已启用最新版，用于内嵌表单自定义字段。 |
| `connectorEndpoints` | `array` | 后端返回的已启用 HTTP 端点修订，不包含任何密钥正文。 |
| `dmnOptions` | `array` | 后端过滤冻结子部署后返回的每个决策 key 最新来源版本，值为精确 `decisionId`。 |
| `dmnLoading` | `boolean` | DMN 正式目录加载状态。 |
| `extensionLoading` | `boolean` | 扩展注册表加载状态。 |
| `assignmentOptions` 等 | `array` | 父组件固定的分段选项。 |

## Emits

组件按属性域发出 `common-change`、`id-change`、`process-change`、`participant-change`、`form-source-change`、`form-change`、`embedded-form-change`、`assignment-change`、`user-task-change`、`service-task-change`、`dmn-change`、`condition-change`、`documentation-change`、`multi-instance-change`、`activity-change`、`call-activity-change`、`event-change`、`identity-search`、两类业务监听器事件和 `extension-properties-change`。

## 公开方法

无。属性状态由父组件在元素选择和命令栈变化时统一回读。

## 关键设计

- 用户可见字段只描述业务语义；系统任务审计监听器、内部多实例 handler 等技术约束不在面板中出现。
- 组件不直接修改 BPMN moddle 对象，父组件必须使用 `modeling.updateProperties` 或 `updateModdleProperties`，确保撤销、重做和保存快照一致。
- 正式模板和内嵌 FormData 使用明确的分段来源选择。内嵌字段由 `EmbeddedFormFieldEditor` 编辑；父组件负责让 `flowable:formKey` 与 `flowable:formProperty` 始终互斥。
- 消息、信号、错误和升级引用由父组件解析为 Definitions 根元素；定时器表达式写入对应 `FormalExpression`。
- Participant 的 `processRef` 由面板显式编辑并写入标准 BPMN 属性；服务端保存/部署时再次核验流程定义存在性和可执行性，避免只绘制池而形成假运行能力。
- MessageFlow 源 SendTask 选择 `COLLABORATION_OUTBOX_V1` 后使用专用编辑器，只保存端点键、消息名、目标流程、关联变量和变量白名单；部署冻结认证端点，运行时事务登记 outbox。
- 异步开关只负责建模。生产能否启用仍由后端 executor、拓扑和启动门禁共同决定。
- ServiceTask 只能选择正式扩展目录项并填写 JSON 对象配置。父组件写入固定调度器和作者字段；部署时由后端冻结精确版本、处理器、配置及校验和。
- BusinessRuleTask 与通用 ServiceTask 完全分离，只能选择后端 DMN 来源目录并写入单一 `flowable:rules=decisionId`；部署编译器再绑定同部署冻结副本。
- 串行和并行多实例对全部 BPMN Activity 开放；动态会签、或签和受控整改循环只对 UserTask 开放。标准循环可稳定导入、编辑和导出，但 Flowable 8 模型不提供对应执行类型，因此服务端明确禁止部署。
- 受控整改循环必须填写判断字段、再次进入值、退出值和 2 至 50 的最大轮次，并显式点击“应用整改循环配置”。半成品只保留在当前面板草稿中，不写入 moddle；静态枚举和布尔字段禁止自由创建条件值。
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
