# WorkflowTaskLifecycleService

## 作用

`WorkflowTaskLifecycleService` 是 Controller 保持不变的稳定应用门面。它不读取 Flowable、Mapper、BPMN、表单、附件或轮次事实，也不建立额外事务。

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

门面只透传原 DTO、规范 ID 和原返回值。每个应用服务通过既有 `WorkflowEngineOperations` 建立自己的唯一外层事务或只读快照，禁止 `REQUIRES_NEW` 和拆分提交。

## 最小接入示例

```java
lifecycleService.completeTask(request);
boolean revocable = lifecycleService.isProcessRevocable(instanceId, taskId);
```
