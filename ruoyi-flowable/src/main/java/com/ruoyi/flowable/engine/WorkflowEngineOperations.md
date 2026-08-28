# WorkflowEngineOperations

## 组件简介与作用

`WorkflowEngineOperations` 是 Flowable 公共 API 的统一执行边界，负责建立 Spring 事务、在写事务内核验当前若依用户、设置并清理 Flowable 操作人，以及把 `FlowableException` 和可重试并发异常翻译为稳定的若依 `ServiceException`。

任务认领、取消认领、委派、解决委派和转办统一调用 [WorkflowProcessEngineAdapter](WorkflowProcessEngineAdapter.md)，任务完成统一调用 `WorkflowTaskLifecycleService.completeTask(...)`。模型发布、标准/按 key 发起、动态多实例和复杂生命周期由各自领域服务直接组合 Flowable 公共 API，并统一经过本组件建立事务、身份和异常边界，同时完成对象权限、业务状态、幂等及数据一致性校验。

## 接入与使用方式

正常调用链如下：

```text
Controller -> 任务动作 Service -> WorkflowProcessEngineAdapter -----------+
Controller -> 完成/模型/发起/多实例/生命周期领域 Service -----------------+
                                                                          v
                                                       WorkflowEngineOperations
                                                         -> WorkflowIdentityResolver
                                                         -> WorkflowAuthenticationContext
                                                         -> 回调中的 Flowable 公共 API
```

P3 业务 Service 已开启同一事务管理器的事务时，本组件加入外层事务；其余入口由组件代理创建只读或写事务。所有写回调开始时都会通过 `TransactionSynchronizationManager` 核验当前事务真实活动、写模式和明确的 `REPEATABLE_READ` 隔离级别。外层事务使用默认隔离、`READ_COMMITTED` 或绕过 Spring 代理直接调用时，会在身份解析及业务访问前返回稳定 `500`，引擎和审计保持原状态。

## 公开方法

| 方法 | 入参 | 返回值 | 身份与事务语义 |
| --- | --- | --- | --- |
| `read(Supplier<T> action)` | 非空的引擎查询 | `T`，查询结果 | 只读事务；操作人上下文保持为空 |
| `readWithServiceExceptionHandler(Supplier<T> action, Function<ServiceException, T> exceptionHandler)` | 非空引擎查询、稳定业务异常处理器 | `T`，查询结果或受控降级结果 | 只读事务；在事务代理返回前处理已翻译的业务异常，确保受控降级结果对应可提交事务 |
| `read(Runnable action)` | 非空的无返回查询 | 无 | 只读事务；操作人上下文保持为空 |
| `writeAsCurrentUser(Supplier<T> action)` | 非空的引擎写操作 | `T`，写操作结果 | `REPEATABLE_READ` 写事务；在事务内重新核验当前用户并设置 Flowable 操作人 |
| `writeAsCurrentUser(Function<WorkflowCurrentIdentity, T> action)` | 接收已核验身份的非空写操作 | `T`，写操作结果 | `REPEATABLE_READ` 写事务；身份核验、权限判断所需身份数据和引擎命令共享同一事务视图 |
| `writeAsCurrentUser(Runnable action)` | 非空的无返回写操作 | 无 | 与上面的当前用户写操作一致 |
| `writeAsUser(String actorUserId, Supplier<T> action)` | 可信用户 ID、非空写操作 | `T`，写操作结果 | 写事务；设置调用方已经过主数据验证的指定 Flowable 操作人 |
| `writeAsUser(String actorUserId, Runnable action)` | 可信用户 ID、非空无返回写操作 | 无 | 与上面的指定用户写操作一致 |
| `withConcurrencyConflictSubCode(RuntimeException exception, String subCode)` | 事务回调内或代理提交阶段异常、非空领域子码 | 可直接重新抛出的 `RuntimeException` | 仅真实可重试并发失败附加子码；普通业务异常和未知异常保持原对象 |

## 关键设计与约束

- `writeAsCurrentUser(...)` 先在已开启的写事务内调用 [WorkflowIdentityResolver](../identity/WorkflowIdentityResolver.md)，确认 `sys_user` 仍有效并取得不可变的 `WorkflowCurrentIdentity`，再由 [WorkflowAuthenticationContext](../identity/WorkflowAuthenticationContext.md) 设置操作人并执行命令。
- `Function<WorkflowCurrentIdentity, T>` 重载用于 Adapter 或专用领域服务在同一事务内完成候选组、starter、办理人等权限校验，身份解析和引擎命令共享一个事务边界。
- 全部只读和写入边界都显式使用 `REPEATABLE_READ`，形成独立于 MySQL、连接池或部署环境默认值的稳定隔离语义。动态多实例 `ADD/REMOVE/COMPLETE` 因此使用同一业务 revision 时会持有一致的 Flowable `REV_` 快照，由引擎乐观锁保证只有一个事务提交。
- 调用方若提供外层写事务，也显式声明 `isolation = Isolation.REPEATABLE_READ`；运行时门禁要求加入事务具备相同隔离级别。
- `writeAsUser(...)` 校验用户 ID 为正整数格式，操作人来自异步任务、补偿任务等受信任持久化字段；调用方负责校验用户存在、启用状态和业务权限。
- `read(...)` 建立只读引擎事务；P3 Service 在调用前校验菜单/接口权限及业务对象可见范围。
- `readWithServiceExceptionHandler(...)` 用于列表能力等明确允许把部分业务异常降级为结果的查询。处理器按稳定业务码逐项放行，并原样传播数据损坏和未知异常；普通写链统一使用写事务异常语义。
- `FlowableException` 交给 [WorkflowExceptionTranslator](WorkflowExceptionTranslator.md)；Flowable 乐观锁、Spring `ConcurrencyFailureException`、MyBatis 直接外泄的 MySQL `1213`/`1205` 或 SQLState `40*` 并发异常稳定映射为 `409`。Adapter 或业务层主动抛出的普通 `ServiceException` 保持原样，其他运行时异常继续传播并触发写事务回滚。
- Spring 事务代理可能在写回调正常返回后、真正提交数据库时才暴露乐观锁或死锁。动态多实例等需要专用客户端刷新策略的领域服务必须在 `writeAsCurrentUser(...)` 调用外捕获异常，并调用 `withConcurrencyConflictSubCode(...)`；该方法在异常链证明真实并发失败时附加机器子码，普通 `409` 保持原错误语义。
- 操作回调必须为非空。写方法使用 `rollbackFor = Exception.class`，回调向外传播异常并由统一事务管理器提交或回滚。

## 最小接入示例

业务层通过正式任务生命周期服务获得本组件的事务、身份和异常边界，任务完成由生命周期服务统一执行：

```java
@Service
public class ApprovalService
{
    private final WorkflowTaskLifecycleService taskLifecycleService;

    public ApprovalService(WorkflowTaskLifecycleService taskLifecycleService)
    {
        this.taskLifecycleService = taskLifecycleService;
    }

    public void approve(String taskId, String comment, Map<String, Object> variables)
    {
        taskLifecycleService.completeTask(new WorkflowTaskCompleteRequest(
                taskId, comment, variables, List.of(), List.of(), null));
    }
}
```
