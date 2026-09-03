# WorkflowProcessDetailService

## 作用

`WorkflowProcessDetailService` 是 `/workflow/process/detail` 的完整只读编排边界。它在读取任何正文前复用 `WorkflowProcessAccessService` 校验当前用户与实例的对象关系；请求携带任务时，还会从 Flowable 重新查询任务并核验 task、instance、definition 三者关系。服务负责授权、只读事务、定义关系、实时会签/退回 capability 和最终 VO 组装顺序；部署表单及历史/当前/退回/循环表单委托给 `WorkflowProcessFormDetailProjection`，活动、任务、意见、时间线、Viewer 和父子流程关系委托给 `WorkflowProcessHistoryProjection`，历史/当前变量存储解码委托给 `WorkflowProcessVariableProjection`。三个具体组件仅由详情编排器在同一已授权只读事务中直接调用。

## 返回内容

- 实例、定义、部署、发起人、时间和稳定流程状态；
- 请求指定的活动或历史任务快照，以及该节点部署表单快照；
- 非空 `formDetail` 将申请表单、请求节点表单、正式历史 UserTask 表单、开始快照缺失和退回申请态
  分别表达。退回申请态必须绑定稳定 `returned` 状态的活动 `currentTask`，申请表单 task ID 必须
  与该任务一致；`resubmitAllowed=true` 还必须同时存在该退回申请表单；
- 活动 `currentTask` 返回服务端 `unclaimAllowed` 快照；它由对象授权服务复用 `WorkflowTaskUnclaimPolicy` 计算，历史任务或无当前任务不会返回 `true`，且不替代 `workflow:process:claim` 权限及写入时重新校验；
- 当前用户对请求任务的权威 `returnAllowed` 能力；该字段复用正式退回准备链，满足唯一 `ACTIVE` 轮次和完整实时快照的受控会签/或签可以返回 `true`，静态多实例、子流程、CallActivity、跨作用域和复杂执行树失败关闭；
- 当前用户对申请人待修改任务的权威原始布尔 `resubmitAllowed`；只有 `returnedApplicantTask` 前置识别命中后才调用正式重提准备链，合法原发起人返回 `true`，无任务、历史任务、普通运行态、非申请人和 returned 协议漂移返回 `false`；
- 当前用户对运行中根流程的权威原始布尔 `urgeAllowed`；详情先完成实例和可选任务对象授权，再由 `WorkflowManualUrgeService.isUrgeAllowed` 复用正式催办的权限、完整 CallActivity 运行树、活动任务、有效接收人和数量门禁。已结束、挂起及 CallActivity 子流程详情固定返回 `false`，不会进入催办准备查询；
- 按实际执行顺序返回且能够由内部提交快照证明的开始节点和已完成用户任务表单；
- 开始、用户任务、结束节点时间线，包含 `completedBy`、assignee、候选身份和受控审批意见；
- 经过 `WorkflowDeploymentService` 大小、UTF-8、安全 XML 和 Flowable 校验的 BPMN XML；
- Viewer 使用的已完成活动、已走顺序流、未完成活动、驳回活动和退回活动集合；退回修改期间保留退回标记，重新提交后同一 BPMN 节点的当前办理标记会覆盖历史退回标记。

当流程稳定状态为 `returned`，且请求任务是唯一活动任务、真实 assignee 为发起人、owner/委派/候选 identity link 均为空，并携带服务端写入且与发起人一致的 `RETURN_APPLICANT_VARIABLE` 时，该任务只表示“待修改”。详情固定返回空的多实例能力投影，把首审批节点退回时产生的临时单成员根与新 `ACTIVE` 审批轮次明确区分。该识别只负责收窄 capability 调用；owner、委派、候选关系、局部标记等不完整组合返回 `resubmitAllowed=false`，正式能力服务继续核验 returned 双状态、冻结办理配置、重开计划、部署开始表单和原提交快照。空身份链、损坏提交快照等数据损坏仍返回 `500`，不会降级为 `false`。

`resubmitAllowed` 是详情只读事务中的瞬时快照，不参与 `/workflow/task/resubmit` 请求。写命令仍在自己的写事务内完整重验全部门禁，表单变量和附件合法性继续由后端决定。

`urgeAllowed` 同样只是详情只读事务中的瞬时快照，不参与 `/workflow/notification/urge` 请求。能力查询使用普通 `SELECT`，不加数据库行锁、不获取 Redis 冷却锁，也不登记通知、Outbox、审计或指标；正式催办仍在独立写事务内锁定并重验全部事实，保持既有 403/409/429/503、通知登记、冷却和指标顺序。

## 表单值白名单

详情以 Flowable 业务制品 `approvaplat/forms-v1.json` 中的 `content` 作为唯一表单来源。每个快照先通过 `WorkflowFormTemplateValidator` 的完整结构与组件白名单校验，再从组件 `__vModel__` 提取允许回显的字段名。

