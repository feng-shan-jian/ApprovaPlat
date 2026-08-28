# WorkflowMultiInstanceRoundRepository

## 作用

`WorkflowMultiInstanceRoundRepository` 是 `wf_multi_instance_round` 的唯一生产持久化边界。它封装正式 Mapper、空结果规范化、实体到不可变快照转换以及各生命周期条件 CAS；Mapper XML 与 SQL 条件继续作为底层数据合同。

## 依赖方向

- 上游：轮次生命周期、整组迁移、轮次终止服务。
- 下游：`WfMultiInstanceRoundMapper`。
- 返回值：`MultiInstanceRoundSnapshot` 或不可变集合；Mapper 实体封装在 Repository 内部。

## 一致性约束

- 所有列表结果使用非空容器和非空行，异常 Mapper 结果返回数据错误。
- 所有 Mapper 实体均通过 `MultiInstanceRoundSnapshot.from` 校验。
- 单行 CAS 影响行数必须为 1；否则保留原有 409 消息与 revision 子码。
- 批量终止影响行数必须与预检轮次数一致。
- 上层应用服务通过 `WorkflowEngineOperations` 建立事务，Repository 加入当前事务执行 Mapper 操作。

## 最小接入示例

```java
MultiInstanceRoundSnapshot round = roundRepository.findByRootExecutionId(rootId);
roundRepository.compareAndSetCompleted(round.roundId(), round.revision(), round.members());
```
