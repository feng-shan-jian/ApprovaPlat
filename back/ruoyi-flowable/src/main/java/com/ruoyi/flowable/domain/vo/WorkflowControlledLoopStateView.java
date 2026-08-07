package com.ruoyi.flowable.domain.vo;

import java.util.List;
import java.util.Objects;

/**
 * 流程详情中的受控重复审批循环配置、当前轮次和历史轨迹。
 *
 * @param activityId String，循环用户任务标识
 * @param activityName String，部署时节点名称
 * @param decisionVariable String，循环判断表单变量
 * @param repeatValue String，再次进入条件值
 * @param exitValue String，退出条件值
 * @param maxIterations int，允许完成该节点的最大轮次
 * @param completedIterations int，已经成功完成并落库的轮次数
 * @param currentIteration int，活动中显示下一轮，否则等于已完成轮次
 * @param active boolean，当前活动任务是否正位于该循环节点
 * @param rounds List&lt;WorkflowControlledLoopRoundView&gt;，按轮次排序的正式审计记录
 */
public record WorkflowControlledLoopStateView(String activityId, String activityName,
        String decisionVariable, String repeatValue, String exitValue, int maxIterations,
        int completedIterations, int currentIteration, boolean active,
        List<WorkflowControlledLoopRoundView> rounds)
{
    /**
     * 复制循环轮次集合并核验计数边界。
     * @return 无返回值，构造后集合不可修改
     */
    public WorkflowControlledLoopStateView
    {
        rounds = List.copyOf(Objects.requireNonNull(rounds, "循环轮次不能为空"));
        if (maxIterations < 2 || completedIterations < 0
                || completedIterations > maxIterations
                || currentIteration < completedIterations
                || currentIteration > maxIterations)
        {
            throw new IllegalArgumentException("受控循环轮次状态不合法");
        }
    }
}
