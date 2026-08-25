package com.ruoyi.flowable.engine;

import java.sql.Connection;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.flowable.common.engine.api.FlowableException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.identity.WorkflowAuthenticationContext;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;

/**
 * Flowable 公共 API 的统一执行边界，集中处理事务、操作人身份和异常翻译。
 */
@Component
public class WorkflowEngineOperations
{
    /** 写边界事务配置不满足生产并发契约时返回的稳定错误。 */
    static final String WRITE_TRANSACTION_CONTRACT_MESSAGE =
            "工作流写操作必须在可重复读事务中执行";

    private final WorkflowAuthenticationContext authenticationContext;

    private final WorkflowExceptionTranslator exceptionTranslator;

    private final WorkflowIdentityResolver identityResolver;

    /**
     * 创建 Flowable 公共 API 执行器。
     *
     * @param authenticationContext WorkflowAuthenticationContext，引擎写操作认证上下文
     * @param exceptionTranslator WorkflowExceptionTranslator，引擎异常翻译器
     * @param identityResolver WorkflowIdentityResolver，当前用户正式身份解析器
     * @return 无返回值，构造后由 Spring 管理该组件
     */
    public WorkflowEngineOperations(WorkflowAuthenticationContext authenticationContext,
            WorkflowExceptionTranslator exceptionTranslator, WorkflowIdentityResolver identityResolver)
    {
        this.authenticationContext = authenticationContext;
        this.exceptionTranslator = exceptionTranslator;
        this.identityResolver = identityResolver;
    }

    /**
     * 在只读事务中执行有返回值的 Flowable 公共 API 查询。
     *
     * @param action Supplier&lt;T&gt;，只读引擎操作
     * @return T，引擎查询结果
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public <T> T read(Supplier<T> action)
    {
        return execute(action);
    }

    /**
     * 在只读事务内部执行查询，并在事务代理返回前处理已经统一翻译的业务异常。
     *
     * @param action Supplier&lt;T&gt;，只读引擎操作
     * @param exceptionHandler Function&lt;ServiceException, T&gt;，业务异常处理器；不能降级的异常必须原样抛出
     * @return T，查询结果或异常处理器生成的降级结果
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public <T> T readWithServiceExceptionHandler(Supplier<T> action,
            Function<ServiceException, T> exceptionHandler)
    {
        Objects.requireNonNull(exceptionHandler, "工作流业务异常处理器不能为空");
        try
        {
            // execute 会先完成 Flowable/数据库异常翻译，处理器因此可以只依赖稳定业务码。
            return execute(action);
        }
        catch (ServiceException exception)
        {
            // 必须在事务方法返回前完成预期异常降级，避免共享事务被 Spring 标记为 rollback-only。
            return exceptionHandler.apply(exception);
        }
    }

    /**
     * 在同一只读事务中解析当前正式身份、执行查询并处理稳定业务异常。
     *
     * @param action Function&lt;WorkflowCurrentIdentity,T&gt;，接收已核验身份的只读业务查询
     * @param exceptionHandler Function&lt;ServiceException,T&gt;，预期业务异常降级处理器
     * @param <T> 查询返回类型
     * @return T，查询结果或处理器生成的稳定投影
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public <T> T readAsCurrentUserWithServiceExceptionHandler(
            Function<WorkflowCurrentIdentity, T> action,
            Function<ServiceException, T> exceptionHandler)
    {
        Objects.requireNonNull(action, "工作流只读身份操作不能为空");
        Objects.requireNonNull(exceptionHandler, "工作流业务异常处理器不能为空");
        try
        {
            return execute(() -> action.apply(identityResolver.resolveCurrentIdentity()));
        }
        catch (ServiceException exception)
        {
            return exceptionHandler.apply(exception);
        }
    }

    /**
     * 在只读事务中执行无返回值的 Flowable 公共 API 操作。
     *
     * @param action Runnable，只读引擎操作
     * @return 无返回值
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void read(Runnable action)
    {
        Objects.requireNonNull(action, "工作流引擎操作不能为空");
        execute(() ->
        {
            action.run();
            return null;
        });
    }

    /**
     * 使用当前登录用户在事务中执行有返回值的 Flowable 公共 API 写操作。
     *
     * @param action Supplier&lt;T&gt;，需要记录操作人的引擎写操作
     * @return T，引擎写操作结果
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public <T> T writeAsCurrentUser(Supplier<T> action)
    {
        Objects.requireNonNull(action, "工作流引擎操作不能为空");
        requireRepeatableReadWriteTransaction();
        return writeAsCurrentUser(identity -> action.get());
    }

    /**
     * 在同一事务中重新核验当前登录用户，并以规范身份执行有返回值的 Flowable 写操作。
     *
     * @param action Function&lt;WorkflowCurrentIdentity, T&gt;，接收已核验身份的引擎写操作
     * @return T，引擎写操作结果
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public <T> T writeAsCurrentUser(Function<WorkflowCurrentIdentity, T> action)
    {
        Objects.requireNonNull(action, "工作流引擎操作不能为空");
        requireRepeatableReadWriteTransaction();
        return execute(() ->
        {
            // 身份主数据核验必须发生在本方法开启的事务内，保证权限判断与后续引擎命令使用同一数据视图。
            WorkflowCurrentIdentity currentIdentity = identityResolver.resolveCurrentIdentity();
            return authenticationContext.runAs(currentIdentity.userId(), () -> action.apply(currentIdentity));
        });
    }

    /**
     * 使用当前登录用户在事务中执行无返回值的 Flowable 公共 API 写操作。
     *
     * @param action Runnable，需要记录操作人的引擎写操作
     * @return 无返回值
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public void writeAsCurrentUser(Runnable action)
    {
        Objects.requireNonNull(action, "工作流引擎操作不能为空");
        requireRepeatableReadWriteTransaction();
        writeAsCurrentUser(identity ->
        {
            action.run();
            return null;
        });
    }

    /**
     * 使用指定有效用户在事务中执行有返回值的 Flowable 公共 API 写操作。
     *
     * @param actorUserId String，数字格式的若依操作人 ID
     * @param action Supplier&lt;T&gt;，需要记录操作人的引擎写操作
     * @return T，引擎写操作结果
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public <T> T writeAsUser(String actorUserId, Supplier<T> action)
    {
        Objects.requireNonNull(action, "工作流引擎操作不能为空");
        requireRepeatableReadWriteTransaction();
        return execute(() -> authenticationContext.runAs(actorUserId, action));
    }

    /**
     * 使用指定有效用户在事务中执行无返回值的 Flowable 公共 API 写操作。
     *
     * @param actorUserId String，数字格式的若依操作人 ID
     * @param action Runnable，需要记录操作人的引擎写操作
     * @return 无返回值
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
    public void writeAsUser(String actorUserId, Runnable action)
    {
        Objects.requireNonNull(action, "工作流引擎操作不能为空");
        requireRepeatableReadWriteTransaction();
        execute(() ->
        {
            authenticationContext.runAs(actorUserId, action);
            return null;
        });
    }

    /**
     * 仅为真实可重试并发失败附加领域子码，并保留普通业务异常和未知运行时异常原对象。
     *
     * @param exception RuntimeException，事务回调内或事务代理提交阶段抛出的原始异常
     * @param subCode String，调用领域用于客户端精确刷新策略的稳定业务子码
     * @return RuntimeException，可直接重新抛出的原异常或保留原异常为 cause 的稳定 409
     */
    public RuntimeException withConcurrencyConflictSubCode(RuntimeException exception,
            String subCode)
    {
        Objects.requireNonNull(exception, "工作流并发异常不能为空");
        if (subCode == null || subCode.isBlank())
        {
            throw new IllegalArgumentException("工作流并发冲突子码不能为空");
        }
        if (!exceptionTranslator.isRetryableConcurrencyConflict(exception))
        {
            // 普通业务 409、权限/参数错误和编程异常必须保持原对象与原分类。
            return exception;
        }
        if (exception instanceof ServiceException serviceException)
        {
            if (!Integer.valueOf(HttpStatus.CONFLICT).equals(serviceException.getCode()))
            {
                return exception;
            }
            return serviceException.setSubCode(subCode);
        }

        // 事务代理提交阶段可能直接抛出 Spring/MyBatis 包装异常，统一翻译后仍把原对象保存在 cause。
        return exceptionTranslator.translateRetryableConcurrencyConflict(exception)
                .orElseThrow(() -> exception)
                .setSubCode(subCode);
    }

