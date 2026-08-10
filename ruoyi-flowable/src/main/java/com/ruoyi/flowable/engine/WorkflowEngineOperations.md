# WorkflowEngineOperations

## 组件简介与作用

`WorkflowEngineOperations` 是 Flowable 公共 API 的统一执行边界，负责建立 Spring 事务、在写事务内核验当前若依用户、设置并清理 Flowable 操作人，以及把 `FlowableException` 和可重试并发异常翻译为稳定的若依 `ServiceException`。

任务认领、取消认领、委派、解决委派、转办和低层完成必须调用 [WorkflowProcessEngineAdapter](WorkflowProcessEngineAdapter.md)，不能由业务 Service 直接发出对应 `TaskService` 命令。模型发布、标准/按 key 发起、动态多实例和复杂生命周期由各自领域服务直接组合 Flowable 公共 API，但必须统一经过本组件建立事务、身份和异常边界，并自行完成对象权限、业务状态、幂等及数据一致性校验。

## 接入与使用方式

正常调用链如下：

```text
Controller -> 任务业务 Service -> WorkflowProcessEngineAdapter -----------+
Controller -> 模型/发起/多实例/生命周期领域 Service ----------------------+
                                                                          v
                                                       WorkflowEngineOperations
                                                         -> WorkflowIdentityResolver
                                                         -> WorkflowAuthenticationContext
                                                         -> 回调中的 Flowable 公共 API
```

当 P3 业务 Service 已开启同一事务管理器的事务时，本组件会加入外层事务；没有外层事务时，本组件代理自行创建只读或写事务。所有写回调开始时都会通过 `TransactionSynchronizationManager` 核验当前事务真实活动、非只读且隔离级别明确为 `REPEATABLE_READ`。外层事务使用默认隔离、`READ_COMMITTED` 或绕过 Spring 代理直接调用时，会在身份解析及任何业务读取/写入前返回稳定 `500`，不产生引擎和审计副作用。

## 公开方法

| 方法 | 入参 | 返回值 | 身份与事务语义 |
| --- | --- | --- | --- |
| `read(Supplier<T> action)` | 非空的引擎查询 | `T`，查询结果 | 只读事务；不设置或核验操作人 |
| `readWithServiceExceptionHandler(Supplier<T> action, Function<ServiceException, T> exceptionHandler)` | 非空引擎查询、稳定业务异常处理器 | `T`，查询结果或受控降级结果 | 只读事务；在事务代理返回前处理已翻译的业务异常，避免调用方吞异常后留下 `rollback-only` |
| `read(Runnable action)` | 非空的无返回查询 | 无 | 只读事务；不设置或核验操作人 |
| `writeAsCurrentUser(Supplier<T> action)` | 非空的引擎写操作 | `T`，写操作结果 | `REPEATABLE_READ` 写事务；在事务内重新核验当前用户并设置 Flowable 操作人 |
| `writeAsCurrentUser(Function<WorkflowCurrentIdentity, T> action)` | 接收已核验身份的非空写操作 | `T`，写操作结果 | `REPEATABLE_READ` 写事务；身份核验、权限判断所需身份数据和引擎命令共享同一事务视图 |
| `writeAsCurrentUser(Runnable action)` | 非空的无返回写操作 | 无 | 与上面的当前用户写操作一致 |
| `writeAsUser(String actorUserId, Supplier<T> action)` | 可信用户 ID、非空写操作 | `T`，写操作结果 | 写事务；设置指定 Flowable 操作人，但不查询主数据验证用户是否仍有效 |
| `writeAsUser(String actorUserId, Runnable action)` | 可信用户 ID、非空无返回写操作 | 无 | 与上面的指定用户写操作一致 |
| `withConcurrencyConflictSubCode(RuntimeException exception, String subCode)` | 事务回调内或代理提交阶段异常、非空领域子码 | 可直接重新抛出的 `RuntimeException` | 仅真实可重试并发失败附加子码；普通业务异常和未知异常保持原对象 |

## 关键设计与约束

- `writeAsCurrentUser(...)` 先在已开启的写事务内调用 [WorkflowIdentityResolver](../identity/WorkflowIdentityResolver.md)，确认 `sys_user` 仍有效并取得不可变的 `WorkflowCurrentIdentity`，再由 [WorkflowAuthenticationContext](../identity/WorkflowAuthenticationContext.md) 设置操作人并执行命令。
- `Function<WorkflowCurrentIdentity, T>` 重载用于 Adapter 或专用领域服务在同一事务内完成候选组、starter、办理人等权限校验，禁止先在事务外解析身份再执行引擎命令。
- 全部只读和写入边界都显式使用 `REPEATABLE_READ`，不依赖 MySQL、连接池或部署环境默认值。动态多实例 `ADD/REMOVE/COMPLETE` 因此使用同一业务 revision 时会持有一致的 Flowable `REV_` 快照，由引擎乐观锁保证只有一个事务提交。
- 调用方若提供外层写事务，也必须显式声明 `isolation = Isolation.REPEATABLE_READ`；Spring `REQUIRED` 加入低隔离事务时不会自动升级隔离级别，运行时门禁会直接拒绝。
- `writeAsUser(...)` 只校验用户 ID 是正整数格式，不验证用户存在、启用状态或调用方权限。它只允许使用异步任务、补偿任务等受信任持久化字段中的操作人，禁止直接接收前端传值。
- `read(...)` 不做身份或对象权限校验。业务查询仍须由 P3 Service 校验菜单/接口权限及业务对象可见范围。
- `readWithServiceExceptionHandler(...)` 只用于列表能力等明确允许把部分业务异常降级为结果的查询。处理器必须按稳定业务码逐项放行，数据损坏和未知异常必须原样抛出；禁止在普通写链中用它吞掉失败。
- `FlowableException` 交给 [WorkflowExceptionTranslator](WorkflowExceptionTranslator.md)；Flowable 乐观锁、Spring `ConcurrencyFailureException`、MyBatis 直接外泄的 MySQL `1213`/`1205` 或 SQLState `40*` 并发异常稳定映射为 `409`。Adapter 或业务层主动抛出的普通 `ServiceException` 保持原样，其他运行时异常继续传播并触发写事务回滚。
- Spring 事务代理可能在写回调正常返回后、真正提交数据库时才暴露乐观锁或死锁。动态多实例等需要专用客户端刷新策略的领域服务必须在 `writeAsCurrentUser(...)` 调用外捕获异常，并调用 `withConcurrencyConflictSubCode(...)`；该方法只对异常链已经证明为真实并发失败的异常附码，不会根据普通 `409` 猜测并发。
- 操作回调不能为 `null`。写方法使用 `rollbackFor = Exception.class`，不要在回调内吞掉异常或自行提交事务。

## 最小接入示例

业务层通过 Adapter 间接获得本组件的事务、身份和异常边界，不直接完成 Flowable 任务：

```java
@Service
public class ApprovalService
{
    private final WorkflowProcessEngineAdapter processEngineAdapter;

    public ApprovalService(WorkflowProcessEngineAdapter processEngineAdapter)
    {
        this.processEngineAdapter = processEngineAdapter;
    }

    @Transactional(rollbackFor = Exception.class)
    public void approve(String taskId, Map<String, Object> variables)
    {
        // 此处先完成业务对象权限、状态与幂等校验，并更新同事务业务记录。
        processEngineAdapter.completeTask(taskId, variables);
    }
}
```
