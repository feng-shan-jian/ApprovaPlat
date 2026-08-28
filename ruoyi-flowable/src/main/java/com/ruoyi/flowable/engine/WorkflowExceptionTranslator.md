# WorkflowExceptionTranslator

## 组件简介与作用

`WorkflowExceptionTranslator` 将 Flowable 8 公共 API 抛出的 `FlowableException`，以及其 MyBatis flush 或 Spring 事务提交阶段可能直接外泄的并发异常，转换为若依全局异常处理器可识别的 `ServiceException`。转换结果使用稳定 HTTP 状态和通用中文提示，客户端字段白名单覆盖业务码与公开提示。

该组件由 [WorkflowEngineOperations](WorkflowEngineOperations.md) 统一调用；[WorkflowProcessEngineAdapter](WorkflowProcessEngineAdapter.md) 对主动识别的权限和状态错误使用相同 HTTP 语义。

## 接入与使用方式

推荐通过 Adapter 间接接入：

```java
processEngineAdapter.claimTaskForCurrentUser(taskId);
```

新增引擎适配器方法时，把 Flowable 公共 API 调用放进 `WorkflowEngineOperations.read(...)` 或 `writeAsCurrentUser(...)`；Controller 直接消费翻译后的稳定业务异常。

## 公开方法

| 方法 | 入参 | 返回值 |
| --- | --- | --- |
| `translate(FlowableException exception)` | 非空的 Flowable 公共 API 异常 | `ServiceException`，包含稳定状态码、稳定提示和原异常 `cause` |
| `translateDatabaseConcurrencyConflict(RuntimeException exception)` | MyBatis/JDBC 包装的运行时异常 | `Optional<ServiceException>`，仅死锁、锁等待超时和事务回滚类并发冲突有值 |
| `translateRetryableConcurrencyConflict(RuntimeException exception)` | Flowable、Spring 事务或 MyBatis 包装的运行时异常 | `Optional<ServiceException>`，仅真实可重试并发失败有值，并保留最外层异常为 `cause` |
| `isRetryableConcurrencyConflict(Throwable exception)` | 原始异常或已经翻译并保留 `cause` 的业务异常 | `boolean`，完整异常链命中 Flowable 乐观锁、Spring 并发异常或数据库事务冲突时为 `true` |

传入 `null` 属于编程错误，会抛出 `NullPointerException` 并终止当前翻译路径。

## 异常映射

| Flowable 异常 | HTTP 状态 | 稳定对外提示 |
| --- | --- | --- |
| `FlowableIllegalArgumentException` | `400` | `工作流请求参数不合法` |
| `FlowableObjectNotFoundException` | `404` | `工作流对象不存在或已被删除` |
| `FlowableOptimisticLockingException` | `409` | `工作流状态已发生变化，请刷新后重试` |
| `FlowableTaskAlreadyClaimedException` | `409` | `工作流状态已发生变化，请刷新后重试` |
| `FlowableIllegalStateException` | `409` | `工作流状态已发生变化，请刷新后重试` |
| MySQL `1213`/`1205`、SQLState `40*` 事务并发冲突 | `409` | `工作流状态已发生变化，请刷新后重试` |
| Spring `ConcurrencyFailureException` 及其锁/序列化子类 | `409` | `工作流状态已发生变化，请刷新后重试` |
| `FlowableForbiddenException` | `403` | `无权执行当前工作流操作` |
| 其他 `FlowableException` | `500` | `工作流引擎执行失败` |

## 关键设计与约束

- 原始 Flowable 异常保存在 `ServiceException.cause` 中供服务端日志和链路追踪使用；客户端响应字段白名单只包含稳定提示和业务码。
- `WorkflowEngineOperations` 翻译 `FlowableException`，并额外识别 Flowable/MyBatis 直接外泄或 Spring 事务提交阶段包装的乐观锁、死锁、锁等待超时和事务回滚类并发冲突。识别会遍历完整 `cause` 链，因此已经翻译且保留原始 cause 的 `ServiceException` 仍可被动态多实例外层边界精确分类。
- 普通业务 `409`、`FlowableIllegalStateException`、重复认领和对象缺失使用各自稳定业务码；只有 revision CAS 失败携带动态多实例 revision 子码并进入对应重试提示。
- 并发认领、乐观锁和非法状态统一为 `409`，调用方刷新任务状态后依据稳定业务码决定一次明确重试或停止。
- 未分类引擎异常统一为 `500`。服务端记录完整 `cause`，API 响应返回稳定通用提示。
- 事务回滚、操作人设置和业务权限分别由 `WorkflowEngineOperations`、`WorkflowAuthenticationContext` 和 P3 业务 Service/Adapter 承担。

## 最小接入示例

新增只读引擎适配方法时，通过统一执行边界自动完成异常翻译：

```java
public Optional<String> findDeploymentName(String deploymentId)
{
    return engineOperations.read(() -> Optional.ofNullable(
            repositoryService.createDeploymentQuery()
                    .deploymentId(deploymentId)
                    .singleResult())
            .map(Deployment::getName));
}
```

业务 Controller 把异常交给若依全局异常处理器，由统一响应字段白名单生成结果。
