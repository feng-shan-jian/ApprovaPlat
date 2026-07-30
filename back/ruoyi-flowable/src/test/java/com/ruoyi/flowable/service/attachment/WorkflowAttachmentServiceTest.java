package com.ruoyi.flowable.service.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.domain.WorkflowAttachmentQuotaUsage;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;

class WorkflowAttachmentServiceTest
{
    private static final String ATTACHMENT_ID =
            "d9428888-122b-4c6f-8f0c-9c3e1dbd3210";
    private static final String SECOND_ATTACHMENT_ID =
            "7f0f5db2-0664-4e5c-a54f-49d9ca16b773";

    @TempDir
    Path profileRoot;

    private WfAttachmentMapper attachmentMapper;
    private WorkflowIdentityResolver identityResolver;
    private WorkflowProcessAccessService processAccessService;
    private WorkflowAttachmentStorage storage;
    private WorkflowAttachmentProperties properties;
    private WorkflowAttachmentService service;

    /**
     * 为每个测试创建隔离私有目录和无共享状态的依赖替身。
     * @return void，无返回值
     */
    @BeforeEach
    void setUp()
    {
        attachmentMapper = mock(WfAttachmentMapper.class);
        identityResolver = mock(WorkflowIdentityResolver.class);
        processAccessService = mock(WorkflowProcessAccessService.class);
        properties = new WorkflowAttachmentProperties();
        storage = spy(new WorkflowAttachmentStorage(profileRoot, properties.getMaxSize()));
        doNothing().when(storage).verify(anyString(), anyLong(), anyString());
        service = new WorkflowAttachmentService(attachmentMapper, storage, properties,
                identityResolver, processAccessService);
        when(identityResolver.resolveCurrentIdentity())
                .thenReturn(new WorkflowCurrentIdentity("7", Set.of()));
        when(attachmentMapper.selectGlobalQuotaGuardForUpdate()).thenReturn(0L);
        when(attachmentMapper.ensureOwnerQuotaGuard(7L)).thenReturn(1);
        when(attachmentMapper.selectOwnerQuotaGuardForUpdate(7L)).thenReturn(7L);
        when(attachmentMapper.selectTemporaryQuotaUsage(7L))
                .thenReturn(new WorkflowAttachmentQuotaUsage(0L, 0L));
        when(attachmentMapper.selectUndeletedTotalBytes()).thenReturn(0L);
        doReturn(Long.MAX_VALUE).when(storage).usableSpace();
    }

    /**
     * 验证上传元数据只来自服务端计算，内部存储键不会进入 API 视图。
     * @return void，文件、摘要、归属或响应投影不符合时测试失败
     */
    @Test
    void uploadsOwnedTemporaryAttachmentWithServerMetadata() throws Exception
    {
        byte[] content = "formal attachment".getBytes(StandardCharsets.UTF_8);
        when(attachmentMapper.insert(any())).thenReturn(1);

        var result = service.uploadTemporary("invoiceFiles", new MockMultipartFile(
                "file", "invoice.txt", "text/html", content));

        ArgumentCaptor<WfAttachment> inserted = ArgumentCaptor.forClass(WfAttachment.class);
        verify(attachmentMapper).insert(inserted.capture());
        WfAttachment row = inserted.getValue();
        assertThat(row.ownerUserId()).isEqualTo(7L);
        assertThat(row.fieldName()).isEqualTo("invoiceFiles");
        assertThat(row.status()).isEqualTo(WorkflowAttachmentStatus.TEMP);
        assertThat(row.storageKey()).doesNotContain("invoice", "profile", "http");
        assertThat(row.expireTime()).isAfter(row.createTime());
        assertThat(result.attachmentId()).isEqualTo(row.attachmentId());
        assertThat(result.originalName()).isEqualTo("invoice.txt");
        assertThat(result.fileSize()).isEqualTo(content.length);
        assertThat(result.toString()).doesNotContain(row.storageKey(), "ownerUserId", "url");
        try (var contentStream = storage.openVerifiedForRead(
                row.storageKey(), row.fileSize(), row.sha256()))
        {
            assertThat(contentStream.readAllBytes()).isEqualTo(content);
        }
    }

    /**
     * 验证用户数量或字节配额在任何磁盘写入前拒绝，并且 guard 行锁与聚合查询均被使用。
     * @return void，超配额请求仍写文件或插入元数据时测试失败
     */
    @Test
    void rejectsTemporaryQuotaBeforeWritingPrivateFile()
    {
        properties.setMaxTemporaryCount(2);
        properties.setMaxTemporaryBytes(10L);
        when(attachmentMapper.selectTemporaryQuotaUsage(7L))
                .thenReturn(new WorkflowAttachmentQuotaUsage(2L, 2L));

        assertServiceError(() -> service.uploadTemporary("files", new MockMultipartFile(
                "file", "count.txt", "text/plain", new byte[] { 1 })),
                HttpStatus.CONFLICT, "工作流临时附件数量或总大小已达到上限");
        verify(storage, never()).store(any());
        verify(attachmentMapper, never()).insert(any());

        when(attachmentMapper.selectTemporaryQuotaUsage(7L))
                .thenReturn(new WorkflowAttachmentQuotaUsage(0L, 9L));
        assertServiceError(() -> service.uploadTemporary("files", new MockMultipartFile(
                "file", "bytes.txt", "text/plain", new byte[] { 1, 2 })),
                HttpStatus.CONFLICT, "工作流临时附件数量或总大小已达到上限");
        verify(attachmentMapper, times(2)).ensureOwnerQuotaGuard(7L);
        verify(attachmentMapper, times(2)).selectOwnerQuotaGuardForUpdate(7L);
        verify(attachmentMapper, times(2)).selectTemporaryQuotaUsage(7L);
    }

