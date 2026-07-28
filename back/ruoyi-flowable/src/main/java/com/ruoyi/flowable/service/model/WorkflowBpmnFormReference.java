package com.ruoyi.flowable.service.model;

import java.util.Objects;

/**
 * BPMN 节点中已经通过格式校验的表单引用。
 *
 * @param formId Long，解析自 key_&lt;formId&gt; 的正数表单主键
 * @param formKey String，BPMN 原始表单键
 * @param nodeKey String，BPMN 节点主键
 * @param nodeName String，BPMN 节点名称，未配置时为空字符串
 */
public record WorkflowBpmnFormReference(Long formId, String formKey, String nodeKey, String nodeName)
{
    /**
     * 创建已经校验的 BPMN 表单引用。
     *
     * @param formId Long，解析自 key_&lt;formId&gt; 的正数表单主键
     * @param formKey String，BPMN 原始表单键
     * @param nodeKey String，BPMN 节点主键
     * @param nodeName String，BPMN 节点名称，允许为空
     * @return 无返回值，构造后得到不可变表单引用
     */
    public WorkflowBpmnFormReference
    {
        Objects.requireNonNull(formId, "表单主键不能为空");
        Objects.requireNonNull(formKey, "表单键不能为空");
        Objects.requireNonNull(nodeKey, "节点主键不能为空");
        if (formId <= 0)
        {
            throw new IllegalArgumentException("表单主键必须为正数");
        }
        nodeName = nodeName == null ? "" : nodeName;
    }
}
