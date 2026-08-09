package com.ruoyi.flowable.domain.vo;

import org.springframework.util.StringUtils;

/**
 * 发起页面必须填写的受控会签或或签成员字段。
 *
 * @param activityId String，多实例用户任务节点标识
 * @param activityName String，部署 BPMN 中的节点显示名称
 * @param mode String，固定 ALL 会签或 ANY 或签
 * @param minUsers int，允许选择的最少正式用户数
 * @param maxUsers int，允许选择的最多正式用户数
 */
public record WorkflowStartMultiInstanceAssignmentView(String activityId,
        String activityName, String mode, int minUsers, int maxUsers)
{
    /**
     * 创建只读发起成员字段并拒绝不完整投影。
     *
     * @param activityId String，多实例用户任务节点标识。
     * @param activityName String，部署 BPMN 中的节点显示名称。
     * @param mode String，固定 ALL 会签或 ANY 或签。
     * @param minUsers int，允许选择的最少正式用户数。
     * @param maxUsers int，允许选择的最多正式用户数。
     * @return 无返回值；字段异常时抛出 IllegalArgumentException。
     */
    public WorkflowStartMultiInstanceAssignmentView
    {
        if (!StringUtils.hasText(activityId) || !StringUtils.hasText(activityName)
                || !("ALL".equals(mode) || "ANY".equals(mode))
                || minUsers < 1 || maxUsers < minUsers)
        {
            throw new IllegalArgumentException("发起多实例成员字段不完整");
        }
    }
}
