package com.ruoyi.flowable.service.model;

import java.util.Objects;

/**
 * BPMN 节点中已经通过格式校验的表单引用。
 *
 * @param sourceType WorkflowFormSourceType，正式模板或 BPMN 内嵌表单
 * @param formId Long，模板来源主键；内嵌表单为空
 * @param formKey String，BPMN 原始表单键或内嵌表单稳定键
 * @param nodeKey String，BPMN 节点主键
 * @param nodeName String，BPMN 节点名称，未配置时为空字符串
 * @param embeddedContent String，内嵌表单转换后的正式 JSON；模板来源为空
 * @param processKey String，表单节点所属可执行流程标识；兼容旧单元测试时允许为空字符串
 */
public record WorkflowBpmnFormReference(WorkflowFormSourceType sourceType, Long formId,
        String formKey, String nodeKey, String nodeName, String embeddedContent,
        String processKey)
{
    /**
     * 兼容既有正式模板调用方的简化构造器。
     *
     * @param formId Long，正式 wf_form 主键
     * @param formKey String，key_正整数格式的 BPMN 表单键
     * @param nodeKey String，BPMN 节点主键
     * @param nodeName String，BPMN 节点名称
     * @return 无返回值，构造 TEMPLATE 来源引用
     */
    public WorkflowBpmnFormReference(Long formId, String formKey,
            String nodeKey, String nodeName)
    {
        this(WorkflowFormSourceType.TEMPLATE, formId, formKey, nodeKey, nodeName, null, "");
    }

    /**
     * 兼容尚不需要流程级扩展快照的既有调用方。
     * @param sourceType WorkflowFormSourceType，表单来源类型
     * @param formId Long，模板来源主键；内嵌表单为空
     * @param formKey String，BPMN 原始表单键或内嵌表单稳定键
     * @param nodeKey String，BPMN 节点主键
     * @param nodeName String，BPMN 节点名称
     * @param embeddedContent String，内嵌表单正式 JSON；模板来源为空
     * @return 无返回值，流程标识使用空字符串，仅供既有测试构造
     */
    public WorkflowBpmnFormReference(WorkflowFormSourceType sourceType, Long formId,
            String formKey, String nodeKey, String nodeName, String embeddedContent)
    {
        this(sourceType, formId, formKey, nodeKey, nodeName, embeddedContent, "");
    }

    /**
     * 创建已经校验的 BPMN 表单引用。
     *
     * @param sourceType WorkflowFormSourceType，表单来源类型
     * @param formId Long，模板来源主键；内嵌表单为空
     * @param formKey String，BPMN 原始表单键或内嵌稳定键
     * @param nodeKey String，BPMN 节点主键
     * @param nodeName String，BPMN 节点名称，允许为空
     * @param embeddedContent String，内嵌表单正式 JSON；模板来源为空
     * @param processKey String，表单节点所属可执行流程标识
     * @return 无返回值，构造后得到不可变表单引用
     */
    public WorkflowBpmnFormReference
    {
        Objects.requireNonNull(sourceType, "表单来源类型不能为空");
        Objects.requireNonNull(formKey, "表单键不能为空");
        Objects.requireNonNull(nodeKey, "节点主键不能为空");
        Objects.requireNonNull(processKey, "流程标识不能为空");
        if (!WorkflowFormSourceType.isConsistent(sourceType.name(), formId))
        {
            throw new IllegalArgumentException("表单来源类型与主键不一致");
        }
        if ((sourceType == WorkflowFormSourceType.EMBEDDED) != (embeddedContent != null))
        {
            throw new IllegalArgumentException("内嵌表单正文与来源类型不一致");
        }
        nodeName = nodeName == null ? "" : nodeName;
    }
}
