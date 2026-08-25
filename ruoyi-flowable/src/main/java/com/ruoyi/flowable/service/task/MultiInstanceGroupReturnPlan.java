package com.ruoyi.flowable.service.task;

import java.util.List;
import org.springframework.util.StringUtils;
import com.ruoyi.flowable.domain.WorkflowMultiInstanceRoundStatus;

/**
 * 整组退回写命令开始前冻结的不可变正式计划。
 *
 * @param round MultiInstanceRoundSnapshot，唯一 ACTIVE 轮次
 * @param runtime ControlledMultiInstanceSnapshot，与轮次逐项对账的实时执行树
 * @param applicantUserId String，流程实例正式发起人主键
 */
public record MultiInstanceGroupReturnPlan(MultiInstanceRoundSnapshot round,
        ControlledMultiInstanceSnapshot runtime, String applicantUserId)
{
    /**
     * 校验 ACTIVE 轮次、实时根和流程发起人属于同一业务事实。
     *
     * @return 无返回值，不一致计划拒绝构造
     */
    public MultiInstanceGroupReturnPlan
    {
        if (round == null || runtime == null || !StringUtils.hasText(applicantUserId)
                || round.status() != WorkflowMultiInstanceRoundStatus.ACTIVE
                || !round.deployId().equals(runtime.deployId())
                || !round.processDefinitionId().equals(runtime.processDefinitionId())
                || !round.processInstanceId().equals(runtime.processInstanceId())
                || !round.activityId().equals(runtime.activityId())
                || !round.rootExecutionId().equals(runtime.rootExecutionId())
                || round.mode() != runtime.mode()
                || !round.members().equals(runtime.members())
                || round.revision() != runtime.revision())
        {
            throw new IllegalArgumentException("多实例整组退回计划不一致");
        }
    }

    /**
     * 返回准备阶段冻结的全部活动成员任务主键。
     *
     * @return List&lt;String&gt;，保持运行时稳定任务顺序的不可变主键列表
     */
    public List<String> activeTaskIds()
    {
        return runtime.activeTasks().stream()
                .map(MultiInstanceActiveTaskSnapshot::taskId).toList();
    }
}
