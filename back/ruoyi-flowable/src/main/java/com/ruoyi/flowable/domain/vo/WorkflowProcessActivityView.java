package com.ruoyi.flowable.domain.vo;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 流程历史时间线中的开始、用户任务或结束活动视图。
 *
 * @param activityId String，BPMN 活动主键
 * @param activityName String，活动显示名称，允许为空
 * @param activityType String，Flowable 活动类型
 * @param taskId String，用户任务主键，非任务活动为空
 * @param assigneeId String，任务结束时办理人主键，允许为空
 * @param assigneeName String，任务办理人显示名称，允许为空
 * @param completedById String，Flowable 记录的真实完成人主键，允许为空
 * @param completedByName String，真实完成人显示名称，允许为空
 * @param candidates List&lt;WorkflowCandidateIdentityView&gt;，候选用户和候选组
 * @param comments List&lt;WorkflowProcessCommentView&gt;，该任务的受控审批意见
 * @param startTime Instant，活动开始时间，允许为空
 * @param endTime Instant，活动结束时间，允许为空
 * @param durationMillis Long，活动耗时毫秒，允许为空
 * @param deleteReason String，活动删除或跳转原因，允许为空
 */
public record WorkflowProcessActivityView(
        String activityId,
        String activityName,
        String activityType,
        String taskId,
        String assigneeId,
        String assigneeName,
        String completedById,
        String completedByName,
        List<WorkflowCandidateIdentityView> candidates,
        List<WorkflowProcessCommentView> comments,
        Instant startTime,
        Instant endTime,
        Long durationMillis,
        String deleteReason)
{
    /**
     * 创建活动视图并复制候选身份与意见集合。
     *
     * @param activityId String，BPMN 活动主键
     * @param activityName String，活动显示名称，允许为空
     * @param activityType String，Flowable 活动类型
     * @param taskId String，用户任务主键，非任务活动为空
     * @param assigneeId String，任务结束时办理人主键，允许为空
     * @param assigneeName String，任务办理人显示名称，允许为空
     * @param completedById String，Flowable 记录的真实完成人主键，允许为空
     * @param completedByName String，真实完成人显示名称，允许为空
     * @param candidates List&lt;WorkflowCandidateIdentityView&gt;，候选身份集合
     * @param comments List&lt;WorkflowProcessCommentView&gt;，审批意见集合
     * @param startTime Instant，活动开始时间，允许为空
     * @param endTime Instant，活动结束时间，允许为空
     * @param durationMillis Long，活动耗时毫秒，允许为空
     * @param deleteReason String，活动删除或跳转原因，允许为空
     * @return 无返回值，构造后集合不可被调用方修改
     */
    public WorkflowProcessActivityView
    {
        candidates = List.copyOf(Objects.requireNonNull(candidates, "候选身份不能为空"));
        comments = List.copyOf(Objects.requireNonNull(comments, "审批意见不能为空"));
    }
}
