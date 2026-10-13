package com.ruoyi.flowable.service.attachment;

import com.ruoyi.common.testsupport.WorkflowMysqlTargetGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.ArgumentMatchers.any;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

/** 仅装配附件 Mapper、文件、事务；真实 MySQL 验证锁与有效期合同。 */
@EnabledIfEnvironmentVariable(named = "WORKFLOW_MYSQL_TEST_URL", matches = ".+")
class WorkflowAttachmentMysqlIntegrationTest
{
    @TempDir Path root;
    private final List<String> createdIds = new ArrayList<>();
    private final List<String> uploadKeys = new ArrayList<>();
    private final long uploadOwner = 700_000_000L + Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000L);
    private WfAttachmentMapper mapper;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private WorkflowAttachmentStorage storage;
    private WorkflowAttachmentService service;
    private WorkflowAttachmentCleanupCoordinator coordinator;
    private WorkflowAttachmentUploadJournal journal;

    @BeforeEach
    void setUp()
    {
        String url = System.getenv("WORKFLOW_MYSQL_TEST_URL");
        WorkflowMysqlTargetGuard.verify(url);
        var dataSource = new DriverManagerDataSource(url,
                System.getenv("WORKFLOW_MYSQL_TEST_USERNAME"), System.getenv("WORKFLOW_MYSQL_TEST_PASSWORD"));
        var checkedJdbc = new JdbcTemplate(dataSource);
        WorkflowAttachmentMysqlTestGuard.assertEmptyUploadJournal(checkedJdbc);
        var manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        mapper = WorkflowAttachmentMysqlTestGuard.protectUploadCleanup(
                WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource, "attachment-mysql",
                        WfAttachmentMapper.class, "mapper/flowable/WfAttachmentMapper.xml"), uploadKeys);
        // A failed preflight must not enable teardown writes either.
        jdbc = checkedJdbc;
        var properties = new WorkflowAttachmentProperties();
        storage = new WorkflowAttachmentStorage(root, properties.getMaxSize());
        journal = spy(new WorkflowAttachmentUploadJournal(mapper, storage, properties, manager));
        doAnswer(call -> {
            String key = (String) call.callRealMethod();
            uploadKeys.add(key);
            return key;
        }).when(journal).reserve(any());
        coordinator = new WorkflowAttachmentCleanupCoordinator(mapper, storage, properties, journal, manager);
        var identity = mock(WorkflowIdentityResolver.class);
        when(identity.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity(Long.toString(uploadOwner), Set.of()));
        service = new WorkflowAttachmentService(mapper, storage, properties,
                identity, mock(WorkflowProcessAccessService.class),
                journal, coordinator, manager);
    }

    @AfterEach
    void tearDown()
    {
        if (jdbc == null) return;
        for (String key : uploadKeys)
        {
            jdbc.update("delete from wf_attachment where storage_key = ?", key);
            jdbc.update("delete from wf_attachment_upload where storage_key = ?", key);
            assertThat(jdbc.queryForObject("select count(*) from wf_attachment_upload where storage_key = ?", Long.class, key)).isZero();
        }
        jdbc.update("delete from wf_attachment_quota_guard where owner_user_id = ?", uploadOwner);
        for (String id : createdIds)
        {
            jdbc.update("delete from wf_attachment where attachment_id = ?", id);
            assertThat(jdbc.queryForObject("select count(*) from wf_attachment where attachment_id = ?",
                    Long.class, id)).isZero();
        }
    }

    @Test
    void taskAttachmentLockDoesNotPreserveExpiryBetweenPreparationAndBinding() throws Exception
    {
        LocalDateTime expiry = LocalDateTime.now().plusSeconds(3);
        WfAttachment attachment = insert(WorkflowAttachmentStatus.TEMP, expiry);
        var executor = Executors.newSingleThreadExecutor();
        var waiting = new AtomicReference<java.util.concurrent.Future<List<WfAttachment>>>();
        try
        {
            transaction.executeWithoutResult(status -> {
                status.setRollbackOnly();
                var references = Map.of("evidence", List.of(attachment.attachmentId()));
                assertThat(service.prepareTaskVariables("100", "instance", Map.of(), references)).containsKey("evidence");
                var started = new CompletableFuture<Void>();
                waiting.set(executor.submit(() -> transaction.execute(other -> {
                    started.complete(null);
                    return mapper.selectByIdsForUpdate(List.of(attachment.attachmentId()));
                })));
                started.orTimeout(2, TimeUnit.SECONDS).join();
                assertThatThrownBy(() -> waiting.get().get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                while (!LocalDateTime.now().isAfter(expiry.plusNanos(50_000_000)))
                {
                    java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
                }
                assertThat(waiting.get().isDone()).isFalse();
                assertThatThrownBy(() -> service.bindTaskAttachments("100", "instance", "task", "review", references))
                        .isInstanceOfSatisfying(ServiceException.class, failure -> {
                            assertThat(failure.getCode()).isEqualTo(409);
                            assertThat(failure.getSubCode()).isEqualTo("WORKFLOW_ATTACHMENT_STATE_CONFLICT");
                        });
            });
            assertThat(waiting.get().get(5, TimeUnit.SECONDS)).singleElement()
                    .satisfies(row -> assertThat(row.status()).isEqualTo(WorkflowAttachmentStatus.TEMP));
        }
        finally
        {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void skipLockedOmitsLockedCandidateWhileAnotherRowCanBeClaimed() throws Exception
    {
        WfAttachment locked = insert(WorkflowAttachmentStatus.DELETED, LocalDateTime.now().minusYears(20));
        WfAttachment available = insert(WorkflowAttachmentStatus.DELETED, LocalDateTime.now().minusYears(20));
        var executor = Executors.newSingleThreadExecutor();
        try
        {
            transaction.executeWithoutResult(status -> {
                mapper.selectByIdsForUpdate(List.of(locked.attachmentId()));
                var worker = executor.submit(() -> transaction.execute(other -> {
                    List<String> ids = mapper.selectCleanupCandidatesForUpdate(1000).stream()
                            .map(WfAttachment::attachmentId).toList();
                    assertThat(ids).contains(available.attachmentId()).doesNotContain(locked.attachmentId());
                    assertThat(mapper.claimCleanupCandidates(List.of(available.attachmentId()),
                            UUID.randomUUID().toString(), 60)).isOne();
                    return true;
                }));
                try
                {
                    assertThat(worker.get(3, TimeUnit.SECONDS)).isTrue();
                }
                catch (Exception failure)
                {
                    throw new AssertionError("SKIP LOCKED must not wait for another owner", failure);
                }
            });
        }
        finally
        {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(mapper.selectById(available.attachmentId()).cleanupClaimToken()).isNotNull();
        assertThat(mapper.selectById(locked.attachmentId()).cleanupClaimToken()).isNull();
    }

    @Test
    void concurrentClaimAndCompletionHaveSingleWinnerAndStaleTokenCannotWrite() throws Exception
    {
        WfAttachment row = insert(WorkflowAttachmentStatus.DELETED, LocalDateTime.now().minusMinutes(1));
        var executor = Executors.newFixedThreadPool(2);
        try
        {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return mapper.claimDeletedAttachment(row.attachmentId(), UUID.randomUUID().toString(), 60); });
            var second = executor.submit(() -> { start.await(); return mapper.claimDeletedAttachment(row.attachmentId(), UUID.randomUUID().toString(), 60); });
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS)).isOne();
            WfAttachment old = mapper.selectById(row.attachmentId());
            jdbc.update("update wf_attachment set cleanup_lease_until = ? where attachment_id = ?",
                    LocalDateTime.now().minusMinutes(1), row.attachmentId());
            String newToken = UUID.randomUUID().toString();
            assertThat(mapper.claimDeletedAttachment(row.attachmentId(), newToken, 60)).isOne();
            assertThat(coordinator.completeClaimedCleanup(old)).isFalse();
            assertThat(coordinator.persistCleanupRetry(old)).isFalse();
            WfAttachment current = mapper.selectById(row.attachmentId());
            assertThat(current.cleanupClaimToken()).isEqualTo(newToken);
            var completionStart = new java.util.concurrent.CountDownLatch(1);
            var completeA = executor.submit(() -> { completionStart.await(); return coordinator.completeClaimedCleanup(current); });
            var completeB = executor.submit(() -> { completionStart.await(); return coordinator.completeClaimedCleanup(current); });
            completionStart.countDown();
            assertThat(List.of(completeA.get(5, TimeUnit.SECONDS), completeB.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(coordinator.completeClaimedCleanup(current)).isFalse();
        }
        finally
        {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void metadataFailureLeavesDurableJournalAndFileUntilRecovery()
    {
        doReturn(0).when(mapper).insert(any());
        assertThatThrownBy(() -> service.uploadTemporary("evidence", uploadFile()))
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode()).isEqualTo(500));
        String key = uploadKeys.get(0);
        assertUploadState(key, 0, 1, true);
        assertThat(journal.cleanupBatch().cleaned()).isOne();
        assertUploadState(key, 0, 0, false);
    }

    @Test
    void outerRollbackRetainsReservationButRollsBackMetadataAndJournalConsumption()
    {
        transaction.executeWithoutResult(status -> {
            var view = service.uploadTemporary("evidence", uploadFile());
            assertThat(mapper.selectById(view.attachmentId())).isNotNull();
            assertUploadState(uploadKeys.get(0), 1, 0, true);
            status.setRollbackOnly();
        });
        String key = uploadKeys.get(0);
        assertUploadState(key, 0, 1, true);
        assertThat(journal.cleanupBatch().cleaned()).isOne();
        assertUploadState(key, 0, 0, false);
    }

    @Test
    void committedUploadConsumesReservationAndCleanupPreservesOwnedFile() throws Exception
    {
        var view = service.uploadTemporary("evidence", uploadFile());
        String key = uploadKeys.get(0);
        assertUploadState(key, 1, 0, true);
        journal.cleanupBatch();
        assertUploadState(key, 1, 0, true);
        // Recover a stale journal defensively without deleting the formally owned object.
        assertThat(mapper.insertUpload(key)).isOne();
        assertThat(journal.cleanupBatch().cleaned()).isOne();
        assertUploadState(key, 1, 0, true);
        try (var content = service.openReadableDownload(view.attachmentId()).content())
        {
            assertThat(content.readAllBytes()).isEqualTo("upload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void claimedUploadKeyStaysUnusableAfterRetryReleasesItsToken()
    {
        String key = journal.reserve(uploadFile());
        String token = UUID.randomUUID().toString();
        assertThat(mapper.claimUpload(key, token, 60)).isOne();
        assertThat(mapper.retryUploadCleanup(key, token, LocalDateTime.now())).isOne();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> journal.lock(key)))
                .isInstanceOfSatisfying(ServiceException.class, failure -> {
                    assertThat(failure.getCode()).isEqualTo(409);
                    assertThat(failure.getSubCode()).isEqualTo("WORKFLOW_ATTACHMENT_STATE_CONFLICT");
                });
        assertUploadState(key, 0, 1, false);
        assertThat(journal.cleanupBatch().cleaned()).isOne();
        assertUploadState(key, 0, 0, false);
    }

    private MockMultipartFile uploadFile()
    {
        return new MockMultipartFile("file", "upload.txt", "text/plain", "upload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void assertUploadState(String key, int attachments, int reservations, boolean exists)
    {
        assertThat(jdbc.queryForObject("select count(*) from wf_attachment where storage_key = ?", Integer.class, key)).isEqualTo(attachments);
        assertThat(jdbc.queryForObject("select count(*) from wf_attachment_upload where storage_key = ?", Integer.class, key)).isEqualTo(reservations);
        assertThat(Files.exists(root.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME).resolve(key))).isEqualTo(exists);
    }

    private WfAttachment insert(WorkflowAttachmentStatus status, LocalDateTime expiry)
    {
        var file = storage.store(new MockMultipartFile("file", "expiry.txt", "text/plain", "expiry".getBytes()));
        LocalDateTime now = LocalDateTime.now();
        var row = new WfAttachment(UUID.randomUUID().toString(), 100L, "evidence",
                file.originalName(), file.storageKey(), file.contentType(), file.fileSize(), file.sha256(),
                status, expiry, null, null, null, null, null, null, 0, null, null, null, null, now, now);
        createdIds.add(row.attachmentId());
        assertThat(mapper.insert(row)).isOne();
        return row;
    }
}