    /**
     * 验证跨用户共享的全局容量在私有文件落盘前拒绝超限请求。
     * @return void，全局占用超限后仍访问磁盘或写入元数据时测试失败
     */
    @Test
    void rejectsGlobalQuotaBeforeWritingPrivateFile()
    {
        properties.setMaxTotalBytes(10L);
        when(attachmentMapper.selectUndeletedTotalBytes()).thenReturn(9L);

        assertServiceError(() -> service.uploadTemporary("files", new MockMultipartFile(
                "file", "global.txt", "text/plain", new byte[] { 1, 2 })),
                HttpStatus.CONFLICT, "工作流附件全局存储容量已达到上限");

        verify(storage, never()).usableSpace();
        verify(storage, never()).store(any());
        verify(attachmentMapper, never()).insert(any());
    }

    /**
     * 验证上传事务始终先取得全局 guard，再取得用户 guard 并读取两级占用。
     * @return void，锁顺序漂移导致跨用户容量超卖或死锁风险时测试失败
     */
    @Test
    void locksGlobalGuardBeforeOwnerGuardAndStorageReservation()
    {
        when(attachmentMapper.insert(any())).thenReturn(1);

        service.uploadTemporary("files", new MockMultipartFile(
                "file", "ordered.txt", "text/plain", new byte[] { 1 }));

        var ordered = inOrder(attachmentMapper, storage);
        ordered.verify(attachmentMapper).selectGlobalQuotaGuardForUpdate();
        ordered.verify(attachmentMapper).ensureOwnerQuotaGuard(7L);
        ordered.verify(attachmentMapper).selectOwnerQuotaGuardForUpdate(7L);
        ordered.verify(attachmentMapper).selectTemporaryQuotaUsage(7L);
        ordered.verify(attachmentMapper).selectUndeletedTotalBytes();
        ordered.verify(storage).usableSpace();
        ordered.verify(storage).store(any());
        ordered.verify(storage).usableSpace();
        ordered.verify(attachmentMapper).insert(any());
    }

    /**
     * 验证上传事务使用 READ_COMMITTED，使锁前身份查询不会固定后续配额聚合的旧快照。
     * @return void，事务隔离级别或异常回滚契约漂移时测试失败
     * @throws NoSuchMethodException 上传方法签名不存在
     */
    @Test
    void usesReadCommittedIsolationForSerializedQuotaReads() throws NoSuchMethodException
    {
        Transactional transaction = WorkflowAttachmentService.class
                .getMethod("uploadTemporary", String.class, MultipartFile.class)
                .getAnnotation(Transactional.class);

        assertThat(transaction).isNotNull();
        assertThat(transaction.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        assertThat(transaction.rollbackFor()).containsExactly(Exception.class);
    }

    /**
     * 验证迁移期预置的固定全局 guard 缺失时上传失败关闭，且不会在运行时竞争创建 guard。
     * @return void，全局 guard 缺失后仍创建用户 guard、写文件或写元数据时测试失败
     */
    @Test
    void rejectsUploadWhenPreseededGlobalGuardIsMissing()
    {
        when(attachmentMapper.selectGlobalQuotaGuardForUpdate()).thenReturn(null);

        assertServiceError(() -> service.uploadTemporary("files", new MockMultipartFile(
                "file", "missing-global-guard.txt", "text/plain", new byte[] { 1 })),
                HttpStatus.ERROR, "工作流附件全局配额锁数据异常");

        verify(attachmentMapper, never()).ensureOwnerQuotaGuard(anyLong());
        verify(attachmentMapper, never()).selectOwnerQuotaGuardForUpdate(anyLong());
        verify(storage, never()).store(any());
        verify(attachmentMapper, never()).insert(any());
    }

    /**
     * 验证磁盘预留不足在落盘前返回 507，并且不产生文件或数据库副作用。
     * @return void，低水位不足后仍写磁盘或写元数据时测试失败
     */
    @Test
    void rejectsInsufficientDiskReservationBeforeStore()
    {
        properties.setMinFreeBytes(100L);
        doReturn(101L).when(storage).usableSpace();

        assertServiceError(() -> service.uploadTemporary("files", new MockMultipartFile(
                "file", "disk.txt", "text/plain", new byte[] { 1, 2 })),
                HttpStatus.INSUFFICIENT_STORAGE, "工作流附件存储空间不足");

        verify(storage, never()).store(any());
        verify(attachmentMapper, never()).insert(any());
    }

    /**
     * 验证落盘后低于磁盘低水位会返回 507，并立即补偿删除孤儿文件。
     * @return void，复核失败仍登记元数据或残留私有文件时测试失败
     * @throws Exception 遍历隔离私有目录失败
     */
    @Test
    void removesStoredFileWhenPostWriteDiskWatermarkFails() throws Exception
    {
        properties.setMinFreeBytes(100L);
        doReturn(102L, 99L).when(storage).usableSpace();

        assertServiceError(() -> service.uploadTemporary("files", new MockMultipartFile(
                "file", "disk-after.txt", "text/plain", new byte[] { 1, 2 })),
                HttpStatus.INSUFFICIENT_STORAGE, "工作流附件存储空间不足");

        verify(attachmentMapper, never()).insert(any());
        Path privateRoot = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME);
        try (var paths = Files.walk(privateRoot))
        {
            assertThat(paths.filter(Files::isRegularFile)).isEmpty();
        }
    }