    /**
     * 核验当前写调用已经进入显式可重复读事务，阻止默认隔离级别或外层低隔离事务削弱 revision 并发契约。
     *
     * @return 无返回值；事务缺失、只读或隔离级别不是 REPEATABLE_READ 时抛出稳定 HTTP 500 业务异常
     */
    private void requireRepeatableReadWriteTransaction()
    {
        Integer isolationLevel = TransactionSynchronizationManager
                .getCurrentTransactionIsolationLevel();
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(Connection.TRANSACTION_REPEATABLE_READ)
                        .equals(isolationLevel))
        {
            // 门禁必须先于身份解析和任何引擎查询，配置错误不得产生认证、审计或业务写副作用。
            throw new ServiceException(WRITE_TRANSACTION_CONTRACT_MESSAGE, HttpStatus.ERROR);
        }
    }

    /**
     * 执行引擎操作，仅翻译 Flowable 异常并保持若依业务异常原样传播。
     *
     * @param action Supplier&lt;T&gt;，待执行的引擎公共 API 操作
     * @return T，引擎操作结果
     */
    private <T> T execute(Supplier<T> action)
    {
        Objects.requireNonNull(action, "工作流引擎操作不能为空");
        try
        {
            return action.get();
        }
        catch (FlowableException exception)
        {
            throw exceptionTranslator.translate(exception);
        }
        catch (ServiceException exception)
        {
            // 领域服务已经给出稳定业务语义时保持原对象，避免重复包装或改变状态码。
            throw exception;
        }
        catch (RuntimeException exception)
        {
            // Flowable 8 的 flush 或 Spring 事务层可能直接抛出并发异常；仅翻译可安全重试的真实竞态。
            throw exceptionTranslator.translateRetryableConcurrencyConflict(exception)
                    .orElseThrow(() -> exception);
        }
    }
}
