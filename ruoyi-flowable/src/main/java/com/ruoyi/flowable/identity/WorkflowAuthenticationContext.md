# WorkflowAuthenticationContext

## 组件简介与作用

`WorkflowAuthenticationContext` 统一设置和清理 Flowable 的 `authenticatedUserId`，保证流程发起、任务命令和审计记录使用规范的若依 `sys_user.user_id` 字符串。它在异常、线程复用和嵌套调用结束时恢复或清空身份，使每个请求保持独立操作人上下文。

本组件管理 Flowable 操作人上下文；Spring 事务、业务授权、任务状态校验和 Flowable 异常翻译由 [WorkflowEngineOperations](../engine/WorkflowEngineOperations.md) 与领域服务承担。任务动作命令通过 [WorkflowProcessEngineAdapter](../engine/WorkflowProcessEngineAdapter.md) 接入，任务完成通过 `WorkflowTaskLifecycleService.completeTask(...)` 接入；模型发布、流程发起、动态多实例和复杂生命周期由专用领域服务接入。

## 接入与使用方式

任务完成的调用链固定为业务 Service 注入正式生命周期服务；本组件仅由生命周期服务内部使用：

```java
taskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
        taskId, comment, variables, copyUserIds, nextUserIds, expectedRevision));
```

`WorkflowEngineOperations` 等引擎执行基础设施直接使用本组件。专用领域服务通过 `WorkflowEngineOperations.writeAsCurrentUser(...)` 或受控 `writeAsUser(...)` 建立操作人作用域；任务完成固定走正式生命周期链，覆盖表单、附件、抄送、动态下一办理人、动态多实例 revision、assignee、委派和挂起状态校验。

## 公开方法

| 方法 | 入参 | 返回值 | 适用范围 |
| --- | --- | --- | --- |
| `runAsCurrentUser(Supplier<T> action)` | 当前请求内的非空操作 | `T`，操作结果 | 使用调用方已解析的 Spring Security 当前用户 ID 设置 Flowable 操作人 |
| `runAsCurrentUser(Runnable action)` | 当前请求内的非空无返回操作 | 无 | 与上面的当前用户作用域一致 |
| `runAs(String actorUserId, Supplier<T> action)` | 正整数格式的可信用户 ID、非空操作 | `T`，操作结果 | 异步、补偿或适配层内部的受控操作人作用域 |
| `runAs(String actorUserId, Runnable action)` | 正整数格式的可信用户 ID、非空无返回操作 | 无 | 与上面的指定用户作用域一致 |

## 关键设计与约束

- 用户标识固定为 `sys_user.user_id` 的正整数十进制规范字符串。`WorkflowIdentityCodec` 统一校验格式并去除前导零。
- `runAsCurrentUser(...)` 读取登录上下文中的用户 ID；安全写命令经 `WorkflowEngineOperations.writeAsCurrentUser(...)`，由 `WorkflowIdentityResolver` 在同一事务内核验用户存在性和启用状态。
- `runAs(...)` 验证 ID 格式，显式操作人来自可信后端持久化审计字段；调用方负责用户有效性校验。
- `finally` 始终清理 `IdentityService` 和组件内部 `ThreadLocal`；业务异常或引擎异常结束后，线程池上下文恢复为空。
- 嵌套调用结束后恢复外层操作人，最外层调用结束后清空身份；`IdentityService.setAuthenticatedUserId(...)` 的生产调用集中在本组件。
- candidate、assignee、owner、委派、挂起、业务对象权限和事务由 Adapter、领域服务及 `WorkflowEngineOperations` 共同校验，本组件作为其中的身份作用域环节。

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

受控异步操作在适配层通过 `WorkflowEngineOperations.writeAsUser(...)` 接入，并从正式审计字段读取 `actorUserId`。
