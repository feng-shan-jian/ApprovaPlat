# WorkflowTaskRejectionApplicationService

## 作用

该服务是任务驳回的唯一应用事务入口，负责当前办理人和委派状态校验、完整流程树 rejected 终止、全部活动任务 comment、流程结果通知以及动作抄送。

## 关键顺序

1. 校验活动任务、实例、办理人与委派状态。
2. 由 `WorkflowProcessInstanceService` 解析并终止完整根执行树。
3. 在终止回调中冻结抄送计划并为全部活动任务写同一结构化驳回意见。
4. 根状态和通知稳定后持久化抄送。

任一任务消失、状态漂移、通知或抄送失败均回滚完整事务。
