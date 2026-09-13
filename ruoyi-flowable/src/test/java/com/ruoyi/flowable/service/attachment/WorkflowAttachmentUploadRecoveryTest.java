package com.ruoyi.flowable.service.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.vo.WorkflowAttachmentView;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;
import com.ruoyi.flowable.testsupport.WorkflowMySqlITSupport;

/** 同一组真实事务、生产 Mapper 和临时文件系统行为也在 MySQL IT 中执行。 */
class WorkflowAttachmentUploadRecoveryTest
{
    @TempDir
    Path profile;

    DataSource dataSource;
    JdbcTemplate jdbc;
    DataSourceTransactionManager transactionManager;
    TransactionTemplate transaction;
    WfAttachmentMapper mapper;
    WorkflowAttachmentStorage storage;
    WorkflowAttachmentProperties properties;
    WorkflowAttachmentUploadJournal journal;
    WorkflowAttachmentService service;
    WorkflowIdentityResolver identity;
    final List<String> ownedKeys = new CopyOnWriteArrayList<>();
    final long ownerId = (UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE) | 1L;

    DataSource createDataSource() throws Exception
    {
        JdbcDataSource source = WorkflowH2SchemaMapperSupport.createDataSource(
                "attachment_upload_" + UUID.randomUUID().toString().replace("-", ""), true, 3000);
        WorkflowH2SchemaMapperSupport.executeSchema(source,
                WorkflowH2SchemaMapperSupport.ATTACHMENT_SCHEMA);
        return source;
    }

