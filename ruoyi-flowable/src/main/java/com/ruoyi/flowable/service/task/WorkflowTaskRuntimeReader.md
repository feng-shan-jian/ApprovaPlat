# WorkflowTaskRuntimeReader

## 作用

统一读取活动任务、活动或挂起流程实例、当前办理人及唯一目标任务，并保持原有 403、404、409 错误契约。组件返回只读运行快照，Flowable 迁移和业务写入由应用服务执行。

## 使用与边界

应用服务传入规范化 ID 或已读取的 `Task`；返回的 Flowable 对象仅在当前用例事务内使用。取消入口使用允许挂起实例的专用读取方法，其他动作使用活动实例读取方法。

```java
Task task = runtimeReader.requireActiveTask(taskId);
runtimeReader.requireCurrentAssignee(task, actor);
```
