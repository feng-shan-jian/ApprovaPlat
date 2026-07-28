package com.ruoyi.flowable.domain.vo;

import java.time.Instant;

/**
 * 当前用户可发起的最新激活流程定义视图。
 *
 * @param definitionId String，流程定义主键
 * @param processKey String，流程定义标识
 * @param processName String，流程定义名称
 * @param category String，流程分类编码
 * @param version int，流程定义版本
 * @param deploymentId String，部署主键
 * @param deploymentTime Instant，部署时间
 */
public record WorkflowStartableDefinitionView(
        String definitionId,
        String processKey,
        String processName,
        String category,
        int version,
        String deploymentId,
        Instant deploymentTime)
{
}
