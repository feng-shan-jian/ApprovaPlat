package com.ruoyi.flowable.domain.dto;

import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 当前用户发起流程实例的查询条件。
 *
 * @param processKey String，流程定义标识精确条件，允许为空
 * @param processName String，流程定义名称模糊条件，允许为空
 * @param category String，流程分类精确条件，允许为空
 * @param businessKey String，业务主键精确条件，允许为空
 * @param startedAfter Instant，流程开始时间下界，允许为空
 * @param startedBefore Instant，流程开始时间上界，允许为空
 */
public record WorkflowOwnedProcessQueryDto(
        String processKey,
        String processName,
        String category,
        String businessKey,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startedAfter,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startedBefore)
{
}
