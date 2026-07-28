# WorkflowProcessAccessService

## 组件作用

`WorkflowProcessAccessService` 为流程详情、流程变量、流程图和任务读取接口提供对象级授权。菜单权限只决定用户能否进入接口，本服务继续核对用户与具体流程实例之间的真实业务关系，避免拥有同一菜单权限的用户横向读取他人流程。

## 授权依据

授权只使用服务端数据，命中以下任一关系即可读取：

- 当前有效用户是流程发起人。
- 当前有效用户是目标框架定义的超级管理员。
- Flowable 历史身份关系确认当前用户参与过该实例。
- 当前用户是未结束运行时任务的办理人；实例挂起时仍保留只读权限。
- 当前用户是未分配运行时任务的直接候选人，或属于有效 `ROLE<id>` / `DEPT<id>` 候选组；挂起状态不会让候选身份丢失详情权限。
- 当前用户办理完成过该实例中的任务。
- `wf_copy` 中存在当前有效抄送记录。

客户端提交的 `userId`、`procInsId` 与 `taskId` 关联不会作为授权依据。任务所属实例始终从 Flowable 任务记录重新查询。

## 公开方法

### `requireReadableInstance(processInstanceId)`

- 入参：Flowable 流程实例 ID。
- 返回：`WorkflowProcessAccessSnapshot` 不可变实例快照。
- 状态：运行中实例从 `RuntimeService` 返回实时 `running/suspended`，已结束实例回退历史状态，避免 Flowable 历史表在挂起期间仍报告 `RUNNING`。
- 异常：空参数返回业务码 400；实例不存在返回 404；无对象权限返回 403。

### `requireReadableTask(taskId)`

- 入参：Flowable 活动或历史任务 ID。
- 返回：`WorkflowTaskAccessSnapshot` 不可变任务快照。
- 异常：空参数返回业务码 400；任务不存在返回 404；无对象权限返回 403；任务与实例内部关联损坏返回 500。

## 最小接入示例

```java
WorkflowTaskAccessSnapshot task = processAccessService.requireReadableTask(taskId);
Map<String, Object> variables = taskVariableService.readAllowedVariables(task.processInstanceId(), task.taskId());
```

调用方仍须保留 Controller 的 `@PreAuthorize`，并对变量字段执行展示白名单过滤；本服务不替代接口权限或敏感字段过滤。
挂起实例的写动作继续由任务和实例服务执行状态校验；扩大只读可见性不会放宽认领、办理等写权限。
