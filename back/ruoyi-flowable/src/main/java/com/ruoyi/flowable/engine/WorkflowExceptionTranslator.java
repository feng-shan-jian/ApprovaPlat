package com.ruoyi.flowable.engine;

import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableForbiddenException;
import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.common.engine.api.FlowableIllegalStateException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.api.FlowableTaskAlreadyClaimedException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 将 Flowable 公共 API 异常翻译为若依稳定业务异常。
 */
@Component
public class WorkflowExceptionTranslator
{
    /** MySQL 唯一键冲突错误码。 */
    private static final int MYSQL_DUPLICATE_KEY_ERROR = 1062;

    /** MySQL InnoDB 死锁错误码。 */
    private static final int MYSQL_DEADLOCK_ERROR = 1213;

    /** MySQL InnoDB 行锁等待超时错误码。 */
    private static final int MYSQL_LOCK_WAIT_TIMEOUT_ERROR = 1205;

    /** JDBC 事务回滚类 SQLState 前缀，包含死锁和序列化冲突。 */
    private static final String TRANSACTION_ROLLBACK_SQL_STATE_PREFIX = "40";

    /** 项目为 Flowable 模型版本增加的数据库唯一约束名称。 */
    private static final String MODEL_VERSION_UNIQUE_CONSTRAINT = "ACT_UNIQ_MODEL";

    /** 参数错误对外提示，禁止透传引擎参数和值。 */
    static final String INVALID_ARGUMENT_MESSAGE = "工作流请求参数不合法";

    /** 工作流对象不存在对外提示。 */
    static final String OBJECT_NOT_FOUND_MESSAGE = "工作流对象不存在或已被删除";

    /** 并发或非法状态冲突对外提示。 */
    static final String CONFLICT_MESSAGE = "工作流状态已发生变化，请刷新后重试";

    /** 引擎权限拒绝对外提示。 */
    static final String FORBIDDEN_MESSAGE = "无权执行当前工作流操作";

    /** 未分类引擎异常对外提示。 */
    static final String ENGINE_FAILURE_MESSAGE = "工作流引擎执行失败";

    /**
     * 按异常语义映射错误码和稳定提示，同时保留原异常作为 cause 供服务端日志追踪。
     *
     * @param exception FlowableException，Flowable 8 公共 API 抛出的异常
     * @return ServiceException，可交给若依全局异常处理器的业务异常
     */
    public ServiceException translate(FlowableException exception)
    {
        Objects.requireNonNull(exception, "Flowable 异常不能为空");

        int code;
        String message;
        if (isModelVersionConflict(exception) || isDatabaseConcurrencyConflict(exception))
        {
            code = HttpStatus.CONFLICT;
            message = CONFLICT_MESSAGE;
        }
        else if (exception instanceof FlowableIllegalArgumentException)
        {
            code = HttpStatus.BAD_REQUEST;
            message = INVALID_ARGUMENT_MESSAGE;
        }
        else if (exception instanceof FlowableObjectNotFoundException)
        {
            code = HttpStatus.NOT_FOUND;
            message = OBJECT_NOT_FOUND_MESSAGE;
        }
        else if (exception instanceof FlowableOptimisticLockingException
                || exception instanceof FlowableTaskAlreadyClaimedException
                || exception instanceof FlowableIllegalStateException)
        {
            code = HttpStatus.CONFLICT;
            message = CONFLICT_MESSAGE;
        }
        else if (exception instanceof FlowableForbiddenException)
        {
            code = HttpStatus.FORBIDDEN;
            message = FORBIDDEN_MESSAGE;
        }
        else
        {
            code = HttpStatus.ERROR;
            message = ENGINE_FAILURE_MESSAGE;
        }

        ServiceException translated = new ServiceException(message, code);
        // 原始异常仅保存在 cause 链供日志记录，对外响应仍只包含上面的稳定提示。
        translated.initCause(exception);
        return translated;
    }

