# WorkflowProcessFormDetailProjection

## 作用

`WorkflowProcessFormDetailProjection` 是流程详情专用的表单投影组件。它负责读取不可变部署表单 schema，并使用已经通过存储安全门禁的 `VariableStore` 生成历史提交表单、当前任务表单、退回修改表单和受控循环继承值。

组件不建立事务、不执行对象授权，也不查询任意流程实例。`WorkflowProcessDetailService` 必须先完成实例与任务对象授权，再在同一只读事务中调用它。

## 依赖

- `WorkflowDeploymentArtifactRepository`：读取部署时冻结的正式表单制品；
- `WorkflowFormTemplateValidator`：验证表单结构并提取全部字段和可读字段白名单；
- `WorkflowProcessVariableProjection`：读取当前变量或投影正式提交快照，不绕过 Blob、类型和容量门禁。

## 接入方式

```java
WorkflowProcessFormDetailProjection projection =
        new WorkflowProcessFormDetailProjection(
                artifactRepository, formTemplateValidator, variableProjection);

FormSchemas schemas = projection.loadSchemas(deploymentId);
FormProjection forms = projection.project(new FormProjectionRequest(
        schemas, history, process, variables, deploymentId,
        requestedTask, currentTaskControlledLoop, returnedApplication));
```

`FormSchemas` 只在授权后的详情编排链中传递部署表单索引；`FormProjection` 返回可选的 `currentTaskForm` 和正式 `processForms` 列表。

## 关键设计

- 历史表单只使用固定内部提交快照，不从最终流程变量反推；
- 活动且有部署表单的任务才读取当前变量，并保持 root/task-local 作用域；
- 退回修改表单复用正式开始提交快照，不查询临时任务变量；
- 受控循环只继承同节点上一轮正式 task-local 快照，当前轮值优先；
- 表单正文累计最多 4 MiB，变量 JSON 累计最多 1 MiB；预算在历史表单和当前表单之间共享；
- deployment、form、node、task、localScope 或历史活动关系漂移时失败关闭。

该组件是详情用直接实现，不提供接口、Registry、Strategy 或通用表单工具入口。