    /**
     * 验证数据库登记失败会同步补偿删除已写入的私有文件。
     * @return void，异常被吞掉或磁盘存在残留时测试失败
     * @throws Exception 遍历隔离目录失败
     */
    @Test
    void deletesStoredFileWhenMetadataInsertFails() throws Exception
    {
        when(attachmentMapper.insert(any())).thenThrow(new IllegalStateException("db failure"));

        assertThatThrownBy(() -> service.uploadTemporary("files", new MockMultipartFile(
                "file", "failure.txt", "text/plain", new byte[] { 1, 2, 3 })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db failure");

        Path privateRoot = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME);
        try (var paths = Files.walk(privateRoot))
        {
            assertThat(paths.filter(Files::isRegularFile)).isEmpty();
        }
    }

    /**
     * 验证开始变量只投影安全附件元数据且不暴露所有者、存储键或静态 URL。
     * @return void，行锁、字段映射或安全 JSON 投影不符合时测试失败
     */
    @Test
    void locksAndProjectsBindableStartAttachments()
    {
        WfAttachment attachment = attachment(ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.TEMP, LocalDateTime.now().plusHours(1), null);
        when(attachmentMapper.selectByIdsForUpdate(List.of(ATTACHMENT_ID)))
                .thenReturn(List.of(attachment));

        Map<String, Object> projected = service.prepareStartVariables("7",
                Map.of("reason", "采购", "files", List.of(ATTACHMENT_ID)),
                Map.of("files", List.of(ATTACHMENT_ID)));

        assertThat(projected.get("reason")).isEqualTo("采购");
        assertThat(projected.get("files")).isInstanceOf(JsonNode.class);
        JsonNode files = (JsonNode) projected.get("files");
        assertThat(files.isArray()).isTrue();
        assertThat(files.path(0).path("attachmentId").asText()).isEqualTo(ATTACHMENT_ID);
        assertThat(files.path(0).path("originalName").asText()).isEqualTo("invoice.pdf");
        assertThat(files.toString()).doesNotContain(
                attachment.storageKey(), "ownerUserId", "processInstanceId", "url");
    }

    /**
     * 验证附件所有者、状态、有效期和上传字段任一不一致都会在引擎发起前拒绝。
     * @return void，任一非法附件可进入安全投影时测试失败
     */
    @Test
    void rejectsForeignExpiredBoundAndWrongFieldAttachments()
    {
        assertPrepareError(attachment(ATTACHMENT_ID, 8L, "files",
                WorkflowAttachmentStatus.TEMP, LocalDateTime.now().plusHours(1), null),
                HttpStatus.FORBIDDEN, "无权访问当前工作流附件");
        assertPrepareError(attachment(ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.TEMP, LocalDateTime.now().minusSeconds(1), null),
                HttpStatus.CONFLICT, "工作流附件状态已变化或已过期");
        assertPrepareError(attachment(ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.BOUND, LocalDateTime.now().plusHours(1), "instance-1"),
                HttpStatus.CONFLICT, "工作流附件状态已变化或已过期");
        assertPrepareError(attachment(ATTACHMENT_ID, 7L, "otherFiles",
                WorkflowAttachmentStatus.TEMP, LocalDateTime.now().plusHours(1), null),
                HttpStatus.BAD_REQUEST, "工作流附件所属表单字段不匹配");
    }

    /**
     * 验证绑定中任一条件更新失败都会抛出冲突，供外层同一事务回滚实例和前序绑定。
     * @return void，部分绑定失败仍返回成功时测试失败
     */
    @Test
    void failsWholeBindingWhenAnyAttachmentStateChanges()
    {
        WfAttachment first = attachment(ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.TEMP, LocalDateTime.now().plusHours(1), null);
        WfAttachment second = attachment(SECOND_ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.TEMP, LocalDateTime.now().plusHours(1), null);
        when(attachmentMapper.selectByIdsForUpdate(
                List.of(ATTACHMENT_ID, SECOND_ATTACHMENT_ID)))
                .thenReturn(List.of(first, second));
        when(attachmentMapper.bindStartAttachment(
                ATTACHMENT_ID, 7L, "files", "instance-42", "start")).thenReturn(1);
        when(attachmentMapper.bindStartAttachment(
                SECOND_ATTACHMENT_ID, 7L, "files", "instance-42", "start")).thenReturn(0);

        assertServiceError(() -> service.bindStartAttachments("7", "instance-42", "start",
                Map.of("files", List.of(ATTACHMENT_ID, SECOND_ATTACHMENT_ID))),
                HttpStatus.CONFLICT, "工作流附件状态已变化或已过期");
        verify(attachmentMapper).bindStartAttachment(
                ATTACHMENT_ID, 7L, "files", "instance-42", "start");
        verify(attachmentMapper).bindStartAttachment(
                SECOND_ATTACHMENT_ID, 7L, "files", "instance-42", "start");
    }

    /**
     * 验证绑定在数据库状态迁移前重算摘要，物理正文被同长度替换时整体拒绝。
     * @return void，篡改文件仍可绑定或发生附件状态更新时测试失败
     * @throws Exception 创建并替换隔离私有文件失败
     */
    @Test
    void rejectsTamperedFileBeforeBindingStateTransition() throws Exception
    {
        byte[] original = "original-content".getBytes(StandardCharsets.UTF_8);
        StoredAttachmentFile stored = storage.store(new MockMultipartFile(
                "file", "evidence.txt", "text/plain", original));
        LocalDateTime now = LocalDateTime.now();
        WfAttachment attachment = new WfAttachment(
                ATTACHMENT_ID, 7L, "files", stored.originalName(), stored.storageKey(),
                stored.contentType(), stored.fileSize(), stored.sha256(),
                WorkflowAttachmentStatus.TEMP, now.plusHours(1), null, null,
                null, null, null, 0, null, null, now, null);
        Path storedPath = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME)
                .resolve(stored.storageKey());
        Files.write(storedPath, "tampered-content".getBytes(StandardCharsets.UTF_8));
        when(attachmentMapper.selectByIdsForUpdate(List.of(ATTACHMENT_ID)))
                .thenReturn(List.of(attachment));
        doCallRealMethod().when(storage).verify(anyString(), anyLong(), anyString());

        assertServiceError(() -> service.bindStartAttachments("7", "instance-42", "start",
                Map.of("files", List.of(ATTACHMENT_ID))),
                HttpStatus.ERROR, "工作流附件文件完整性校验失败");
        verify(attachmentMapper, never()).bindStartAttachment(
                anyString(), anyLong(), anyString(), anyString(), anyString());
    }