    @BeforeEach
    void setup() throws Exception
    {
        dataSource = createDataSource();
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        mapper = spy(WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource,
                "attachment-upload-recovery", WfAttachmentMapper.class,
                "mapper/flowable/WfAttachmentMapper.xml"));
        properties = new WorkflowAttachmentProperties();
        properties.setMinFreeBytes(0);
        properties.setCleanupBatchSize(2);
        properties.setCleanupLeaseDuration(Duration.ofSeconds(1));
        storage = spy(new WorkflowAttachmentStorage(profile, properties.getMaxSize()));
        doAnswer(invocation -> {
            String key = (String) invocation.callRealMethod();
            ownedKeys.add(key);
            return key;
        }).when(storage).reserveKey(any());
        journal = newJournal(storage);
        identity = mock(WorkflowIdentityResolver.class);
        doReturn(new WorkflowCurrentIdentity(Long.toString(ownerId), Set.of()))
                .when(identity).resolveCurrentIdentity();
        service = WorkflowMySqlITSupport.transactionalProxy(new WorkflowAttachmentService(
                mapper, storage, properties, identity, mock(WorkflowProcessAccessService.class),
                journal, transactionManager),
                transactionManager);
    }

    @AfterEach
    void cleanup()
    {
        if (jdbc != null)
        {
            for (String key : ownedKeys)
            {
                jdbc.update("delete from wf_attachment_upload where storage_key=?", key);
            }
            jdbc.update("delete from wf_attachment where owner_user_id=?", ownerId);
            jdbc.update("delete from wf_attachment_quota_guard where owner_user_id=?", ownerId);
            assertThat(jdbc.queryForObject("select count(*) from wf_attachment where owner_user_id=?",
                    Long.class, ownerId)).isZero();
        }
        if (dataSource instanceof JdbcDataSource)
        {
            WorkflowH2SchemaMapperSupport.shutdown(dataSource);
        }
    }

    @Test
    void identityOrQuotaDenialLeavesOnlyRecoverableEmptyIntent()
    {
        when(identity.resolveCurrentIdentity()).thenThrow(new ServiceException("identity denied", 403));
        assertThatThrownBy(() -> service.uploadTemporary("evidence", file()))
                .isInstanceOf(ServiceException.class).hasMessage("identity denied");
        doReturn(new WorkflowCurrentIdentity(Long.toString(ownerId), Set.of()))
                .when(identity).resolveCurrentIdentity();
        properties.setMaxTemporaryBytes(1L);
        assertThatThrownBy(() -> service.uploadTemporary("evidence", file()))
                .isInstanceOf(ServiceException.class).hasMessageContaining("总大小已达到上限");
        assertThat(metadataCount()).isZero();
        assertThat(uploadCount()).isEqualTo(2);
        for (String key : ownedKeys)
        {
            assertThat(Files.exists(physical(key))).isFalse();
            assertThat(Files.exists(temporary(key))).isFalse();
        }
        assertThat(journal.cleanupBatch().cleaned()).isEqualTo(2);
        assertThat(uploadCount()).isZero();
    }

    @Test
    void rollbackAndFailedDeleteRemainRecoverableAfterServiceRestart() throws Exception
    {
        doThrow(new WorkflowAttachmentStorageOperationException("injected delete failure",
                new IOException("busy"))).when(storage).delete(anyString());
        String key = uploadThenRollback();
        assertThat(metadataCount()).isZero();
        assertThat(uploadCount()).isOne();
        assertThat(Files.readString(physical(key))).isEqualTo("persisted-content");
        assertThat(journal.cleanupBatch()).isEqualTo(new WorkflowAttachmentCleanupResult(0, 1, 0));
        assertThat(uploadCount()).isOne();
        assertThat(Files.exists(physical(key))).isTrue();
        assertThat(journal.cleanupBatch()).isEqualTo(new WorkflowAttachmentCleanupResult(0, 0, 0));

        // 重建服务与存储对象，恢复只依赖数据库和真实目录，不复用失败进程的内存。
        makeDue(key);
        WorkflowAttachmentStorage restarted = new WorkflowAttachmentStorage(profile, properties.getMaxSize());
        assertThat(newJournal(restarted).cleanupBatch())
                .isEqualTo(new WorkflowAttachmentCleanupResult(1, 0, 0));
        assertThat(uploadCount()).isZero();
        assertThat(Files.exists(physical(key))).isFalse();
    }

    @Test
    void unavailableStorageRootRetainsIntentUntilTheSameDirectoryReturns() throws Exception
    {
        String key = uploadThenRollback();
        Path root = profile.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME).toAbsolutePath().normalize();
        Path unavailable = profile.resolve("temporarily-unavailable-storage").toAbsolutePath().normalize();
        assertThat(root.startsWith(profile.toAbsolutePath().normalize())).isTrue();
        assertThat(unavailable.startsWith(profile.toAbsolutePath().normalize())).isTrue();
        Files.move(root, unavailable);
        try
        {
            assertThat(journal.cleanupBatch()).isEqualTo(new WorkflowAttachmentCleanupResult(0, 1, 0));
            assertThat(uploadCount()).isOne();
            assertThat(Files.exists(unavailable.resolve(key))).isTrue();
        }
        finally
        {
            Files.move(unavailable, root);
        }
        makeDue(key);
        assertThat(journal.cleanupBatch()).isEqualTo(new WorkflowAttachmentCleanupResult(1, 0, 0));
        assertThat(uploadCount()).isZero();
        assertThat(Files.exists(physical(key))).isFalse();
    }

    @Test
    void committedAttachmentAndUnexpectedResidualJournalNeverDeleteOwnedFile() throws Exception
    {
        WorkflowAttachmentView uploaded = service.uploadTemporary("evidence", file());
        WfAttachment row = mapper.selectById(uploaded.attachmentId());
        assertThat(uploadCount()).isZero();
        assertThat(metadataCount()).isOne();
        assertThat(journal.cleanupBatch().cleaned()).isZero();
        // 模拟异常残留账本；已存在正式所有者时只消费残留，不触碰其文件。
        mapper.insertUpload(row.storageKey());
        assertThat(journal.cleanupBatch().cleaned()).isOne();
        assertThat(uploadCount()).isZero();
        try (var input = service.openReadableDownload(uploaded.attachmentId()).content())
        {
            assertThat(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("persisted-content");
        }
    }

    @Test
    void interruptedPartialWriteHasDurablePathAndIsRecovered() throws Exception
    {
        transaction.executeWithoutResult(status -> {
            String key = journal.reserve(file());
            journal.lock(key);
            Path partial = temporary(key);
            try
            {
                Files.createDirectories(partial.getParent());
                Files.writeString(partial, "half-written");
            }
            catch (IOException failure)
            {
                throw new IllegalStateException(failure);
            }
            // 进程终止相同的持久状态：未提交文件写入，没有回调可执行。
            status.setRollbackOnly();
        });
        String key = ownedKeys.get(0);
        assertThat(uploadCount()).isOne();
        assertThat(metadataCount()).isZero();
        assertThat(Files.exists(temporary(key))).isTrue();
        assertThat(newJournal(new WorkflowAttachmentStorage(profile, properties.getMaxSize()))
                .cleanupBatch().cleaned()).isOne();
        assertThat(Files.exists(temporary(key))).isFalse();
        assertThat(uploadCount()).isZero();
    }

    @Test
    void activeUploadIsSkippedBeyondLeaseAndCommitsWithoutFileLoss() throws Exception
    {
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch finishUpload = new CountDownLatch(1);
        doAnswer(invocation -> {
            StoredAttachmentFile result = (StoredAttachmentFile) invocation.callRealMethod();
            written.countDown();
            assertThat(finishUpload.await(15, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(storage).store(any(), anyString());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try
        {
            Future<WorkflowAttachmentView> uploading = executor.submit(
                    () -> service.uploadTemporary("evidence", file()));
            assertThat(written.await(5, TimeUnit.SECONDS)).isTrue();
            // 持锁时间超过清理租约，仍必须依赖行锁保护，不按文件年龄认定孤儿。
            assertThat(finishUpload.await(1200, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(executor.submit(journal::cleanupBatch).get(3, TimeUnit.SECONDS))
                    .isEqualTo(new WorkflowAttachmentCleanupResult(0, 0, 0));
            assertThat(Files.exists(physical(ownedKeys.get(0)))).isTrue();
            finishUpload.countDown();
            WorkflowAttachmentView result = uploading.get(5, TimeUnit.SECONDS);
            assertThat(mapper.selectById(result.attachmentId())).isNotNull();
            assertThat(uploadCount()).isZero();
            assertThat(journal.cleanupBatch().cleaned()).isZero();
            assertThat(Files.exists(physical(ownedKeys.get(0)))).isTrue();
        }
        finally
        {
            finishUpload.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void uploaderCannotReuseKeyClaimedBeforeItsRowLockEvenAfterTokenCleared() throws Exception
    {
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch resumeUploader = new CountDownLatch(1);
        doAnswer(invocation -> {
            registered.countDown();
            assertThat(resumeUploader.await(15, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(mapper).selectUploadClaimCountForUpdate(anyString());
        doThrow(new WorkflowAttachmentStorageOperationException("injected delete failure",
                new IOException("busy"))).when(storage).delete(anyString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try
        {
            Future<?> uploading = executor.submit(() -> service.uploadTemporary("evidence", file()));
            assertThat(registered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(journal.cleanupBatch().failures()).isOne();
            String key = ownedKeys.get(0);
            assertThat(jdbc.queryForObject("select claim_token from wf_attachment_upload where storage_key=?",
                    String.class, key)).isNull();
            resumeUploader.countDown();
            assertThatThrownBy(() -> uploading.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ServiceException.class)
                    .hasStackTraceContaining("工作流附件上传已过期");
            assertThat(metadataCount()).isZero();
            assertThat(Files.exists(physical(key))).isFalse();
            assertThat(Files.exists(temporary(key))).isFalse();
            makeDue(key);
            assertThat(newJournal(new WorkflowAttachmentStorage(profile, properties.getMaxSize()))
                    .cleanupBatch().cleaned()).isOne();
        }
        finally
        {
            resumeUploader.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void expiredWorkerCannotOverwriteReclaimedTokenOrRecreateCompletedIntent() throws Exception
    {
        String key = uploadThenRollback();
        CountDownLatch oldWorkerDeleting = new CountDownLatch(1);
        CountDownLatch resumeOldWorker = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            oldWorkerDeleting.countDown();
            assertThat(resumeOldWorker.await(15, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(storage).deleteUncommittedUpload(key);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try
        {
            Future<WorkflowAttachmentCleanupResult> oldWorker = executor.submit(journal::cleanupBatch);
            assertThat(oldWorkerDeleting.await(5, TimeUnit.SECONDS)).isTrue();
            String oldToken = jdbc.queryForObject(
                    "select claim_token from wf_attachment_upload where storage_key=?", String.class, key);
            jdbc.update("update wf_attachment_upload set lease_until=? where storage_key=?",
                    LocalDateTime.now().minusDays(1), key);
            WorkflowAttachmentStorage secondStorage = spy(new WorkflowAttachmentStorage(profile,
                    properties.getMaxSize()));
            doThrow(new WorkflowAttachmentStorageOperationException("second delete failure",
                    new IOException("busy"))).when(secondStorage).delete(key);
            assertThat(newJournal(secondStorage).cleanupBatch().failures()).isOne();
            assertThat(mapper.completeUploadCleanup(key, oldToken)).isZero();
            assertThat(mapper.retryUploadCleanup(key, oldToken, LocalDateTime.now())).isZero();
            makeDue(key);
            assertThat(newJournal(new WorkflowAttachmentStorage(profile, properties.getMaxSize()))
                    .cleanupBatch().cleaned()).isOne();
            resumeOldWorker.countDown();
            assertThat(oldWorker.get(5, TimeUnit.SECONDS))
                    .isEqualTo(new WorkflowAttachmentCleanupResult(0, 0, 1));
            assertThat(uploadCount()).isZero();
            assertThat(Files.exists(physical(key))).isFalse();
            assertThat(metadataCount()).isZero();
        }
        finally
        {
            resumeOldWorker.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cleanupIsBoundedAndFailureDoesNotStarveRemainingFiles() throws Exception
    {
        for (int index = 0; index < 5; index++)
        {
            uploadThenRollback();
        }
        String failingKey = ownedKeys.get(0);
        doThrow(new WorkflowAttachmentStorageOperationException("injected delete failure",
                new IOException("busy"))).when(storage).delete(failingKey);
        int successes = 0;
        int failures = 0;
        for (int pass = 0; pass < 3; pass++)
        {
            WorkflowAttachmentCleanupResult result = journal.cleanupBatch();
            assertThat(result.cleaned() + result.failures() + result.leaseLost()).isLessThanOrEqualTo(2);
            successes += result.cleaned();
            failures += result.failures();
        }
        assertThat(successes).isEqualTo(4);
        assertThat(failures).isOne();
        assertThat(uploadCount()).isOne();
        makeDue(failingKey);
        assertThat(newJournal(new WorkflowAttachmentStorage(profile, properties.getMaxSize()))
                .cleanupBatch().cleaned()).isOne();
    }

    @Test
    void metadataFailureRollsBackQuotaAndCanBeRetriedWithoutLeakingFiles() throws Exception
    {
        doThrow(new ServiceException("injected metadata failure", 500)).when(mapper).insert(any());
        assertThatThrownBy(() -> service.uploadTemporary("evidence", file()))
                .isInstanceOf(ServiceException.class).hasMessage("injected metadata failure");
        String key = ownedKeys.get(0);
        assertThat(uploadCount()).isOne();
        assertThat(metadataCount()).isZero();
        assertThat(journal.cleanupBatch().cleaned()).isOne();
        assertThat(Files.exists(physical(key))).isFalse();
        doAnswer(invocation -> invocation.callRealMethod()).when(mapper).insert(any());
        assertThat(service.uploadTemporary("evidence", file()).attachmentId()).isNotBlank();
        assertThat(metadataCount()).isOne();
        assertThat(uploadCount()).isZero();
    }

    private WorkflowAttachmentUploadJournal newJournal(WorkflowAttachmentStorage targetStorage)
    {
        return new WorkflowAttachmentUploadJournal(mapper, targetStorage, properties, transactionManager);
    }

    private String uploadThenRollback()
    {
        transaction.executeWithoutResult(status -> {
            service.uploadTemporary("evidence", file());
            status.setRollbackOnly();
        });
        return ownedKeys.get(ownedKeys.size() - 1);
    }

    private int metadataCount()
    {
        return jdbc.queryForObject("select count(*) from wf_attachment where owner_user_id=?",
                Integer.class, ownerId);
    }

    private int uploadCount()
    {
        return ownedKeys.stream().mapToInt(key -> jdbc.queryForObject(
                "select count(*) from wf_attachment_upload where storage_key=?", Integer.class, key)).sum();
    }

    private void makeDue(String key)
    {
        jdbc.update("update wf_attachment_upload set next_retry_time=? where storage_key=?",
                LocalDateTime.now().minusDays(1), key);
    }

    private Path physical(String key)
    {
        return profile.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME).resolve(key);
    }

    private Path temporary(String key)
    {
        return profile.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME).resolve(".tmp")
                .resolve(Path.of(key).getFileName().toString() + ".part");
    }

    private MockMultipartFile file()
    {
        return new MockMultipartFile("file", "evidence.txt", "text/plain",
                "persisted-content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
