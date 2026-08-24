# WorkflowProcessDetailService

## 作用

`WorkflowProcessDetailService` 是 `/workflow/process/detail` 的完整只读领域边界。它在读取任何正文前复用 `WorkflowProcessAccessService` 校验当前用户与实例的对象关系；请求携带任务时，还会从 Flowable 重新查询任务并核验 task、instance、definition 三者关系。

## 返回内容

- 实例、定义、部署、发起人、时间和稳定流程状态；
- 请求指定的活动或历史任务快照，以及该节点部署表单快照；
- 当前用户对请求任务的权威 `returnAllowed` 能力；该字段复用正式退回准备链，满足唯一 `ACTIVE` 轮次和完整实时快照的受控会签/或签可以返回 `true`，静态多实例、子流程、CallActivity、跨作用域和复杂执行树失败关闭；
- 按实际执行顺序返回且能够由内部提交快照证明的开始节点和已完成用户任务表单；
- 开始、用户任务、结束节点时间线，包含 `completedBy`、assignee、候选身份和受控审批意见；
- 经过 `WorkflowDeploymentService` 大小、UTF-8、安全 XML 和 Flowable 校验的 BPMN XML；
- Viewer 使用的已完成活动、已走顺序流、未完成活动、驳回活动和退回活动集合；退回修改期间保留退回标记，重新提交后同一 BPMN 节点的当前办理标记会覆盖历史退回标记。

当流程稳定状态为 `returned`，且请求任务是唯一活动任务、真实 assignee 为发起人、没有 owner、委派或候选 identity link，并携带服务端写入且与发起人一致的 `RETURN_APPLICANT_VARIABLE` 时，该任务只表示“待修改”。详情不会为它调用 `WorkflowMultiInstanceService.getOptionalState(...)`，固定返回空的多实例能力投影，避免把首审批节点退回时产生的临时单成员根误认为新 `ACTIVE` 审批轮次。流程状态、局部标记、任务唯一性或身份关系任一不满足时，详情失败关闭，不会把损坏的申请人任务猜测成普通或正式多实例任务。

## 表单值白名单

详情只读取 Flowable 业务制品 `approvaplat/forms-v1.json` 中的 `content`，不会回连当前 `wf_form`。每个快照先通过 `WorkflowFormTemplateValidator` 的完整结构与组件白名单校验，再从组件 `__vModel__` 提取允许回显的字段名。

已经提交的开始表单和历史任务表单不再从普通全局变量或最终历史变量反推值。发起服务和任务完成服务会把提交当时的安全字段投影编码为固定内部字符串变量 `__ruoyi_workflow_form_submission_v1`：开始快照随流程发起原子写入，退回后重新提交会保留同一开始表单的审计版本并以最后一次提交覆盖业务回显；任务快照始终以 task-local 变量写入并与真实 task ID 强关联。快照正文同时固化 deployment、form、node、task、业务字段作用域和值，读取时必须与 BPMN、部署表单及 Flowable 历史元数据逐项一致，不同开始表单身份的版本会被拒绝。

Flowable 8 的 `HistoricDetailQuery` 不支持按变量名、变量类型过滤，也不能禁止查询阶段初始化变量正文，因此服务不会通过它扫描全部历史更新。内部快照改由参数化 MyBatis 查询在数据库层限定流程实例和固定内部变量名，第一阶段完整读取 `VariableUpdate`、`VAR_TYPE_`、`BYTEARRAY_ID_`、正文存在性及物理字节统计，只取上限加一的 10001 行识别超限；全部元数据和累计容量通过后，第二阶段才按已验证主键读取正文。真实历史更新可能出现 `string` 类型但正文位于 `ACT_GE_BYTEARRAY.BYTES_` 的组合，因此 `string` 允许 `TEXT_` 或序列化 Blob 两种互斥存储，`longString` 仍只允许序列化 Blob；Blob 使用只允许恢复单个 `String` 的 `ObjectInputFilter`，不会调用任意 `HistoricVariableUpdate.getValue()`。

通过上述数据库门禁取得的快照继续执行严格重复字段检测、固定结构、受限 JSON 类型、深度/节点/容器/文本/总字节门禁；损坏、重复或关联矛盾会使整个详情失败。`snapshotTime` 直接取对应 `ACT_HI_DETAIL.TIME_`，不使用任务结束时间或普通变量更新时间代替。

因此，后续节点即使覆盖同名全局字段，也不会污染前序节点已经提交的历史值；多个 `localScope` 任务的同名字段也按 task ID 隔离。升级前旧实例没有内部提交快照时，详情会省略对应历史表单，而不是用最终变量伪造提交值。

只有请求明确指向活动任务且该 BPMN 节点确有部署表单时，才按部署 schema 查询当前变量并固定返回 `snapshotTime=null`：普通节点只读取流程根变量，BPMN 节点声明 `localScope=true` 或 `localScope=1` 时只读取当前 task ID 的局部变量。无任务请求、无表单节点和已完成任务不会创建当前变量查询；历史详情始终直接使用正式提交快照。`initiator`、`processStatus`、多实例计数、Flowable 跳过标志和所有 `__ruoyi_workflow_` 保留变量始终隐藏，即使表单 schema 错误地声明了这些名称。

普通流程变量和任务局部变量查询统一启用 `excludeVariableInitialization()`，先只取得变量元数据，再与 `ACT_HI_VARINST` 的类型、作用域、`BYTEARRAY_ID_` 和真实正文长度逐项核对。`json` / `longJson` 以及实际关联 Blob 的 `string` / `longString` 一律执行两阶段受控正文读取并计入累计物理字节预算：字符串 Blob 只允许反序列化单个 `String`，JSON Blob 按 UTF-8 严格解析；只有确认没有任何 Blob 关联的安全标量才允许调用 `getValue()`。`bytes`、`serializable`、自定义对象和未知类型不会被初始化、反序列化或返回。JSON 还会执行深度、节点数、容器成员数、单文本和累计响应字节门禁；Java 浮点值和 Jackson 原生浮点节点中的 `NaN`、正负 `Infinity` 均不会进入响应。

## 容量门禁

单个详情最多读取 1000 个历史活动、500 个历史任务、500 个部署表单快照、活动表单当前作用域 2000 个历史变量、10000 条正式提交历史更新和 1000 条原始意见。单条意见最多 8 KiB，全部意见最多 512 KiB；响应中重复出现的表单正文累计最多 4 MiB，全部变量 JSON 最多 1 MiB。当前变量不会按全部历史 task ID 预加载；任何实际读取的计数或关联异常都会停止整个详情，不返回截断的审计结果。

## 异常语义

- `400`：实例或任务请求参数为空、过长；
- `403`：当前用户与实例没有发起、办理、候选、参与、抄送或管理员关系；
- `404`：实例、任务或流程定义不存在；
- `409`：任务、实例、定义、部署关系不一致；
- `500`：历史活动、任务、变量、内部提交快照、部署表单快照、意见或时间关系异常，或安全容量门禁被存量数据触发。
