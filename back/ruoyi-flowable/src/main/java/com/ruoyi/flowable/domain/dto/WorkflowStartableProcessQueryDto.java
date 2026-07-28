package com.ruoyi.flowable.domain.dto;

/**
 * 当前用户可发起流程定义的查询条件。
 *
 * @param processKey String，流程定义标识模糊条件，允许为空
 * @param processName String，流程定义名称模糊条件，允许为空
 * @param category String，流程分类精确条件，允许为空
 */
public record WorkflowStartableProcessQueryDto(
        String processKey,
        String processName,
        String category)
{
}
