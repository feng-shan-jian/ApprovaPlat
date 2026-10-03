package com.ruoyi.flowable.service.attachment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.config.WorkflowAttachmentProperties;
import com.ruoyi.flowable.domain.WfAttachmentUpload;
import com.ruoyi.flowable.mapper.WfAttachmentMapper;

/** 文件写入前的持久登记，以及未提交上传的有界恢复；正式附件仍由附件账本管理。 */
@Component
public class WorkflowAttachmentUploadJournal
{
    private static final Logger log = LoggerFactory.getLogger(WorkflowAttachmentUploadJournal.class);

    private final WfAttachmentMapper mapper;
    private final WorkflowAttachmentStorage storage;
    private final WorkflowAttachmentProperties properties;
    private final TransactionTemplate shortTransaction;

    public WorkflowAttachmentUploadJournal(WfAttachmentMapper mapper,
            WorkflowAttachmentStorage storage, WorkflowAttachmentProperties properties,
            PlatformTransactionManager transactionManager)
    {
        this.mapper = mapper;
        this.storage = storage;
        this.properties = properties;
        this.shortTransaction = new TransactionTemplate(transactionManager);
        this.shortTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.shortTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    String reserve(MultipartFile file)
    {
        String storageKey = storage.reserveKey(file);
        shortTransaction.executeWithoutResult(status -> {
            if (mapper.insertUpload(storageKey) != 1)
            {
                throw new ServiceException("工作流附件上传登记失败", HttpStatus.ERROR);
            }
        });
        return storageKey;
    }

    void lock(String storageKey)
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
        {
            throw new IllegalStateException("附件上传必须处于数据库事务中");
        }
        // 从此锁到附件提交之间，不论上传多久，清理器都只能 SKIP LOCKED。
        // 登记后先被清理器接手的键永久作废，不能等重试清空 token 后再次使用。
        if (!Integer.valueOf(0).equals(mapper.selectUploadClaimCountForUpdate(storageKey)))
        {
            throw new ServiceException("工作流附件上传已过期，请重试", HttpStatus.CONFLICT)
                    .setSubCode("WORKFLOW_ATTACHMENT_STATE_CONFLICT");
        }
    }

    void consume(String storageKey)
    {
        if (mapper.consumeUpload(storageKey) != 1)
        {
            throw new ServiceException("工作流附件上传登记状态异常", HttpStatus.ERROR);
        }
    }

    WorkflowAttachmentCleanupResult cleanupBatch()
    {
        String token = UUID.randomUUID().toString();
        List<WfAttachmentUpload> claimed = shortTransaction.execute(status -> {
            List<WfAttachmentUpload> candidates = mapper.selectUploadsForUpdate(
                    properties.getCleanupBatchSize());
            for (WfAttachmentUpload upload : candidates)
            {
                if (mapper.claimUpload(upload.storageKey(), token,
                        Math.max(1L, properties.getCleanupLeaseDuration().toSeconds())) != 1)
                {
                    throw new ServiceException("工作流附件上传清理领取失败", HttpStatus.ERROR);
                }
            }
            return candidates;
        });
        int cleaned = 0;
        int failures = 0;
        int leaseLost = 0;
        for (WfAttachmentUpload upload : claimed)
        {
            // 已认领的键再无上传者可写；防御异常残留账本，不删除正式附件拥有的对象。
            if (!mapper.existsByStorageKey(upload.storageKey()))
            {
                try
                {
                    storage.deleteUncommittedUpload(upload.storageKey());
                }
                catch (WorkflowAttachmentStorageOperationException failure)
                {
                    try
                    {
                        LocalDateTime nextRetry = LocalDateTime.now().plus(
                                properties.cleanupRetryDelay(upload.claimCount()));
                        int updated = shortTransaction.execute(status -> mapper.retryUploadCleanup(
                                upload.storageKey(), token, nextRetry));
                        failures += updated;
                        leaseLost += 1 - updated;
                    }
                    catch (RuntimeException retryFailure)
                    {
                        failure.addSuppressed(retryFailure);
                        throw failure;
                    }
                    log.error("工作流附件未提交文件清理失败，errorCode=attachment_storage_cleanup_failed，failureType={}",
                            failure.getClass().getSimpleName());
                    continue;
                }
            }
            int updated = shortTransaction.execute(status -> mapper.completeUploadCleanup(
                    upload.storageKey(), token));
            cleaned += updated;
            leaseLost += 1 - updated;
        }
        return new WorkflowAttachmentCleanupResult(cleaned, failures, leaseLost);
    }
}
