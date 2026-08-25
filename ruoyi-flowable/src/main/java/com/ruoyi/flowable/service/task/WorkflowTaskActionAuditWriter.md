# WorkflowTaskActionAuditWriter

## 作用

统一构造任务完成、取消、驳回、退回和撤回的结构化 Flowable comment，保持既有 JSON 字段、comment 类型和动态多实例 revision 审计。

## 事务边界

组件不创建事务。comment 写入由调用应用服务的 `WorkflowEngineOperations` 外层事务管理，后续附件、通知或 CAS 失败时一并回滚。

```java
auditWriter.write(task, "3", "REJECT", actor.userId(), opinion, null, null);
```