    /**
     * 验证任务表单可同时引用办理人新 TEMP 附件和同实例同字段 BOUND 附件。
     * @return void，安全投影、任务绑定或既有归属保留任一不符合时测试失败
     */
    @Test
    void projectsAndBindsNewTaskAttachmentWhileReusingBoundReference()
    {
        WfAttachment temporary = attachment(ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.TEMP, LocalDateTime.now().plusHours(1), null);
        WfAttachment reused = attachment(SECOND_ATTACHMENT_ID, 8L, "files",
                WorkflowAttachmentStatus.BOUND, LocalDateTime.now().plusHours(1),
                "instance-42", "task-old", "review-old");
        List<String> references = List.of(ATTACHMENT_ID, SECOND_ATTACHMENT_ID);
        when(attachmentMapper.selectByIdsForUpdate(references))
                .thenReturn(List.of(temporary, reused));

        Map<String, Object> projected = service.prepareTaskVariables("7", "instance-42",
                Map.of("files", references), Map.of("files", references));
        JsonNode files = (JsonNode) projected.get("files");
        assertThat(files.size()).isEqualTo(2);
        assertThat(files.path(1).path("attachmentId").asText())
                .isEqualTo(SECOND_ATTACHMENT_ID);

        when(attachmentMapper.bindTaskAttachment(ATTACHMENT_ID, 7L, "files",
                "instance-42", "task-42", "review")).thenReturn(1);
        service.bindTaskAttachments("7", "instance-42", "task-42", "review",
                Map.of("files", references));

        verify(attachmentMapper).bindTaskAttachment(ATTACHMENT_ID, 7L, "files",
                "instance-42", "task-42", "review");
        verify(attachmentMapper, never()).bindTaskAttachment(SECOND_ATTACHMENT_ID, 7L,
                "files", "instance-42", "task-42", "review");
    }

    /**
     * 验证任务附件拒绝跨用户 TEMP、跨实例 BOUND 和跨字段引用。
     * @return void，任一非法引用可进入任务变量时测试失败
     */
    @Test
    void rejectsForeignTemporaryCrossInstanceAndWrongFieldTaskReferences()
    {
        assertTaskPrepareError(attachment(ATTACHMENT_ID, 8L, "files",
                WorkflowAttachmentStatus.TEMP, LocalDateTime.now().plusHours(1), null),
                HttpStatus.FORBIDDEN, "无权访问当前工作流附件");
        assertTaskPrepareError(attachment(ATTACHMENT_ID, 8L, "files",
                WorkflowAttachmentStatus.BOUND, LocalDateTime.now().plusHours(1),
                "instance-other", "task-old", "review"),
                HttpStatus.FORBIDDEN, "无权访问当前工作流附件");
        assertTaskPrepareError(attachment(ATTACHMENT_ID, 8L, "otherFiles",
                WorkflowAttachmentStatus.BOUND, LocalDateTime.now().plusHours(1),
                "instance-42", "task-old", "review"),
                HttpStatus.BAD_REQUEST, "工作流附件所属表单字段不匹配");
        assertTaskPrepareError(attachment(ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.DELETED, LocalDateTime.now().plusHours(1), null),
                HttpStatus.NOT_FOUND, "工作流附件不存在或已清理");
    }

