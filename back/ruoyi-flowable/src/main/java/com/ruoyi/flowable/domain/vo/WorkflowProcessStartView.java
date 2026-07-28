package com.ruoyi.flowable.domain.vo;

/**
 * 流程发起成功响应，同时保留旧前端使用的 procInsId 字段别名。
 *
 * @param processInstanceId String，新建 Flowable 流程实例主键
 * @param procInsId String，兼容旧前端的流程实例主键别名
 * @param processDefinitionId String，实际使用的路径流程定义主键
 * @param businessKey String，可为空的外部业务主键
 */
public record WorkflowProcessStartView(
        String processInstanceId,
        String procInsId,
        String processDefinitionId,
        String businessKey)
{
}
