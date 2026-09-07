# WorkflowTaskLifecycleService

## 作用

`WorkflowTaskLifecycleService` 是 Controller 保持不变的稳定应用门面。它把 Flowable、Mapper、BPMN、表单、附件和轮次职责委托给专用服务，并复用专用服务建立的事务边界。

## 公开方法与委派

| 门面方法 | 应用服务 |
|---|---|
| `cancelProcess` | `WorkflowProcessCancelApplicationService` |
| `isProcessRevocable` / `revokeProcess` | `WorkflowTaskRevokeApplicationService` |
| `completeTask` | `WorkflowTaskCompletionApplicationService` |
| `rejectTask` | `WorkflowTaskRejectionApplicationService` |
| `returnTask` / `isTaskReturnAllowed` | `WorkflowTaskReturnApplicationService` |
| `resubmitApplication` / `isApplicationResubmitAllowed` | `WorkflowApplicationResubmitApplicationService` |

## 事务边界

门面透传原 DTO、规范 ID 和原返回值。每个应用服务通过既有 `WorkflowEngineOperations` 建立唯一外层事务或只读快照，所有业务写入在该事务内一次提交。

完成、驳回、退回、重提、取消和撤回写入口只接收已经通过 DTO Bean Validation 的请求。正式调用方是 `WfTaskController` 的 `@Valid @RequestBody` 方法；Java 调用方也必须先完成相同校验。应用服务保留 ID 和意见的 `trim()` 规范化，能力查询 ID 校验、对象归属、权限、持久化事实及最终写入复核继续在服务端执行。

## 最小接入示例

```java
lifecycleService.completeTask(request);
boolean revocable = lifecycleService.isProcessRevocable(instanceId, taskId);
boolean resubmitAllowed = lifecycleService.isApplicationResubmitAllowed(taskId);
```
