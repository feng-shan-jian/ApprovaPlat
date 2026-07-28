package com.ruoyi.flowable.domain.dto;

/**
 * 流程发起表单快照的关系核验参数。
 *
 * @param definitionId String，流程定义主键
 * @param deploymentId String，客户端声明的部署主键，必须与定义及实例一致
 * @param processInstanceId String，重新查看实例表单时的实例主键，首次发起时为空
 */
public record WorkflowProcessFormQueryDto(
        String definitionId,
        String deploymentId,
        String processInstanceId)
{
}
