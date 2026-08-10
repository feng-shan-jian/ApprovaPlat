package com.ruoyi.flowable.engine;

/**
 * 流程实例的只读业务快照，隔离 Flowable 可变运行时对象与后续业务服务。
 *
 * @param id String，流程实例 ID
 * @param processDefinitionId String，流程定义 ID
 * @param businessKey String，可为空的业务主键
 * @param suspended boolean，流程实例是否已挂起
 */
public record WorkflowProcessInstanceSnapshot(String id, String processDefinitionId,
        String businessKey, boolean suspended)
{
}
