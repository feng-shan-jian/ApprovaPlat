# WorkflowMultiInstanceRoundLifecycleService

## 作用

`WorkflowMultiInstanceRoundLifecycleService` 只负责正式轮次的 ACTIVE/COMPLETED 生命周期：任务创建登记或复核、任务完成收口、唯一 ACTIVE 轮次读取和成员快照 CAS。

## 调用方

- `WorkflowUserTaskListener` 传入不可变 `WorkflowTaskEventSnapshot`。
- `WorkflowMultiInstanceService` 在 Flowable revision 写锁之后调用成员快照 CAS，并在任务完成后验证轮次状态。
- `WorkflowMultiInstanceGroupTransitionService` 在重提创建新审批组时复用同一创建监听链。

## 并发与事务

Flowable revision/执行树锁始终先于业务表 CAS。CAS 影响行数不为一时保持稳定 `409` 子码；同一路径只读取一次 OPEN 轮次并由其状态判断 ACTIVE，`insertActive` 返回的正式快照由监听器直接复用，不再按根重复查询。完成 listener 的 CAS 是完成状态唯一确认点，外层不再回读轮次。