    /**
     * 验证任务绑定的条件更新失败会抛出冲突，由外层引擎事务统一回滚。
     * @return void，绑定竞争失败仍被当作任务成功时测试失败
     */
    @Test
    void failsTaskBindingWhenTemporaryAttachmentStateChanges()
    {
        WfAttachment temporary = attachment(ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.TEMP, LocalDateTime.now().plusHours(1), null);
        when(attachmentMapper.selectByIdsForUpdate(List.of(ATTACHMENT_ID)))
                .thenReturn(List.of(temporary));
        when(attachmentMapper.bindTaskAttachment(ATTACHMENT_ID, 7L, "files",
                "instance-42", "task-42", "review")).thenReturn(0);

        assertServiceError(() -> service.bindTaskAttachments("7", "instance-42",
                "task-42", "review", Map.of("files", List.of(ATTACHMENT_ID))),
                HttpStatus.CONFLICT, "工作流附件状态已变化或已过期");
    }

    /**
     * 验证临时附件只允许所有者删除，绑定附件必须保留审计和流程关联。
     * @return void，越权或绑定态删除调用到原子更新时测试失败
     */
    @Test
    void deletesOnlyOwnedUnboundAttachment()
    {
        WfAttachment foreign = attachment(ATTACHMENT_ID, 8L, "files",
                WorkflowAttachmentStatus.TEMP, LocalDateTime.now().plusHours(1), null);
        when(attachmentMapper.selectById(ATTACHMENT_ID)).thenReturn(foreign);
        assertServiceError(() -> service.deleteOwnedTemporary(ATTACHMENT_ID),
                HttpStatus.FORBIDDEN, "无权访问当前工作流附件");
        verify(attachmentMapper, never()).markDeletedByOwner(any(), any());

        WfAttachment bound = attachment(ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.BOUND, LocalDateTime.now().plusHours(1), "instance-1");
        when(attachmentMapper.selectById(ATTACHMENT_ID)).thenReturn(bound);
        assertServiceError(() -> service.deleteOwnedTemporary(ATTACHMENT_ID),
                HttpStatus.CONFLICT, "工作流附件状态已变化或已过期");
        verify(attachmentMapper, never()).markDeletedByOwner(any(), any());
    }

    /**
     * 验证绑定附件下载委托实例对象授权，临时附件则只认上传所有者。
     * @return void，任一对象级授权分支被绕过时测试失败
     */
    @Test
    void enforcesTemporaryOwnerAndBoundInstanceReadAuthorization()
    {
        WfAttachment foreign = attachment(ATTACHMENT_ID, 8L, "files",
                WorkflowAttachmentStatus.TEMP, LocalDateTime.now().plusHours(1), null);
        when(attachmentMapper.selectById(ATTACHMENT_ID)).thenReturn(foreign);
        assertServiceError(() -> service.getReadableMetadata(ATTACHMENT_ID),
                HttpStatus.FORBIDDEN, "无权访问当前工作流附件");

        WfAttachment bound = attachment(ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.BOUND, LocalDateTime.now().plusHours(1), "instance-1");
        when(attachmentMapper.selectById(ATTACHMENT_ID)).thenReturn(bound);
        var metadata = service.getReadableMetadata(ATTACHMENT_ID);
        assertThat(metadata.attachmentId()).isEqualTo(ATTACHMENT_ID);
        assertThat(metadata.processInstanceId()).isEqualTo("instance-1");
        assertThat(metadata.nodeKey()).isEqualTo("start");
        verify(processAccessService).requireReadableInstance("instance-1");
    }

    /**
     * 验证到期临时附件先迁移为 EXPIRED，再真实删除私有文件并记录清理完成时间。
     * @return void，状态迁移、物理删除或完成标记任一步缺失时测试失败
     */
    @Test
    void expiresAndPhysicallyDeletesTemporaryAttachment()
    {
        StoredAttachmentFile stored = storage.store(new MockMultipartFile(
                "file", "expired.txt", "text/plain",
                "expired attachment".getBytes(StandardCharsets.UTF_8)));
        LocalDateTime now = LocalDateTime.now();
        WfAttachment candidate = new WfAttachment(
                ATTACHMENT_ID, 7L, "files", stored.originalName(), stored.storageKey(),
                stored.contentType(), stored.fileSize(), stored.sha256(),
                WorkflowAttachmentStatus.TEMP, now.minusSeconds(1), null, null,
                null, null, null, 0, null, null, now.minusHours(1), null);
        when(attachmentMapper.selectCleanupCandidates(properties.getCleanupBatchSize()))
                .thenReturn(List.of(candidate));
        when(attachmentMapper.markExpired(ATTACHMENT_ID)).thenReturn(1);
        when(attachmentMapper.markStorageDeleted(ATTACHMENT_ID)).thenReturn(1);

        WorkflowAttachmentCleanupResult result = service.cleanupExpiredBatch();
        assertThat(result.cleaned()).isEqualTo(1);
        assertThat(result.failures()).isZero();

        verify(attachmentMapper).markExpired(ATTACHMENT_ID);
        verify(attachmentMapper).markStorageDeleted(ATTACHMENT_ID);
        Path storedPath = profileRoot.resolve(WorkflowAttachmentStorage.PRIVATE_DIRECTORY_NAME)
                .resolve(stored.storageKey());
        assertThat(storedPath).doesNotExist();
    }

