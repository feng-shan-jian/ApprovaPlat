package com.ruoyi.flowable.service.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import com.ruoyi.flowable.config.WorkflowRuntimeProperties;

class WorkflowAttachmentCleanupCoordinatorTest
{
    /**
     * 验证锁覆盖内部事务完成边界，RELEASE_LOCK 在清理事务返回后才由同一连接执行。
     *
     * @return void，释放早于提交可见边界或更换连接时测试失败
     * @throws Exception JDBC 测试替身配置失败
     */
    @Test
    void releasesSamePhysicalConnectionOnlyAfterCleanupTransactionCompletes() throws Exception
    {
        List<String> events = new ArrayList<>();
        LockJdbc jdbc = lockJdbc(1, 1, events);
        WorkflowAttachmentService service = mock(WorkflowAttachmentService.class);
        when(service.cleanupExpiredBatch()).thenAnswer(invocation ->
        {
            events.add("cleanup");
            return new WorkflowAttachmentCleanupResult(2, 0);
        });
        TransactionOperations transaction = transactionOperations(events);
        WorkflowAttachmentCleanupCoordinator coordinator = newCoordinator(
                service, jdbc.dataSource(), transaction);

        Optional<WorkflowAttachmentCleanupResult> result =
                coordinator.cleanupIfLockAcquired();

        assertThat(result).contains(new WorkflowAttachmentCleanupResult(2, 0));
        assertThat(events).containsExactly(
                "acquire", "transaction_begin", "cleanup",
                "transaction_completed", "release");
        verify(jdbc.connection()).prepareStatement("select get_lock(?, 0)");
        verify(jdbc.connection()).prepareStatement("select release_lock(?)");
        verify(jdbc.connection()).close();
        assertThat(coordinator.isLockActive()).isFalse();
        assertThat(coordinator.isLockDegraded()).isFalse();
    }

    /**
     * 验证未获取 MySQL 锁时不创建事务、不查询候选、不执行 RELEASE_LOCK，形成零副作用分支。
     *
     * @return void，竞争失败仍进入任何清理步骤时测试失败
     * @throws Exception JDBC 测试替身配置失败
     */
    @Test
    void skipsWithoutStartingTransactionWhenLockIsOwnedByAnotherNode() throws Exception
    {
        List<String> events = new ArrayList<>();
        LockJdbc jdbc = lockJdbc(0, 1, events);
        WorkflowAttachmentService service = mock(WorkflowAttachmentService.class);
        TransactionOperations transaction = mock(TransactionOperations.class);
        WorkflowAttachmentCleanupCoordinator coordinator = newCoordinator(
                service, jdbc.dataSource(), transaction);

        assertThat(coordinator.cleanupIfLockAcquired()).isEmpty();

        assertThat(events).containsExactly("acquire");
        verifyNoInteractions(service, transaction);
        verify(jdbc.connection(), never()).prepareStatement("select release_lock(?)");
        verify(jdbc.connection()).close();
        assertThat(coordinator.isLockDegraded()).isFalse();
    }

    /**
     * 验证清理事务异常回滚返回后仍释放原锁连接，并保留原始异常作为主失败。
     *
     * @return void，异常路径漏释放、提前释放或替换主异常时测试失败
     * @throws Exception JDBC 测试替身配置失败
     */
    @Test
    void releasesLockAfterCleanupTransactionRollsBack() throws Exception
    {
        List<String> events = new ArrayList<>();
        LockJdbc jdbc = lockJdbc(1, 1, events);
        WorkflowAttachmentService service = mock(WorkflowAttachmentService.class);
        IllegalStateException cleanupFailure = new IllegalStateException("forced cleanup failure");
        when(service.cleanupExpiredBatch()).thenAnswer(invocation ->
        {
            events.add("cleanup_failed");
            throw cleanupFailure;
        });
        TransactionOperations transaction = transactionOperations(events);
        WorkflowAttachmentCleanupCoordinator coordinator = newCoordinator(
                service, jdbc.dataSource(), transaction);

        assertThatThrownBy(coordinator::cleanupIfLockAcquired).isSameAs(cleanupFailure);

        assertThat(events).containsExactly(
                "acquire", "transaction_begin", "cleanup_failed",
                "transaction_rolled_back", "release");
        verify(jdbc.connection()).close();
        assertThat(coordinator.isLockActive()).isFalse();
        assertThat(coordinator.isLockDegraded()).isFalse();
    }

