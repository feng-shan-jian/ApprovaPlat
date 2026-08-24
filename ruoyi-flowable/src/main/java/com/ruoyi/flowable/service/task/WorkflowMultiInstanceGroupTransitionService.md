# WorkflowMultiInstanceGroupTransitionService

## 作用

`WorkflowMultiInstanceGroupTransitionService` 只拥有受控多实例执行树迁移、退回双状态、轮次状态机、SLA 收口和 Coordinator 协议。它冻结实时执行树和正式轮次计划，原子执行 `ACTIVE → RETURNED`、`RETURNED → REOPENED`，并重建新 ACTIVE 轮次与完整成员任务。

## 调用方式

Return/Resubmit 应用服务先调用只读准备入口，再分别调用一次 `returnGroup` 或 `reopenGroup` 写入口。退回使用 `MultiInstanceGroupReturnPlan`，重提使用 `MultiInstanceGroupReopenPlan`；成员、顺序、模式、revision 和目标节点均来自服务端冻结事实，客户端不具备权威性。

返回的 `GroupReturnResult` 和 `GroupReopenResult` 只保留后续通知和命令观察真正需要的任务主键与新根信息，不再重复表达已经由 Mapper CAS 确认的旧轮成功状态。

## 原子副作用

Flowable 状态迁移、退回双状态、轮次 CAS 和 SLA 收口处于应用服务建立的同一外层事务，并由单个整组写入口保持固定顺序。审计、抄送、附件和通知由 ApplicationService 编排，本服务不解析当前用户，也不依赖这些组件。Coordinator 的 Scope 只由本服务开启和关闭；Mapper CAS 影响行数和 Coordinator 正常返回共同确认命令成功，不再回读刚更新的旧轮逐字段自证。
