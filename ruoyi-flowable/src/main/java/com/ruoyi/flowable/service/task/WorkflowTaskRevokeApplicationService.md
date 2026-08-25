# WorkflowTaskRevokeApplicationService

## 作用

该服务是已办撤回和撤回能力判断的唯一应用边界，集中历史来源授权、实时后继冻结、BPMN 直接路径判断、Flowable revision 锁、原子迁移和结构化审计。

## 公开用法

```java
boolean allowed = revokeApplicationService.isProcessRevocable(instanceId, taskId);
revokeApplicationService.revokeProcess(request);
```

## 安全边界

- 仅允许当前用户真实完成的来源任务。
- 后继任务必须仍是未处理的直接同步用户任务；复杂网关、边界事件、多实例、异步和子流程均失败关闭。
- 多个安全并行后继使用一次 `moveExecutionsToSingleActivityId` 合并。
- 先通过 `saveTask` 获取 Flowable revision 行锁，再二次核验，最后执行原子迁移；`changeState` 正常返回即确认引擎命令成功，不再重查任务、活动节点、历史和刚写入的 comment。
- 能力查询仅把既有 400/403/404/409 归一为 `false`，数据损坏 500 继续上抛。
