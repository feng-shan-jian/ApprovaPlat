package com.ruoyi.flowable.domain.vo;

/**
 * 已结束流程历史批量删除结果。
 *
 * @param requestedCount int，客户端去重后的目标实例数量
 * @param deletedHistoryCount int，连同子流程一起删除的真实历史实例数量
 * @param deletedCopyCount int，同一事务内逻辑删除的有效抄送记录数量
 */
public record WorkflowHistoryDeletionView(
        int requestedCount,
        int deletedHistoryCount,
        int deletedCopyCount)
{
}
