# WorkflowMultiInstanceModelContract

## 作用

`WorkflowMultiInstanceModelContract` 是动态会签和或签共用的 BPMN 白名单。模型保存、来源任务完成、运行时加减签、任务完成 revision 以及低层任务动作都会调用同一契约，避免不同入口对“受控动态多实例”作出不同判断。

## 固定模型

- 节点必须是主流程中的并行 `UserTask`，办理人为 `${assignee}`。
- 集合必须是 `${multiInstanceHandler.getUserIds(execution)}`，元素变量必须是 `assignee`。
- 完成条件只能是 ALL `${nrOfCompletedInstances == nrOfInstances}` 或 ANY `${nrOfCompletedInstances > 0}`。
- 节点不得包含 async、async leave、非排他、skip、补偿、边界事件、loop cardinality、索引变量、聚合或自定义 handler。
- 动态目标必须在来源任务完成的同一事务内同步创建全部 task 和 execution，供服务端立即核对成员快照、revision 与 `nrOf*` 根计数。

## 调用示例

```java
WorkflowMultiInstanceMode mode =
        WorkflowMultiInstanceModelContract.requireMode(flowElement);
```

不符合任一条件时抛出 `IllegalArgumentException`。HTTP 服务必须在自己的业务边界将其转换为稳定的 `400` 或 `409`，不能向客户端暴露部署模型细节。