    /**
     * 尝试把 Flowable 公共 API 外泄的数据库并发异常翻译为稳定 409。
     *
     * @param exception RuntimeException，MyBatis 或 JDBC 包装的运行时持久化异常
     * @return Optional&lt;ServiceException&gt;，仅死锁、锁等待超时或事务回滚冲突有值
     */
    public Optional<ServiceException> translateDatabaseConcurrencyConflict(
            RuntimeException exception)
    {
        Objects.requireNonNull(exception, "数据库并发异常不能为空");
        if (!isDatabaseConcurrencyConflict(exception))
        {
            return Optional.empty();
        }
        ServiceException translated = new ServiceException(CONFLICT_MESSAGE, HttpStatus.CONFLICT);
        // 只保留原异常供服务端诊断，响应不得泄露 SQL、表名、锁信息或参数。
        translated.initCause(exception);
        return Optional.of(translated);
    }

    /**
     * 尝试把原始或被事务代理包装的可重试并发异常翻译为稳定 409。
     *
     * @param exception RuntimeException，Flowable、Spring 事务或 MyBatis 暴露的运行时异常
     * @return Optional&lt;ServiceException&gt;，仅乐观锁、死锁、锁等待或事务并发失败有值
     */
    public Optional<ServiceException> translateRetryableConcurrencyConflict(
            RuntimeException exception)
    {
        Objects.requireNonNull(exception, "并发异常不能为空");
        if (!isRetryableConcurrencyConflict(exception))
        {
            return Optional.empty();
        }
        ServiceException translated = new ServiceException(CONFLICT_MESSAGE, HttpStatus.CONFLICT);
        // 保留最外层异常才能同时追踪事务提交包装器与底层 Flowable/JDBC 首因。
        translated.initCause(exception);
        return Optional.of(translated);
    }

    /**
     * 判断完整 cause 链是否代表允许客户端刷新后重试的真实并发失败。
     *
     * @param exception Throwable，原始异常或已经翻译并保留 cause 的 ServiceException
     * @return boolean，仅 Flowable 乐观锁、模型版本唯一键、Spring 并发异常或数据库事务冲突返回 true
     */
    public boolean isRetryableConcurrencyConflict(Throwable exception)
    {
        Objects.requireNonNull(exception, "并发异常不能为空");
        // 模型版本唯一键可能在 MyBatis flush 或事务提交阶段绕过 FlowableException 包装。
        if (isModelVersionConflict(exception))
        {
            return true;
        }
        Throwable current = exception;
        while (current != null)
        {
            if (current instanceof FlowableOptimisticLockingException
                    || current instanceof ConcurrencyFailureException
                    || isDatabaseConcurrencyConflict(current))
            {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 判断异常链是否来自模型 key、版本和租户唯一约束的并发冲突。
     *
     * @param exception Throwable，Flowable 保存模型时产生的完整异常链
     * @return boolean，仅当 MySQL 1062 与模型版本约束名称同时出现时返回 true
     */
    private boolean isModelVersionConflict(Throwable exception)
    {
        boolean duplicateKeyError = false;
        boolean modelConstraintMatched = false;
        // SQLException 和约束名称可能被不同层包装，必须遍历完整 cause 链后组合判断。
        Throwable current = exception;
        while (current != null)
        {
            if (current instanceof SQLException sqlException
                    && sqlException.getErrorCode() == MYSQL_DUPLICATE_KEY_ERROR)
            {
                duplicateKeyError = true;
            }
            String message = current.getMessage();
            if (message != null && message.toUpperCase(Locale.ROOT)
                    .contains(MODEL_VERSION_UNIQUE_CONSTRAINT))
            {
                modelConstraintMatched = true;
            }
            current = current.getCause();
        }
        return duplicateKeyError && modelConstraintMatched;
    }

    /**
     * 遍历完整异常链识别数据库死锁、锁等待超时和 JDBC 事务回滚类并发冲突。
     *
     * @param exception Throwable，Flowable、MyBatis 或 JDBC 抛出的完整异常链
     * @return boolean，仅可安全重试的数据库并发冲突返回 true
     */
    private boolean isDatabaseConcurrencyConflict(Throwable exception)
    {
        Throwable current = exception;
        while (current != null)
        {
            if (current instanceof SQLException sqlException)
            {
                String sqlState = sqlException.getSQLState();
                if (sqlException instanceof SQLTransactionRollbackException
                        || sqlException.getErrorCode() == MYSQL_DEADLOCK_ERROR
                        || sqlException.getErrorCode() == MYSQL_LOCK_WAIT_TIMEOUT_ERROR
                        || (sqlState != null
                                && sqlState.startsWith(TRANSACTION_ROLLBACK_SQL_STATE_PREFIX)))
                {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