    /**
     * 验证终态附件物理删除失败时写入首次退避和固定脱敏错误码，且不误写完成标记。
     * @return void，失败未进入正式重试状态或错误正文被持久化时测试失败
     */
    @Test
    void persistsInitialBackoffAfterStorageFailure()
    {
        WorkflowAttachmentStorage retryStorage = mock(WorkflowAttachmentStorage.class);
        WorkflowAttachmentService retryService = new WorkflowAttachmentService(
                attachmentMapper, retryStorage, properties, identityResolver,
                processAccessService);
        WfAttachment candidate = attachment(ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.EXPIRED, LocalDateTime.now().minusMinutes(1), null);
        when(attachmentMapper.selectCleanupCandidates(properties.getCleanupBatchSize()))
                .thenReturn(List.of(candidate));
        when(retryStorage.delete(candidate.storageKey()))
                .thenThrow(new WorkflowAttachmentStorageOperationException(
                        "forced sensitive cleanup failure", new java.io.IOException()));
        when(attachmentMapper.scheduleCleanupRetry(eq(ATTACHMENT_ID), eq(0),
                any(LocalDateTime.class), eq("attachment_storage_cleanup_failed")))
                .thenReturn(1);

        LocalDateTime startedAt = LocalDateTime.now();
        WorkflowAttachmentCleanupResult failedResult = retryService.cleanupExpiredBatch();
        LocalDateTime completedAt = LocalDateTime.now();

        assertThat(failedResult.cleaned()).isZero();
        assertThat(failedResult.failures()).isEqualTo(1);
        verify(attachmentMapper, never()).markStorageDeleted(ATTACHMENT_ID);
        ArgumentCaptor<LocalDateTime> nextRetryTime =
                ArgumentCaptor.forClass(LocalDateTime.class);
        verify(attachmentMapper).scheduleCleanupRetry(eq(ATTACHMENT_ID), eq(0),
                nextRetryTime.capture(), eq("attachment_storage_cleanup_failed"));
        assertThat(nextRetryTime.getValue())
                .isAfterOrEqualTo(startedAt.plus(properties.getCleanupRetryInitialDelay()))
                .isBeforeOrEqualTo(completedAt.plus(properties.getCleanupRetryInitialDelay()));
    }

    /**
     * 验证异常大的既有重试次数只会调度到配置上限，不会溢出或形成无界循环。
     * @return void，退避超过上限或重试版本未参与乐观更新时测试失败
     */
    @Test
    void capsCleanupRetryBackoffAtConfiguredMaximum()
    {
        properties.setCleanupRetryInitialDelay(Duration.ofMinutes(1));
        properties.setCleanupRetryMaxDelay(Duration.ofMinutes(5));
        WorkflowAttachmentStorage retryStorage = mock(WorkflowAttachmentStorage.class);
        WorkflowAttachmentService retryService = new WorkflowAttachmentService(
                attachmentMapper, retryStorage, properties, identityResolver,
                processAccessService);
        WfAttachment candidate = withCleanupRetryCount(attachment(ATTACHMENT_ID, 7L,
                "files", WorkflowAttachmentStatus.EXPIRED,
                LocalDateTime.now().minusMinutes(1), null), Integer.MAX_VALUE);
        when(attachmentMapper.selectCleanupCandidates(properties.getCleanupBatchSize()))
                .thenReturn(List.of(candidate));
        when(retryStorage.delete(candidate.storageKey()))
                .thenThrow(new WorkflowAttachmentStorageOperationException(
                        "forced cleanup failure", new java.io.IOException()));
        when(attachmentMapper.scheduleCleanupRetry(eq(ATTACHMENT_ID),
                eq(Integer.MAX_VALUE), any(LocalDateTime.class), anyString()))
                .thenReturn(1);

        LocalDateTime startedAt = LocalDateTime.now();
        WorkflowAttachmentCleanupResult result = retryService.cleanupExpiredBatch();
        LocalDateTime completedAt = LocalDateTime.now();

        assertThat(result).isEqualTo(new WorkflowAttachmentCleanupResult(0, 1));
        ArgumentCaptor<LocalDateTime> nextRetryTime =
                ArgumentCaptor.forClass(LocalDateTime.class);
        verify(attachmentMapper).scheduleCleanupRetry(eq(ATTACHMENT_ID),
                eq(Integer.MAX_VALUE), nextRetryTime.capture(),
                eq("attachment_storage_cleanup_failed"));
        assertThat(nextRetryTime.getValue())
                .isAfterOrEqualTo(startedAt.plus(Duration.ofMinutes(5)))
                .isBeforeOrEqualTo(completedAt.plus(Duration.ofMinutes(5)));
    }

