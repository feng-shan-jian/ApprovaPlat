package com.ruoyi.flowable.domain.vo;

import java.util.Date;

/**
 * 流程模型不可变视图。
 *
 * @param modelId String，Flowable 模型主键
 * @param modelName String，模型显示名称
 * @param modelKey String，模型版本分组标识
 * @param category String，工作流分类编码
 * @param version Integer，模型版本号
 * @param formType Integer，表单模式编码
 * @param formId Long，模型级流程表单主键
 * @param description String，模型业务描述
 * @param createTime Date，模型创建时间
 * @param lastUpdateTime Date，模型最后更新时间
 * @param bpmnXml String，详情场景返回的 BPMN XML
 * @param content String，详情场景返回的模型级表单内容
 * @param deployed boolean，模型是否已关联部署
 */
public record WorkflowModelView(String modelId, String modelName, String modelKey, String category,
        Integer version, Integer formType, Long formId, String description, Date createTime,
        Date lastUpdateTime, String bpmnXml, String content, boolean deployed)
{
    /**
     * 创建模型视图并复制可变时间对象。
     *
     * @param modelId String，Flowable 模型主键
     * @param modelName String，模型显示名称
     * @param modelKey String，模型版本分组标识
     * @param category String，工作流分类编码
     * @param version Integer，模型版本号
     * @param formType Integer，表单模式编码
     * @param formId Long，模型级流程表单主键
     * @param description String，模型业务描述
     * @param createTime Date，模型创建时间
     * @param lastUpdateTime Date，模型最后更新时间
     * @param bpmnXml String，详情场景返回的 BPMN XML
     * @param content String，详情场景返回的模型级表单内容
     * @param deployed boolean，模型是否已关联部署
     * @return 无返回值，构造后得到不可变模型视图
     */
    public WorkflowModelView
    {
        createTime = copyDate(createTime);
        lastUpdateTime = copyDate(lastUpdateTime);
    }

    /**
     * 返回模型创建时间副本，防止调用方修改视图内部状态。
     *
     * @return Date，模型创建时间副本
     */
    @Override
    public Date createTime()
    {
        return copyDate(createTime);
    }

    /**
     * 返回模型最后更新时间副本，防止调用方修改视图内部状态。
     *
     * @return Date，模型最后更新时间副本
     */
    @Override
    public Date lastUpdateTime()
    {
        return copyDate(lastUpdateTime);
    }

    /**
     * 复制可变 Date 对象。
     *
     * @param value Date，待复制时间，允许为空
     * @return Date，时间副本或 null
     */
    private static Date copyDate(Date value)
    {
        return value == null ? null : new Date(value.getTime());
    }
}
