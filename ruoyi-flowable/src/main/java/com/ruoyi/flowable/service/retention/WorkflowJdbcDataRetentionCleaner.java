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
 * 通知领域使用 JdbcTemplate 执行终态历史清理，不接管 outbox 状态机。
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
     * 创建通知数据域清理器。
     * @param domain WorkflowDataRetentionDomain，通知 outbox 或已读 inbox 数据域
     * @param jdbcTemplate JdbcTemplate，正式 MySQL 数据访问入口
     * @param retentionSupplier Supplier&lt;Duration&gt;，动态读取保留期
     * @param batchSizeSupplier Supplier&lt;Integer&gt;，动态读取批次上限
     * @param selectSql String，带行锁和稳定排序的主键领取 SQL
     * @param deletePrefix String，主键 IN 条件前缀
     * @param deleteSuffix String，重复终态和截止时间条件后缀
     * @param oldestSql String，查询最早剩余终态时间的 SQL
     * @return 无返回值，构造后由 Spring 事务代理调用
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
     * 在短事务内领取通知终态主键并删除，受保护状态不会进入领取或删除条件。
     * @param executionTime LocalDateTime，本轮统一业务时间
     * @return WorkflowDataRetentionBatchResult，成功提交的真实通知清理结果
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
            throw new IllegalStateException("工作流通知保留删除行数与事务内领取行数不一致: " + domain);
        }
        LocalDateTime oldestPendingTime = jdbcTemplate.queryForObject(oldestSql, LocalDateTime.class);
        return new WorkflowDataRetentionBatchResult(domain, claimedIds.size(),
                claimedIds.size(), deleted, 0, oldestPendingTime);
    }

    /**
     * 使用参数占位符删除已锁定主键，并在 SQL 尾部重复终态和截止条件。
     * @param claimedIds List&lt;Long&gt;，当前事务已锁定的通知主键
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
