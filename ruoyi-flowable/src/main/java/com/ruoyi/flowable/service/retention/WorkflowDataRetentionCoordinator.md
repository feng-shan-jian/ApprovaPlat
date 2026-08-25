# WorkflowDataRetentionCoordinator

## 组件作用

统一触发工作流持久化数据生命周期批处理。协调器只编排十二个固定父领域并记录指标，不直接依赖 `JdbcTemplate`、Mapper、表名或 SQL，也不创建状态表和全局锁。

## 使用方式

Spring 自动装配全部 `WorkflowDataRetentionCleaner` 后，按 `flowable.data-retention.initial-delay` 和 `flowable.data-retention.fixed-delay` 周期执行。每个领域在自己的短事务中使用稳定主键、`LIMIT` 和 `FOR UPDATE SKIP LOCKED` 领取一个有界批次。

## 配置

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `batch-size` | `100` | 单域单轮最大领取数 |
| `notification-outbox-retention` | `P90D` | 成功或取消通知 outbox 保留期 |
| `runtime-event-retention` | `P90D` | 已完成运行事件请求保留期 |
| `process-draft-retention` | `P30D` | SUBMITTED/DELETED 草稿保留期 |
| `collaboration-retention` | `P90D` | 已完成协作消息/outbox 保留期 |
| `attachment-metadata-retention` | `P90D` | 已物理删除附件元数据保留期 |
| `notification-inbox-retention` | `P180D` | 已读 inbox 保留期 |
| `bpmn-event-audit-retention` | `P180D` | 已结束流程 BPMN 事件审计保留期 |
| `task-sla-retention` | `P180D` | COMPLETED/ESCALATED SLA 执行及级联审计保留期 |
| `copy-retention` | `P180D` | 已结束流程已读或逻辑删除抄送保留期 |
| `controlled-loop-retention` | `P180D` | 已结束流程受控循环记录保留期 |
| `multi-instance-round-retention` | `P180D` | 已结束流程多实例轮次快照保留期，从流程结束时间起算 |

## 公开方法

`runScheduledBatch()` 执行一轮全部数据域。某一领域失败后仍继续其他领域，最终抛出聚合异常并保留各领域真实指标。

## 关键设计

- 未读 inbox、待处理 outbox、DELIVERING、RETRYING 和 DEAD_LETTER 不进入任何自动删除条件。
- 通知清理只执行终态历史 `DELETE`，通知状态迁移仍由通知领域服务唯一拥有。
- 协作 audit 在父记录清理事务内按 `message_id + direction` 先删；SLA audit 由数据库外键级联删除。
- BPMN 事件、抄送、受控循环和多实例轮次只有在 Flowable 历史流程已结束时才进入候选。
- 多实例轮次以 Flowable 历史流程的结束时间计算保留期，运行流程的 ACTIVE/RETURNED 轮次不会进入清理候选。
- 不持久化清理游标；多节点竞争由 InnoDB 行锁和 `SKIP LOCKED` 分摊。
- 指标只使用固定 `domain`、`result` 标签，并输出扫描、领取、删除、失败、耗时及最老终态年龄。

## 最小接入示例

通常无需手工调用；测试或运维受控入口可注入协调器后执行：

```java
workflowDataRetentionCoordinator.runScheduledBatch();
```
