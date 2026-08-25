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
| `resubmitApplication` | `WorkflowApplicationResubmitApplicationService` |

## 事务边界

门面透传原 DTO、规范 ID 和原返回值。每个应用服务通过既有 `WorkflowEngineOperations` 建立唯一外层事务或只读快照，所有业务写入在该事务内一次提交。

## 最小接入示例

```java
lifecycleService.completeTask(request);
boolean revocable = lifecycleService.isProcessRevocable(instanceId, taskId);
```