已经提交的开始表单和历史任务表单直接读取提交时写入的固定内部字符串变量 `__ruoyi_workflow_form_submission_v1`。发起服务和任务完成服务把当时的安全字段投影编码到该变量：开始快照随流程发起原子写入，退回后重新提交保留同一开始表单的审计版本并以最后一次提交覆盖业务回显；任务快照始终以 task-local 变量写入并与真实 task ID 强关联。快照正文同时固化 deployment、form、node、task、业务字段作用域和值，读取时与 BPMN、部署表单及 Flowable 历史元数据逐项一致；身份冲突返回数据异常。

参数化 MyBatis 两阶段查询直接替换 `HistoricDetailQuery` 的全历史扫描。第一阶段在数据库层限定流程实例和固定内部变量名，完整读取 `VariableUpdate`、`VAR_TYPE_`、`BYTEARRAY_ID_`、正文存在性及物理字节统计，并以 10001 行探针识别超限；全部元数据和累计容量通过后，第二阶段按已验证主键读取正文。真实历史更新中的 `string` 允许 `TEXT_` 或序列化 Blob 两种互斥存储，`longString` 只允许序列化 Blob；Blob 使用仅恢复单个 `String` 的 `ObjectInputFilter`。

上述物理存储协议由 `WorkflowProcessVariableProjection` 独占维护。`WorkflowProcessDetailService` 在完成对象授权后加载正式 `VariableStore`；`WorkflowProcessFormDetailProjection` 按部署 schema 消费 `VariableStore` / `ProjectedValues` 并统一累计表单和变量响应预算。该投影组件直接替换编排服务和表单投影中的历史变量 Mapper、Blob 与 JSON 解码职责。

通过上述数据库门禁取得的快照继续执行严格重复字段检测、固定结构、受限 JSON 类型、深度/节点/容器/文本/总字节门禁；损坏、重复或关联矛盾会使整个详情失败。`snapshotTime` 直接取对应 `ACT_HI_DETAIL.TIME_`，作为提交时间的唯一来源。

因此，后续节点覆盖同名全局字段时，前序节点已经提交的历史值保持不变；多个 `localScope` 任务的同名字段按 task ID 隔离。存在正式内部提交快照的表单进入历史详情；缺少快照的记录由页面标记缺失并默认展示流程图。

`formDetail.applicationForm` 直接表达唯一正式开始提交：普通态保持 `taskId=null`，退回申请态绑定
当前活动任务；不存在时返回 null 并令 `applicationMissing=true`。`nodeTaskForm` 只表达本次请求
UserTask，退回申请态固定为空。`historyForms` 只含正式历史 UserTask 提交，并排除本次请求的
`currentTask.taskId`，避免历史任务详情重复显示。

过渡期顶层 `currentTaskForm` 和 `processFormList` 暂时保留。二者与 `formDetail` 由同一次
`WorkflowProcessFormDetailProjection` 结果生成，不增加部署制品、Mapper、Flowable 历史或当前变量
查询，也不使用 alias、fallback、转换器或兼容 Adapter。后续前端完成正式协议切换后再单独删除旧字段。

请求明确指向活动任务且该 BPMN 节点具有部署表单时，服务按部署 schema 查询当前变量并固定返回 `snapshotTime=null`：普通节点读取流程根变量，BPMN 节点声明 `localScope=true` 或 `localScope=1` 时读取当前 task ID 的局部变量。历史详情直接使用正式提交快照。响应字段白名单排除 `initiator`、`processStatus`、多实例计数、Flowable 跳过标志和所有 `__ruoyi_workflow_` 保留变量。

普通流程变量和任务局部变量查询统一启用 `excludeVariableInitialization()`，先取得变量元数据，再与 `ACT_HI_VARINST` 的类型、作用域、`BYTEARRAY_ID_` 和真实正文长度逐项核对。`json` / `longJson` 以及实际关联 Blob 的 `string` / `longString` 一律执行两阶段受控正文读取并计入累计物理字节预算：字符串 Blob 仅反序列化单个 `String`，JSON Blob 按 UTF-8 严格解析；确认 Blob 关联为空的安全标量才调用 `getValue()`。响应类型白名单覆盖安全标量、字符串和受控 JSON；`bytes`、`serializable`、自定义对象、未知类型、非有限浮点值和危险 JSON 结构触发数据异常。

## 容量门禁

单个详情最多读取 1000 个历史活动、500 个历史任务、500 个部署表单快照、活动表单当前作用域 2000 个历史变量、10000 条正式提交历史更新和 1000 条原始意见。单条意见最多 8 KiB，全部意见最多 512 KiB；响应中重复出现的表单正文累计最多 4 MiB，全部变量 JSON 最多 1 MiB。历史数量和意见预算由 `WorkflowProcessHistoryProjection` 维护，表单与响应预算由 `WorkflowProcessFormDetailProjection` 维护，变量物理存储预算由 `WorkflowProcessVariableProjection` 维护。当前变量按请求任务作用域读取；任何计数或关联异常都会停止整个详情并返回完整错误。

## 异常语义

- `400`：实例或任务请求参数为空、过长；
- `403`：当前用户与实例的发起、办理、候选、参与、抄送及管理员关系集合为空；
- `404`：实例、任务或流程定义不存在；
- `409`：任务、实例、定义、部署关系不一致；
- `500`：历史活动、任务、变量、内部提交快照、部署表单快照、意见或时间关系异常，或安全容量门禁被存量数据触发。
