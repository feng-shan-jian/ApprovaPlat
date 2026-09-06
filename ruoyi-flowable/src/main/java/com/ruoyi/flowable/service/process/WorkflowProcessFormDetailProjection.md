# WorkflowProcessFormDetailProjection

## 作用

`WorkflowProcessFormDetailProjection` 是流程详情专用的表单投影组件。它负责读取不可变部署表单 schema，并使用已经通过存储安全门禁的 `VariableStore` 生成历史提交表单、当前任务表单、退回修改表单和受控循环继承值。

事务、对象授权和流程实例定位由 `WorkflowProcessDetailService` 统一完成；本组件接收已授权快照，并在同一只读事务中执行表单投影。

## 依赖

- `WorkflowDeploymentArtifactRepository`：读取部署时冻结的正式表单制品；
- `WorkflowFormTemplateValidator`：验证表单结构并提取全部字段和可读字段白名单；
- `WorkflowProcessVariableProjection`：按 Blob、类型和容量门禁读取当前变量或投影正式提交快照。
- `WorkflowAttachmentService`：按每批最多 100 条查询并核验所有附件的对象、字段、状态和绑定关系，再返回安全元数据。

## 接入方式

```java
WorkflowProcessFormDetailProjection projection =
        new WorkflowProcessFormDetailProjection(
                artifactRepository, formTemplateValidator, variableProjection,
                attachmentService);

FormSchemas schemas = projection.loadSchemas(deploymentId);
FormProjection forms = projection.project(new FormProjectionRequest(
        schemas, history, process, variables, processInstanceId, deploymentId,
        requestedTask, currentTaskControlledLoop, returnedApplication));
```

`FormSchemas` 只在授权后的详情编排链中传递部署表单索引；`FormProjection` 只返回正式
`formDetail`，它是流程详情唯一的表单协议。

`formDetail` 固定包含：

- `applicationForm`：普通态为唯一正式开始提交快照；退回申请态为绑定当前活动任务的可编辑开始表单；
- `nodeTaskForm`：普通态为本次请求任务的节点表单，退回申请态固定为空；
- `historyForms`：仅正式历史 UserTask 提交，排除开始表单和本次请求 task ID，并复制为不可修改集合；
- `returnedApplication`：仅由稳定 `returned` 状态和本次已授权活动任务确定。

## 关键设计

- 历史表单使用固定内部提交快照；
- 活动且有部署表单的任务才读取当前变量，并保持 root/task-local 作用域；
- 退回修改表单以正式开始提交快照作为唯一值来源；
- 受控循环只继承同节点上一轮正式 task-local 快照，当前轮值优先；
- 表单正文累计最多 4 MiB，变量 JSON 累计最多 1 MiB；预算在历史表单和当前表单之间共享；
- 单次正式提交最多引用 100 个附件；详情允许聚合多个合法提交超过 100 个附件，并按同一查询批次上限分批完成对象授权与一致性核验；
- deployment、form、node、task、localScope 或历史活动关系漂移时失败关闭。
- 新 VO 构造时拒绝缺少申请表单、退回态出现节点表单、申请表单 task 绑定错误以及历史表单没有 task ID；流程详情继续核对 returned 状态、活动任务和任务主键。

`WorkflowProcessDetailService` 直接注入并调用该具体组件，表单投影职责集中在这一实现中。
