package com.ruoyi.flowable.domain.vo;

import java.time.Instant;

/**
 * Flowable 历史表中同一 CallActivity 根执行树的流程实例关系。
 *
 * @param processInstanceId String，当前关系节点的流程实例主键
 * @param parentProcessInstanceId String，直接父流程实例主键，根实例为空
 * @param rootProcessInstanceId String，整棵调用树的根流程实例主键
 * @param definitionId String，当前节点实际运行的不可变流程定义主键
 * @param processKey String，当前节点流程定义 key
 * @param processName String，当前节点流程名称
 * @param version int，当前节点实际运行版本
 * @param businessKey String，当前节点业务主键，允许为空
 * @param processStatus String，当前节点稳定运行或终态
 * @param startTime Instant，当前节点开始时间
 * @param endTime Instant，当前节点结束时间，运行中为空
 * @param current boolean，是否为详情页正在查看的流程实例
 */
public record WorkflowProcessRelationView(
        String processInstanceId,
        String parentProcessInstanceId,
        String rootProcessInstanceId,
        String definitionId,
        String processKey,
        String processName,
        int version,
        String businessKey,
        String processStatus,
        Instant startTime,
        Instant endTime,
        boolean current)
{
}
