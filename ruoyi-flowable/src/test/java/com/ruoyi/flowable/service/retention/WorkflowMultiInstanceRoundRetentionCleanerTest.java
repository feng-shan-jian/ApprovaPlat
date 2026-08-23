package com.ruoyi.flowable.service.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 多实例轮次保留清理器的截止时间、有界领取和精确删除数量测试。
 */
class WorkflowMultiInstanceRoundRetentionCleanerTest
{
    /**
     * 验证轮次域使用流程结束保留截止时间领取有界主键，并只删除同事务领取的主键。
     * @return void，截止时间、批次上限、主键集合或结果计数不一致时测试失败
     */
    @Test
    void deletesExactlyClaimedExpiredFinishedProcessRounds()
    {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LocalDateTime executionTime = LocalDateTime.of(2026, 8, 23, 12, 0);
        LocalDateTime cutoffTime = executionTime.minusDays(180);
        LocalDateTime oldestTime = cutoffTime.minusDays(10);
        when(jdbcTemplate.queryForList(
                WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_SELECT,
                Long.class, cutoffTime, 2)).thenReturn(List.of(7L, 8L));
        when(jdbcTemplate.update(eq(deleteSql(2)),
                aryEq(new Object[] {7L, 8L, cutoffTime}))).thenReturn(2);
        when(jdbcTemplate.queryForObject(
                WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_OLDEST,
                LocalDateTime.class)).thenReturn(oldestTime);
        WorkflowJdbcDataRetentionCleaner cleaner = roundCleaner(jdbcTemplate, 2);

        WorkflowDataRetentionBatchResult result = cleaner.cleanBatch(executionTime);

        assertThat(result).isEqualTo(new WorkflowDataRetentionBatchResult(
                WorkflowDataRetentionDomain.MULTI_INSTANCE_ROUND,
                2, 2, 2, 0, oldestTime));
        verify(jdbcTemplate).queryForList(
                WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_SELECT,
                Long.class, cutoffTime, 2);
        verify(jdbcTemplate).update(eq(deleteSql(2)),
                aryEq(new Object[] {7L, 8L, cutoffTime}));
    }

    /**
     * 验证删除影响行数不是事务内领取数量时批次失败，交由 Spring 回滚整个短事务。
     * @return void，部分删除被误报成功时测试失败
     */
    @Test
    void rejectsDeleteCountMismatch()
    {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        LocalDateTime executionTime = LocalDateTime.of(2026, 8, 23, 12, 0);
        LocalDateTime cutoffTime = executionTime.minusDays(180);
        when(jdbcTemplate.queryForList(
                WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_SELECT,
                Long.class, cutoffTime, 2)).thenReturn(List.of(7L, 8L));
        when(jdbcTemplate.update(eq(deleteSql(2)),
                aryEq(new Object[] {7L, 8L, cutoffTime}))).thenReturn(1);
        WorkflowJdbcDataRetentionCleaner cleaner = roundCleaner(jdbcTemplate, 2);

        assertThatThrownBy(() -> cleaner.cleanBatch(executionTime))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MULTI_INSTANCE_ROUND");
    }

    /**
     * 创建使用正式轮次 SQL 的清理器。
     * @param jdbcTemplate JdbcTemplate，当前测试的数据访问替身
     * @param batchSize int，单轮领取上限
     * @return WorkflowJdbcDataRetentionCleaner，保留期固定为 180 天的轮次清理器
     */
    private WorkflowJdbcDataRetentionCleaner roundCleaner(JdbcTemplate jdbcTemplate,
            int batchSize)
    {
        return new WorkflowJdbcDataRetentionCleaner(
                WorkflowDataRetentionDomain.MULTI_INSTANCE_ROUND, jdbcTemplate,
                () -> Duration.ofDays(180), () -> batchSize,
                WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_SELECT,
                WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_DELETE_PREFIX,
                WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_DELETE_SUFFIX,
                WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_OLDEST);
    }

    /**
     * 按领取数量构造生产清理器实际执行的参数化删除 SQL。
     * @param claimedCount int，当前事务已领取的轮次主键数量
     * @return String，包含相同数量占位符和流程结束时间复核的删除 SQL
     */
    private String deleteSql(int claimedCount)
    {
        String placeholders = String.join(",", java.util.Collections.nCopies(
                claimedCount, "?"));
        return WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_DELETE_PREFIX
                + placeholders + WorkflowCoreRetentionSql.MULTI_INSTANCE_ROUND_DELETE_SUFFIX;
    }
}
