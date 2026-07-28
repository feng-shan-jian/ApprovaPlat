package com.ruoyi.flowable.domain.dto;

/**
 * 流程 BPMN XML 的可见性核验参数。
 *
 * @param definitionId String，流程定义主键
 * @param processInstanceId String，详情场景中的流程实例主键；发起场景为空
 */
public record WorkflowBpmnXmlQueryDto(String definitionId, String processInstanceId)
{
}
