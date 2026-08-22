# WorkflowAuthenticationContext

## 组件简介与作用

`WorkflowAuthenticationContext` 统一设置和清理 Flowable 的 `authenticatedUserId`，保证流程发起、任务命令和审计记录使用规范的若依 `sys_user.user_id` 字符串。它处理异常、线程复用和嵌套调用时的身份恢复，避免一个请求的操作人泄漏到后续请求。

本组件只管理 Flowable 操作人上下文，不提供 Spring 事务、业务授权、任务状态校验或 Flowable 异常翻译。任务动作命令应通过 [WorkflowProcessEngineAdapter](../engine/WorkflowProcessEngineAdapter.md) 接入，任务完成应通过 `WorkflowTaskLifecycleService.completeTask(...)` 接入；模型发布、流程发起、动态多实例和复杂生命周期由专用领域服务接入。各类入口都必须由 [WorkflowEngineOperations](../engine/WorkflowEngineOperations.md) 在事务内完成身份复核后调用本组件。

## 接入与使用方式

任务完成的推荐调用链是业务 Service 注入正式生命周期服务，而不是直接注入本组件：

```java
taskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
        taskId, comment, variables, copyUserIds, nextUserIds, expectedRevision));
```

只有 `WorkflowEngineOperations` 等引擎执行基础设施才可直接使用本组件。专用领域服务可以组合 Flowable 公共 API，但只能通过 `WorkflowEngineOperations.writeAsCurrentUser(...)` 或受控 `writeAsUser(...)` 建立操作人作用域。禁止在业务 Service 中使用 `runAsCurrentUser(() -> taskService.complete(...))`，该写法会绕过正式完成链的表单、附件、抄送、动态下一办理人、动态多实例 revision、assignee、委派和挂起状态校验。

## 公开方法

| 方法 | 入参 | 返回值 | 适用范围 |
| --- | --- | --- | --- |
| `runAsCurrentUser(Supplier<T> action)` | 当前请求内的非空操作 | `T`，操作结果 | 使用 Spring Security 当前用户 ID 设置 Flowable 操作人；本方法不重新查询主数据 |
| `runAsCurrentUser(Runnable action)` | 当前请求内的非空无返回操作 | 无 | 与上面的当前用户作用域一致 |
| `runAs(String actorUserId, Supplier<T> action)` | 正整数格式的可信用户 ID、非空操作 | `T`，操作结果 | 异步、补偿或适配层内部的受控操作人作用域 |
| `runAs(String actorUserId, Runnable action)` | 正整数格式的可信用户 ID、非空无返回操作 | 无 | 与上面的指定用户作用域一致 |

## 关键设计与约束

- 用户标识固定为 `sys_user.user_id` 的十进制字符串，不使用用户名。`WorkflowIdentityCodec` 会拒绝空值、符号、小数、零、负数和 `long` 溢出，并去除前导零。
- `runAsCurrentUser(...)` 只读取登录上下文中的用户 ID，不验证该用户是否仍存在或启用。安全写命令必须经 `WorkflowEngineOperations.writeAsCurrentUser(...)`，由 `WorkflowIdentityResolver` 在同一事务内核验主数据。
- `runAs(...)` 同样只验证 ID 格式，不验证用户有效性。显式操作人只能来自可信后端持久化审计字段，禁止使用前端自由传入的用户 ID。
- `finally` 始终清理 `IdentityService` 和组件内部 `ThreadLocal`；业务异常或引擎异常不会污染线程池中的后续请求。
- 嵌套调用结束后恢复外层操作人，最外层调用结束后清空身份。不得绕过本组件长期调用 `IdentityService.setAuthenticatedUserId(...)`。
- 本组件不检查 candidate、assignee、owner、委派、挂起或业务对象权限，也不启动事务。它不能作为任务命令的独立安全入口。

## 最小接入示例

业务 Service 的安全接入示例：

```java
@Service
public class ApprovalService
{
    private final WorkflowTaskLifecycleService taskLifecycleService;

    public ApprovalService(WorkflowTaskLifecycleService taskLifecycleService)
    {
        this.taskLifecycleService = taskLifecycleService;
    }

    public void complete(String taskId, String comment, Map<String, Object> variables)
    {
        // LifecycleService 通过 EngineOperations 间接使用 AuthenticationContext。
        taskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                taskId, comment, variables, List.of(), List.of(), null));
    }
}
```

受控异步操作如需指定操作人，应在适配层通过 `WorkflowEngineOperations.writeAsUser(...)` 接入，并从正式审计字段读取 `actorUserId`；不要在业务层直接调用 `runAs(...)`。
