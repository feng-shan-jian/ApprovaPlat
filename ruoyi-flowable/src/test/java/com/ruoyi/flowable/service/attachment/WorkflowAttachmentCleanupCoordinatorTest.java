package com.ruoyi.flowable.service.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;

class WorkflowAttachmentCleanupCoordinatorTest
{
    /**
     * 验证领取和每条状态回写使用独立短事务，对象存储删除始终位于事务之外。
     *
     * @return void，事务边界覆盖对象存储 IO 或遗漏完成回写时测试失败
     */
    @Test
    void separatesShortDatabaseTransactionsFromStorageDeletion()
    {
        WorkflowAttachmentService service = mock(WorkflowAttachmentService.class);
        RecordingTransactionOperations transactions = new RecordingTransactionOperations();
        WfAttachment first = claimedAttachment(
                "d9428888-122b-4c6f-8f0c-9c3e1dbd3210");
        WfAttachment second = claimedAttachment(
                "7f0f5db2-0664-4e5c-a54f-49d9ca16b773");
        when(service.claimCleanupBatch(anyString())).thenAnswer(invocation ->
        {
            assertThat(transactions.isActive()).isTrue();
            return List.of(first, second);
        });
        doAnswer(invocation ->
        {
            assertThat(transactions.isActive()).isFalse();
            return null;
        }).when(service).deleteClaimedStorage(first);
        doAnswer(invocation ->
        {
            assertThat(transactions.isActive()).isFalse();
            return null;
        }).when(service).deleteClaimedStorage(second);
        when(service.completeClaimedCleanup(first)).thenAnswer(invocation ->
        {
            assertThat(transactions.isActive()).isTrue();
            return true;
        });
        when(service.completeClaimedCleanup(second)).thenReturn(true);
        WorkflowAttachmentCleanupCoordinator coordinator =
                new WorkflowAttachmentCleanupCoordinator(service, transactions);

        WorkflowAttachmentCleanupResult result = coordinator.cleanupBatch();

        assertThat(result).isEqualTo(new WorkflowAttachmentCleanupResult(2, 0, 0));
        assertThat(transactions.executions()).isEqualTo(3);
        verify(service).deleteClaimedStorage(first);
        verify(service).deleteClaimedStorage(second);
    }

    /**
     * 验证对象删除失败后仅在新的短事务中持久化重试，并继续返回可观测失败数。
     *
     * @return void，存储失败未进入重试或错误地执行完成回写时测试失败
     */
    @Test
    void schedulesRetryAfterStorageFailure()
    {
        WorkflowAttachmentService service = mock(WorkflowAttachmentService.class);
        RecordingTransactionOperations transactions = new RecordingTransactionOperations();
        WfAttachment attachment = claimedAttachment(
                "d9428888-122b-4c6f-8f0c-9c3e1dbd3210");
        WorkflowAttachmentStorageOperationException storageFailure =
                new WorkflowAttachmentStorageOperationException(
                        "forced cleanup failure", new java.io.IOException());
        when(service.claimCleanupBatch(anyString())).thenReturn(List.of(attachment));
        doThrow(storageFailure).when(service).deleteClaimedStorage(attachment);
        when(service.persistCleanupRetry(attachment)).thenReturn(true);
        WorkflowAttachmentCleanupCoordinator coordinator =
                new WorkflowAttachmentCleanupCoordinator(service, transactions);

        assertThat(coordinator.cleanupBatch())
                .isEqualTo(new WorkflowAttachmentCleanupResult(0, 1, 0));
        assertThat(transactions.executions()).isEqualTo(2);
        verify(service, never()).completeClaimedCleanup(attachment);
    }

