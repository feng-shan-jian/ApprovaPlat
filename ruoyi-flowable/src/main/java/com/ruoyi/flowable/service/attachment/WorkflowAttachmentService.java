package com.ruoyi.flowable.service.attachment;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.authorization.WorkflowProcessAccessService;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WorkflowAttachmentQuotaUsage;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.domain.vo.WorkflowAttachmentView;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.identity.WorkflowIdentityResolver;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;

/**
 * 工作流附件领域服务，统一处理临时归属、对象授权、流程绑定和可重试物理清理。
 */
@Service
public class WorkflowAttachmentService
{
    /** 单次表单提交允许引用的附件总数，防止跨字段放大查询和变量正文。 */
    static final int MAX_FORM_ATTACHMENTS = 100;

    /** 表单字段名与流程变量白名单使用同一稳定 ASCII 标识约束。 */
    private static final Pattern FIELD_NAME_PATTERN = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]{0,127}");

    /** UUID 文本必须使用服务端生成的规范小写连字符格式。 */
    private static final Pattern ATTACHMENT_ID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    /** 物理文件或清理元数据处理失败时落库的固定脱敏错误码。 */
    private static final String CLEANUP_FAILURE_ERROR_CODE =
            "attachment_storage_cleanup_failed";

    private static final Logger log = LoggerFactory.getLogger(WorkflowAttachmentService.class);

    private final WfAttachmentMapper attachmentMapper;
    private final WorkflowAttachmentStorage storage;
    private final WorkflowAttachmentProperties properties;
    private final WorkflowIdentityResolver identityResolver;
    private final WorkflowProcessAccessService processAccessService;
    private final ObjectMapper objectMapper;

    /**
     * 创建工作流附件领域服务。
     *
     * @param attachmentMapper WfAttachmentMapper，正式附件元数据 Mapper
     * @param storage WorkflowAttachmentStorage，私有文件存储边界
     * @param properties WorkflowAttachmentProperties，单文件、用户临时配额、有效期和清理批次配置
     * @param identityResolver WorkflowIdentityResolver，当前有效登录用户解析器
     * @param processAccessService WorkflowProcessAccessService，绑定附件的实例级读取授权
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowAttachmentService(WfAttachmentMapper attachmentMapper,
            WorkflowAttachmentStorage storage, WorkflowAttachmentProperties properties,
            WorkflowIdentityResolver identityResolver,
            WorkflowProcessAccessService processAccessService)
    {
        this.attachmentMapper = attachmentMapper;
        this.storage = storage;
        this.properties = properties;
        // 配置绑定在依赖 Bean 创建前完成，此处拒绝跨字段关系非法的退避参数。
        this.properties.validateCleanupRetryBackoff();
        this.identityResolver = identityResolver;
        this.processAccessService = processAccessService;
        this.objectMapper = JsonMapper.shared();
    }

    /**
     * 为当前认证用户写入临时附件，数据库失败或事务回滚时补偿删除私有文件。
     *
     * @param fieldName String，当前 el-upload 组件的表单字段名
     * @param file MultipartFile，客户端上传文件；客户端 URL 和路径不会参与存储
     * @return WorkflowAttachmentView，不包含内部存储键的安全附件元数据
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public WorkflowAttachmentView uploadTemporary(String fieldName, MultipartFile file)
    {
        String normalizedFieldName = requireFieldName(fieldName);
        Long ownerUserId = currentUserId();
        long declaredFileSize = requireDeclaredFileSize(file);

        // 身份解析会在锁前查询用户主数据；READ_COMMITTED 保证等待同用户 guard 后读取已提交占用。
        WorkflowAttachmentQuotaUsage quotaUsage = lockAndLoadQuotaUsage(ownerUserId);
        requireTemporaryQuota(quotaUsage, declaredFileSize);
        requireDiskReservation(declaredFileSize);
        StoredAttachmentFile stored = storage.store(file);
        registerRollbackFileCompensation(stored.storageKey());
        try
        {
            // 以服务端实际读取值复核数据库容量，并确认落盘后仍满足磁盘低水位。
            // 任一步失败都进入同一补偿分支，避免无事务代理的直接调用遗留孤儿文件。
            requireTemporaryQuota(quotaUsage, stored.fileSize());
            requireDiskLowWatermark();

            LocalDateTime now = LocalDateTime.now();
            WfAttachment attachment = new WfAttachment(
                    UUID.randomUUID().toString(), ownerUserId, normalizedFieldName,
                    stored.originalName(), stored.storageKey(), stored.contentType(),
                    stored.fileSize(), stored.sha256(), WorkflowAttachmentStatus.TEMP,
                    now.plus(properties.getTemporaryTtl()), null, null, null, null, null, null,
                    0, null, null, null, null, now, null);
            if (attachmentMapper.insert(attachment) != 1)
            {
                throw new ServiceException("工作流附件元数据写入失败", HttpStatus.ERROR);
            }
            return toView(attachment);
        }
        catch (RuntimeException failure)
        {
            deleteCompensationFile(stored.storageKey(), failure);
            throw failure;
        }
    }

    /**
     * 校验 multipart 容器报告的服务端已接收文件大小，避免超配额请求先落入私有目录。
     *
     * @param file MultipartFile，Spring 已解析的上传文件
     * @return long，正数且不超过单文件配置的已接收字节数
     */
    private long requireDeclaredFileSize(MultipartFile file)
    {
        if (file == null)
        {
            throw new ServiceException("上传附件不能为空", HttpStatus.BAD_REQUEST);
        }
        long declaredFileSize = file.getSize();
        if (declaredFileSize <= 0L)
        {
            throw new ServiceException("上传附件不能为空文件", HttpStatus.BAD_REQUEST);
        }
        if (declaredFileSize > properties.getMaxSize())
        {
            throw new ServiceException("上传附件大小不能超过"
                    + properties.getMaxSize() + "字节", HttpStatus.BAD_REQUEST);
        }
        return declaredFileSize;
    }

    /**
     * 幂等创建并锁定当前用户 guard，再读取该用户仍占用私有存储的临时附件。
     * 不同用户使用不同主键，因此可以并行上传；同一用户仍由行锁阻止配额超卖。
     *
     * @param ownerUserId Long，事务内核验的当前用户主键
     * @return WorkflowAttachmentQuotaUsage，同用户行锁内的当前临时附件占用
     */
    private WorkflowAttachmentQuotaUsage lockAndLoadQuotaUsage(Long ownerUserId)
    {
        // 重复键更新会直接持有同一 guard 行的排他锁，首次创建竞争也按用户串行，避免 INSERT IGNORE 锁升级死锁。
        attachmentMapper.ensureOwnerQuotaGuard(ownerUserId);
        Long lockedOwnerUserId = attachmentMapper.selectOwnerQuotaGuardForUpdate(ownerUserId);
        if (!ownerUserId.equals(lockedOwnerUserId))
        {
            throw new ServiceException("工作流附件配额锁数据异常", HttpStatus.ERROR);
        }
        WorkflowAttachmentQuotaUsage usage = attachmentMapper
                .selectTemporaryQuotaUsage(ownerUserId);
        if (usage == null)
        {
            throw new ServiceException("工作流附件配额统计异常", HttpStatus.ERROR);
        }
        return usage;
    }

    /**
     * 在数据库 guard 行锁内校验新增文件不会突破用户 TEMP 数量和累计字节上限。
     *
     * @param usage WorkflowAttachmentQuotaUsage，当前仍占用私有临时存储的聚合值
     * @param incomingBytes long，本次待落盘或已实际写入的文件字节数
     * @return void，超过任一配额时抛出稳定 409 业务异常
     */
    private void requireTemporaryQuota(WorkflowAttachmentQuotaUsage usage,
            long incomingBytes)
    {
        long maxTemporaryBytes = properties.getMaxTemporaryBytes();
        boolean countExceeded = usage.temporaryCount() >= properties.getMaxTemporaryCount();
        boolean bytesExceeded = usage.temporaryBytes() > maxTemporaryBytes
                || incomingBytes > maxTemporaryBytes - usage.temporaryBytes();
        if (countExceeded || bytesExceeded)
        {
            throw new ServiceException("工作流临时附件数量或总大小已达到上限",
                    HttpStatus.CONFLICT);
        }
    }

    /**
     * 在落盘前核对文件系统可用空间；不同用户不再因全局数据库锁而串行。
     *
     * @param incomingBytes long，multipart 容器报告的已接收文件字节数
     * @return void，当前可用空间无法同时容纳文件和低水位时抛出 507
     */
    private void requireDiskReservation(long incomingBytes)
    {
        long usableBytes = storage.usableSpace();
        long minFreeBytes = properties.getMinFreeBytes();
        if (usableBytes < minFreeBytes || incomingBytes > usableBytes - minFreeBytes)
        {
            throw new ServiceException("工作流附件存储空间不足",
                    HttpStatus.INSUFFICIENT_STORAGE);
        }
    }

    /**
     * 文件真实落盘后复核磁盘低水位，处理声明大小偏差和非本服务磁盘写入竞争。
     *
     * @return void，可用空间低于配置低水位时抛出 507 并触发事务文件补偿
     */
    private void requireDiskLowWatermark()
    {
        if (storage.usableSpace() < properties.getMinFreeBytes())
        {
            throw new ServiceException("工作流附件存储空间不足",
                    HttpStatus.INSUFFICIENT_STORAGE);
        }
    }

    /**
     * 查询当前用户可读取的附件安全元数据。
     *
     * @param attachmentId String，服务端生成的附件 UUID
     * @return WorkflowAttachmentView，完成对象授权后的安全元数据
     */
    @Transactional(readOnly = true)
    public WorkflowAttachmentView getReadableMetadata(String attachmentId)
    {
        WfAttachment attachment = requireReadableAttachment(attachmentId);
        return toView(attachment);
    }

    /**
     * 获取当前用户可下载的私有文件流，并在同一打开通道核对大小和正式摘要。
     *
     * @param attachmentId String，服务端生成的附件 UUID
     * @return WorkflowAttachmentDownload，仅供 Controller 构造受控下载响应
     */
    @Transactional(readOnly = true)
    public WorkflowAttachmentDownload openReadableDownload(String attachmentId)
    {
        WfAttachment attachment = requireReadableAttachment(attachmentId);
        InputStream content = storage.openVerifiedForRead(attachment.storageKey(),
                attachment.fileSize(), attachment.sha256());
        return new WorkflowAttachmentDownload(content, attachment.originalName(),
                attachment.contentType(), attachment.fileSize(), attachment.sha256());
    }

    /**
     * 仅允许当前所有者删除仍未绑定的临时附件，状态先提交再执行可重试物理清理。
     *
     * @param attachmentId String，服务端生成的附件 UUID
     * @return void，越权或状态竞争时抛出稳定业务异常
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deleteOwnedTemporary(String attachmentId)
    {
        String normalizedId = requireAttachmentId(attachmentId);
        Long ownerUserId = currentUserId();
        WfAttachment attachment = attachmentMapper.selectById(normalizedId);
        if (attachment == null)
        {
            throw notFound();
        }
        if (!ownerUserId.equals(attachment.ownerUserId()))
        {
            throw forbidden();
        }
        if (attachment.status() == WorkflowAttachmentStatus.DELETED
                && attachment.storageDeletedTime() != null)
        {
            // 客户端未收到首次成功响应时允许安全重试，不把已完成删除降级为 409。
            return;
        }
        if (attachment.status() == WorkflowAttachmentStatus.DELETED)
        {
            cleanupOwnedDeletedAttachment(normalizedId);
            return;
        }
        if (attachment.status() != WorkflowAttachmentStatus.TEMP)
        {
            throw stateConflict();
        }

        // 条件更新与流程绑定竞争，只有先完成状态迁移的一方可以继续产生副作用。
        if (attachmentMapper.markDeletedByOwner(normalizedId, ownerUserId) != 1)
        {
            throw stateConflict();
        }
        cleanupOwnedDeletedAttachment(normalizedId);
    }

    /**
     * 为已逻辑删除的本人附件领取单行租约，并在事务外完成对象删除和按令牌回写。
     * 其他节点已经持有有效租约时直接返回，逻辑删除结果仍保持成功且由调度器继续处理。
     *
     * @param attachmentId String，已迁移为 DELETED 的附件 UUID
     * @return void，物理删除失败时已持久化重试并继续抛出存储异常
     */
    private void cleanupOwnedDeletedAttachment(String attachmentId)
    {
        String claimToken = UUID.randomUUID().toString();
        if (attachmentMapper.claimDeletedAttachment(attachmentId, claimToken,
                cleanupLeaseSeconds()) != 1)
        {
            return;
        }
        WfAttachment claimed = attachmentMapper.selectById(attachmentId);
        if (claimed == null || !claimToken.equals(claimed.cleanupClaimToken())
                || claimed.status() != WorkflowAttachmentStatus.DELETED)
        {
            throw new ServiceException("工作流附件清理领取结果异常", HttpStatus.ERROR);
        }
        try
        {
            deleteClaimedStorage(claimed);
            completeClaimedCleanup(claimed);
        }
        catch (WorkflowAttachmentStorageOperationException failure)
        {
            try
            {
                persistCleanupRetry(claimed);
            }
            catch (RuntimeException retryFailure)
            {
                failure.addSuppressed(retryFailure);
            }
            throw failure;
        }
    }

    /**
     * 在任务完成事务中锁定附件并生成安全变量投影，允许复用同实例同字段的 BOUND 附件。
     *
     * @param actorUserId String，WorkflowEngineOperations 事务内核验的当前办理人 ID
     * @param processInstanceId String，当前活动任务所属真实流程实例主键
     * @param normalizedVariables Map&lt;String, Object&gt;，部署任务表单 schema 已规范化的变量
     * @param attachmentIdsByField Map&lt;String, List&lt;String&gt;&gt;，上传字段到附件 UUID 的白名单映射
     * @return Map&lt;String, Object&gt;，上传字段已替换为安全 JSON 数组的任务完成变量
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> prepareTaskVariables(String actorUserId,
            String processInstanceId, Map<String, Object> normalizedVariables,
            Map<String, List<String>> attachmentIdsByField)
    {
        Long actorId = requireNumericUserId(actorUserId);
        String normalizedInstanceId = requireEngineId(processInstanceId);
        return prepareTaskReferencedVariables(actorId, normalizedInstanceId,
                normalizedVariables, attachmentIdsByField);
    }

    /**
     * 在草稿写事务中对账当前引用，将新增 TEMP 附件绑定草稿并把移除项转入清理终态。
     *
     * @param actorUserId String，事务内重新核验的草稿所有者 ID
     * @param draftId String，服务端生成的草稿 UUID
     * @param attachmentIdsByField Map&lt;String,List&lt;String&gt;&gt;，表单字段到附件 UUID 的引用
     * @return void，任一归属、字段、状态或文件完整性异常时整体回滚
     */
    @Transactional(rollbackFor = Exception.class)
    public void reconcileDraftAttachments(String actorUserId, String draftId,
            Map<String, List<String>> attachmentIdsByField)
    {
        Long ownerUserId = requireNumericUserId(actorUserId);
        String normalizedDraftId = requireAttachmentId(draftId);
        Map<String, List<String>> references = checkedReferences(attachmentIdsByField);
        Set<String> requestedIds = new LinkedHashSet<>(flattenUniqueIds(references));

        // 草稿行已经由上层锁定；先锁当前关联，再锁请求引用，保证附件状态对账具有确定顺序。
        List<WfAttachment> currentRows = attachmentMapper.selectByDraftIdForUpdate(
                normalizedDraftId, ownerUserId);
        List<WfAttachment> requestedRows = requestedIds.isEmpty() ? List.of()
                : attachmentMapper.selectByIdsForUpdate(requestedIds);
        Map<String, WfAttachment> requestedById = indexLockedRows(requestedRows);
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, List<String>> fieldEntry : references.entrySet())
        {
            for (String attachmentId : fieldEntry.getValue())
            {
                WfAttachment attachment = requestedById.get(attachmentId);
                assertDraftAttachmentReference(attachment, ownerUserId, normalizedDraftId,
                        fieldEntry.getKey(), now);
                verifyStoredAttachment(attachment);
                if (attachment.status() == WorkflowAttachmentStatus.TEMP
                        && attachmentMapper.bindDraftAttachment(attachmentId, ownerUserId,
                                fieldEntry.getKey(), normalizedDraftId) != 1)
                {
                    throw stateConflict();
                }
            }
        }
        for (WfAttachment current : currentRows)
        {
            if (!requestedIds.contains(current.attachmentId())
                    && attachmentMapper.markDraftAttachmentDeleted(current.attachmentId(),
                            ownerUserId, normalizedDraftId) != 1)
            {
                throw stateConflict();
            }
        }
    }

    /**
     * 正式提交草稿时一次锁定现有与新增附件，并用同一批结果完成对账、校验和安全投影。
     *
     * @param actorUserId String，外层写事务已经核验的草稿所有者 ID
     * @param draftId String，已经锁定主行的草稿 UUID
     * @param normalizedVariables Map&lt;String,Object&gt;，唯一一次正式 schema 校验后的字段值
     * @param attachmentIdsByField Map&lt;String,List&lt;String&gt;&gt;，上传字段到附件 UUID 的受控映射
     * @return Map&lt;String,Object&gt;，完成 TEMP 到 DRAFT 对账且附件字段已安全投影的引擎变量
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> prepareDraftSubmissionVariables(String actorUserId,
            String draftId, Map<String, Object> normalizedVariables,
            Map<String, List<String>> attachmentIdsByField)
    {
        Long ownerUserId = requireNumericUserId(actorUserId);
        String normalizedDraftId = requireAttachmentId(draftId);
        Map<String, List<String>> references = checkedReferences(attachmentIdsByField);
        LinkedHashSet<String> requestedIds = new LinkedHashSet<>(flattenUniqueIds(references));

        // 先锁当前 DRAFT 集合，再只锁本次新增 UUID；同一附件不会被第二个批量查询重复取得。
        List<WfAttachment> currentRows = attachmentMapper.selectByDraftIdForUpdate(
                normalizedDraftId, ownerUserId);
        Map<String, WfAttachment> lockedById = new LinkedHashMap<>(
                indexLockedRows(currentRows));
        LinkedHashSet<String> newIds = new LinkedHashSet<>(requestedIds);
        newIds.removeAll(lockedById.keySet());
        if (!newIds.isEmpty())
        {
            Map<String, WfAttachment> newRows = indexLockedRows(
                    attachmentMapper.selectByIdsForUpdate(newIds));
            lockedById.putAll(newRows);
        }

        LinkedHashMap<String, Object> projected = new LinkedHashMap<>(normalizedVariables);
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, List<String>> fieldEntry : references.entrySet())
        {
            ArrayNode safeAttachments = objectMapper.createArrayNode();
            for (String attachmentId : fieldEntry.getValue())
            {
                WfAttachment attachment = lockedById.get(attachmentId);
                assertDraftAttachmentReference(attachment, ownerUserId, normalizedDraftId,
                        fieldEntry.getKey(), now);
                verifyStoredAttachment(attachment);
                if (attachment.status() == WorkflowAttachmentStatus.TEMP
                        && attachmentMapper.bindDraftAttachment(attachmentId, ownerUserId,
                                fieldEntry.getKey(), normalizedDraftId) != 1)
                {
                    throw stateConflict();
                }
                safeAttachments.add(toSafeVariableProjection(attachment));
            }
            projected.put(fieldEntry.getKey(), safeAttachments);
        }
        for (WfAttachment current : currentRows)
        {
            if (!requestedIds.contains(current.attachmentId())
                    && attachmentMapper.markDraftAttachmentDeleted(current.attachmentId(),
                            ownerUserId, normalizedDraftId) != 1)
            {
                throw stateConflict();
            }
        }
        return Collections.unmodifiableMap(projected);
    }

    /**
     * 在同一发起事务中把草稿附件迁移到刚创建的真实流程实例。
     *
     * @param actorUserId String，事务内重新核验的草稿所有者 ID
     * @param draftId String，草稿 UUID
     * @param processInstanceId String，RuntimeService 创建的实例主键
     * @param nodeKey String，开始节点 key
     * @param attachmentIdsByField Map&lt;String,List&lt;String&gt;&gt;，上传字段引用
     * @return void，任一条件更新失败时抛错并回滚实例、草稿和全部附件
     */
    @Transactional(rollbackFor = Exception.class)
    public void bindDraftStartAttachments(String actorUserId, String draftId,
            String processInstanceId, String nodeKey,
            Map<String, List<String>> attachmentIdsByField)
    {
        Long ownerUserId = requireNumericUserId(actorUserId);
        String normalizedDraftId = requireAttachmentId(draftId);
        String normalizedInstanceId = requireEngineId(processInstanceId);
        String normalizedNodeKey = requireNodeKey(nodeKey);
        Map<String, List<String>> references = checkedReferences(attachmentIdsByField);
        for (Map.Entry<String, List<String>> fieldEntry : references.entrySet())
        {
            for (String attachmentId : fieldEntry.getValue())
            {
                if (attachmentMapper.bindDraftStartAttachment(attachmentId, ownerUserId,
                        fieldEntry.getKey(), normalizedDraftId, normalizedInstanceId,
                        normalizedNodeKey) != 1)
                {
                    throw stateConflict();
                }
            }
        }
    }

    /**
     * 将草稿当前全部附件转为 DELETED，物理文件继续由现有可重试清理器处理。
     *
     * @param actorUserId String，事务内重新核验的草稿所有者 ID
     * @param draftId String，草稿 UUID
     * @return void，附件状态竞争时回滚草稿删除
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDraftAttachments(String actorUserId, String draftId)
    {
        Long ownerUserId = requireNumericUserId(actorUserId);
        String normalizedDraftId = requireAttachmentId(draftId);
        List<WfAttachment> rows = attachmentMapper.selectByDraftIdForUpdate(
                normalizedDraftId, ownerUserId);
        for (WfAttachment attachment : rows)
        {
            if (attachmentMapper.markDraftAttachmentDeleted(attachment.attachmentId(),
                    ownerUserId, normalizedDraftId) != 1)
            {
                throw stateConflict();
            }
        }
    }

    /**
     * 在同一任务完成事务中绑定新 TEMP 附件，并跳过已验证的同实例同字段 BOUND 引用。
     *
     * @param actorUserId String，WorkflowEngineOperations 事务内核验的当前办理人 ID
     * @param processInstanceId String，当前任务所属真实流程实例主键
     * @param taskId String，待完成的真实 Flowable 任务主键
     * @param nodeKey String，待完成任务的 BPMN 节点 key
     * @param attachmentIdsByField Map&lt;String, List&lt;String&gt;&gt;，已通过部署表单校验的附件引用
     * @return void，任一新附件绑定失败时抛错并回滚意见、变量、任务和前序附件更新
     */
    @Transactional(rollbackFor = Exception.class)
    public void bindTaskAttachments(String actorUserId, String processInstanceId,
            String taskId, String nodeKey,
            Map<String, List<String>> attachmentIdsByField)
    {
        Long actorId = requireNumericUserId(actorUserId);
        String normalizedInstanceId = requireEngineId(processInstanceId);
        String normalizedTaskId = requireTaskId(taskId);
        String normalizedNodeKey = requireNodeKey(nodeKey);
        Map<String, List<String>> references = checkedReferences(attachmentIdsByField);
        if (references.isEmpty())
        {
            return;
        }

        List<WfAttachment> lockedRows = attachmentMapper.selectByIdsForUpdate(
                flattenUniqueIds(references));
        Map<String, WfAttachment> attachmentsById = indexLockedRows(lockedRows);
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, List<String>> fieldEntry : references.entrySet())
        {
            String fieldName = fieldEntry.getKey();
            for (String attachmentId : fieldEntry.getValue())
            {
                WfAttachment attachment = attachmentsById.get(attachmentId);
                assertTaskAttachmentReference(attachment, actorId,
                        normalizedInstanceId, fieldName, now);
                verifyStoredAttachment(attachment);
                if (attachment.status() == WorkflowAttachmentStatus.BOUND)
                {
                    // 已绑定引用只参与本次变量投影，不能覆盖其首次提交任务和节点审计归属。
                    continue;
                }
                if (attachmentMapper.bindTaskAttachment(attachmentId, actorId,
                        fieldName, normalizedInstanceId, normalizedTaskId,
                        normalizedNodeKey) != 1)
                {
                    throw stateConflict();
                }
            }
        }
    }

    /**
     * 在附件状态迁移前核对物理文件大小和 SHA-256，失败时由外层事务整体回滚。
     *
     * @param attachment WfAttachment，已完成行锁和对象归属校验的正式元数据
     * @return void，文件缺失或被篡改时抛出稳定业务异常
     */
    private void verifyStoredAttachment(WfAttachment attachment)
    {
        storage.verify(attachment.storageKey(), attachment.fileSize(), attachment.sha256());
    }

    /**
     * 在短事务中锁定并领取一批到期附件；到期 TEMP 会在同一更新中迁移为 EXPIRED。
     * 候选查询使用 SKIP LOCKED，其他节点已锁定或仍持有有效租约的行不会阻塞本批次。
     *
     * @param claimToken String，协调器生成的规范 UUID 批次令牌
     * @return List&lt;WfAttachment&gt;，已写入相同 token/lease 的正式候选快照
     */
    List<WfAttachment> claimCleanupBatch(String claimToken)
    {
        String normalizedToken = requireCleanupClaimToken(claimToken);
        List<WfAttachment> candidates = attachmentMapper
                .selectCleanupCandidatesForUpdate(properties.getCleanupBatchSize());
        if (candidates == null || candidates.isEmpty())
        {
            return List.of();
        }
        List<String> attachmentIds = candidates.stream()
                .map(WfAttachment::attachmentId)
                .toList();
        if (attachmentMapper.claimCleanupCandidates(attachmentIds, normalizedToken,
                cleanupLeaseSeconds()) != attachmentIds.size())
        {
            throw new ServiceException("工作流附件清理批次领取失败", HttpStatus.ERROR);
        }
        List<WfAttachment> claimed = attachmentMapper.selectClaimedByToken(normalizedToken);
        if (claimed == null || claimed.size() != attachmentIds.size()
                || claimed.stream().anyMatch(item ->
                        !normalizedToken.equals(item.cleanupClaimToken())
                        || item.cleanupLeaseUntil() == null
                        || (item.status() != WorkflowAttachmentStatus.EXPIRED
                            && item.status() != WorkflowAttachmentStatus.DELETED)))
        {
            throw new ServiceException("工作流附件清理批次领取结果异常", HttpStatus.ERROR);
        }
        return List.copyOf(claimed);
    }

    /**
     * 在数据库事务外幂等删除已领取附件的私有对象。
     *
     * @param attachment WfAttachment，带有当前批次 token/lease 的领取快照
     * @return void，存储依赖失败时抛出 WorkflowAttachmentStorageOperationException
     */
    void deleteClaimedStorage(WfAttachment attachment)
    {
        requireClaimedCleanupAttachment(attachment);
        storage.delete(attachment.storageKey());
    }

    /**
     * 以候选快照中的重试版本原子调度下一次清理，并兼容手工删除的并发完成结果。
     *
     * @param attachment WfAttachment，本轮清理失败的候选快照
     * @return boolean，仍存在待重试记录时为 true，并发方已完成物理清理时为 false
     */
    boolean persistCleanupRetry(WfAttachment attachment)
    {
        requireClaimedCleanupAttachment(attachment);
        int expectedRetryCount = attachment.cleanupRetryCount();
        if (expectedRetryCount < 0)
        {
            throw new ServiceException("工作流附件清理重试状态数据异常", HttpStatus.ERROR);
        }
        LocalDateTime nextRetryTime = LocalDateTime.now().plus(
                cleanupRetryDelay(expectedRetryCount));
        if (attachmentMapper.scheduleCleanupRetry(attachment.attachmentId(),
                attachment.cleanupClaimToken(), expectedRetryCount, nextRetryTime,
                CLEANUP_FAILURE_ERROR_CODE) == 1)
        {
            return true;
        }

        // 0 行可能是租约过期后被重领或其他节点已完成，旧令牌不得覆盖新所有者状态。
        WfAttachment latest = attachmentMapper.selectById(attachment.attachmentId());
        if (latest != null && latest.storageDeletedTime() != null
                && (latest.status() == WorkflowAttachmentStatus.EXPIRED
                    || latest.status() == WorkflowAttachmentStatus.DELETED))
        {
            return false;
        }
        if (latest != null && !attachment.cleanupClaimToken()
                .equals(latest.cleanupClaimToken()))
        {
            return false;
        }
        throw new ServiceException("工作流附件清理重试状态写入失败", HttpStatus.ERROR);
    }

    /**
     * 按已失败次数计算有上限的二倍指数退避，避免整数溢出和异常配置突破最大值。
     *
     * @param completedRetryCount int，候选行已经持久化的失败重试次数
     * @return Duration，本次失败到下一次候选扫描的等待时长
     */
    private Duration cleanupRetryDelay(int completedRetryCount)
    {
        Duration delay = properties.getCleanupRetryInitialDelay();
        Duration maxDelay = properties.getCleanupRetryMaxDelay();
        for (int doubling = 0; doubling < completedRetryCount
                && delay.compareTo(maxDelay) < 0; doubling++)
        {
            // 两个配置均不超过七天；达到上限后立即终止，循环次数不会受异常大计数放大。
            Duration doubled = delay.multipliedBy(2L);
            delay = doubled.compareTo(maxDelay) >= 0 ? maxDelay : doubled;
        }
        return delay;
    }

    /**
     * 在短事务中仅按领取令牌记录对象删除完成并清空租约和重试状态。
     *
     * @param attachment WfAttachment，已在事务外完成对象删除的领取快照
     * @return boolean，本令牌首次完成返回 true；租约被重领或其他节点已完成返回 false
     */
    boolean completeClaimedCleanup(WfAttachment attachment)
    {
        requireClaimedCleanupAttachment(attachment);
        if (attachmentMapper.markStorageDeleted(attachment.attachmentId(),
                attachment.cleanupClaimToken()) == 1)
        {
            return true;
        }

        // token 不再匹配表示租约已过期并被其他节点重领，旧执行者只能放弃回写。
        WfAttachment latest = attachmentMapper.selectById(attachment.attachmentId());
        if (latest != null && latest.storageDeletedTime() != null
                && (latest.status() == WorkflowAttachmentStatus.EXPIRED
                    || latest.status() == WorkflowAttachmentStatus.DELETED))
        {
            return false;
        }
        if (latest != null && !attachment.cleanupClaimToken()
                .equals(latest.cleanupClaimToken()))
        {
            return false;
        }
        throw new ServiceException("工作流附件清理状态写入失败", HttpStatus.ERROR);
    }

    /**
     * 校验清理候选携带完整且合法的领取状态，禁止无令牌对象删除或状态回写。
     *
     * @param attachment WfAttachment，协调器处理的领取快照
     * @return void，领取状态不完整时抛出稳定服务异常
     */
    private void requireClaimedCleanupAttachment(WfAttachment attachment)
    {
        if (attachment == null
                || !ATTACHMENT_ID_PATTERN.matcher(attachment.attachmentId()).matches()
                || !ATTACHMENT_ID_PATTERN.matcher(
                        String.valueOf(attachment.cleanupClaimToken())).matches()
                || attachment.cleanupLeaseUntil() == null
                || (attachment.status() != WorkflowAttachmentStatus.EXPIRED
                    && attachment.status() != WorkflowAttachmentStatus.DELETED)
                || attachment.storageDeletedTime() != null)
        {
            throw new ServiceException("工作流附件清理领取状态异常", HttpStatus.ERROR);
        }
    }

    /**
     * 校验协调器生成的领取令牌为规范 UUID。
     *
     * @param claimToken String，待持久化的清理批次令牌
     * @return String，原样返回的规范令牌
     */
    private String requireCleanupClaimToken(String claimToken)
    {
        if (!StringUtils.hasText(claimToken)
                || !ATTACHMENT_ID_PATTERN.matcher(claimToken).matches())
        {
            throw new ServiceException("工作流附件清理令牌格式非法", HttpStatus.ERROR);
        }
        return claimToken;
    }

    /**
     * 将已校验的清理租约转换为 MySQL TIMESTAMPADD 使用的正数秒数。
     *
     * @return long，至少一秒且不超过一天的租约秒数
     */
    private long cleanupLeaseSeconds()
    {
        long leaseSeconds = properties.getCleanupLeaseDuration().toSeconds();
        if (leaseSeconds <= 0L)
        {
            throw new ServiceException("工作流附件清理租约配置异常", HttpStatus.ERROR);
        }
        return leaseSeconds;
    }

    /**
     * 查询附件并执行临时所有者或绑定实例的对象级读取授权。
     *
     * @param attachmentId String，待授权附件 UUID
     * @return WfAttachment，授权通过的完整内部元数据
     */
    private WfAttachment requireReadableAttachment(String attachmentId)
    {
        String normalizedId = requireAttachmentId(attachmentId);
        WorkflowCurrentIdentity actor = identityResolver.resolveCurrentIdentity();
        WfAttachment attachment = attachmentMapper.selectById(normalizedId);
        if (attachment == null
                || attachment.status() == WorkflowAttachmentStatus.EXPIRED
                || attachment.status() == WorkflowAttachmentStatus.DELETED)
        {
            throw notFound();
        }
        if (attachment.status() == WorkflowAttachmentStatus.TEMP)
        {
            if (!requireNumericUserId(actor.userId()).equals(attachment.ownerUserId()))
            {
                throw forbidden();
            }
            if (!attachment.expireTime().isAfter(LocalDateTime.now()))
            {
                throw stateConflict();
            }
            return attachment;
        }
        if (attachment.status() == WorkflowAttachmentStatus.DRAFT)
        {
            if (!requireNumericUserId(actor.userId()).equals(attachment.ownerUserId())
                    || !StringUtils.hasText(attachment.draftId())
                    || attachment.processInstanceId() != null || attachment.taskId() != null
                    || attachment.nodeKey() != null || attachment.boundTime() != null
                    || attachment.storageDeletedTime() != null)
            {
                throw forbidden();
            }
            return attachment;
        }
        if (attachment.status() == WorkflowAttachmentStatus.BOUND
                && StringUtils.hasText(attachment.processInstanceId()))
        {
            processAccessService.requireReadableInstance(attachment.processInstanceId());
            return attachment;
        }
        throw new ServiceException("工作流附件状态数据异常", HttpStatus.ERROR);
    }

    /**
     * 锁定任务表单引用附件并生成不含内部路径的变量投影。
     *
     * @param actorUserId Long，事务内核验的当前用户主键
     * @param processInstanceId String，当前任务所属流程实例主键
     * @param normalizedVariables Map&lt;String, Object&gt;，表单 schema 已规范化的变量
     * @param attachmentIdsByField Map&lt;String, List&lt;String&gt;&gt;，上传字段附件 UUID 映射
     * @return Map&lt;String, Object&gt;，附件字段替换为六项安全元数据后的不可变变量
     */
    private Map<String, Object> prepareTaskReferencedVariables(Long actorUserId,
            String processInstanceId, Map<String, Object> normalizedVariables,
            Map<String, List<String>> attachmentIdsByField)
    {
        Map<String, List<String>> references = checkedReferences(attachmentIdsByField);
        if (references.isEmpty())
        {
            return normalizedVariables;
        }

        List<WfAttachment> lockedRows = attachmentMapper.selectByIdsForUpdate(
                flattenUniqueIds(references));
        Map<String, WfAttachment> attachmentsById = indexLockedRows(lockedRows);
        LocalDateTime now = LocalDateTime.now();
        LinkedHashMap<String, Object> projectedVariables = new LinkedHashMap<>(
                normalizedVariables == null ? Map.of() : normalizedVariables);
        for (Map.Entry<String, List<String>> fieldEntry : references.entrySet())
        {
            String fieldName = fieldEntry.getKey();
            ArrayNode safeAttachments = objectMapper.createArrayNode();
            for (String attachmentId : fieldEntry.getValue())
            {
                WfAttachment attachment = attachmentsById.get(attachmentId);
                assertTaskAttachmentReference(attachment, actorUserId,
                        processInstanceId, fieldName, now);
                safeAttachments.add(toSafeVariableProjection(attachment));
            }
            projectedVariables.put(fieldName, safeAttachments);
        }
        return Collections.unmodifiableMap(projectedVariables);
    }

    /**
     * 校验锁定附件可以绑定当前用户和表单字段。
     *
     * @param attachment WfAttachment，按 UUID 查询并锁定的附件；允许为空以识别不存在
     * @param ownerUserId Long，事务内核验的当前用户主键
     * @param fieldName String，部署表单中的上传字段名
     * @param now LocalDateTime，当前服务时间
     * @return void，归属、状态、有效期或字段不一致时抛出业务异常
     */
    private void assertBindableAttachment(WfAttachment attachment, Long ownerUserId,
            String fieldName, LocalDateTime now)
    {
        if (attachment == null)
        {
            throw notFound();
        }
        if (!ownerUserId.equals(attachment.ownerUserId()))
        {
            throw forbidden();
        }
        if (attachment.status() != WorkflowAttachmentStatus.TEMP
                || attachment.storageDeletedTime() != null
                || attachment.expireTime() == null
                || !attachment.expireTime().isAfter(now))
        {
            throw stateConflict();
        }
        if (!fieldName.equals(attachment.fieldName()))
        {
            throw new ServiceException("工作流附件所属表单字段不匹配", HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * 校验草稿保存引用可以是仍有效 TEMP，或已经属于同一草稿的 DRAFT 附件。
     *
     * @param attachment WfAttachment，锁定附件或 null
     * @param ownerUserId Long，草稿所有者
     * @param draftId String，当前草稿 UUID
     * @param fieldName String，开始表单字段名
     * @param now LocalDateTime，本次对账统一时间
     * @return void，越权、字段或状态不一致时抛出稳定业务异常
     */
    private void assertDraftAttachmentReference(WfAttachment attachment, Long ownerUserId,
            String draftId, String fieldName, LocalDateTime now)
    {
        if (attachment == null)
        {
            throw notFound();
        }
        if (!ownerUserId.equals(attachment.ownerUserId()))
        {
            throw forbidden();
        }
        if (!fieldName.equals(attachment.fieldName()))
        {
            throw new ServiceException("工作流附件所属表单字段不匹配", HttpStatus.BAD_REQUEST);
        }
        if (attachment.status() == WorkflowAttachmentStatus.TEMP)
        {
            assertBindableAttachment(attachment, ownerUserId, fieldName, now);
            return;
        }
        assertPersistedDraftAttachment(attachment, ownerUserId, draftId, fieldName);
    }

    /**
     * 校验附件已经完整绑定当前草稿且尚未产生流程对象副作用。
     *
     * @param attachment WfAttachment，锁定附件或 null
     * @param ownerUserId Long，草稿所有者
     * @param draftId String，当前草稿 UUID
     * @param fieldName String，开始表单字段名
     * @return void，关联或状态异常时抛出稳定业务异常
     */
    private void assertPersistedDraftAttachment(WfAttachment attachment, Long ownerUserId,
            String draftId, String fieldName)
    {
        if (attachment == null)
        {
            throw notFound();
        }
        if (!ownerUserId.equals(attachment.ownerUserId()))
        {
            throw forbidden();
        }
        if (!fieldName.equals(attachment.fieldName()))
        {
            throw new ServiceException("工作流附件所属表单字段不匹配", HttpStatus.BAD_REQUEST);
        }
        if (attachment.status() != WorkflowAttachmentStatus.DRAFT
                || !draftId.equals(attachment.draftId())
                || attachment.expireTime() == null || attachment.processInstanceId() != null
                || attachment.taskId() != null || attachment.nodeKey() != null
                || attachment.boundTime() != null || attachment.storageDeletedTime() != null)
        {
            throw stateConflict();
        }
    }

    /**
     * 校验任务表单附件引用：TEMP 必须属于办理人，BOUND 必须属于同实例同字段。
     *
     * @param attachment WfAttachment，按 UUID 锁定的附件；允许为空以识别不存在
     * @param actorUserId Long，事务内核验的当前办理人主键
     * @param processInstanceId String，当前任务所属真实流程实例主键
     * @param fieldName String，部署任务表单中的上传字段名
     * @param now LocalDateTime，本次校验使用的统一服务时间
     * @return void，归属、状态、有效期、实例或字段不一致时抛出稳定业务异常
     */
    private void assertTaskAttachmentReference(WfAttachment attachment, Long actorUserId,
            String processInstanceId, String fieldName, LocalDateTime now)
    {
        if (attachment == null)
        {
            throw notFound();
        }
        if (attachment.status() == WorkflowAttachmentStatus.TEMP)
        {
            assertBindableAttachment(attachment, actorUserId, fieldName, now);
            return;
        }
        if (attachment.status() == WorkflowAttachmentStatus.EXPIRED
                || attachment.status() == WorkflowAttachmentStatus.DELETED)
        {
            throw notFound();
        }
        if (attachment.status() != WorkflowAttachmentStatus.BOUND)
        {
            throw stateConflict();
        }
        if (!fieldName.equals(attachment.fieldName()))
        {
            throw new ServiceException("工作流附件所属表单字段不匹配",
                    HttpStatus.BAD_REQUEST);
        }
        if (!processInstanceId.equals(attachment.processInstanceId()))
        {
            // BOUND 附件不再以原上传者授权，必须以流程实例归属阻断跨实例引用。
            throw forbidden();
        }
        if (!StringUtils.hasText(attachment.nodeKey())
                || attachment.boundTime() == null
                || attachment.storageDeletedTime() != null)
        {
            throw new ServiceException("工作流附件状态数据异常", HttpStatus.ERROR);
        }
    }

    /**
     * 将完整附件元数据缩减为可进入 Flowable JSON 变量的安全字段集合。
     *
     * @param attachment WfAttachment，已完成锁定和业务校验的临时附件
     * @return ObjectNode，不包含存储键、所有者或可绕过授权 URL 的 JSON 对象
     */
    private ObjectNode toSafeVariableProjection(WfAttachment attachment)
    {
        ObjectNode projection = objectMapper.createObjectNode();
        projection.put("attachmentId", attachment.attachmentId());
        projection.put("fieldName", attachment.fieldName());
        projection.put("originalName", attachment.originalName());
        projection.put("contentType", attachment.contentType());
        projection.put("fileSize", attachment.fileSize());
        projection.put("sha256", attachment.sha256());
        return projection;
    }

    /**
     * 将完整内部附件转换为不暴露私有存储定位的 API 视图。
     *
     * @param attachment WfAttachment，已完成业务授权的附件
     * @return WorkflowAttachmentView，安全对外元数据
     */
    private WorkflowAttachmentView toView(WfAttachment attachment)
    {
        return new WorkflowAttachmentView(attachment.attachmentId(), attachment.fieldName(),
                attachment.originalName(), attachment.contentType(), attachment.fileSize(),
                attachment.sha256(), attachment.status(), attachment.expireTime(),
                attachment.processInstanceId(), attachment.taskId(), attachment.nodeKey());
    }

    /**
     * 将锁定附件列表转换为 UUID 索引，并拒绝 Mapper 重复或空记录。
     *
     * @param lockedRows List&lt;WfAttachment&gt;，Mapper 返回的锁定附件
     * @return Map&lt;String, WfAttachment&gt;，附件 UUID 到元数据的不可变索引
     */
    private Map<String, WfAttachment> indexLockedRows(List<WfAttachment> lockedRows)
    {
        if (lockedRows == null)
        {
            throw new ServiceException("工作流附件查询结果异常", HttpStatus.ERROR);
        }
        LinkedHashMap<String, WfAttachment> indexed = new LinkedHashMap<>();
        for (WfAttachment attachment : lockedRows)
        {
            if (attachment == null || indexed.putIfAbsent(
                    attachment.attachmentId(), attachment) != null)
            {
                throw new ServiceException("工作流附件查询结果异常", HttpStatus.ERROR);
            }
        }
        return Collections.unmodifiableMap(indexed);
    }

    /**
     * 校验上传字段引用结构并返回不可修改副本。
     *
     * @param references Map&lt;String, List&lt;String&gt;&gt;，变量校验器提取的附件引用
     * @return Map&lt;String, List&lt;String&gt;&gt;，字段和 UUID 均完成规范校验的副本
     */
    private Map<String, List<String>> checkedReferences(
            Map<String, List<String>> references)
    {
        if (references == null || references.isEmpty())
        {
            return Map.of();
        }
        LinkedHashMap<String, List<String>> checked = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : references.entrySet())
        {
            String fieldName = requireFieldName(entry.getKey());
            if (entry.getValue() == null)
            {
                throw new ServiceException("工作流附件引用不合法", HttpStatus.BAD_REQUEST);
            }
            List<String> attachmentIds = entry.getValue().stream()
                    .map(this::requireAttachmentId)
                    .toList();
            checked.put(fieldName, List.copyOf(attachmentIds));
        }
        flattenUniqueIds(checked);
        return Collections.unmodifiableMap(checked);
    }

    /**
     * 按字段顺序展开附件 UUID 并拒绝跨字段重复引用及总量超限。
     *
     * @param references Map&lt;String, List&lt;String&gt;&gt;，已完成单值格式校验的引用
     * @return List&lt;String&gt;，全局去重且顺序稳定的附件 UUID
     */
    private List<String> flattenUniqueIds(Map<String, List<String>> references)
    {
        Set<String> uniqueIds = new LinkedHashSet<>();
        for (List<String> fieldIds : references.values())
        {
            for (String attachmentId : fieldIds)
            {
                if (!uniqueIds.add(attachmentId))
                {
                    throw new ServiceException("同一工作流附件不能重复绑定", HttpStatus.BAD_REQUEST);
                }
                if (uniqueIds.size() > MAX_FORM_ATTACHMENTS)
                {
                    throw new ServiceException("单次表单提交附件不能超过"
                            + MAX_FORM_ATTACHMENTS + "个", HttpStatus.BAD_REQUEST);
                }
            }
        }
        return List.copyOf(uniqueIds);
    }

    /**
     * 获取当前登录用户的数字主键，并依赖身份解析器复核正式用户状态。
     *
     * @return Long，当前有效用户主键
     */
    private Long currentUserId()
    {
        return requireNumericUserId(identityResolver.resolveCurrentIdentity().userId());
    }

    /**
     * 将工作流用户字符串转换为正式 Long 主键。
     *
     * @param userId String，身份解析器返回的数字用户 ID
     * @return Long，正数用户主键
     */
    private Long requireNumericUserId(String userId)
    {
        try
        {
            long parsed = Long.parseLong(userId);
            if (parsed <= 0L)
            {
                throw new NumberFormatException("non-positive");
            }
            return parsed;
        }
        catch (RuntimeException exception)
        {
            ServiceException failure = new ServiceException("工作流用户身份异常",
                    HttpStatus.ERROR);
            failure.initCause(exception);
            throw failure;
        }
    }

    /**
     * 校验表单上传字段名并去除首尾空白。
     *
     * @param fieldName String，客户端上传或部署表单提取的字段名
     * @return String，合法 ASCII 字段名
     */
    private String requireFieldName(String fieldName)
    {
        if (!StringUtils.hasText(fieldName))
        {
            throw new ServiceException("工作流附件表单字段不能为空", HttpStatus.BAD_REQUEST);
        }
        String normalized = fieldName.trim();
        if (!FIELD_NAME_PATTERN.matcher(normalized).matches())
        {
            throw new ServiceException("工作流附件表单字段不合法", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 校验附件 UUID 格式，彻底拒绝路径、URL 和任意客户端对象引用。
     *
     * @param attachmentId String，客户端提交的附件标识
     * @return String，规范小写 UUID
     */
    private String requireAttachmentId(String attachmentId)
    {
        if (!StringUtils.hasText(attachmentId))
        {
            throw new ServiceException("工作流附件标识不合法", HttpStatus.BAD_REQUEST);
        }
        String normalized = attachmentId.trim().toLowerCase(Locale.ROOT);
        if (!ATTACHMENT_ID_PATTERN.matcher(normalized).matches())
        {
            throw new ServiceException("工作流附件标识不合法", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    /**
     * 校验 Flowable 实例主键长度并规范化空白。
     *
     * @param processInstanceId String，RuntimeService 返回的实例主键
     * @return String，非空且不超过 64 字符的实例主键
     */
    private String requireEngineId(String processInstanceId)
    {
        if (!StringUtils.hasText(processInstanceId)
                || processInstanceId.trim().length() > 64)
        {
            throw new ServiceException("工作流附件实例关联异常", HttpStatus.ERROR);
        }
        return processInstanceId.trim();
    }

    /**
     * 校验任务主键长度，禁止将空白或异常引擎标识写入附件归属。
     *
     * @param taskId String，当前真实 Flowable 任务主键
     * @return String，非空且不超过 64 字符的任务主键
     */
    private String requireTaskId(String taskId)
    {
        if (!StringUtils.hasText(taskId) || taskId.trim().length() > 64)
        {
            throw new ServiceException("工作流附件任务关联异常", HttpStatus.ERROR);
        }
        return taskId.trim();
    }

    /**
     * 校验 BPMN 节点 key，保证绑定审计可回放到明确表单节点。
     *
     * @param nodeKey String，开始事件或用户任务的 BPMN 节点 key
     * @return String，非空且不超过 255 字符的节点 key
     */
    private String requireNodeKey(String nodeKey)
    {
        if (!StringUtils.hasText(nodeKey) || nodeKey.trim().length() > 255)
        {
            throw new ServiceException("工作流附件节点关联异常", HttpStatus.ERROR);
        }
        return nodeKey.trim();
    }

    /**
     * 在上传事务最终未提交时补偿删除已落盘文件。
     *
     * @param storageKey String，刚写入的私有对象键
     * @return void，无返回值
     */
    private void registerRollbackFileCompensation(String storageKey)
    {
        if (!TransactionSynchronizationManager.isSynchronizationActive())
        {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
        {
            /**
             * 在数据库事务完成后仅对非提交结果执行文件补偿。
             *
             * @param status int，Spring 事务完成状态
             * @return void，无返回值
             */
            @Override
            public void afterCompletion(int status)
            {
                if (status != TransactionSynchronization.STATUS_COMMITTED)
                {
                    try
                    {
                        storage.delete(storageKey);
                    }
                    catch (RuntimeException cleanupFailure)
                    {
                        log.error("工作流附件事务回滚补偿删除失败", cleanupFailure);
                    }
                }
            }
        });
    }

    /**
     * 在数据库登记立即失败时同步删除文件，并把清理异常挂到原失败上。
     *
     * @param storageKey String，刚写入的私有对象键
     * @param originalFailure RuntimeException，必须继续抛出的数据库失败
     * @return void，无返回值
     */
    private void deleteCompensationFile(String storageKey, RuntimeException originalFailure)
    {
        try
        {
            storage.delete(storageKey);
        }
        catch (RuntimeException cleanupFailure)
        {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * 创建附件不存在异常。
     *
     * @return ServiceException，稳定 HTTP 404 业务异常
     */
    private ServiceException notFound()
    {
        return new ServiceException("工作流附件不存在或已清理", HttpStatus.NOT_FOUND);
    }

    /**
     * 创建附件对象越权异常。
     *
     * @return ServiceException，稳定 HTTP 403 业务异常
     */
    private ServiceException forbidden()
    {
        return new ServiceException("无权访问当前工作流附件", HttpStatus.FORBIDDEN);
    }

    /**
     * 创建附件生命周期状态冲突异常。
     *
     * @return ServiceException，稳定 HTTP 409 业务异常
     */
    private ServiceException stateConflict()
    {
        return new ServiceException("工作流附件状态已变化或已过期", HttpStatus.CONFLICT);
    }

}
