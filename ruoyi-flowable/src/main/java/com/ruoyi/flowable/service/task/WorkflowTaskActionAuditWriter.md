# WorkflowTaskActionAuditWriter

## 作用

统一构造任务完成、取消、驳回、退回和撤回的结构化 Flowable comment，保持既有 JSON 字段、comment 类型和动态多实例 revision 审计。

## 事务边界

comment 写入加入调用应用服务通过 `WorkflowEngineOperations` 建立的外层事务，后续附件、通知或 CAS 失败时一并回滚。

```java
auditWriter.write(task, "3", "REJECT", actor.userId(), opinion, null, null);
```
