# WorkflowTaskActionService

## 作用

`WorkflowTaskActionService` 是任务认领、取消认领、委派办结、委派和转办五个写动作的应用服务边界。它接受经过 HTTP DTO 校验的请求，负责参数规范化和动作编排；业务权限、任务状态及目标用户资格由现有引擎事务内逻辑核验，操作人和流程实例主键同样在事务内解析。

## 接入方式

五个生产入口均为 `WfTaskController` 的 `@Valid @RequestBody` 动作方法。Controller 完成 DTO 校验后，通过构造器注入的应用服务调用对应动作；接入前提是请求已经通过该 HTTP 校验边界，不能把任意手工构造、未经校验的 DTO 直接传入服务：

```java
public AjaxResult delegate(@Valid @RequestBody WorkflowTaskDelegateRequest request)
{
    taskActionService.delegate(request);
    return success();
}
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

DTO 按原始输入校验 `taskId` 最多 64 个字符、`comment` 最多 500 个字符。校验通过后，应用服务在调用 Adapter 前对 `taskId` 和 `comment` 执行 `trim()`，目标用户仍通过 `String.valueOf(request.userId())` 转为字符串；Adapter 校验接收规范化后的意见，最终审计也使用该结果。

## 返回与异常

五个方法成功时无返回值。引擎业务校验失败使用稳定业务状态：参数错误 `400`、无权操作 `403`、任务不存在 `404`、任务状态或并发冲突 `409`。HTTP DTO 校验和空请求体的响应继续由现有全局异常处理映射；传输层 HTTP 状态与响应体 `AjaxResult.code` 分别表示，不能把参数拒绝统一理解为 HTTP 400。

## 关键设计

- 当前用户由 `WorkflowEngineOperations.writeAsCurrentUser` 在事务内重新核验。
- Flowable 状态变更和审计 comment 使用同一身份上下文与事务，任一步失败都会整体回滚。
- 抄送元数据在任务状态变化前由服务端冻结，状态变化成功后才写 `wf_copy`；写入数量不一致或唯一键冲突会回滚引擎动作。
- 审计正文由服务端生成 JSON，操作人、目标用户和任务所属实例均使用事务内权威事实。
- 委派办结提交受托人的真实意见和可选抄送，owner 的审批任务及任务表单变量保持原状态。
- 委派和转办要求 owner/delegation 为空且目标用户与当前办理人不同。
