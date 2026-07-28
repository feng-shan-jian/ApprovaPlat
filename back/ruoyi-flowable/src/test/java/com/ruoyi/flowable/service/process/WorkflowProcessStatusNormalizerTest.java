package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * WorkflowProcessStatusNormalizer 的业务状态一致性测试。
 */
class WorkflowProcessStatusNormalizerTest
{
    /**
     * 验证服务端取消或终止状态覆盖 Flowable 的通用完成状态。
     *
     * @return 无返回值，断言两种业务终态不会退化为 completed
     */
    @Test
    void businessTerminalStateOverridesEngineCompletion()
    {
        Instant endTime = Instant.parse("2026-07-26T00:00:00Z");

        assertThat(WorkflowProcessStatusNormalizer.normalize(
                "canceled", "COMPLETED", endTime, "canceled: user request"))
                .isEqualTo("canceled");
        assertThat(WorkflowProcessStatusNormalizer.normalize(
                "terminated", "COMPLETED", endTime, null))
                .isEqualTo("terminated");
        assertThat(WorkflowProcessStatusNormalizer.normalize(
                "rejected", "COMPLETED", endTime, null))
                .isEqualTo("rejected");
    }

    /**
     * 验证活动实例的真实挂起状态覆盖可能滞后的 running 业务状态。
     *
     * @return 无返回值，断言详情与列表均呈现 suspended
     */
    @Test
    void suspendedEngineStateOverridesStaleRunningBusinessStatus()
    {
        assertThat(WorkflowProcessStatusNormalizer.normalize(
                "running", "SUSPENDED", null, null))
                .isEqualTo("suspended");
    }

    /**
     * 验证英式取消拼写和正常完成状态统一为稳定 API 编码。
     *
     * @return 无返回值，断言 cancelled 兼容及 completed 保持不变
     */
    @Test
    void normalizesEngineStateSpelling()
    {
        Instant endTime = Instant.parse("2026-07-26T00:00:00Z");

        assertThat(WorkflowProcessStatusNormalizer.normalize(
                null, "CANCELLED", endTime, "legacy"))
                .isEqualTo("canceled");
        assertThat(WorkflowProcessStatusNormalizer.normalize(
                null, "COMPLETED", endTime, null))
                .isEqualTo("completed");
    }

    /**
     * 验证缺少 Flowable 状态的旧历史仍按结束时间和删除原因稳定兜底。
     *
     * @return 无返回值，断言活动、正常结束和删除结束三个分支
     */
    @Test
    void fallsBackForLegacyHistoryWithoutState()
    {
        Instant endTime = Instant.parse("2026-07-26T00:00:00Z");

        assertThat(WorkflowProcessStatusNormalizer.normalize(null, null, null, null))
                .isEqualTo("running");
        assertThat(WorkflowProcessStatusNormalizer.normalize(null, null, endTime, null))
                .isEqualTo("completed");
        assertThat(WorkflowProcessStatusNormalizer.normalize(null, null, endTime, "legacy delete"))
                .isEqualTo("canceled");
    }
}
