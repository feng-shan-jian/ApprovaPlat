# WorkflowMultiInstanceRuntimeSnapshotReader

## 作用

`WorkflowMultiInstanceRuntimeSnapshotReader` 是受控多实例运行时解析的唯一生产实现。它只读核对部署定义、受控 UserTask、流程实例、多实例根、child execution、活动任务、有序成员、ALL/ANY 模式、revision 和三项引擎计数，并返回顶层不可变快照。

## 使用方式

由 `WorkflowMultiInstanceService`、轮次生命周期、整组迁移和轮次终止服务直接注入。调用方按用例选择完整活动任务快照、创建事件根快照、取消事件根快照或流程树全部活动根；普通未受控节点返回 `null`，缺失、重复和漂移数据失败关闭。

## 关键设计

- 通过 `RepositoryService`、`RuntimeService` 和 `TaskService` 读取运行事实并返回不可变快照；Flowable 写入、Mapper、审计和通知由各自领域服务负责。
- `readActiveRoots` 在一次扫描内按流程定义复用已加载的 ProcessDefinition/BPMN model，并复用同批 execution 与 task 完成根、child 和成员对账。
- 正式 ACTIVE 根要求任务办理人属于冻结成员；RETURNED 临时申请人根只在流程树扫描入口允许申请人改派，后续由正式轮次和申请人任务关系继续严格核对。
- 集合均在 record 构造时复制，服务间传递不可变任务快照；可变 Flowable `Task` 保留在读取边界内。