    /**
     * 验证最大退避小于首次延迟时附件服务拒绝启动，避免生产配置静默倒置。
     * @return void，非法跨字段配置未被拒绝时测试失败
     */
    @Test
    void rejectsCleanupRetryMaximumBelowInitialDelay()
    {
        WorkflowAttachmentProperties invalidProperties =
                new WorkflowAttachmentProperties();
        invalidProperties.setCleanupRetryMaxDelay(Duration.ofSeconds(30));

        assertThatThrownBy(() -> new WorkflowAttachmentService(attachmentMapper, storage,
                invalidProperties, identityResolver, processAccessService))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("工作流附件清理最大退避不能小于初始退避");
    }

    /**
     * 验证 Mapper 故障会中止整批并交由事务回滚，不能被误记为可重试文件系统失败。
     * @return void，数据库异常被吞并、继续其他候选或写入退避状态时测试失败
     */
    @Test
    void propagatesDatabaseFailureInsteadOfSchedulingStorageRetry()
    {
        WorkflowAttachmentStorage retryStorage = mock(WorkflowAttachmentStorage.class);
        WorkflowAttachmentService retryService = new WorkflowAttachmentService(
                attachmentMapper, retryStorage, properties, identityResolver,
                processAccessService);
        WfAttachment candidate = attachment(ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.EXPIRED,
                LocalDateTime.now().minusMinutes(1), null);
        DataAccessResourceFailureException databaseFailure =
                new DataAccessResourceFailureException("forced database failure");
        when(attachmentMapper.selectCleanupCandidates(properties.getCleanupBatchSize()))
                .thenReturn(List.of(candidate));
        when(retryStorage.delete(candidate.storageKey())).thenReturn(true);
        when(attachmentMapper.markStorageDeleted(ATTACHMENT_ID))
                .thenThrow(databaseFailure);

        assertThatThrownBy(retryService::cleanupExpiredBatch).isSameAs(databaseFailure);

        verify(attachmentMapper, never()).scheduleCleanupRetry(anyString(), anyInt(),
                any(LocalDateTime.class), anyString());
    }

    /**
     * 验证手工删除与调度清理并发时，0 行更新只有在正式行已完成清理后才按幂等成功处理。
     * @return void，并发完成结果被误报为 500 或重复产生状态副作用时测试失败
     */
    @Test
    void acceptsConcurrentStorageCleanupAfterReloadingTerminalState()
    {
        WfAttachment pending = attachment(ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.DELETED, LocalDateTime.now().minusMinutes(1), null);
        WfAttachment completed = withStorageDeletedTime(pending, LocalDateTime.now());
        when(attachmentMapper.selectById(ATTACHMENT_ID)).thenReturn(pending, completed);
        when(attachmentMapper.markStorageDeleted(ATTACHMENT_ID)).thenReturn(0);

        service.deleteOwnedTemporary(ATTACHMENT_ID);

        verify(storage).delete(pending.storageKey());
        verify(attachmentMapper).markStorageDeleted(ATTACHMENT_ID);
        verify(attachmentMapper, times(2)).selectById(ATTACHMENT_ID);
    }

    /**
     * 验证清理完成标记异常返回 0 且正式行未完成时仍返回真实 500，不能伪装并发成功。
     * @return void，异常数据库结果被吞掉时测试失败
     */
    @Test
    void rejectsUnconfirmedZeroRowStorageCleanup()
    {
        WfAttachment pending = attachment(ATTACHMENT_ID, 7L, "files",
                WorkflowAttachmentStatus.TEMP, LocalDateTime.now().plusMinutes(1), null);
        when(attachmentMapper.selectById(ATTACHMENT_ID)).thenReturn(pending);
        when(attachmentMapper.markDeletedByOwner(ATTACHMENT_ID, 7L)).thenReturn(1);
        when(attachmentMapper.markStorageDeleted(ATTACHMENT_ID)).thenReturn(0);

        assertServiceError(() -> service.deleteOwnedTemporary(ATTACHMENT_ID),
                HttpStatus.ERROR, "工作流附件清理状态写入失败");
        verify(attachmentMapper, times(2)).selectById(ATTACHMENT_ID);
    }

    /**
     * 配置单个待绑定附件并断言稳定业务失败。
     * @param attachment WfAttachment，待返回的锁定附件
     * @param expectedCode int，预期 HTTP 业务码
     * @param expectedMessage String，预期业务提示
     * @return void，异常契约不符合时测试失败
     */
    private void assertPrepareError(WfAttachment attachment, int expectedCode,
            String expectedMessage)
    {
        when(attachmentMapper.selectByIdsForUpdate(List.of(ATTACHMENT_ID)))
                .thenReturn(List.of(attachment));
        assertServiceError(() -> service.prepareStartVariables("7",
                Map.of("files", List.of(ATTACHMENT_ID)),
                Map.of("files", List.of(ATTACHMENT_ID))),
                expectedCode, expectedMessage);
    }

