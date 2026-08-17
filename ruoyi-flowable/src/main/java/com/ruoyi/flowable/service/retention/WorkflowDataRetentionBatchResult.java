package com.ruoyi.flowable.service.retention;

import java.time.LocalDateTime;

/**
 * 单个数据域一次短事务批处理的真实结果。
 * @param domain WorkflowDataRetentionDomain，固定数据域
 * @param scanned int，查询扫描并返回的候选数
 * @param claimed int，在当前事务内锁定领取的候选数
 * @param deleted int，满足终态复核并实际删除的记录数
 * @param failed int，单条处理失败数；事务级失败由协调器单独记录
 * @param oldestPendingTime LocalDateTime，清理后仍保留的最早终态时间，可为空
 */
public record WorkflowDataRetentionBatchResult(
        WorkflowDataRetentionDomain domain,
        int scanned,
        int claimed,
        int deleted,
        int failed,
        LocalDateTime oldestPendingTime)
{
}
