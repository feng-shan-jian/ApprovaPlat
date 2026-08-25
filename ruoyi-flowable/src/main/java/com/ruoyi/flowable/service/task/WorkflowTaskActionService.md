# WorkflowTaskActionService

## 作用

`WorkflowTaskActionService` 是任务认领、取消认领、委派办结、委派和转办五个写动作的应用服务边界。它接受动作专用 DTO；操作人和流程实例主键由服务端在 Flowable 写事务内解析，目标用户有效性同样在事务内复核。

## 接入方式

通过 Spring 构造器注入后调用对应动作方法：

```java
taskActionService.delegate(
    new WorkflowTaskDelegateRequest("task-id", 8L, "请协助处理合同条款", List.of(12L)));
```

## 请求对象

| 动作 | DTO | 字段 |
| --- | --- | --- |
| 认领 | `WorkflowTaskClaimRequest` | `taskId` |
| 取消认领 | `WorkflowTaskUnclaimRequest` | `taskId` |
| 委派办结 | `WorkflowTaskResolveRequest` | `taskId`、`comment`、可选 `copyUserIds` |
| 委派 | `WorkflowTaskDelegateRequest` | `taskId`、`userId`、`comment`、可选 `copyUserIds` |
| 转办 | `WorkflowTaskTransferRequest` | `taskId`、`userId`、`comment`、可选 `copyUserIds` |

请求 DTO 仅接受动作字段，`actor`、`assignee` 和 `processInstanceId` 均由服务端生成。委派和转办的 `userId` 必须对应正式启用用户；委派、委派办结和转办的 `comment` 必填且最多 500 个字符。`copyUserIds` 最多 100 个，重复、不存在、停用、删除或非法用户会在引擎写入前使整个动作失败。

## 返回与异常

五个方法成功时无返回值。失败统一使用稳定业务状态：参数错误 `400`、无权操作 `403`、任务不存在 `404`、任务状态或并发冲突 `409`。

## 关键设计

- 当前用户由 `WorkflowEngineOperations.writeAsCurrentUser` 在事务内重新核验。
- Flowable 状态变更和审计 comment 使用同一身份上下文与事务，任一步失败都会整体回滚。
- 抄送元数据在任务状态变化前由服务端冻结，状态变化成功后才写 `wf_copy`；写入数量不一致或唯一键冲突会回滚引擎动作。
- 审计正文由服务端生成 JSON，操作人、目标用户和任务所属实例均使用事务内权威事实。
- 委派办结提交受托人的真实意见和可选抄送，owner 的审批任务及任务表单变量保持原状态。
- 委派和转办要求 owner/delegation 为空且目标用户与当前办理人不同。
