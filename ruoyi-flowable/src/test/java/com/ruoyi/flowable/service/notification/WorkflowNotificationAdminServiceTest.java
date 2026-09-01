package com.ruoyi.flowable.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ruoyi.common.core.page.PageResult;

/**
 * 验证通知运维查询的补偿能力投影类型、周期边界和错误摘要脱敏合同。
 */
class WorkflowNotificationAdminServiceTest
{
    private JdbcTemplate jdbcTemplate;
    private WorkflowNotificationAdminService service;
    /** 模拟 MySQL queryForList 返回的 JDBC Map 行，能力字段有意保留数字类型。 */
    private List<Map<String, Object>> jdbcRows;
    /** 最近一次分页明细 SQL，用于核对读取能力与写端周期上限同源。 */
    private String selectedSql;

    /**
     * 装配只读服务并为计数、明细查询提供可检查的 JDBC 桩。
     * @return void，每个用例获得独立行集合和 SQL 快照
     */
    @BeforeEach
    void setUp()
    {
        jdbcTemplate = mock(JdbcTemplate.class);
        service = new WorkflowNotificationAdminService(jdbcTemplate);
        jdbcRows = new ArrayList<>();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenAnswer(invocation -> (long) jdbcRows.size());
        doAnswer(invocation ->
        {
            selectedSql = invocation.getArgument(0, String.class);
            return List.copyOf(jdbcRows);
        }).when(jdbcTemplate).queryForList(anyString(), any(Object[].class));
    }

    /**
     * 验证死信在首个周期和可补偿最大周期都返回严格布尔 true。
     * @return void，1 或 65534 被错误拒绝、或 JDBC 数字泄漏时测试失败
     */
    @Test
    void returnsBooleanTrueForCompensableCycleBoundaries()
    {
        jdbcRows.add(outboxRow("DEAD_LETTER", 1, 1, null, null));
        jdbcRows.add(outboxRow("DEAD_LETTER", 65_534, 1L, null, null));

        PageResult<Map<String, Object>> page = service.listOutbox(null, 1, 100);

        assertThat(page.rows()).extracting(row -> row.get("canCompensate"))
                .containsExactly(Boolean.TRUE, Boolean.TRUE)
                .allSatisfy(value -> assertThat(value).isInstanceOf(Boolean.class));
    }

    /**
     * 验证 deliveryCycle 达到无符号 SMALLINT 上限后能力关闭。
     * @return void，65535 仍被投影为可补偿时测试失败
     */
    @Test
    void returnsBooleanFalseAtDeliveryCycleLimit()
    {
        jdbcRows.add(outboxRow("DEAD_LETTER", 65_535, 0, null, null));

        Object capability = service.listOutbox(null, 1, 100).rows().get(0)
                .get("canCompensate");

        assertThat(capability).isEqualTo(Boolean.FALSE).isInstanceOf(Boolean.class);
    }

    /**
     * 验证非死信状态无论周期值如何都不能补偿。
     * @return void，非 DEAD_LETTER 行被错误开放能力时测试失败
     */
    @Test
    void returnsBooleanFalseForNonDeadLetterStatus()
    {
        jdbcRows.add(outboxRow("RETRYING", 1, 0L, null, null));

        Object capability = service.listOutbox(null, 1, 100).rows().get(0)
                .get("canCompensate");

        assertThat(capability).isEqualTo(Boolean.FALSE).isInstanceOf(Boolean.class);
    }

    /**
     * 验证未知底层失败摘要继续被稳定文案替换，不泄露地址、账号或供应商原文。
     * @return void，原始敏感摘要进入公开投影时测试失败
     */
    @Test
    void keepsUnknownFailureSummaryRedacted()
    {
        String rawSummary = "smtp://mail.internal recipient=user@example.test credential=secret";
        jdbcRows.add(outboxRow("DEAD_LETTER", 1, 1,
                "UNRECOGNIZED_PROVIDER_FAILURE", rawSummary));

        Map<String, Object> row = service.listOutbox(null, 1, 100).rows().get(0);

        assertThat(row.get("lastErrorSummary"))
                .isEqualTo("通知投递失败，详细信息已隐藏")
                .asString().doesNotContain("mail.internal", "user@example.test", "secret");
    }

    /**
     * 验证 SQL 能力投影同时包含死信状态和 delivery_cycle 严格上限。
     * @return void，读取能力条件与最终补偿更新条件漂移时测试失败
     */
    @Test
    void projectsCompensationCapabilityWithDeliveryCycleLimit()
    {
        jdbcRows.add(outboxRow("DEAD_LETTER", 65_534, 1, null, null));

        service.listOutbox(null, 1, 100);

        assertThat(selectedSql).contains(
                "case when outbox.status='DEAD_LETTER' "
                + "and outbox.delivery_cycle<65535 then 1 else 0 end as canCompensate");
    }

    /**
     * 构造生产查询返回的最小 JDBC 行，保留能力值的原始驱动类型供服务归一化。
     * @param status String，outbox 当前状态
     * @param deliveryCycle int，当前有界投递周期
     * @param canCompensate Object，SQL CASE 返回的 JDBC 数值
     * @param errorCode String，可空稳定错误码
     * @param errorSummary String，可空底层失败摘要
     * @return Map&lt;String,Object&gt;，可由查询服务完成公开投影处理的数据库行
     */
    private Map<String, Object> outboxRow(String status, int deliveryCycle,
            Object canCompensate, String errorCode, String errorSummary)
    {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.put("status", status);
        row.put("deliveryCycle", deliveryCycle);
        row.put("canCompensate", canCompensate);
        row.put("lastErrorCode", errorCode);
        row.put("lastErrorSummary", errorSummary);
        return row;
    }
}
