package com.ruoyi.flowable.service.attachment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties.AttachmentCleanupLockMode;

/**
 * 使用专用 JDBC 会话持有 MySQL advisory lock，并在其覆盖范围内提交或回滚独立
 * REQUIRES_NEW 附件清理事务；锁连接与业务事务连接职责分离。
 */
@Component
public class WorkflowAttachmentCleanupCoordinator
{
    /** MySQL advisory lock 立即返回，不等待其他节点释放。 */
    private static final String ACQUIRE_LOCK_SQL = "select get_lock(?, 0)";

    /** advisory lock 必须由获取锁的同一物理连接显式释放。 */
    private static final String RELEASE_LOCK_SQL = "select release_lock(?)";

    /** 锁函数查询超时只保护依赖异常，不改变 GET_LOCK 的零等待竞争语义。 */
    private static final int LOCK_QUERY_TIMEOUT_SECONDS = 5;

    /** JDBC abort 使用调用线程同步执行，确保异常连接在返回连接池前已经失效。 */
    private static final Executor CONNECTION_ABORT_EXECUTOR = Runnable::run;

    private final WorkflowAttachmentService attachmentService;
    private final WorkflowRuntimeProperties runtimeProperties;
    private final DataSource dataSource;
    private final TransactionOperations cleanupTransaction;

    /** 当前 JVM 是否正在持有附件清理锁，仅用于本节点实时指标，不参与互斥决策。 */
    private final AtomicBoolean lockActive = new AtomicBoolean(false);

    /** 获取或释放锁出现不确定结果后保持降级到进程重启，防止锁会话泄漏被掩盖。 */
    private final AtomicBoolean lockDegraded = new AtomicBoolean(false);

    /** GET_LOCK 无法得到完整确定结果的累计次数，供固定 Gauge 和告警规则使用。 */
    private final AtomicLong lockAcquisitionFailures = new AtomicLong();

    /** RELEASE_LOCK 无法确认成功的累计次数，供固定 Gauge 和告警规则使用。 */
    private final AtomicLong lockReleaseFailures = new AtomicLong();

    /**
     * 创建附件清理事务协调器。
     *
     * @param attachmentService WorkflowAttachmentService，执行正式状态迁移和物理删除
     * @param runtimeProperties WorkflowRuntimeProperties，清理锁模式与稳定锁名
     * @param dataSource DataSource，创建持有 advisory lock 的专用物理连接
     * @param transactionManager PlatformTransactionManager，提交或回滚正式清理事务
     * @return 无返回值，构造后由 Spring 管理事务代理
     */
    @Autowired
    public WorkflowAttachmentCleanupCoordinator(
            WorkflowAttachmentService attachmentService,
            WorkflowRuntimeProperties runtimeProperties, DataSource dataSource,
            PlatformTransactionManager transactionManager)
    {
        this(attachmentService, runtimeProperties, dataSource,
                createCleanupTransaction(transactionManager));
    }

    /**
     * 使用显式事务操作器创建协调器，供隔离时序和异常测试复用。
     *
     * @param attachmentService WorkflowAttachmentService，正式附件清理服务
     * @param runtimeProperties WorkflowRuntimeProperties，清理锁配置
     * @param dataSource DataSource，专用锁连接来源
     * @param cleanupTransaction TransactionOperations，完成后才允许释放锁的清理事务
     * @return 无返回值，参数会固定为协调器依赖
     */
    WorkflowAttachmentCleanupCoordinator(WorkflowAttachmentService attachmentService,
            WorkflowRuntimeProperties runtimeProperties, DataSource dataSource,
            TransactionOperations cleanupTransaction)
    {
        this.attachmentService = attachmentService;
        this.runtimeProperties = runtimeProperties;
        this.dataSource = dataSource;
        this.cleanupTransaction = cleanupTransaction;
    }

