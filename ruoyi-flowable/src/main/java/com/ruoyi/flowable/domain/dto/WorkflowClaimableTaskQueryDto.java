package com.ruoyi.flowable.domain.dto;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 当前用户可认领未分配任务的查询条件。
 *
 * @param processKey String，流程定义标识模糊条件，允许为空
 * @param processName String，流程定义名称模糊条件，允许为空
 * @param category String，流程分类精确条件，允许为空
 * @param taskName String，任务名称模糊条件，允许为空
 * @param createdAfter Instant，任务创建时间下界，允许为空
 * @param createdBefore Instant，任务创建时间上界，允许为空
 */
public record WorkflowClaimableTaskQueryDto(
        String processKey,
        String processName,
        String category,
        String taskName,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdAfter,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore)
{
}
