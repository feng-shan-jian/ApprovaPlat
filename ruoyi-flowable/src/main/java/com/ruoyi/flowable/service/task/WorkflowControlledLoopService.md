# WorkflowControlledLoopService

## 组件简介

`WorkflowControlledLoopService` 负责受控重复审批节点的完成决策和详情投影。完成任务时，它使用部署阶段冻结的循环配置判断本轮是 `REPEAT` 还是 `EXIT`，校验最大轮次，并在同一事务写入路由变量、Flowable comment 和 `wf_controlled_loop_execution` 审计记录。

该服务不负责查询当前任务所属流程定义。任务完成链必须先完成任务、实例、办理人和 BPMN 关系校验，再把同一不可变 BPMN 上下文中的 `processKey`、`deploymentId` 传入本服务。详情查询入口则直接传入已完成对象授权的部署和流程信息。

## 使用方式

### 完成任务前决策

```java
controlledLoopService.prepareCompletion(
        task,
        processDefinition.getKey(),
        processDefinition.getDeploymentId(),
        validatedVariables,
        currentUserId);
```

`prepareCompletion` 必须位于任务完成事务内，并在普通完成审计、附件绑定、提交快照和 `taskService.complete(...)` 之前调用。普通节点没有部署循环快照时直接返回，不产生变量或数据库写入。

参数说明：

- `task`：已经通过活动态、实例、办理人、委派状态校验的真实 Flowable 任务。
- `processKey`：完成链唯一流程定义上下文中的流程 key。
- `deploymentId`：同一流程定义上下文中的部署主键。
- `variables`：已经通过当前节点部署表单 schema 校验和附件安全投影的变量。
- `actorUserId`：当前真实完成人主键。

方法无返回值。判断值非法、轮次达到上限或并发重复写入时抛出原有稳定业务异常和 `subCode`，外层事务必须整体回滚。

### 构建详情状态

```java
List<WorkflowControlledLoopStateView> states = controlledLoopService.buildStates(
        deploymentId,
        processKey,
        processInstanceId,
        activeActivityId);
```

`activeActivityId` 可以为空；有活动循环任务时用于计算当前待处理轮次。返回结果按节点稳定排序，并包含部署上限、已完成轮次、当前轮次和逐轮审计。

## 公开方法

### `prepareCompletion(...)`

读取唯一部署循环快照，规范化判断字段，校验 `REPEAT/EXIT` 和最大轮次，然后按以下顺序写入：

1. 插入 `wf_controlled_loop_execution` 单轮审计；
2. 写入流程实例路由变量和轮次变量；
3. 写入类型为 `CONTROLLED_LOOP` 的结构化 Flowable comment。

数据库对 `taskId` 以及“实例、节点、轮次”设置唯一约束，用于把重复提交和并发完成收敛为稳定冲突。

### `buildStates(...)`

组合部署循环快照和实例审计记录，验证轮次连续、未超过上限且 `EXIT` 后不存在后续记录，再生成只读状态视图。调用方必须先完成流程详情对象授权。

## 关键设计

- 循环配置来自不可变部署资源，不读取设计草稿或客户端配置。
- `processKey` 与 `deploymentId` 来自完成链已经核验的单一 BPMN 上下文，本服务不重复查询流程定义。
- 判断变量只能来自节点表单 schema 已放行的标量值，服务端保留路由变量不能由客户端直接控制。
- 审计表、路由变量、循环 comment 和任务完成共享同一事务；任一步失败都不能留下部分副作用。
- 最大轮次达到上限时不会自动替用户选择退出，仍要求明确提交退出结果。

## 最小接入示例

```java
BpmnContext context = requireBpmnContext(task.getProcessDefinitionId());
Map<String, Object> projectedVariables = validateAndProject(task, request.variables());

controlledLoopService.prepareCompletion(
        task,
        context.definition().getKey(),
        context.definition().getDeploymentId(),
        projectedVariables,
        actor.userId());

taskService.complete(task.getId(), actor.userId(), projectedVariables, false);
```

示例中的上下文装载、表单校验和任务完成都应由统一生命周期写入口组织；不要在本服务外另起事务或缓存 BPMN/任务状态。
