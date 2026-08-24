package com.ruoyi.flowable.service.task;

import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 中断监听器观察到受控根取消时获得的只读授权事实。
 *
 * @param action MultiInstanceTransitionAction，RETURN 或 REOPEN
 * @param roundId long，正式旧轮次主键
 * @param deployId String，部署主键
 * @param processDefinitionId String，流程定义主键
 * @param processInstanceId String，流程实例主键
 * @param activityId String，原受控多实例节点
 * @param roundRootExecutionId String，轮次冻结的原审批根
 * @param sourceTaskId String，原退回来源审批任务
 * @param applicantTaskId String，REOPEN 时申请人任务；RETURN 时为空
 * @param mode WorkflowMultiInstanceMode，冻结模式
 * @param members List&lt;String&gt;，冻结有序成员
 * @param revision int，冻结 revision
 */
public record MultiInstanceTransitionCancellation(MultiInstanceTransitionAction action,
        long roundId, String deployId, String processDefinitionId,
        String processInstanceId, String activityId, String roundRootExecutionId,
        String sourceTaskId, String applicantTaskId, WorkflowMultiInstanceMode mode,
        List<String> members, int revision)
{
    /**
     * 冻结成员并校验取消授权包含全部稳定业务事实。
     *
     * @return 无返回值，非法授权拒绝构造
     */
    public MultiInstanceTransitionCancellation
    {
        if (action == null || roundId <= 0 || !StringUtils.hasText(deployId)
                || !StringUtils.hasText(processDefinitionId)
                || !StringUtils.hasText(processInstanceId)
                || !StringUtils.hasText(activityId)
                || !StringUtils.hasText(roundRootExecutionId)
                || !StringUtils.hasText(sourceTaskId) || mode == null
                || members == null || members.isEmpty() || revision < 0
                || (action == MultiInstanceTransitionAction.REOPEN
                        && !StringUtils.hasText(applicantTaskId))
                || (action == MultiInstanceTransitionAction.RETURN
                        && applicantTaskId != null))
        {
            throw new IllegalArgumentException("多实例迁移取消授权不完整");
        }
        members = List.copyOf(members);
    }
}
