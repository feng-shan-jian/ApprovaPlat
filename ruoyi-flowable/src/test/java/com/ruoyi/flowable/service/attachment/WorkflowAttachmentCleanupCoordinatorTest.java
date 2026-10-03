package com.ruoyi.flowable.service.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

/** H2 只验证业务分支、真实提交和 IO 事务边界；MySQL 锁协议另有独立用例。 */
class WorkflowAttachmentCleanupCoordinatorTest
{
    @TempDir Path root;
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private WfAttachmentMapper mapper;
    private WorkflowAttachmentStorage storage;
    private WorkflowAttachmentCleanupCoordinator coordinator;
    private TransactionTemplate transaction;
    private DataSourceTransactionManager manager;
    private WorkflowAttachmentProperties properties;
    private WorkflowAttachmentUploadJournal journal;

    @BeforeEach
    void setUp()
    {
        dataSource = WorkflowH2SchemaMapperSupport.createDataSource(
                "cleanup-" + UUID.randomUUID(), true, 5000);
        WorkflowH2SchemaMapperSupport.executeSchema(dataSource,
                WorkflowH2SchemaMapperSupport.ATTACHMENT_SCHEMA);
        jdbc = new JdbcTemplate(dataSource);
        mapper = spy(WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource,
                "cleanup", WfAttachmentMapper.class, "mapper/flowable/WfAttachmentMapper.xml"));
        manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        properties = new WorkflowAttachmentProperties();
        storage = spy(new WorkflowAttachmentStorage(root, properties.getMaxSize()));
        journal = new WorkflowAttachmentUploadJournal(mapper, storage, properties, manager);
        coordinator = new WorkflowAttachmentCleanupCoordinator(mapper, storage, properties, journal, manager);
    }

    @AfterEach
    void tearDown()
    {
        WorkflowH2SchemaMapperSupport.shutdown(dataSource);
    }

    @Test
    void manualDeleteCommitsOutsideCallerTransactionAndRepeatedDeleteSucceeds()
    {
        WorkflowAttachmentService service = manualService();
        WfAttachment attachment = insert(WorkflowAttachmentStatus.TEMP);
        assertStorageRunsOutsideTransaction();
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            service.deleteOwnedTemporary(attachment.attachmentId());
        });
        service.deleteOwnedTemporary(attachment.attachmentId());
        WfAttachment deleted = mapper.selectById(attachment.attachmentId());
        assertThat(deleted.status()).isEqualTo(WorkflowAttachmentStatus.DELETED);
        assertThat(deleted.storageDeletedTime()).isNotNull();
        assertThat(deleted.cleanupClaimToken()).isNull();
        assertThat(root.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME)
                .resolve(attachment.storageKey())).doesNotExist();
    }

    @Test
    void manualFailurePersistsRetryAndDoesNotRollbackLogicalDeletion()
    {
        WorkflowAttachmentService service = manualService();
        WfAttachment attachment = insert(WorkflowAttachmentStatus.TEMP);
        var failure = failStorage(attachment);
        transaction.executeWithoutResult(status -> {
            status.setRollbackOnly();
            assertThatThrownBy(() -> service.deleteOwnedTemporary(attachment.attachmentId()))
                    .isSameAs(failure);
        });
        assertRetry(attachment.attachmentId());
        assertThat(mapper.selectById(attachment.attachmentId()).status())
                .isEqualTo(WorkflowAttachmentStatus.DELETED);
    }

    @Test
    void batchContinuesOnlyAfterStorageFailureRetryIsCommitted()
    {
        WfAttachment first = insert(WorkflowAttachmentStatus.DELETED);
        WfAttachment second = insert(WorkflowAttachmentStatus.DELETED);
        jdbc.update("update wf_attachment set expire_time = ? where attachment_id = ?",
                LocalDateTime.now().minusHours(2), first.attachmentId());
        failStorage(first);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertRetry(first.attachmentId());
            return invocation.callRealMethod();
        }).when(storage).delete(second.storageKey());
        assertThat(coordinator.cleanupBatch()).isEqualTo(new WorkflowAttachmentCleanupResult(1, 1, 0));
        assertThat(mapper.selectById(second.attachmentId()).storageDeletedTime()).isNotNull();
    }

    @Test
    void batchRetryPersistenceFailureStillThrowsOriginalStorageFailure()
    {
        WfAttachment attachment = insert(WorkflowAttachmentStatus.DELETED);
        var failure = failStorage(attachment);
        var databaseFailure = new IllegalStateException("retry database failure");
        doThrow(databaseFailure).when(mapper).scheduleCleanupRetry(anyString(), anyString(),
                anyInt(), any(), anyString());
        assertThatThrownBy(coordinator::cleanupBatch).isSameAs(failure)
                .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(databaseFailure));
        assertThat(mapper.selectById(attachment.attachmentId()).cleanupRetryCount()).isZero();
        assertThat(mapper.selectById(attachment.attachmentId()).cleanupClaimToken()).isNotNull();
    }

    @Test
    void manualRetryPersistenceFailureStillThrowsOriginalStorageFailure()
    {
        WorkflowAttachmentService service = manualService();
        WfAttachment attachment = insert(WorkflowAttachmentStatus.TEMP);
        var failure = failStorage(attachment);
        var databaseFailure = new IllegalStateException("retry database failure");
        doThrow(databaseFailure).when(mapper).scheduleCleanupRetry(anyString(), anyString(),
                anyInt(), any(), anyString());
        assertThatThrownBy(() -> service.deleteOwnedTemporary(attachment.attachmentId()))
                .isSameAs(failure).satisfies(thrown ->
                        assertThat(thrown.getSuppressed()).containsExactly(databaseFailure));
        assertThat(mapper.selectById(attachment.attachmentId()).status())
                .isEqualTo(WorkflowAttachmentStatus.DELETED);
    }

    @Test
    void batchUsesCommittedClaimsAndWritesButNoTransactionDuringStorageIo()
    {
        WfAttachment attachment = insert(WorkflowAttachmentStatus.TEMP);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return invocation.callRealMethod();
        }).when(mapper).selectCleanupCandidatesForUpdate(anyInt());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return invocation.callRealMethod();
        }).when(mapper).markStorageDeleted(anyString(), anyString());
        assertStorageRunsOutsideTransaction();
        assertThat(coordinator.cleanupBatch()).isEqualTo(new WorkflowAttachmentCleanupResult(1, 0, 0));
        assertThat(mapper.selectById(attachment.attachmentId()).status())
                .isEqualTo(WorkflowAttachmentStatus.EXPIRED);
    }

    @Test
    void oldTokenCannotCompleteOrRetryAfterReclaim()
    {
        WfAttachment attachment = insert(WorkflowAttachmentStatus.DELETED);
        WfAttachment oldClaim = transaction.execute(status -> coordinator.claimCleanupBatch(UUID.randomUUID().toString()).get(0));
        jdbc.update("update wf_attachment set cleanup_lease_until = ? where attachment_id = ?",
                LocalDateTime.now().minusMinutes(1), attachment.attachmentId());
        WfAttachment newClaim = transaction.execute(status -> coordinator.claimCleanupBatch(UUID.randomUUID().toString()).get(0));
        assertThat(coordinator.completeClaimedCleanup(oldClaim)).isFalse();
        assertThat(coordinator.persistCleanupRetry(oldClaim)).isFalse();
        assertThat(mapper.selectById(attachment.attachmentId()).cleanupClaimToken()).isEqualTo(newClaim.cleanupClaimToken());
        assertThat(coordinator.completeClaimedCleanup(newClaim)).isTrue();
        assertThat(coordinator.completeClaimedCleanup(newClaim)).isFalse();
        assertThat(coordinator.persistCleanupRetry(newClaim)).isFalse();
    }

    @Test
    void retryCasDoesNotOverwriteChangedRetryVersion()
    {
        WfAttachment attachment = insert(WorkflowAttachmentStatus.DELETED);
        WfAttachment claimed = transaction.execute(status -> coordinator.claimCleanupBatch(UUID.randomUUID().toString()).get(0));
        jdbc.update("update wf_attachment set cleanup_retry_count = 3 where attachment_id = ?", attachment.attachmentId());
        assertThatThrownBy(() -> coordinator.persistCleanupRetry(claimed)).isInstanceOf(ServiceException.class);
        assertThat(mapper.selectById(attachment.attachmentId()).cleanupRetryCount()).isEqualTo(3);
    }

    @Test
    void stateCorruptionIsNotConvertedToRetry()
    {
        WfAttachment attachment = insert(WorkflowAttachmentStatus.DELETED);
        doReturn(0).when(mapper).markStorageDeleted(anyString(), anyString());
        assertThatThrownBy(coordinator::cleanupBatch).isInstanceOf(ServiceException.class);
        assertThat(mapper.selectById(attachment.attachmentId()).cleanupRetryCount()).isZero();
    }

    private WfAttachment insert(WorkflowAttachmentStatus status)
    {
        var file = storage.store(new MockMultipartFile("file", "test.txt", "text/plain", "cleanup".getBytes()));
        LocalDateTime now = LocalDateTime.now();
        var row = new WfAttachment(UUID.randomUUID().toString(), 100L, "evidence",
                file.originalName(), file.storageKey(), file.contentType(), file.fileSize(), file.sha256(),
                status, now.minusHours(1), null, null, null, null, null, null, 0,
                null, null, null, null, now, now);
        assertThat(mapper.insert(row)).isOne();
        return row;
    }

    private WorkflowAttachmentStorageOperationException failStorage(WfAttachment attachment)
    {
        var failure = new WorkflowAttachmentStorageOperationException("storage unavailable", null);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw failure;
        }).when(storage).delete(attachment.storageKey());
        return failure;
    }

    /** 只有手动入口用例装配对象身份服务；批次清理无需该业务总服务。 */
    private WorkflowAttachmentService manualService()
    {
        var identity = mock(WorkflowIdentityResolver.class);
        when(identity.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        var target = new WorkflowAttachmentService(mapper, storage, properties, identity,
                mock(WorkflowProcessAccessService.class), journal, coordinator, manager);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (WorkflowAttachmentService) proxy.getProxy();
    }

    private void assertStorageRunsOutsideTransaction()
    {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return invocation.callRealMethod();
        }).when(storage).delete(anyString());
    }

    private void assertRetry(String id)
    {
        WfAttachment retry = mapper.selectById(id);
        assertThat(retry.cleanupRetryCount()).isOne();
        assertThat(retry.cleanupNextRetryTime()).isAfter(LocalDateTime.now());
        assertThat(retry.cleanupLastErrorCode()).isEqualTo("attachment_storage_cleanup_failed");
        assertThat(retry.cleanupClaimToken()).isNull();
        assertThat(retry.storageDeletedTime()).isNull();
    }
}
