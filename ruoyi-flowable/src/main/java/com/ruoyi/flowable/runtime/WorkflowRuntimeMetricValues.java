package com.ruoyi.flowable.runtime;

/**
 * 单次数据库合并采集得到的工作流运行指标值，字段集合固定且不包含业务标识。
 *
 * @param activeProcessInstances long，当前运行流程实例数
 * @param activeTasks long，当前运行任务数
 * @param executableJobs long，可执行异步 job 数
 * @param timerJobs long，timer job 数
 * @param suspendedJobs long，挂起 job 数
 * @param deadletterJobs long，deadletter job 数
 * @param externalWorkerJobs long，external worker job 数
 * @param historyJobs long，异步历史 job 数
 * @param temporaryAttachments long，TEMP 附件记录数
 * @param boundAttachments long，BOUND 附件记录数
 * @param expiredAttachments long，EXPIRED 附件记录数
 * @param deletedAttachments long，DELETED 附件记录数
 * @param undeletedAttachmentBytes long，尚未完成物理删除的附件登记字节数
 * @param pendingAttachmentCleanup long，终态且尚未完成物理删除的记录数
 * @param deferredAttachmentCleanup long，已退避且尚未到下次清理时间的记录数
 */
public record WorkflowRuntimeMetricValues(
        long activeProcessInstances,
        long activeTasks,
        long executableJobs,
        long timerJobs,
        long suspendedJobs,
        long deadletterJobs,
        long externalWorkerJobs,
        long historyJobs,
        long temporaryAttachments,
        long boundAttachments,
        long expiredAttachments,
        long deletedAttachments,
        long undeletedAttachmentBytes,
        long pendingAttachmentCleanup,
        long deferredAttachmentCleanup)
{
    /**
     * 拒绝任何负数数据库结果，避免异常映射或溢出被发布为正常容量指标。
     *
     * @return 无返回值，任一字段为负时拒绝创建快照值
     */
    public WorkflowRuntimeMetricValues
    {
        if (activeProcessInstances < 0L || activeTasks < 0L
                || executableJobs < 0L || timerJobs < 0L || suspendedJobs < 0L
                || deadletterJobs < 0L || externalWorkerJobs < 0L || historyJobs < 0L
                || temporaryAttachments < 0L || boundAttachments < 0L
                || expiredAttachments < 0L || deletedAttachments < 0L
                || undeletedAttachmentBytes < 0L || pendingAttachmentCleanup < 0L
                || deferredAttachmentCleanup < 0L)
        {
            throw new IllegalArgumentException("工作流运行指标不能为负数");
        }
    }
}
