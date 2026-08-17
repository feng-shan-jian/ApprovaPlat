package com.ruoyi.flowable.service.attachment;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.flowable.domain.WfAttachment;

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

    private final WorkflowAttachmentService attachmentService;
    private final TransactionOperations shortTransaction;

    /**
     * 创建附件清理协调器。
     *
     * @param attachmentService WorkflowAttachmentService，领取、对象删除和状态回写边界
     * @param transactionManager PlatformTransactionManager，执行短 REQUIRES_NEW 事务
     * @return 无返回值，构造后由 Spring 调度器复用
     */
    @Autowired
    public WorkflowAttachmentCleanupCoordinator(
            WorkflowAttachmentService attachmentService,
            PlatformTransactionManager transactionManager)
    {
        this(attachmentService, createShortTransaction(transactionManager));
    }

    /**
     * 使用显式事务操作器创建协调器，供事务边界单元测试复用。
     *
     * @param attachmentService WorkflowAttachmentService，正式附件清理服务
     * @param shortTransaction TransactionOperations，每次调用返回前已提交或回滚的短事务
     * @return 无返回值，参数会固定为协调器依赖
     */
    WorkflowAttachmentCleanupCoordinator(WorkflowAttachmentService attachmentService,
            TransactionOperations shortTransaction)
    {
        this.attachmentService = attachmentService;
        this.shortTransaction = shortTransaction;
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
                () -> attachmentService.claimCleanupBatch(claimToken));
        int cleaned = 0;
        int failures = 0;
        int leaseLost = 0;
        for (WfAttachment attachment : claimed)
        {
            try
            {
                // 对象存储调用明确位于数据库事务之外，慢删除不会占用行锁或事务连接。
                attachmentService.deleteClaimedStorage(attachment);
                boolean completed = executeShortTransaction(
                        () -> attachmentService.completeClaimedCleanup(attachment));
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
                            () -> attachmentService.persistCleanupRetry(attachment));
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
        return new WorkflowAttachmentCleanupResult(cleaned, failures, leaseLost);
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