    /**
     * 验证 RELEASE_LOCK 返回非 1 时立即 abort 专用会话并保持运行健康降级。
     *
     * @return void，异常物理连接可能回池或降级状态被遗漏时测试失败
     * @throws Exception JDBC 测试替身配置失败
     */
    @Test
    void abortsPhysicalSessionAndDegradesWhenReleaseResultIsNotOne() throws Exception
    {
        List<String> events = new ArrayList<>();
        LockJdbc jdbc = lockJdbc(1, 0, events);
        WorkflowAttachmentService service = mock(WorkflowAttachmentService.class);
        when(service.cleanupExpiredBatch())
                .thenReturn(new WorkflowAttachmentCleanupResult(1, 0));
        WorkflowAttachmentCleanupCoordinator coordinator = newCoordinator(
                service, jdbc.dataSource(), transactionOperations(events));

        assertThatThrownBy(coordinator::cleanupIfLockAcquired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("工作流附件 MySQL 清理锁释放结果异常");

        verify(jdbc.connection()).abort(any());
        verify(jdbc.connection()).close();
        assertThat(coordinator.isLockActive()).isFalse();
        assertThat(coordinator.isLockDegraded()).isTrue();
    }

    /**
     * 验证事务回滚后 RELEASE_LOCK 的 JDBC 异常不会替换业务主异常，且连接仍被 abort。
     *
     * @return void，主异常、suppressed 证据或异常连接淘汰任一丢失时测试失败
     * @throws Exception JDBC 测试替身配置失败
     */
    @Test
    void preservesCleanupFailureAndAbortsWhenReleaseThrowsSQLException() throws Exception
    {
        List<String> events = new ArrayList<>();
        LockJdbc jdbc = lockJdbc(1, 1, events);
        SQLException releaseFailure = new SQLException("forced release failure");
        doThrow(releaseFailure).when(jdbc.releaseStatement()).executeQuery();
        WorkflowAttachmentService service = mock(WorkflowAttachmentService.class);
        IllegalStateException cleanupFailure = new IllegalStateException(
                "forced cleanup failure");
        when(service.cleanupExpiredBatch()).thenThrow(cleanupFailure);
        WorkflowAttachmentCleanupCoordinator coordinator = newCoordinator(
                service, jdbc.dataSource(), transactionOperations(events));

        assertThatThrownBy(coordinator::cleanupIfLockAcquired)
                .isSameAs(cleanupFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(releaseFailure));

        verify(jdbc.connection()).abort(any());
        verify(jdbc.connection()).close();
        assertThat(coordinator.isLockActive()).isFalse();
        assertThat(coordinator.isLockDegraded()).isTrue();
    }

    /**
     * 验证 abort 自身失败作为 suppressed 证据保留，仍不掩盖 RELEASE_LOCK 主失败。
     *
     * @return void，连接淘汰失败证据丢失或错误层级倒置时测试失败
     * @throws Exception JDBC 测试替身配置失败
     */
    @Test
    void retainsAbortFailureAsSuppressedOnReleaseFailure() throws Exception
    {
        LockJdbc jdbc = lockJdbc(1, 0, new ArrayList<>());
        SQLException abortFailure = new SQLException("forced abort failure");
        doThrow(abortFailure).when(jdbc.connection()).abort(any());
        WorkflowAttachmentService service = mock(WorkflowAttachmentService.class);
        when(service.cleanupExpiredBatch())
                .thenReturn(new WorkflowAttachmentCleanupResult(1, 0));
        WorkflowAttachmentCleanupCoordinator coordinator = newCoordinator(
                service, jdbc.dataSource(), transactionOperations(new ArrayList<>()));

        assertThatThrownBy(coordinator::cleanupIfLockAcquired)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(abortFailure));

        verify(jdbc.connection()).close();
        assertThat(coordinator.isLockDegraded()).isTrue();
    }

