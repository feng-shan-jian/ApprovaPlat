# WorkflowMultiInstanceRoundRepository

## 作用

`WorkflowMultiInstanceRoundRepository` 是 `wf_multi_instance_round` 的唯一生产持久化边界。它封装正式 Mapper、空结果规范化、实体到不可变快照转换以及各生命周期条件 CAS，不改变 Mapper XML 与 SQL 条件。

## 依赖方向

- 上游：轮次生命周期、整组迁移、轮次终止服务。
- 下游：`WfMultiInstanceRoundMapper`。
- 返回值：只返回 `MultiInstanceRoundSnapshot` 或不可变集合，不向领域服务暴露 Mapper 实体。

## 一致性约束

- 所有列表结果拒绝 `null` 列表和 `null` 行。
- 所有 Mapper 实体均通过 `MultiInstanceRoundSnapshot.from` 校验。
- 单行 CAS 影响行数必须为 1；否则保留原有 409 消息与 revision 子码。
- 批量终止影响行数必须与预检轮次数一致。
- Repository 不开启事务，事务仍由上层应用服务的 `WorkflowEngineOperations` 建立。

## 最小接入示例

```java
MultiInstanceRoundSnapshot round = roundRepository.findByRootExecutionId(rootId);
roundRepository.compareAndSetCompleted(round.roundId(), round.revision(), round.members());
```