    /**
     * 立即尝试获取集群锁；未获锁时不查询候选、不改状态、不触碰文件，获得后在 finally 释放。
     * MySQL GET_LOCK 不随事务提交自动释放，因此专用连接必须从获取锁开始一直持有到内部
     * 清理事务提交或回滚完成，再由同一物理连接执行 RELEASE_LOCK。
     *
     * @return Optional&lt;WorkflowAttachmentCleanupResult&gt;，未获锁为空，执行后返回真实批次结果
     */
    public Optional<WorkflowAttachmentCleanupResult> cleanupIfLockAcquired()
    {
        if (runtimeProperties.getAttachmentCleanupLockMode()
                == AttachmentCleanupLockMode.NONE)
        {
            return Optional.of(executeCleanupTransaction());
        }

        String lockName = runtimeProperties.getAttachmentCleanupLockName();
        try (Connection lockConnection = dataSource.getConnection())
        {
            Integer acquired;
            try
            {
                acquired = executeLockFunction(lockConnection, ACQUIRE_LOCK_SQL, lockName);
                if (!Integer.valueOf(0).equals(acquired)
                        && !Integer.valueOf(1).equals(acquired))
                {
                    throw new IllegalStateException("工作流附件 MySQL 清理锁获取结果异常");
                }
            }
            catch (RuntimeException | Error | SQLException acquisitionFailure)
            {
                // GET_LOCK 可能已在服务端成功；结果读取或 JDBC 资源关闭不完整时必须销毁会话。
                lockAcquisitionFailures.incrementAndGet();
                lockDegraded.set(true);
                abortLockConnection(lockConnection, acquisitionFailure);
                throw acquisitionFailure;
            }
            if (Integer.valueOf(0).equals(acquired))
            {
                return Optional.empty();
            }

            return cleanupAndRelease(lockConnection, lockName);
        }
        catch (SQLException exception)
        {
            throw new IllegalStateException("工作流附件 MySQL 清理锁连接异常", exception);
        }
    }

    /**
     * 在已获取锁的专用连接存活期间提交或回滚清理事务，并在 finally 由原连接释放锁。
     *
     * @param lockConnection Connection，成功执行 GET_LOCK 的同一 JDBC 连接
     * @param lockName String，集群级稳定锁名
     * @return Optional&lt;WorkflowAttachmentCleanupResult&gt;，已提交清理事务的真实结果
     * @throws SQLException RELEASE_LOCK 执行失败
     */
    private Optional<WorkflowAttachmentCleanupResult> cleanupAndRelease(
            Connection lockConnection, String lockName) throws SQLException
    {
        lockActive.set(true);
        Throwable cleanupFailure = null;
        try
        {
            // TransactionOperations.execute 返回前已经完成提交或回滚，锁不会暴露未提交旧候选。
            return Optional.of(executeCleanupTransaction());
        }
        catch (RuntimeException | Error failure)
        {
            cleanupFailure = failure;
            throw failure;
        }
        finally
        {
            try
            {
                Integer released = executeLockFunction(
                        lockConnection, RELEASE_LOCK_SQL, lockName);
                if (!Integer.valueOf(1).equals(released))
                {
                    throw new IllegalStateException("工作流附件 MySQL 清理锁释放结果异常");
                }
            }
            catch (RuntimeException | Error | SQLException releaseFailure)
            {
                // 无法证明 named lock 已释放时必须先淘汰物理会话，禁止带锁连接回到连接池。
                lockReleaseFailures.incrementAndGet();
                lockDegraded.set(true);
                abortLockConnection(lockConnection, releaseFailure);
                if (cleanupFailure != null)
                {
                    cleanupFailure.addSuppressed(releaseFailure);
                }
                else
                {
                    if (releaseFailure instanceof SQLException sqlFailure)
                    {
                        throw sqlFailure;
                    }
                    throw releaseFailure;
                }
            }
            finally
            {
                lockActive.set(false);
            }
        }
    }

