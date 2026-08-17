package com.ruoyi.flowable.service.retention;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 将各领域 Mapper 的终态查询与删除能力适配为统一批处理操作。
 * @param <T> 领域稳定主键类型
 */
interface WorkflowRetentionBatchOperations<T>
{
    /** 按截止时间锁定有界主键。 @param cutoffTime LocalDateTime，截止时间 @param limit int，批次上限 @return List&lt;T&gt;，锁定主键 */
    List<T> selectIdsForUpdate(LocalDateTime cutoffTime, int limit);
    /**
     * 删除必须与父记录同事务清理的依赖数据，默认领域没有应用侧依赖记录。
     * @param ids List&lt;T&gt;，当前事务已锁定的父记录主键
     * @return int，实际删除的依赖记录数
     */
    default int deleteDependentRecords(List<T> ids)
    {
        return 0;
    }
    /** 删除仍满足终态条件的锁定主键。 @param ids List&lt;T&gt;，锁定主键 @param cutoffTime LocalDateTime，截止时间 @return int，删除数 */
    int deleteByIds(List<T> ids, LocalDateTime cutoffTime);
    /** 查询清理后最老终态时间。 @return LocalDateTime，最老时间或空 */
    LocalDateTime selectOldestPendingTime();
}
