# WorkflowProcessFormDetailProjection

## 作用

`WorkflowProcessFormDetailProjection` 是流程详情专用的表单投影组件。它负责读取不可变部署表单 schema，并使用已经通过存储安全门禁的 `VariableStore` 生成历史提交表单、当前任务表单、退回修改表单和受控循环继承值。

事务、对象授权和流程实例定位由 `WorkflowProcessDetailService` 统一完成；本组件接收已授权快照，并在同一只读事务中执行表单投影。

## 依赖

- `WorkflowDeploymentArtifactRepository`：读取部署时冻结的正式表单制品；
- `WorkflowFormTemplateValidator`：验证表单结构并提取全部字段和可读字段白名单；
- `WorkflowProcessVariableProjection`：按 Blob、类型和容量门禁读取当前变量或投影正式提交快照。

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

- 历史表单使用固定内部提交快照；
- 活动且有部署表单的任务才读取当前变量，并保持 root/task-local 作用域；
- 退回修改表单以正式开始提交快照作为唯一值来源；
- 受控循环只继承同节点上一轮正式 task-local 快照，当前轮值优先；
- 表单正文累计最多 4 MiB，变量 JSON 累计最多 1 MiB；预算在历史表单和当前表单之间共享；
- deployment、form、node、task、localScope 或历史活动关系漂移时失败关闭。

`WorkflowProcessDetailService` 直接注入并调用该具体组件，表单投影职责集中在这一实现中。
