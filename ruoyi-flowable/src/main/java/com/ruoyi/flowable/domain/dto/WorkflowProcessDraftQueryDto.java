package com.ruoyi.flowable.domain.dto;

import java.time.Instant;

/**
 * 当前用户草稿列表筛选条件。
 *
 * @param processName String，流程名称模糊条件
 * @param updatedAfter Instant，更新时间下界
 * @param updatedBefore Instant，更新时间上界
 */
public record WorkflowProcessDraftQueryDto(String processName,
        Instant updatedAfter, Instant updatedBefore)
{
}
