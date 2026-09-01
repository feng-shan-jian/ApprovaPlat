# WorkflowTaskUnclaimPolicy

## 组件简介与作用

`WorkflowTaskUnclaimPolicy` 是取消认领能力与写入门禁共享的唯一纯规则组件。它不查询 Flowable、数据库或外部 API，不开启事务，也不捕获异常降级；调用方必须先取得可信的服务端事实，再调用 `isUnclaimAllowed(...)`。

## 规则合同

只有以下条件全部成立时返回 `true`：

- 流程实例仍活动且未挂起；
- 任务仍为运行时活动任务且未挂起；
- 服务端当前用户同时等于 `assignee` 和 `claimedBy`；
- `owner == null`；
- `delegationState == null`；
- `claimTime` 已存在。

任一事实不满足时返回 `false`。`owner` 或 `delegationState` 的空字符串不是合法空值，同样失败关闭。历史任务、静态直接指派任务、转办后遗留认领来源以及不完整认领元数据均不能开放取消认领。

## 调用边界

- `/workflow/process/todoList` 使用当前页已批量装载的任务和历史实例事实生成 `rows[*].unclaimAllowed`，不增加逐行查询。
- `/workflow/process/detail` 由 `WorkflowProcessAccessService` 使用同一只读事务中的运行时实例、任务和当前身份生成 `data.currentTask.unclaimAllowed`。
- `/workflow/task/unClaim` 在写事务中重新查询活动任务和活动实例，保留 404、403、409 异常顺序后，再以本规则执行最终门禁。

能力字段只是服务端状态快照，不替代 Controller 的 `workflow:process:claim` 权限，也不能跳过提交时的重新查询和最终校验。

## 最小接入示例

```java
boolean unclaimAllowed = unclaimPolicy.isUnclaimAllowed(
        processActive, taskActive, task.isSuspended(), currentUserId,
        task.getAssignee(), task.getOwner(), delegationState,
        task.getClaimedBy(), task.getClaimTime() != null);
```
