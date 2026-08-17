package com.ruoyi.flowable.service.attachment;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.ruoyi.flowable.runtime.WorkflowAttachmentCleanupMetrics;

/**
 * 周期触发临时附件过期和终态文件重试清理。
 */
@Component
public class WorkflowAttachmentCleanupScheduler
{
    private final WorkflowAttachmentCleanupCoordinator cleanupCoordinator;
    private final WorkflowAttachmentCleanupMetrics cleanupMetrics;

    /**
     * 创建附件清理调度器。
     *
     * @param cleanupCoordinator WorkflowAttachmentCleanupCoordinator，数据库领取租约清理协调器
     * @param cleanupMetrics WorkflowAttachmentCleanupMetrics，固定低基数清理指标
     * @return 无返回值，构造后由 Spring 调度基础设施管理
     */
    public WorkflowAttachmentCleanupScheduler(
            WorkflowAttachmentCleanupCoordinator cleanupCoordinator,
            WorkflowAttachmentCleanupMetrics cleanupMetrics)
    {
        this.cleanupCoordinator = cleanupCoordinator;
        this.cleanupMetrics = cleanupMetrics;
    }

    /**
     * 按配置间隔清理一个有界批次，避免单轮任务长时间占用数据库连接。
     *
     * @return void，单条清理失败由领域服务记录并留待下轮重试
     */
    @Scheduled(
            initialDelayString = "${flowable.attachment.cleanup-initial-delay:PT1M}",
            fixedDelayString = "${flowable.attachment.cleanup-fixed-delay:PT10M}")
    public void cleanupExpiredAttachments()
    {
        try
        {
            cleanupMetrics.recordCompleted(cleanupCoordinator.cleanupBatch());
        }
        catch (RuntimeException failure)
        {
            cleanupMetrics.recordSchedulerFailure();
            throw failure;
        }
    }
}