    /**
     * 验证 GET_LOCK 返回 null、2 等非法结果时按锁状态不确定处理，禁止连接直接回池。
     *
     * @return void，非法结果未触发 abort、降级或零副作用约束时测试失败
     * @throws Exception JDBC 测试替身配置失败
     */
    @Test
    void abortsAndDegradesWhenAcquireResultIsInvalid() throws Exception
    {
        LockJdbc jdbc = lockJdbc(2, 1, new ArrayList<>());
        WorkflowAttachmentService service = mock(WorkflowAttachmentService.class);
        TransactionOperations transaction = mock(TransactionOperations.class);
        WorkflowAttachmentCleanupCoordinator coordinator = newCoordinator(
                service, jdbc.dataSource(), transaction);

        assertThatThrownBy(coordinator::cleanupIfLockAcquired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("工作流附件 MySQL 清理锁获取结果异常");

        verify(jdbc.connection()).abort(any());
        verify(jdbc.connection()).close();
        verify(jdbc.connection(), never()).prepareStatement("select release_lock(?)");
        verifyNoInteractions(service, transaction);
        assertThat(coordinator.isLockDegraded()).isTrue();
        assertThat(coordinator.getLockAcquisitionFailures()).isEqualTo(1L);
        assertThat(coordinator.getLockReleaseFailures()).isZero();
    }

    /**
     * 验证 GET_LOCK 已读取结果但 ResultSet 关闭异常时仍视为不确定，并淘汰物理会话。
     *
     * @return void，JDBC 资源关闭异常允许可能持锁的连接回池时测试失败
     * @throws Exception JDBC 测试替身配置失败
     */
    @Test
    void abortsAndDegradesWhenAcquireResultSetCloseFails() throws Exception
    {
        LockJdbc jdbc = lockJdbc(1, 1, new ArrayList<>());
        SQLException acquireFailure = new SQLException("forced acquire close failure");
        doThrow(acquireFailure).when(jdbc.acquireResultSet()).close();
        WorkflowAttachmentService service = mock(WorkflowAttachmentService.class);
        TransactionOperations transaction = mock(TransactionOperations.class);
        WorkflowAttachmentCleanupCoordinator coordinator = newCoordinator(
                service, jdbc.dataSource(), transaction);

        assertThatThrownBy(coordinator::cleanupIfLockAcquired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("工作流附件 MySQL 清理锁连接异常")
                .hasCause(acquireFailure);

        verify(jdbc.connection()).abort(any());
        verify(jdbc.connection()).close();
        verify(jdbc.connection(), never()).prepareStatement("select release_lock(?)");
        verifyNoInteractions(service, transaction);
        assertThat(coordinator.isLockDegraded()).isTrue();
        assertThat(coordinator.getLockAcquisitionFailures()).isEqualTo(1L);
    }

    /**
     * 验证获取结果不确定且 abort 自身失败时保留获取异常为主异常，并附加淘汰失败证据。
     *
     * @return void，abort 失败覆盖主异常或丢失 suppressed 证据时测试失败
     * @throws Exception JDBC 测试替身配置失败
     */
    @Test
    void retainsAbortFailureAsSuppressedOnAcquireFailure() throws Exception
    {
        LockJdbc jdbc = lockJdbc(2, 1, new ArrayList<>());
        SQLException abortFailure = new SQLException("forced acquire abort failure");
        doThrow(abortFailure).when(jdbc.connection()).abort(any());
        WorkflowAttachmentCleanupCoordinator coordinator = newCoordinator(
                mock(WorkflowAttachmentService.class), jdbc.dataSource(),
                mock(TransactionOperations.class));

        assertThatThrownBy(coordinator::cleanupIfLockAcquired)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("工作流附件 MySQL 清理锁获取结果异常")
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(abortFailure));

        verify(jdbc.connection()).close();
        assertThat(coordinator.isLockDegraded()).isTrue();
        assertThat(coordinator.getLockAcquisitionFailures()).isEqualTo(1L);
    }

    /**
     * 验证一个节点事务尚未完成时，第二节点未获锁且不会启动自己的清理事务。
     *
     * @return void，第二节点在第一节点提交边界前进入清理时测试失败
     * @throws Exception 并发执行或等待超时
     */
    @Test
    void concurrentNodeCannotCleanBeforeFirstTransactionCompletes() throws Exception
    {
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        LockJdbc firstJdbc = lockJdbc(1, 1,
                Collections.synchronizedList(new ArrayList<>()));
        LockJdbc secondJdbc = lockJdbc(0, 1,
                Collections.synchronizedList(new ArrayList<>()));
        WorkflowAttachmentService firstService = mock(WorkflowAttachmentService.class);
        WorkflowAttachmentService secondService = mock(WorkflowAttachmentService.class);
        when(firstService.cleanupExpiredBatch()).thenAnswer(invocation ->
        {
            cleanupEntered.countDown();
            assertThat(allowCommit.await(5, TimeUnit.SECONDS)).isTrue();
            return new WorkflowAttachmentCleanupResult(1, 0);
        });
        TransactionOperations firstTransaction = transactionOperations(new ArrayList<>());
        TransactionOperations secondTransaction = mock(TransactionOperations.class);
        WorkflowAttachmentCleanupCoordinator first = newCoordinator(
                firstService, firstJdbc.dataSource(), firstTransaction);
        WorkflowAttachmentCleanupCoordinator second = newCoordinator(
                secondService, secondJdbc.dataSource(), secondTransaction);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try
        {
            Future<Optional<WorkflowAttachmentCleanupResult>> firstRun =
                    executor.submit(first::cleanupIfLockAcquired);
            assertThat(cleanupEntered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(second.cleanupIfLockAcquired()).isEmpty();
            verifyNoInteractions(secondService, secondTransaction);

            allowCommit.countDown();
            assertThat(firstRun.get(5, TimeUnit.SECONDS))
                    .contains(new WorkflowAttachmentCleanupResult(1, 0));
        }
        finally
        {
            allowCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    /**
     * 创建使用 MySQL advisory lock 的被测协调器。
     *
     * @param service WorkflowAttachmentService，清理领域服务替身
     * @param dataSource DataSource，锁连接来源
     * @param transaction TransactionOperations，可观测提交边界的事务替身
     * @return WorkflowAttachmentCleanupCoordinator，被测协调器
     */
    private WorkflowAttachmentCleanupCoordinator newCoordinator(
            WorkflowAttachmentService service, DataSource dataSource,
            TransactionOperations transaction)
    {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        return new WorkflowAttachmentCleanupCoordinator(
                service, properties, dataSource, transaction);
    }

    /**
     * 创建会真实调用事务回调并记录完成或回滚边界的事务操作器替身。
     *
     * @param events List&lt;String&gt;，按执行顺序收集的事件
     * @return TransactionOperations，回调返回后记录完成、异常后记录回滚
     */
    private TransactionOperations transactionOperations(List<String> events)
    {
        TransactionOperations transaction = mock(TransactionOperations.class);
        when(transaction.execute(any())).thenAnswer(invocation ->
        {
            @SuppressWarnings("unchecked")
            TransactionCallback<WorkflowAttachmentCleanupResult> callback =
                    invocation.getArgument(0);
            events.add("transaction_begin");
            try
            {
                WorkflowAttachmentCleanupResult result = callback.doInTransaction(
                        mock(TransactionStatus.class));
                events.add("transaction_completed");
                return result;
            }
            catch (RuntimeException | Error failure)
            {
                events.add("transaction_rolled_back");
                throw failure;
            }
        });
        return transaction;
    }

    /**
     * 创建单一物理连接及两条锁函数语句，结果分别模拟获取和释放。
     *
     * @param acquireResult Integer，GET_LOCK 返回值
     * @param releaseResult Integer，RELEASE_LOCK 返回值
     * @param events List&lt;String&gt;，锁函数执行顺序记录
     * @return LockJdbc，数据源、同一连接和语句测试替身
     * @throws Exception JDBC 替身配置失败
     */
    private LockJdbc lockJdbc(Integer acquireResult, Integer releaseResult,
            List<String> events) throws Exception
    {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement acquire = lockStatement(acquireResult, "acquire", events);
        PreparedStatement release = lockStatement(releaseResult, "release", events);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
        {
            String sql = invocation.getArgument(0);
            return sql.contains("release_lock") ? release : acquire;
        });
        return new LockJdbc(dataSource, connection, acquire, release,
                acquire.getResultSet());
    }

    /**
     * 创建返回单行数值的锁函数 PreparedStatement。
     *
     * @param result Integer，MySQL 锁函数返回值
     * @param event String，执行时记录的事件名
     * @param events List&lt;String&gt;，顺序事件集合
     * @return PreparedStatement，可重复关闭的 JDBC 替身
     * @throws Exception JDBC 替身配置失败
     */
    private PreparedStatement lockStatement(Integer result, String event,
            List<String> events) throws Exception
    {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(statement.executeQuery()).thenAnswer(invocation ->
        {
            events.add(event);
            return resultSet;
        });
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(result);
        when(statement.getResultSet()).thenReturn(resultSet);
        return statement;
    }

    /**
     * JDBC 锁测试所需的同一数据源与物理连接。
     *
     * @param dataSource DataSource，返回固定连接
     * @param connection Connection，获取和释放均使用的连接
     * @param acquireStatement PreparedStatement，供获取异常分支精确注入的语句
     * @param releaseStatement PreparedStatement，供释放异常分支精确注入的语句
     * @param acquireResultSet ResultSet，供结果读取和关闭异常分支精确注入
     */
    private record LockJdbc(DataSource dataSource, Connection connection,
            PreparedStatement acquireStatement, PreparedStatement releaseStatement,
            ResultSet acquireResultSet)
    {
    }
}