    /**
     * 验证租约过期重领后旧执行者不覆盖新 token，并计入 lease_lost 结果。
     *
     * @return void，旧执行者被误记为完成或失败时测试失败
     */
    @Test
    void recordsLeaseLossWithoutOverwritingNewOwner()
    {
        WorkflowAttachmentService service = mock(WorkflowAttachmentService.class);
        WfAttachment attachment = claimedAttachment(
                "d9428888-122b-4c6f-8f0c-9c3e1dbd3210");
        when(service.claimCleanupBatch(anyString())).thenReturn(List.of(attachment));
        when(service.completeClaimedCleanup(attachment)).thenReturn(false);
        WorkflowAttachmentCleanupCoordinator coordinator =
                new WorkflowAttachmentCleanupCoordinator(service,
                        new RecordingTransactionOperations());

        assertThat(coordinator.cleanupBatch())
                .isEqualTo(new WorkflowAttachmentCleanupResult(0, 0, 1));
        verify(service, never()).persistCleanupRetry(attachment);
    }

    /**
     * 验证重试状态无法持久化时保留原存储异常为主异常，并附加数据库失败证据。
     *
     * @return void，主异常被替换或数据库失败被吞掉时测试失败
     */
    @Test
    void preservesStorageFailureWhenRetryPersistenceFails()
    {
        WorkflowAttachmentService service = mock(WorkflowAttachmentService.class);
        WfAttachment attachment = claimedAttachment(
                "d9428888-122b-4c6f-8f0c-9c3e1dbd3210");
        WorkflowAttachmentStorageOperationException storageFailure =
                new WorkflowAttachmentStorageOperationException(
                        "forced cleanup failure", new java.io.IOException());
        IllegalStateException retryFailure = new IllegalStateException("forced db failure");
        when(service.claimCleanupBatch(anyString())).thenReturn(List.of(attachment));
        doThrow(storageFailure).when(service).deleteClaimedStorage(attachment);
        when(service.persistCleanupRetry(attachment)).thenThrow(retryFailure);
        WorkflowAttachmentCleanupCoordinator coordinator =
                new WorkflowAttachmentCleanupCoordinator(service,
                        new RecordingTransactionOperations());

        assertThatThrownBy(coordinator::cleanupBatch)
                .isSameAs(storageFailure)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .containsExactly(retryFailure));
    }

    /**
     * 创建带完整领取 token 和租约的终态附件快照。
     *
     * @param attachmentId String，测试附件 UUID
     * @return WfAttachment，可执行事务外对象删除的领取快照
     */
    private WfAttachment claimedAttachment(String attachmentId)
    {
        LocalDateTime now = LocalDateTime.now();
        return new WfAttachment(attachmentId, 7L, "files", "invoice.pdf",
                "2026/08/16/0123456789abcdef0123456789abcdef.pdf",
                "application/pdf", 128L, "a".repeat(64),
                WorkflowAttachmentStatus.EXPIRED, now.minusHours(1), null,
                null, null, null, null, null, 0, null, null,
                "b9428888-122b-4c6f-8f0c-9c3e1dbd3210", now.plusMinutes(5),
                now.minusHours(2), now);
    }

    /**
     * 记录并暴露测试事务是否处于执行中，用于验证对象存储调用不占用数据库事务。
     */
    private static final class RecordingTransactionOperations
            implements TransactionOperations
    {
        /** 当前线程是否正在执行短事务回调。 */
        private final AtomicBoolean active = new AtomicBoolean(false);
        /** 已完成的短事务回调次数。 */
        private int executions;

        /**
         * 同步执行事务回调并在返回前恢复非事务状态。
         *
         * @param action TransactionCallback&lt;T&gt;，待执行的数据库动作
         * @param <T> 回调返回值类型
         * @return T，回调返回值
         */
        @Override
        public <T> T execute(TransactionCallback<T> action)
        {
            executions++;
            active.set(true);
            try
            {
                return action.doInTransaction(mock(TransactionStatus.class));
            }
            finally
            {
                active.set(false);
            }
        }

        /**
         * 返回当前测试线程是否位于短事务回调中。
         *
         * @return boolean，事务回调执行中为 true
         */
        boolean isActive()
        {
            return active.get();
        }

        /**
         * 返回累计执行的短事务次数。
         *
         * @return int，事务回调次数
         */
        int executions()
        {
            return executions;
        }
    }
}
