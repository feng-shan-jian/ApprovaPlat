package com.ruoyi.flowable.service.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessSnapshot;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;
import com.ruoyi.flowable.testsupport.WorkflowH2SchemaMapperSupport;

/** 附件局部规则只装配正式 Mapper、事务和私有文件；实例授权事实由调用方显式提供。 */
class WorkflowAttachmentLocalContractTest
{
    @TempDir Path root;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private WfAttachmentMapper mapper;
    private TransactionTemplate transaction;
    private WorkflowAttachmentStorage storage;
    private WorkflowAttachmentService service;

    @BeforeEach
    void setUp()
    {
        dataSource = WorkflowH2SchemaMapperSupport.createDataSource("attachment-local-" + UUID.randomUUID(), true, 5000);
        WorkflowH2SchemaMapperSupport.executeSchema(dataSource, WorkflowH2SchemaMapperSupport.ATTACHMENT_SCHEMA);
        jdbc = new JdbcTemplate(dataSource);
        var manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        mapper = WorkflowH2SchemaMapperSupport.createSpringMapper(dataSource, "attachment-local",
                WfAttachmentMapper.class, "mapper/flowable/WfAttachmentMapper.xml");
        var properties = new WorkflowAttachmentProperties();
        storage = new WorkflowAttachmentStorage(root, properties.getMaxSize());
        var journal = new WorkflowAttachmentUploadJournal(mapper, storage, properties, manager);
        var cleanup = new WorkflowAttachmentCleanupCoordinator(mapper, storage, properties, journal, manager);
        var identity = mock(WorkflowIdentityResolver.class);
        when(identity.resolveCurrentIdentity()).thenReturn(new WorkflowCurrentIdentity("100", Set.of()));
        service = new WorkflowAttachmentService(mapper, storage, properties, identity,
                mock(WorkflowProcessAccessService.class), journal, cleanup, manager);
    }

    @AfterEach
    void close()
    {
        WorkflowH2SchemaMapperSupport.shutdown(dataSource);
    }

    @Test
    void draftCoordinationRejectsDuplicatesWrongFieldsForeignOwnerExpiredAndMissingRows()
    {
        String draft = UUID.randomUUID().toString();
        String id = insert().attachmentId();
        for (boolean submission : List.of(false, true))
        {
            java.util.function.Consumer<Map<String, List<String>>> coordinate = references ->
                    transaction.executeWithoutResult(status -> {
                        if (submission) service.prepareDraftSubmissionVariables("100", draft, Map.of(), references);
                        else service.reconcileDraftAttachments("100", draft, references);
                    });
            error(() -> coordinate.accept(Map.of("evidence", List.of(id, id))), 400);
            error(() -> coordinate.accept(Map.of("other", List.of(id))), 400);
            jdbc.update("update wf_attachment set owner_user_id = 200 where attachment_id = ?", id);
            error(() -> coordinate.accept(Map.of("evidence", List.of(id))), 403);
            jdbc.update("update wf_attachment set owner_user_id = 100, expire_time = ? where attachment_id = ?",
                    LocalDateTime.now().minusMinutes(1), id);
            error(() -> coordinate.accept(Map.of("evidence", List.of(id))), 409);
            jdbc.update("update wf_attachment set expire_time = ? where attachment_id = ?", LocalDateTime.now().plusHours(1), id);
            error(() -> coordinate.accept(Map.of("evidence", List.of(UUID.randomUUID().toString()))), 404);
            assertThat(mapper.selectById(id).status()).isEqualTo(WorkflowAttachmentStatus.TEMP);
        }
        error(() -> transaction.executeWithoutResult(status -> service.bindDraftStartAttachments("100", draft,
                "instance", "start", Map.of("evidence", List.of(id, id)))), 400);
    }

