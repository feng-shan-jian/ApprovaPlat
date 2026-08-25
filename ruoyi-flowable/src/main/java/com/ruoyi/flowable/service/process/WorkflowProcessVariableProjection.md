# WorkflowProcessVariableProjection

## 作用

`WorkflowProcessVariableProjection` 是流程详情专用的变量存储与安全投影组件。它不是通用 JSON 工具，也不提供可替换策略：`WorkflowProcessDetailService` 在完成实例/任务对象授权后直接调用它，并继续使用详情入口已经建立的同一只读事务。

组件维护三组必须一起变化的不变量：

- 从 `ACT_HI_DETAIL` 读取正式提交快照时，先完整校验元数据、类型、物理存储关系和累计容量，再分批读取正文并执行固定快照协议解码；
- 从 `ACT_HI_VARINST` 读取活动表单当前值时，先通过 Flowable 禁止初始化查询限定根变量或当前 task-local 作用域，再用生产 Mapper 核对物理元数据，按需读取 Blob/JSON 正文；
- 所有响应值统一执行内部变量隐藏、JSON 危险键、深度、节点、容器、文本、正文和序列化字节门禁，非法存储或关联矛盾统一失败关闭。

## 接入方式

Spring 通过构造器注入真实 `HistoryService` 和 `WorkflowHistoricVariableMapper`。详情服务只注入该具体组件，不增加接口、Registry、Strategy、工厂或兼容路径。

```java
WorkflowProcessVariableProjection variableProjection =
        new WorkflowProcessVariableProjection(historyService, historicVariableMapper);
WorkflowProcessFormDetailProjection formProjection =
        new WorkflowProcessFormDetailProjection(
                artifactRepository, formTemplateValidator, variableProjection);
WorkflowProcessHistoryProjection historyProjection =
        new WorkflowProcessHistoryProjection(
                historyService, taskService, repositoryService, userService);
WorkflowProcessDetailService detailService = new WorkflowProcessDetailService(
        engineOperations, processAccessService, repositoryService, taskService,
        deploymentService, variableProjection, formProjection, historyProjection,
        multiInstanceService,
        taskLifecycleService, controlledLoopService);
```

## 入口与返回值

- `loadSubmissionSnapshots(instanceId, deploymentId, ancestorDeploymentIds)`：返回已完成两阶段读取和固定协议解码的 `VariableStore`；升级前没有正式快照时返回空索引。
- `projectCurrentValues(instanceId, taskId, taskLocal, readableNames)`：只为已授权活动且有部署表单的任务读取当前作用域变量，返回安全 `ProjectedValues`。
- `projectSubmittedValues(...)` / `projectControlledLoopValues(...)`：按部署 schema 投影固定提交快照，并分别保留正式历史和受控循环的稳定错误语义。

`ProjectedValues` 同时返回逐字段 JSON 序列化字节数。`WorkflowProcessFormDetailProjection` 把这些字节并入跨历史表单和当前表单共享的整页 `MAX_TOTAL_VARIABLE_BYTES` 预算，因此职责拆分不会改变原有资源上限。

## 关键设计约束

- 调用前置条件是详情服务已经完成对象授权；组件本身不得成为绕过授权的 HTTP 或公共业务入口。
- 快照与活动变量正文继续使用生产 `WorkflowHistoricVariableMapper` 的元数据/正文两阶段 SQL，Blob 只按需读取。
- 字符串 Blob 仅允许反序列化单个 `String`；JSON 使用严格重复字段和尾随内容检测。
- `bytes`、`serializable`、未知类型、自定义对象、内部变量和危险 JSON 键不得进入详情响应。
- 存储损坏、关联漂移、作用域矛盾或任一资源上限触发时返回原有 HTTP 500 数据异常，不返回截断或猜测结果。
