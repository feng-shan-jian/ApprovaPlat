# WorkflowTaskCopyService

## 作用

`WorkflowTaskCopyService` 为完成、驳回、退回、委派和转办动作生成并持久化正式抄送记录。客户端只提交接收用户主键；任务、实例、定义、部署、分类、标题、发起人和操作人均从已授权活动任务及服务端身份上下文解析。

## 接入方式

在 Flowable 状态变化前调用 `prepare` 冻结 `CopyPlan`，状态变化成功后在同一 `WorkflowEngineOperations.writeAsCurrentUser` 事务中调用 `persist`：

```java
CopyPlan plan = taskCopyService.prepare(
    WorkflowTaskCopyAction.COMPLETE, task, actor, List.of(12L, 13L));
taskService.complete(task.getId());
taskCopyService.persist(plan);
```

## 关键约束

- 单次最多 100 个接收人，必须是不重复的正式启用用户。
- 活动任务 revision 在对象授权后由独立只读 Mapper 从 `ACT_RU_TASK` 复核；该引擎表查询不会混入带 `del_flag` 契约的 `wf_copy` 业务 Mapper。实例、定义和部署关系也必须在动作前真实存在且一致。
- 稳定事件键格式为 `动作类型:任务ID:r任务revision`。
- `wf_copy(copy_event_id,user_id)` 唯一键提供数据库级幂等兜底。
- `persist` 只在 `wf_copy` 正式写入或幂等命中后登记 `COPY_CREATED` outbox；通知读取真实 `copy_id`、接收人和流程定义，不能使用客户端快照替代。
- 抄送事实与通知 outbox 共用当前写事务；通知登记失败会向上抛出并回滚任务动作及 `wf_copy`，不允许仅有抄送或仅有通知的半状态。
- 批量写入数量必须与计划数量一致；不一致时返回数据错误并回滚整个动作。
- 发起人名称、流程名称和任务名称是服务端快照，不能由客户端覆盖。

## 返回与异常

`prepare` 返回不可变 `CopyPlan`；未选择接收人时返回空计划。非法用户返回 `400`，任务并发变化返回 `409`，引擎或业务关联损坏返回 `500`。`persist` 成功时无返回值。
