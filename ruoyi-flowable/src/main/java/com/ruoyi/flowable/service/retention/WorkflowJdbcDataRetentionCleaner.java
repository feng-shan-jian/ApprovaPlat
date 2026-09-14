package com.ruoyi.flowable.service.retention;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 使用 JdbcTemplate 执行固定领域的有界历史清理，不接管任何业务状态机。
 */
public class WorkflowJdbcDataRetentionCleaner implements WorkflowDataRetentionCleaner
{
    private final WorkflowDataRetentionDomain domain;
    private final JdbcTemplate jdbcTemplate;
    private final Supplier<Duration> retentionSupplier;
    private final Supplier<Integer> batchSizeSupplier;
    private final String selectSql;
    private final String deletePrefix;
    private final String deleteSuffix;
    private final String oldestSql;

    /**
     * 创建 JdbcTemplate 数据域清理器。
     * 构造后由 Spring 事务代理调用
     */
    public WorkflowJdbcDataRetentionCleaner(WorkflowDataRetentionDomain domain,
            JdbcTemplate jdbcTemplate, Supplier<Duration> retentionSupplier,
            Supplier<Integer> batchSizeSupplier, String selectSql,
            String deletePrefix, String deleteSuffix, String oldestSql)
    {
        this.domain = domain;
        this.jdbcTemplate = jdbcTemplate;
        this.retentionSupplier = retentionSupplier;
        this.batchSizeSupplier = batchSizeSupplier;
        this.selectSql = selectSql;
        this.deletePrefix = deletePrefix;
        this.deleteSuffix = deleteSuffix;
        this.oldestSql = oldestSql;
    }

    @Override
    public WorkflowDataRetentionDomain domain()
    {
        return domain;
    }

    /**
     * 在短事务内领取满足领域 SQL 的历史主键并删除，删除阶段必须复核原候选条件。
     * @param executionTime LocalDateTime，本轮统一业务时间
     * @return WorkflowDataRetentionBatchResult，成功提交的真实领域清理结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowDataRetentionBatchResult cleanBatch(LocalDateTime executionTime)
    {
        LocalDateTime cutoffTime = executionTime.minus(retentionSupplier.get());
        List<Long> claimedIds = jdbcTemplate.queryForList(selectSql, Long.class,
                cutoffTime, batchSizeSupplier.get());
        int deleted = deleteClaimed(claimedIds, cutoffTime);
        if (deleted != claimedIds.size())
        {
            throw new IllegalStateException("工作流数据保留删除行数与事务内领取行数不一致: " + domain);
        }
        LocalDateTime oldestPendingTime = jdbcTemplate.queryForObject(oldestSql, LocalDateTime.class);
        return new WorkflowDataRetentionBatchResult(domain, claimedIds.size(),
                claimedIds.size(), deleted, 0, oldestPendingTime);
    }

    /**
     * 使用参数占位符删除已锁定主键，并在 SQL 尾部重复终态和截止条件。
     * @param claimedIds List&lt;Long&gt;，当前事务已锁定的领域主键
     * @param cutoffTime LocalDateTime，领域保留截止时间
     * @return int，实际删除记录数
     */
    private int deleteClaimed(List<Long> claimedIds, LocalDateTime cutoffTime)
    {
        if (claimedIds.isEmpty())
        {
            return 0;
        }
        String placeholders = claimedIds.stream().map(ignored -> "?")
                .collect(Collectors.joining(","));
        List<Object> parameters = new ArrayList<>(claimedIds);
        parameters.add(cutoffTime);
        return jdbcTemplate.update(deletePrefix + placeholders + deleteSuffix,
                parameters.toArray());
    }
}
