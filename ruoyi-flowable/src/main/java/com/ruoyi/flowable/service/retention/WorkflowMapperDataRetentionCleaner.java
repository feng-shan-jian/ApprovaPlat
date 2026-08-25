package com.ruoyi.flowable.service.retention;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用领域 Mapper 在单个短事务中执行主键有界领取和终态复核删除。
 * @param <T> 领域稳定主键类型
 */
public class WorkflowMapperDataRetentionCleaner<T> implements WorkflowDataRetentionCleaner
{
    private final WorkflowDataRetentionDomain domain;
    private final Supplier<Duration> retentionSupplier;
    private final Supplier<Integer> batchSizeSupplier;
    private final WorkflowRetentionBatchOperations<T> operations;

    /**
     * 创建 Mapper 数据域清理器。
     * @param domain WorkflowDataRetentionDomain，清理器唯一负责的数据域
     * @param retentionSupplier Supplier&lt;Duration&gt;，动态读取领域保留期
     * @param batchSizeSupplier Supplier&lt;Integer&gt;，动态读取有界批次上限
     * @param operations WorkflowRetentionBatchOperations&lt;T&gt;，领域 Mapper 操作适配器
     * @return 无返回值，构造完成后由 Spring 事务代理调用
     */
    public WorkflowMapperDataRetentionCleaner(WorkflowDataRetentionDomain domain,
            Supplier<Duration> retentionSupplier, Supplier<Integer> batchSizeSupplier,
            WorkflowRetentionBatchOperations<T> operations)
    {
        this.domain = domain;
        this.retentionSupplier = retentionSupplier;
        this.batchSizeSupplier = batchSizeSupplier;
        this.operations = operations;
    }

    @Override
    public WorkflowDataRetentionDomain domain()
    {
        return domain;
    }

    /**
     * 在同一短事务中锁定候选并按原终态条件删除，防止并发状态变化扩大删除范围。
     * @param executionTime LocalDateTime，本轮统一业务时间
     * @return WorkflowDataRetentionBatchResult，成功提交的真实批次结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowDataRetentionBatchResult cleanBatch(LocalDateTime executionTime)
    {
        LocalDateTime cutoffTime = executionTime.minus(retentionSupplier.get());
        List<T> claimedIds = operations.selectIdsForUpdate(cutoffTime, batchSizeSupplier.get());
        int deleted = 0;
        if (!claimedIds.isEmpty())
        {
            // 依赖数据必须先删；后续父记录条件复核失败时由当前事务整体回滚，避免产生孤立审计。
            operations.deleteDependentRecords(claimedIds);
            deleted = operations.deleteByIds(claimedIds, cutoffTime);
        }
        if (deleted != claimedIds.size())
        {
            throw new IllegalStateException("工作流数据保留删除行数与事务内领取行数不一致: " + domain);
        }
        LocalDateTime oldestPendingTime = operations.selectOldestPendingTime();
        return new WorkflowDataRetentionBatchResult(domain, claimedIds.size(),
                claimedIds.size(), deleted, 0, oldestPendingTime);
    }
}
