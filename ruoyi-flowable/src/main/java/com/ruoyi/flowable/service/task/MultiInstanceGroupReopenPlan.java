package com.ruoyi.flowable.service.task;

import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 发起人重提前由 RETURNED 轮次和唯一申请人任务组成的不可变计划。
 *
 * @param round MultiInstanceRoundSnapshot，唯一 RETURNED 旧轮次
 * @param application ReturnedApplicationSnapshot，唯一申请人任务及迁移来源
 */
public record MultiInstanceGroupReopenPlan(MultiInstanceRoundSnapshot round,
        ReturnedApplicationSnapshot application)
{
    /**
     * 校验旧轮和申请人任务严格属于同一流程及正式关联。
     *
     * @return 无返回值，不一致计划拒绝构造
     */
    public MultiInstanceGroupReopenPlan
    {
        if (round == null || application == null
                || round.status() != WorkflowMultiInstanceRoundStatus.RETURNED
                || !round.processInstanceId().equals(application.processInstanceId())
                || !round.processDefinitionId().equals(
                        application.processDefinitionId())
                || round.applicantTaskId() == null
                || !round.applicantTaskId().equals(application.taskId()))
        {
            throw new IllegalArgumentException("多实例重提计划不一致");
        }
    }
}
