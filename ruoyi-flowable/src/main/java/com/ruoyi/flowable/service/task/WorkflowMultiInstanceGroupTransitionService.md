# WorkflowMultiInstanceGroupTransitionService

## 作用

`WorkflowMultiInstanceGroupTransitionService` 只拥有受控多实例执行树迁移、退回双状态、轮次状态机、SLA 收口和 Coordinator 协议。它冻结实时执行树、首审批到来源的安全路径及路径上每个受控节点的最近正式轮次，原子执行 `ACTIVE → RETURNED`、`RETURNED → REOPENED`，并让重新流转节点从各自权威成员来源建立新 ACTIVE 轮次。

## 调用方式

Return/Resubmit 应用服务先调用只读准备入口，再调用写入口。退回使用包含来源组、首审批目标、`ControlledReturnPathPlan` 和逐节点重放快照的 `MultiInstanceGroupReturnExecutionPlan`；重提使用 `MultiInstanceGroupReopenPlan`。目标、模式、旧轮成员、revision 和路径均来自服务端冻结事实，客户端不具备权威性。

普通首审批目标不移动 execution：来源轮次 CAS 为 `REOPENED` 后，当前首审批任务恢复退回时冻结的 assignee/owner/candidate 配置，并清除路径旧多实例状态，后续节点自然重新进入。受控首审批目标会取消退回期间的临时单成员根，在同一命令内先核验旧轮变量，再从该首节点的开始选择、固定配置或受控身份目录重新解析成员并创建 revision 0 新轮；Flowable 重复求值集合时只能复用第一次写入的新状态。无论来源位于后续 ALL 还是 ANY，都不得直接跳回来源节点。

返回的 `GroupReturnResult` 和 `GroupReopenResult` 只保留后续通知和命令观察真正需要的任务主键与新根信息，不再重复表达已经由 Mapper CAS 确认的旧轮成功状态。

## 原子副作用

Flowable 状态迁移、退回双状态、轮次 CAS 和 SLA 收口处于应用服务建立的同一外层事务，并由单个整组写入口保持固定顺序。审计、抄送、附件和通知由 ApplicationService 编排，本服务不解析当前用户，也不依赖这些组件。Coordinator 的 Scope 只由本服务开启和关闭；它分别绑定来源正式轮与真实取消根，并核对集合刷新、唯一新根和新权威成员。Mapper CAS 影响行数和 Coordinator 正常返回共同确认命令成功。
