package com.ruoyi.flowable.domain.vo;

import java.util.Objects;

/**
 * 当前任务允许退回的历史用户任务节点。
 *
 * @param id String，BPMN 用户任务节点主键，保留旧页面使用的字段名
 * @param name String，BPMN 用户任务节点名称，保留旧页面使用的字段名
 */
public record WorkflowReturnNodeView(String id, String name)
{
    /**
     * 创建不可变可退节点并拒绝缺失的服务端模型数据。
     *
     * @param id String，BPMN 用户任务节点主键
     * @param name String，BPMN 用户任务节点名称
     * @return 无返回值，构造后节点字段不可变
     */
    public WorkflowReturnNodeView
    {
        Objects.requireNonNull(id, "可退节点主键不能为空");
        Objects.requireNonNull(name, "可退节点名称不能为空");
    }
}
