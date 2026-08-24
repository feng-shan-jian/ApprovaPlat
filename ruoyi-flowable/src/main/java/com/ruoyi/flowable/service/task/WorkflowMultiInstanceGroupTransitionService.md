# WorkflowMultiInstanceGroupTransitionService

## 作用

`WorkflowMultiInstanceGroupTransitionService` 只拥有受控多实例执行树迁移、轮次状态机、SLA 收口和 Coordinator 协议。它冻结实时执行树和正式轮次计划，执行 `ACTIVE → RETURNED`、`RETURNED → REOPENED`，并重建新 ACTIVE 轮次与完整成员任务。

## 调用方式

Return/Resubmit 应用服务分别调用准备、迁移和完成入口。退回使用 `MultiInstanceGroupReturnPlan`，重提使用 `MultiInstanceGroupReopenPlan`；成员、顺序、模式、revision 和目标节点均来自服务端冻结事实，客户端不具备权威性。

返回的 `GroupReturnMigration`、`GroupReturnResult` 和 `GroupReopenResult` 只包含任务主键、不可变轮次快照和新根信息，不暴露可变 Flowable 对象。

## 原子副作用

Flowable 状态迁移、轮次 CAS 和 SLA 收口继续处于应用服务建立的同一外层事务。审计、抄送、附件和通知由 ApplicationService 按冻结顺序编排，本服务不解析当前用户，也不依赖这些组件。Coordinator 的 Scope 只由本服务开启和关闭，正常、异常和 Flowable 回调失败均由 try-with-resources 清除线程状态；任一写后对账失败整体回滚。