    /**
     * 废弃无法确认锁状态的物理连接，并把 abort 自身失败附加到原始释放异常。
     *
     * @param connection Connection，可能仍持有 MySQL named lock 的专用连接
     * @param primaryFailure Throwable，必须保留为主异常的锁释放失败
     * @return void，abort 失败作为 suppressed 异常保留并由外层继续关闭连接
     */
    private void abortLockConnection(Connection connection, Throwable primaryFailure)
    {
        try
        {
            connection.abort(CONNECTION_ABORT_EXECUTOR);
        }
        catch (SQLException | RuntimeException | Error abortFailure)
        {
            primaryFailure.addSuppressed(abortFailure);
        }
    }

    /**
     * 在独立 REQUIRES_NEW 事务中执行有界清理，方法返回即表示事务已提交。
     *
     * @return WorkflowAttachmentCleanupResult，已提交的完成数和单条失败数
     */
    private WorkflowAttachmentCleanupResult executeCleanupTransaction()
    {
        WorkflowAttachmentCleanupResult result = cleanupTransaction.execute(
                status -> attachmentService.cleanupExpiredBatch());
        if (result == null)
        {
            throw new IllegalStateException("工作流附件清理事务未返回结果");
        }
        return result;
    }

    /**
     * 在指定物理连接执行单行 MySQL advisory lock 函数，拒绝空结果和额外结果行。
     *
     * @param connection Connection，获取和释放阶段复用的同一连接
     * @param sql String，固定 GET_LOCK 或 RELEASE_LOCK SQL
     * @param lockName String，已通过生产启动门禁校验的锁名
     * @return Integer，MySQL 锁函数的 1、0 或 null 结果
     * @throws SQLException 查询超时、连接失败或结果结构异常
     */
    private Integer executeLockFunction(Connection connection, String sql,
            String lockName) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, lockName);
            statement.setQueryTimeout(LOCK_QUERY_TIMEOUT_SECONDS);
            try (ResultSet resultSet = statement.executeQuery())
            {
                if (!resultSet.next())
                {
                    throw new SQLException("MySQL 清理锁函数未返回结果");
                }
                Object rawResult = resultSet.getObject(1);
                Integer result;
                if (rawResult == null)
                {
                    result = null;
                }
                else if (rawResult instanceof Number number)
                {
                    result = number.intValue();
                }
                else
                {
                    throw new SQLException("MySQL 清理锁函数结果类型异常");
                }
                if (resultSet.next())
                {
                    throw new SQLException("MySQL 清理锁函数返回多行结果");
                }
                return result;
            }
        }
    }

    /**
     * 创建专用于附件清理的 REQUIRES_NEW 事务模板，确保返回前完成提交或回滚。
     *
     * @param transactionManager PlatformTransactionManager，应用正式事务管理器
     * @return TransactionOperations，固定事务传播和名称的执行器
     */
    private static TransactionOperations createCleanupTransaction(
            PlatformTransactionManager transactionManager)
    {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setName("workflowAttachmentCleanup");
        return template;
    }

    /**
     * 返回当前 JVM 的清理锁持有状态，仅供 Gauge 采集且不能作为锁判断依据。
     *
     * @return boolean，本节点正在持有 MySQL 清理锁时为 true
     */
    public boolean isLockActive()
    {
        return lockActive.get();
    }

    /**
     * 返回本进程是否发生过无法确认成功的 GET_LOCK 或 RELEASE_LOCK，仅供健康检查和固定
     * Gauge 使用。
     *
     * @return boolean，释放失败后保持 true 直至进程重启
     */
    public boolean isLockDegraded()
    {
        return lockDegraded.get();
    }

    /**
     * 返回本进程无法确认 GET_LOCK 结果的累计次数。
     *
     * @return long，进程生命周期内单调递增的获取失败数
     */
    public long getLockAcquisitionFailures()
    {
        return lockAcquisitionFailures.get();
    }

    /**
     * 返回本进程无法确认成功的 RELEASE_LOCK 累计次数。
     *
     * @return long，进程生命周期内单调递增的释放失败数
     */
    public long getLockReleaseFailures()
    {
        return lockReleaseFailures.get();
    }
}
