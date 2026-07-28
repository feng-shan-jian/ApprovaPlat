package com.ruoyi.flowable.domain.vo;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 当前用户发起的流程实例视图。
 *
 * @param processInstanceId String，流程实例主键
 * @param definitionId String，流程定义主键
 * @param processKey String，流程定义标识
 * @param processName String，流程定义名称
 * @param version int，流程定义版本
 * @param category String，流程分类编码
 * @param deploymentId String，部署主键
 * @param businessKey String，业务主键，允许为空
 * @param startUserId String，流程发起人主键
 * @param startTime Instant，流程开始时间
 * @param endTime Instant，流程结束时间，运行中为空
 * @param durationMillis Long，流程耗时毫秒，运行中为空
 * @param processStatus String，running/completed/terminated/canceled 等稳定状态
 * @param currentTaskNames List&lt;String&gt;，当前活动任务名称
 */
public record WorkflowOwnedProcessView(
        String processInstanceId,
        String definitionId,
        String processKey,
        String processName,
        int version,
        String category,
        String deploymentId,
        String businessKey,
        String startUserId,
        Instant startTime,
        Instant endTime,
        Long durationMillis,
        String processStatus,
        List<String> currentTaskNames)
{
    /**
     * 创建流程实例视图并复制活动任务名称，防止调用方修改领域结果。
     *
     * @param processInstanceId String，流程实例主键
     * @param definitionId String，流程定义主键
     * @param processKey String，流程定义标识
     * @param processName String，流程定义名称
     * @param version int，流程定义版本
     * @param category String，流程分类编码
     * @param deploymentId String，部署主键
     * @param businessKey String，业务主键，允许为空
     * @param startUserId String，流程发起人主键
     * @param startTime Instant，流程开始时间
     * @param endTime Instant，流程结束时间，允许为空
     * @param durationMillis Long，流程耗时毫秒，允许为空
     * @param processStatus String，稳定流程状态
     * @param currentTaskNames List&lt;String&gt;，当前活动任务名称
     * @return 无返回值，构造后得到不可变视图
     */
    public WorkflowOwnedProcessView
    {
        currentTaskNames = List.copyOf(Objects.requireNonNull(currentTaskNames, "活动任务名称不能为空"));
    }
}
