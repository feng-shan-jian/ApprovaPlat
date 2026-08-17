package com.ruoyi.flowable.service.retention;

import java.time.LocalDateTime;

/**
 * 单一数据域生命周期批处理契约。
 */
public interface WorkflowDataRetentionCleaner
{
    /**
     * 返回本清理器唯一负责的数据域。
     * @return WorkflowDataRetentionDomain，固定且不可动态变化的数据域
     */
    WorkflowDataRetentionDomain domain();

    /**
     * 在领域短事务内领取并删除一个有界批次。
     * @param executionTime LocalDateTime，本轮统一计算得到的业务时间
     * @return WorkflowDataRetentionBatchResult，扫描、领取、删除和最老积压结果
     */
    WorkflowDataRetentionBatchResult cleanBatch(LocalDateTime executionTime);
}