    /**
     * 配置单个任务附件引用并断言稳定业务失败。
     * @param attachment WfAttachment，待返回的锁定附件
     * @param expectedCode int，预期 HTTP 业务码
     * @param expectedMessage String，预期业务提示
     * @return void，异常契约不符合时测试失败
     */
    private void assertTaskPrepareError(WfAttachment attachment, int expectedCode,
            String expectedMessage)
    {
        when(attachmentMapper.selectByIdsForUpdate(List.of(ATTACHMENT_ID)))
                .thenReturn(List.of(attachment));
        assertServiceError(() -> service.prepareTaskVariables("7", "instance-42",
                Map.of("files", List.of(ATTACHMENT_ID)),
                Map.of("files", List.of(ATTACHMENT_ID))),
                expectedCode, expectedMessage);
    }

    /**
     * 创建指定归属和状态的附件元数据测试对象。
     * @param attachmentId String，附件 UUID
     * @param ownerUserId Long，所有者主键
     * @param fieldName String，表单字段名
     * @param status WorkflowAttachmentStatus，附件状态
     * @param expireTime LocalDateTime，临时失效时间
     * @param processInstanceId String，可为空的绑定实例主键
     * @return WfAttachment，完整测试元数据
     */
    private WfAttachment attachment(String attachmentId, Long ownerUserId, String fieldName,
            WorkflowAttachmentStatus status, LocalDateTime expireTime,
            String processInstanceId)
    {
        return attachment(attachmentId, ownerUserId, fieldName, status, expireTime,
                processInstanceId, null,
                status == WorkflowAttachmentStatus.BOUND ? "start" : null);
    }

    /**
     * 创建带首次任务和节点归属的附件元数据测试对象。
     * @param attachmentId String，附件 UUID
     * @param ownerUserId Long，原上传者主键
     * @param fieldName String，表单字段名
     * @param status WorkflowAttachmentStatus，附件状态
     * @param expireTime LocalDateTime，临时失效时间
     * @param processInstanceId String，可为空的绑定实例主键
     * @param taskId String，可为空的首次提交任务主键
     * @param nodeKey String，可为空的首次提交节点 key
     * @return WfAttachment，完整测试元数据
     */
    private WfAttachment attachment(String attachmentId, Long ownerUserId, String fieldName,
            WorkflowAttachmentStatus status, LocalDateTime expireTime,
            String processInstanceId, String taskId, String nodeKey)
    {
        LocalDateTime now = LocalDateTime.now();
        return new WfAttachment(attachmentId, ownerUserId, fieldName, "invoice.pdf",
                "2026/07/26/0123456789abcdef0123456789abcdef.pdf",
                "application/pdf", 128L, "a".repeat(64), status, expireTime,
                processInstanceId, taskId, nodeKey,
                status == WorkflowAttachmentStatus.BOUND ? now : null,
                null, 0, null, null, now.minusMinutes(1), null);
    }

    /**
     * 复制附件正式行并写入物理清理完成时间，用于构造并发清理后的数据库快照。
     * @param attachment WfAttachment，待复制的终态附件
     * @param storageDeletedTime LocalDateTime，另一事务已提交的物理清理完成时间
     * @return WfAttachment，其他业务字段保持不变且已完成物理清理的正式行
     */
    private WfAttachment withStorageDeletedTime(WfAttachment attachment,
            LocalDateTime storageDeletedTime)
    {
        return new WfAttachment(attachment.attachmentId(), attachment.ownerUserId(),
                attachment.fieldName(), attachment.originalName(), attachment.storageKey(),
                attachment.contentType(), attachment.fileSize(), attachment.sha256(),
                attachment.status(), attachment.expireTime(), attachment.processInstanceId(),
                attachment.taskId(), attachment.nodeKey(), attachment.boundTime(),
                storageDeletedTime, attachment.cleanupRetryCount(), null, null,
                attachment.createTime(), storageDeletedTime);
    }

    /**
     * 复制附件正式行并覆盖清理重试次数，用于验证指数退避上限和乐观版本条件。
     * @param attachment WfAttachment，待复制的终态附件
     * @param cleanupRetryCount int，候选快照中已持久化的失败次数
     * @return WfAttachment，其他业务字段保持不变的重试候选
     */
    private WfAttachment withCleanupRetryCount(WfAttachment attachment,
            int cleanupRetryCount)
    {
        return new WfAttachment(attachment.attachmentId(), attachment.ownerUserId(),
                attachment.fieldName(), attachment.originalName(), attachment.storageKey(),
                attachment.contentType(), attachment.fileSize(), attachment.sha256(),
                attachment.status(), attachment.expireTime(), attachment.processInstanceId(),
                attachment.taskId(), attachment.nodeKey(), attachment.boundTime(),
                attachment.storageDeletedTime(), cleanupRetryCount,
                attachment.cleanupNextRetryTime(), attachment.cleanupLastErrorCode(),
                attachment.createTime(), attachment.updateTime());
    }

    /**
     * 断言附件领域异常包含稳定 HTTP 语义和用户提示。
     * @param action ThrowingCallable，预期失败的领域操作
     * @param expectedCode int，预期 HTTP 业务码
     * @param expectedMessage String，预期业务提示
     * @return void，异常契约不符合时测试失败
     */
    private void assertServiceError(ThrowingCallable action, int expectedCode,
            String expectedMessage)
    {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, exception ->
        {
            assertThat(exception.getCode()).isEqualTo(expectedCode);
            assertThat(exception.getMessage()).isEqualTo(expectedMessage);
        });
    }
}
