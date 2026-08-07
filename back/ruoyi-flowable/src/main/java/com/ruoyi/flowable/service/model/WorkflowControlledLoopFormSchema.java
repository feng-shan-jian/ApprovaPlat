package com.ruoyi.flowable.service.model;

import java.util.Map;

/**
 * 部署前从不可变节点表单快照提取的循环判断字段白名单。
 *
 * @param processKey String，表单节点所属可执行流程标识
 * @param activityId String，用户任务节点标识
 * @param fields Map&lt;String,WorkflowControlledLoopFormField&gt;，表单验证器提取的可写标量字段契约
 */
public record WorkflowControlledLoopFormSchema(String processKey, String activityId,
        Map<String, WorkflowControlledLoopFormField> fields)
{
    /**
     * 创建不可变字段白名单并拒绝空集合引用。
     * @return 无返回值，字段集合会被复制为不可修改集合
     */
    public WorkflowControlledLoopFormSchema
    {
        fields = Map.copyOf(fields);
    }
}
