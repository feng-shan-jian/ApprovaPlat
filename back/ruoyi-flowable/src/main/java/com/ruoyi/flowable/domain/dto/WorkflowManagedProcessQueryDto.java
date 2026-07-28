package com.ruoyi.flowable.domain.dto;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 流程管理员跨用户实例运维查询条件。
 *
 * @param processInstanceId String，流程实例主键精确条件，允许为空
 * @param processKey String，流程定义标识精确条件，允许为空
 * @param processName String，流程定义名称模糊条件，允许为空
 * @param category String，流程分类精确条件，允许为空
 * @param businessKey String，业务主键精确条件，允许为空
 * @param startUserId String，流程发起人主键精确条件，允许为空
 * @param startedAfter Instant，流程开始时间下界，允许为空
 * @param startedBefore Instant，流程开始时间上界，允许为空
 */
public record WorkflowManagedProcessQueryDto(
        String processInstanceId,
        String processKey,
        String processName,
        String category,
        String businessKey,
        String startUserId,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startedAfter,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startedBefore)
{
}