    @Test
    void boundProjectionChecksEveryRelationAndExposesOnlySafeMetadata()
    {
        WfAttachment row = insert();
        jdbc.update("update wf_attachment set attachment_status = 'BOUND', process_instance_id = 'instance', bound_time = ? where attachment_id = ?",
                LocalDateTime.now(), row.attachmentId());
        var access = access("instance");
        assertThat(service.projectReadableBoundMetadata(access, Map.of())).isEmpty();
        var metadata = service.projectReadableBoundMetadata(access, Map.of(row.attachmentId(), "evidence")).get(row.attachmentId());
        assertThat(metadata.size()).isEqualTo(6);
        assertThat(metadata.get("attachmentId").asText()).isEqualTo(row.attachmentId());
        assertThat(metadata.has("storageKey")).isFalse();
        assertThat(metadata.has("ownerUserId")).isFalse();
        assertThat(metadata.get("sha256").asText()).isEqualTo(row.sha256());
        error(() -> service.projectReadableBoundMetadata(access, Map.of(row.attachmentId(), "wrongField")), 500);
        error(() -> service.projectReadableBoundMetadata(access("other"), Map.of(row.attachmentId(), "evidence")), 500);
        error(() -> service.projectReadableBoundMetadata(access, Map.of(insert().attachmentId(), "evidence")), 500);
        error(() -> service.projectReadableBoundMetadata(access, Map.of(UUID.randomUUID().toString(), "evidence")), 500);
        jdbc.update("update wf_attachment set storage_deleted_time = ? where attachment_id = ?", LocalDateTime.now(), row.attachmentId());
        error(() -> service.projectReadableBoundMetadata(access, Map.of(row.attachmentId(), "evidence")), 500);
    }

    @Test
    void downloadChecksContentAndMetadataAndRejectsForeignOwnerAndMissingObjects() throws Exception
    {
        WfAttachment row = insert();
        var download = service.openReadableDownload(row.attachmentId());
        try (var input = download.content())
        {
            assertThat(input.readAllBytes()).isEqualTo("evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertThat(download.fileSize()).isEqualTo(row.fileSize());
        assertThat(download.sha256()).isEqualTo(row.sha256());
        assertThat(service.getReadableMetadata(row.attachmentId()).originalName()).isEqualTo("evidence.txt");
        jdbc.update("update wf_attachment set owner_user_id = 200 where attachment_id = ?", row.attachmentId());
        error(() -> service.openReadableDownload(row.attachmentId()), 403);
        jdbc.update("update wf_attachment set owner_user_id = 100 where attachment_id = ?", row.attachmentId());
        Files.delete(file(row));
        error(() -> service.openReadableDownload(row.attachmentId()), 404);
        error(() -> service.openReadableDownload(UUID.randomUUID().toString()), 404);
    }

    @Test
    void downloadRejectsTraversalAndCorruptedContentWithoutExposingAnotherFile() throws Exception
    {
        WfAttachment row = insert();
        Files.writeString(file(row), "tampered");
        error(() -> service.openReadableDownload(row.attachmentId()), 500);
        jdbc.update("update wf_attachment set storage_key = '../outside.txt' where attachment_id = ?", row.attachmentId());
        error(() -> service.openReadableDownload(row.attachmentId()), 500);
        assertThat(root.resolve("outside.txt")).doesNotExist();
    }

    private WfAttachment insert()
    {
        var file = storage.store(new MockMultipartFile("file", "evidence.txt", "text/plain", "evidence".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var now = LocalDateTime.now();
        var row = new WfAttachment(UUID.randomUUID().toString(), 100L, "evidence", file.originalName(), file.storageKey(),
                file.contentType(), file.fileSize(), file.sha256(), WorkflowAttachmentStatus.TEMP, now.plusHours(1),
                null, null, null, null, null, null, 0, null, null, null, null, now, now);
        transaction.executeWithoutResult(status -> assertThat(mapper.insert(row)).isOne());
        return row;
    }

    private Path file(WfAttachment row)
    {
        return root.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME).resolve(row.storageKey());
    }

    private WorkflowProcessAccessSnapshot access(String id)
    {
        return new WorkflowProcessAccessSnapshot(id, "definition", "deployment", null, "100", null,
                null, null, "RUNNING", "running", null, null);
    }

    private void error(Runnable operation, int code)
    {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ServiceException.class,
                failure -> assertThat(failure.getCode()).isEqualTo(code));
    }
}
