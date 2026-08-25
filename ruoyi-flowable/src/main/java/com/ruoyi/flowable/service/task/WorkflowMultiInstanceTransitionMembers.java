package com.ruoyi.flowable.service.task;

import java.util.List;

/**
 * 受控迁移期间集合表达式采用单成员覆盖或按节点权威来源重新初始化的指令。
 *
 * @param overrideMembers List&lt;String&gt;，RETURN 临时任务使用的明确成员；刷新模式为空
 * @param refreshAuthoritative boolean，REOPEN 是否清除已对账旧状态并重新读取节点权威来源
 */
public record WorkflowMultiInstanceTransitionMembers(
        List<String> overrideMembers, boolean refreshAuthoritative)
{
    /**
     * 冻结迁移成员指令并保证覆盖与刷新两种模式互斥。
     *
     * @return 无返回值，构造时拒绝空覆盖、重复成员或模式冲突
     */
    public WorkflowMultiInstanceTransitionMembers
    {
        overrideMembers = overrideMembers == null
                ? List.of() : List.copyOf(overrideMembers);
        if (refreshAuthoritative == !overrideMembers.isEmpty()
                || overrideMembers.stream().anyMatch(
                        member -> member == null || member.isBlank())
                || overrideMembers.stream().distinct().count()
                        != overrideMembers.size())
        {
            throw new IllegalArgumentException("受控迁移成员指令不合法");
        }
    }

    /**
     * 创建整组退回临时任务的单成员覆盖指令。
     *
     * @param actorUserId String，真实退回操作人主键
     * @return WorkflowMultiInstanceTransitionMembers，单成员覆盖指令
     */
    public static WorkflowMultiInstanceTransitionMembers override(String actorUserId)
    {
        return new WorkflowMultiInstanceTransitionMembers(
                List.of(actorUserId), false);
    }

    /**
     * 创建从首审批受控节点权威来源重新初始化成员的指令。
     *
     * @return WorkflowMultiInstanceTransitionMembers，权威刷新指令
     */
    public static WorkflowMultiInstanceTransitionMembers refresh()
    {
        return new WorkflowMultiInstanceTransitionMembers(List.of(), true);
    }
}
