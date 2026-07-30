package com.ruoyi.flowable.domain.dto;

/**
 * 流程模型新增、修改、设计保存和查询条件的数据传输对象。
 */
public class WorkflowModelDto
{
    /** Flowable 模型主键。 */
    private String modelId;

    /** 模型显示名称。 */
    private String modelName;

    /** 模型版本分组标识。 */
    private String modelKey;

    /** 工作流分类编码。 */
    private String category;

    /** 模型业务描述。 */
    private String description;

    /** 表单模式：0 流程表单、1 外置表单、2 节点独立表单。 */
    private Integer formType;

    /** 模型级流程表单主键。 */
    private Long formId;

    /** BPMN 2.0 XML 正文。 */
    private String bpmnXml;

    /** 用户本次保存意图的 UUID 幂等键。 */
    private String saveRequestId;

    /** 保存设计时是否显式创建新模型版本；已部署或历史版本会由服务端自动创建新版本。 */
    private Boolean newVersion;

    /**
     * 获取 Flowable 模型主键。
     *
     * @return String，Flowable 模型主键
     */
    public String getModelId()
    {
        return modelId;
    }

    /**
     * 设置 Flowable 模型主键。
     *
     * @param modelId String，Flowable 模型主键
     * @return 无返回值
     */
    public void setModelId(String modelId)
    {
        this.modelId = modelId;
    }

    /**
     * 获取模型显示名称。
     *
     * @return String，模型显示名称
     */
    public String getModelName()
    {
        return modelName;
    }

    /**
     * 设置模型显示名称。
     *
     * @param modelName String，模型显示名称
     * @return 无返回值
     */
    public void setModelName(String modelName)
    {
        this.modelName = modelName;
    }

    /**
     * 获取模型版本分组标识。
     *
     * @return String，模型版本分组标识
     */
    public String getModelKey()
    {
        return modelKey;
    }

    /**
     * 设置模型版本分组标识。
     *
     * @param modelKey String，模型版本分组标识
     * @return 无返回值
     */
    public void setModelKey(String modelKey)
    {
        this.modelKey = modelKey;
    }

    /**
     * 获取工作流分类编码。
     *
     * @return String，工作流分类编码
     */
    public String getCategory()
    {
        return category;
    }

    /**
     * 设置工作流分类编码。
     *
     * @param category String，工作流分类编码
     * @return 无返回值
     */
    public void setCategory(String category)
    {
        this.category = category;
    }

    /**
     * 获取模型业务描述。
     *
     * @return String，模型业务描述
     */
    public String getDescription()
    {
        return description;
    }

    /**
     * 设置模型业务描述。
     *
     * @param description String，模型业务描述
     * @return 无返回值
     */
    public void setDescription(String description)
    {
        this.description = description;
    }

    /**
     * 获取表单模式。
     *
     * @return Integer，表单模式编码
     */
    public Integer getFormType()
    {
        return formType;
    }

    /**
     * 设置表单模式。
     *
     * @param formType Integer，表单模式编码
     * @return 无返回值
     */
    public void setFormType(Integer formType)
    {
        this.formType = formType;
    }

    /**
     * 获取模型级流程表单主键。
     *
     * @return Long，模型级流程表单主键
     */
    public Long getFormId()
    {
        return formId;
    }

    /**
     * 设置模型级流程表单主键。
     *
     * @param formId Long，模型级流程表单主键
     * @return 无返回值
     */
    public void setFormId(Long formId)
    {
        this.formId = formId;
    }

    /**
     * 获取 BPMN 2.0 XML 正文。
     *
     * @return String，BPMN 2.0 XML 正文
     */
    public String getBpmnXml()
    {
        return bpmnXml;
    }

    /**
     * 设置 BPMN 2.0 XML 正文。
     *
     * @param bpmnXml String，BPMN 2.0 XML 正文
     * @return 无返回值
     */
    public void setBpmnXml(String bpmnXml)
    {
        this.bpmnXml = bpmnXml;
    }

    /**
     * 获取用户本次保存意图的幂等键。
     *
     * @return String，符合 UUID 格式的保存请求主键
     */
    public String getSaveRequestId()
    {
        return saveRequestId;
    }

    /**
     * 设置用户本次保存意图的幂等键。
     *
     * @param saveRequestId String，符合 UUID 格式的保存请求主键
     * @return 无返回值
     */
    public void setSaveRequestId(String saveRequestId)
    {
        this.saveRequestId = saveRequestId;
    }

    /**
     * 获取是否创建新模型版本。
     *
     * @return Boolean，true 表示显式创建新版本，false 仍会由服务端按部署和历史版本状态判定
     */
    public Boolean getNewVersion()
    {
        return newVersion;
    }

    /**
     * 设置是否创建新模型版本。
     *
     * @param newVersion Boolean，true 表示显式创建新版本，false 表示由服务端按版本状态自动判定
     * @return 无返回值
     */
    public void setNewVersion(Boolean newVersion)
    {
        this.newVersion = newVersion;
    }
}
