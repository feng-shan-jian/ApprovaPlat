package com.ruoyi.flowable.service.task;

import org.springframework.util.StringUtils;

/**
 * RETURNED 期间唯一申请人任务与来源 execution 的不可变事实。
 *
 * @param taskId String，申请人待修改任务主键
 * @param executionId String，任务实际 child execution 主键
 * @param sourceExecutionId String，重提状态迁移使用的普通 execution 或临时多实例根
 * @param processInstanceId String，流程实例主键
 * @param processDefinitionId String，流程定义主键
 * @param activityId String，申请人任务当前节点
 * @param applicantUserId String，流程正式发起人主键
 * @param sourceKind SourceKind，普通任务或首节点临时多实例根
 */
public record ReturnedApplicationSnapshot(String taskId, String executionId,
        String sourceExecutionId, String processInstanceId,
        String processDefinitionId, String activityId, String applicantUserId,
        SourceKind sourceKind)
{
    /**
     * 校验申请人任务和迁移来源包含全部稳定身份。
     *
     * @return 无返回值，缺失字段拒绝构造
     */
    public ReturnedApplicationSnapshot
    {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(executionId)
                || !StringUtils.hasText(sourceExecutionId)
                || !StringUtils.hasText(processInstanceId)
                || !StringUtils.hasText(processDefinitionId)
                || !StringUtils.hasText(activityId)
                || !StringUtils.hasText(applicantUserId) || sourceKind == null)
        {
            throw new IllegalArgumentException("退回申请任务快照不完整");
        }
    }

    /** 申请人待修改任务的 execution 结构类型。 */
    public enum SourceKind
    {
        /** 普通串行首审批任务。 */
        ORDINARY_EXECUTION,

        /** 首审批本身为受控多实例时产生的临时单成员根。 */
        TEMPORARY_MULTI_INSTANCE_ROOT
    }
}
