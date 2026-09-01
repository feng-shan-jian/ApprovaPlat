package com.ruoyi.flowable.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 验证取消认领纯规则对全部服务端事实严格执行失败关闭。
 */
class WorkflowTaskUnclaimPolicyTest
{
    private final WorkflowTaskUnclaimPolicy policy = new WorkflowTaskUnclaimPolicy();

    /**
     * 验证真实认领的活动任务在全部事实一致时开放取消认领。
     *
     * @return void，任一必要事实未参与计算时测试失败
     */
    @Test
    void allowsOnlyCompleteClaimFacts()
    {
        assertThat(policy.isUnclaimAllowed(true, true, false,
                "100", "100", null, null, "100", true)).isTrue();
    }

    /**
     * 验证流程结束、任务历史化或任务挂起均关闭取消认领。
     *
     * @return void，任一生命周期非法状态返回 true 时测试失败
     */
    @Test
    void rejectsInactiveOrSuspendedLifecycle()
    {
        assertThat(policy.isUnclaimAllowed(false, true, false,
                "100", "100", null, null, "100", true)).isFalse();
        assertThat(policy.isUnclaimAllowed(true, false, false,
                "100", "100", null, null, "100", true)).isFalse();
        assertThat(policy.isUnclaimAllowed(true, true, true,
                "100", "100", null, null, "100", true)).isFalse();
    }

    /**
     * 验证当前用户不是 assignee 或 claimedBy 与当前用户不一致时关闭能力。
     *
     * @return void，身份漂移被错误接受时测试失败
     */
    @Test
    void rejectsAssigneeOrClaimedByMismatch()
    {
        assertThat(policy.isUnclaimAllowed(true, true, false,
                "200", "100", null, null, "100", true)).isFalse();
        assertThat(policy.isUnclaimAllowed(true, true, false,
                "100", "100", null, null, "200", true)).isFalse();
        assertThat(policy.isUnclaimAllowed(true, true, false,
                null, null, null, null, null, true)).isFalse();
    }

    /**
     * 验证 owner 或 delegationState 任一存在时关闭取消认领。
     *
     * @return void，委派关系能够绕过规则时测试失败
     */
    @Test
    void rejectsOwnerOrDelegationState()
    {
        assertThat(policy.isUnclaimAllowed(true, true, false,
                "100", "100", "100", null, "100", true)).isFalse();
        assertThat(policy.isUnclaimAllowed(true, true, false,
                "100", "100", null, "PENDING", "100", true)).isFalse();
        assertThat(policy.isUnclaimAllowed(true, true, false,
                "100", "100", "", null, "100", true)).isFalse();
        assertThat(policy.isUnclaimAllowed(true, true, false,
                "100", "100", null, "", "100", true)).isFalse();
    }

    /**
     * 验证直接指派或认领时间缺失时关闭取消认领。
     *
     * @return void，认领元数据不完整却返回 true 时测试失败
     */
    @Test
    void rejectsMissingClaimMetadata()
    {
        assertThat(policy.isUnclaimAllowed(true, true, false,
                "100", "100", null, null, null, false)).isFalse();
        assertThat(policy.isUnclaimAllowed(true, true, false,
                "100", "100", null, null, "100", false)).isFalse();
    }
}
