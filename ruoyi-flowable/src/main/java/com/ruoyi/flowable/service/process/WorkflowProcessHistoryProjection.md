# WorkflowProcessHistoryProjection

## 作用

`WorkflowProcessHistoryProjection` 是流程详情专用的历史展示组件。它集中读取和核验历史活动、任务、意见、候选身份、用户名与父子流程关系，并生成时间线和 Viewer 状态。

组件不建立事务、不执行对象授权。调用方必须传入 `WorkflowProcessAccessService` 已授权的实例快照，并继续使用详情入口建立的同一只读事务。

## 依赖

- `HistoryService`：历史实例、活动、任务和候选身份；
- `TaskService`：流程意见；
- `RepositoryService`：父子实例引用的流程定义；
- `ISysUserService`：历史用户主键的当前显示名称。

## 接入方式

```java
WorkflowProcessHistoryProjection projection =
        new WorkflowProcessHistoryProjection(
                historyService, taskService, repositoryService, userService);

HistoryData history = projection.loadHistory(authorizedInstance);
Set<String> ancestorDeploymentIds =
        projection.loadAncestorDeploymentIds(authorizedInstance.processInstanceId());
HistoryPresentation presentation = projection.projectPresentation(
        history, authorizedInstance, applicationReturned);
List<WorkflowProcessRelationView> relations =
        projection.buildProcessRelations(authorizedInstance.processInstanceId());
```

`HistoryData` 让表单投影与历史展示复用同一批有界活动、任务和意见，不产生第二套历史读取；`HistoryPresentation` 返回发起人名称、时间线和 Viewer。

## 关键设计

- 活动、任务、意见、候选身份和父子流程树都执行固定数量上限，不返回截断审计；
- 意见只公开正式业务类型，结构化审计只提取用户可见 `opinion`；
- 时间线优先使用 `completedBy`，删除用户或异常历史身份保留原始主键；
- Viewer 从同一份活动和意见数据生成已完成、未完成、驳回和退回集合；
- 祖先部署集合只用于识别 CallActivity `inheritVariables` 复制的内部提交快照；
- 父子流程关系存在循环、断链、重复或定义缺失时失败关闭。

该组件是详情用直接实现，不提供接口、Registry、Strategy 或通用历史查询入口。
