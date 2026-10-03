package com.ruoyi.flowable.service.attachment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachment;
import com.ruoyi.flowable.domain.WorkflowAttachmentStatus;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;

/**
 * 使用数据库行领取租约协调附件清理，数据库事务不覆盖对象存储删除。
 */
@Component
public class WorkflowAttachmentCleanupCoordinator
{
    private static final Logger log = LoggerFactory.getLogger(
            WorkflowAttachmentCleanupCoordinator.class);

    /** 物理文件清理失败时写入数据库的固定脱敏错误码。 */
    private static final String CLEANUP_FAILURE_ERROR_CODE =
            "attachment_storage_cleanup_failed";

    /** 清理令牌与附件主键均使用规范 UUID。 */
    private static final Pattern CLEANUP_UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private final WfAttachmentMapper attachmentMapper;
    private final WorkflowAttachmentStorage storage;
    private final WorkflowAttachmentProperties properties;
    private final WorkflowAttachmentUploadJournal uploadJournal;
    private final TransactionOperations shortTransaction;

    /**
     * 创建附件清理协调器。
     *
     * 构造后由 Spring 调度器复用
     */
    @Autowired
    public WorkflowAttachmentCleanupCoordinator(
            WfAttachmentMapper attachmentMapper,
            WorkflowAttachmentStorage storage,
            WorkflowAttachmentProperties properties,
            WorkflowAttachmentUploadJournal uploadJournal,
            PlatformTransactionManager transactionManager)
    {
        this.attachmentMapper = attachmentMapper;
        this.storage = storage;
        this.properties = properties;
        this.properties.validateCleanupRetryBackoff();
        this.uploadJournal = uploadJournal;
        this.shortTransaction = createShortTransaction(transactionManager);
    }

    /**
     * 执行一轮有界清理：短事务领取批次、事务外删除对象、短事务按 token 完成或重试。
     * 租约过期并被其他节点重领时，旧执行者只记录 leaseLost，不覆盖新 token 的状态。
     *
     * @return WorkflowAttachmentCleanupResult，完成、重试和租约丢失的真实记录数
     */
    public WorkflowAttachmentCleanupResult cleanupBatch()
    {
        String claimToken = UUID.randomUUID().toString();
        List<WfAttachment> claimed = executeShortTransaction(
                () -> claimCleanupBatch(claimToken));
        int cleaned = 0;
        int failures = 0;
        int leaseLost = 0;
        for (WfAttachment attachment : claimed)
        {
            try
            {
                // 对象存储调用明确位于数据库事务之外，慢删除不会占用行锁或事务连接。
                deleteClaimedStorage(attachment);
                boolean completed = executeShortTransaction(
                        () -> completeClaimedCleanup(attachment));
                if (completed)
                {
                    cleaned++;
                }
                else
                {
                    leaseLost++;
                }
            }
            catch (WorkflowAttachmentStorageOperationException storageFailure)
            {
                try
                {
                    boolean retryScheduled = executeShortTransaction(
                            () -> persistCleanupRetry(attachment));
                    if (retryScheduled)
                    {
                        failures++;
                    }
                    else
                    {
                        leaseLost++;
                    }
                }
                catch (RuntimeException retryFailure)
                {
                    storageFailure.addSuppressed(retryFailure);
                    throw storageFailure;
                }
                log.error("工作流附件物理清理失败，attachmentId={}，errorCode={}，failureType={}",
                        attachment.attachmentId(), CLEANUP_FAILURE_ERROR_CODE,
                        storageFailure.getClass().getSimpleName());
            }
        }
        WorkflowAttachmentCleanupResult uploads = uploadJournal.cleanupBatch();
        return new WorkflowAttachmentCleanupResult(cleaned + uploads.cleaned(),
                failures + uploads.failures(), leaseLost + uploads.leaseLost());
    }

    /**
     * 为已逻辑删除的本人附件领取单行租约，并在事务外完成对象删除和按令牌回写。
     * 其他节点已经持有有效租约时直接返回，逻辑删除结果仍保持成功且由调度器继续处理。
     *
     * @param attachmentId String，已迁移为 DELETED 的附件 UUID
     * @return void，物理删除失败时已持久化重试并继续抛出存储异常
     */
    void cleanupOwnedDeletedAttachment(String attachmentId)
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
                properties.cleanupRetryDelay(expectedRetryCount));
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
                || !CLEANUP_UUID_PATTERN.matcher(attachment.attachmentId()).matches()
                || !CLEANUP_UUID_PATTERN.matcher(
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
                || !CLEANUP_UUID_PATTERN.matcher(claimToken).matches())
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
     * 在独立短事务中执行单个数据库步骤，返回前保证事务已经提交或回滚。
     *
     * @param action Supplier&lt;T&gt;，只包含领取或按 token 回写的数据库动作
     * @param <T> 数据库动作返回值类型
     * @return T，事务已提交的动作结果
     */
    private <T> T executeShortTransaction(Supplier<T> action)
    {
        return shortTransaction.execute(status -> action.get());
    }

    /**
     * 创建附件清理短事务模板，避免对象存储 IO 进入事务边界。
     *
     * @param transactionManager PlatformTransactionManager，应用正式事务管理器
     * @return TransactionOperations，固定 REQUIRES_NEW 传播级别的事务执行器
     */
    private static TransactionOperations createShortTransaction(
            PlatformTransactionManager transactionManager)
    {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setName("workflowAttachmentCleanupShortTransaction");
        return template;
    }
}
