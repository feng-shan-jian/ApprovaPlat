package com.ruoyi.flowable.domain.dto;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 当前用户真实完成任务的查询条件。
 *
 * @param processKey String，流程定义标识模糊条件，允许为空
 * @param processName String，流程定义名称模糊条件，允许为空
 * @param category String，流程分类精确条件，允许为空
 * @param taskName String，任务名称模糊条件，允许为空
 * @param completedAfter Instant，任务完成时间下界，允许为空
 * @param completedBefore Instant，任务完成时间上界，允许为空
 */
public record WorkflowCompletedTaskQueryDto(
        String processKey,
        String processName,
        String category,
        String taskName,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant completedAfter,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant completedBefore)
{
}
