package com.ruoyi.flowable.domain.vo;

import java.util.List;
import java.util.Objects;

/**
 * 当前设计者有权引用的已发布子流程目录项。
 *
 * @param definitionId String，Flowable 流程定义主键
 * @param processKey String，流程定义 key
 * @param processName String，流程定义名称
 * @param version int，流程定义版本
 * @param category String，流程分类编码
 * @param deploymentId String，流程部署主键
 * @param status String，ACTIVE 或 SUSPENDED
 * @param inputFields List&lt;WorkflowCallActivityVariableView&gt;，子流程开始表单可写字段
 * @param outputFields List&lt;WorkflowCallActivityVariableView&gt;，子流程全部表单可读字段
 */
public record WorkflowCallActivityOptionView(String definitionId, String processKey,
        String processName, int version, String category, String deploymentId, String status,
        List<WorkflowCallActivityVariableView> inputFields,
        List<WorkflowCallActivityVariableView> outputFields)
{
    /**
     * 创建不可变目录项并复制变量字段集合。
     *
     * @param definitionId String，Flowable 流程定义主键
     * @param processKey String，流程定义 key
     * @param processName String，流程定义名称
     * @param version int，流程定义版本
     * @param category String，流程分类编码
     * @param deploymentId String，流程部署主键
     * @param status String，ACTIVE 或 SUSPENDED
     * @param inputFields List&lt;WorkflowCallActivityVariableView&gt;，输入字段
     * @param outputFields List&lt;WorkflowCallActivityVariableView&gt;，输出字段
     * @return 无返回值，构造后列表不可变
     */
    public WorkflowCallActivityOptionView
    {
        inputFields = List.copyOf(Objects.requireNonNull(inputFields, "调用活动输入字段不能为空"));
        outputFields = List.copyOf(Objects.requireNonNull(outputFields, "调用活动输出字段不能为空"));
    }
}
