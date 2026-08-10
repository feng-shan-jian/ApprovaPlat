package com.ruoyi.flowable.domain.vo;

import com.ruoyi.flowable.domain.WorkflowInstanceState;

/**
 * 流程实例状态切换结果。
 *
 * @param instanceId String，Flowable 流程实例主键
 * @param state WorkflowInstanceState，事务提交后的目标状态
 * @param changed boolean，本次请求是否真实执行了状态变更
 */
public record WorkflowInstanceStateView(
        String instanceId,
        WorkflowInstanceState state,
        boolean changed)
{
}
