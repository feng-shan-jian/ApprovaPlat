package com.ruoyi.flowable.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 验证流程状态只接受迁移后的强制 businessStatus，并由实时引擎状态完成正式投影。
 */
class WorkflowProcessStatusNormalizerTest
{
    /**
     * 验证活动、挂起、完成、退回和业务终态都生成唯一稳定状态。
     *
     * @return void，任一正式状态的投影规则变化时测试失败
     */
    @Test
    void projectsFormalBusinessStatusInBothDirections()
    {
        assertThat(WorkflowProcessStatusNormalizer.normalize(
                "running", "running", null, null)).isEqualTo("running");
        assertThat(WorkflowProcessStatusNormalizer.normalize(
                "running", "suspended", null, null)).isEqualTo("suspended");
        assertThat(WorkflowProcessStatusNormalizer.normalize(
                "running", "completed", Instant.now(), null)).isEqualTo("completed");
        assertThat(WorkflowProcessStatusNormalizer.normalize(
                "returned", "running", null, null)).isEqualTo("returned");
        assertThat(WorkflowProcessStatusNormalizer.normalize(
                "canceled", "completed", Instant.now(), "cancel"))
                .isEqualTo("canceled");
        assertThat(WorkflowProcessStatusNormalizer.normalize(
                "rejected", "completed", Instant.now(), "reject"))
                .isEqualTo("rejected");
        assertThat(WorkflowProcessStatusNormalizer.normalize(
                "terminated", "completed", Instant.now(), "terminate"))
                .isEqualTo("terminated");
    }

    /**
     * 验证空值和未知 businessStatus 不会再被引擎状态或结束时间补写。
     *
     * @return void，旧实例状态兜底重新出现时测试失败
     */
    @Test
    void rejectsMissingOrUnknownBusinessStatus()
    {
        assertInvalidBusinessStatus(null);
        assertInvalidBusinessStatus(" ");
        assertInvalidBusinessStatus("legacy-running");
    }

    /**
     * 断言一个非法 businessStatus 稳定返回 HTTP 500 数据一致性错误。
     *
     * @param businessStatus String，可空的非法业务状态
     * @return void，状态码或错误提示变化时测试失败
     */
    private void assertInvalidBusinessStatus(String businessStatus)
    {
        assertThatThrownBy(() -> WorkflowProcessStatusNormalizer.normalize(
                businessStatus, "running", null, null))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                {
                    assertThat(exception.getCode()).isEqualTo(HttpStatus.ERROR);
                    assertThat(exception.getMessage())
                            .isEqualTo("流程实例 businessStatus 数据异常");
                });
    }
}
