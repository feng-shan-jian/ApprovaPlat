package com.ruoyi.flowable.domain.vo;

import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.time.Instant;

/**
 * BPMN Viewer 所需的活动状态集合。
 *
 * @param finishedActivityIds Set&lt;String&gt;，已完成的非连线活动主键
 * @param finishedSequenceFlowIds Set&lt;String&gt;，已走过的顺序流主键
 * @param unfinishedActivityIds Set&lt;String&gt;，当前未完成活动主键
 * @param rejectedActivityIds Set&lt;String&gt;，存在驳回意见或原因的任务节点主键
 * @param returnedActivityIds Set&lt;String&gt;，存在退回意见或原因的任务节点主键
 */
public record WorkflowProcessViewerView(
        Set<String> finishedActivityIds,
        Set<String> finishedSequenceFlowIds,
        Set<String> unfinishedActivityIds,
        Set<String> rejectedActivityIds,
        Set<String> returnedActivityIds,
        Set<String> cancelledActivityIds,
        Set<String> unknownActivityIds,
        List<ActivityState> activityInstances)
{
    public record ActivityState(String activityInstanceId, String activityId, String activityName,
            String state, Instant startTime, Instant endTime) { }

    public WorkflowProcessViewerView(Set<String> finishedActivityIds, Set<String> finishedSequenceFlowIds,
            Set<String> unfinishedActivityIds, Set<String> rejectedActivityIds, Set<String> returnedActivityIds)
    {
        this(finishedActivityIds, finishedSequenceFlowIds, unfinishedActivityIds, rejectedActivityIds,
                returnedActivityIds, Set.of(), Set.of(), List.of());
    }
    /**
     * 创建 Viewer 状态并复制全部集合。
     *
     * 构造后状态集合不可变
     */
    public WorkflowProcessViewerView
    {
        finishedActivityIds = Set.copyOf(Objects.requireNonNull(
                finishedActivityIds, "已完成活动不能为空"));
        finishedSequenceFlowIds = Set.copyOf(Objects.requireNonNull(
                finishedSequenceFlowIds, "已完成连线不能为空"));
        unfinishedActivityIds = Set.copyOf(Objects.requireNonNull(
                unfinishedActivityIds, "未完成活动不能为空"));
        rejectedActivityIds = Set.copyOf(Objects.requireNonNull(
                rejectedActivityIds, "驳回活动不能为空"));
        returnedActivityIds = Set.copyOf(Objects.requireNonNull(
                returnedActivityIds, "退回活动不能为空"));
        cancelledActivityIds = Set.copyOf(cancelledActivityIds);
        unknownActivityIds = Set.copyOf(unknownActivityIds);
        activityInstances = List.copyOf(activityInstances);
    }
}
