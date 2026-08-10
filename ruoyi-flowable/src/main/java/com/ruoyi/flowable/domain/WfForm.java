package com.ruoyi.flowable.domain;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 可编辑工作流表单模板，对应业务表 {@code wf_form}。
 */
public class WfForm extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 表单主键。 */
    private Long formId;

    /** 表单名称。 */
    private String formName;

    /** 表单设计器产生的 JSON 模板。 */
    private String content;

    /** 逻辑删除标志：0 表示有效，2 表示已删除。 */
    private String delFlag;

    /**
     * 获取表单主键。
     * @return Long，表单主键
     */
    public Long getFormId()
    {
        return formId;
    }

    /**
     * 设置表单主键。
     * @param formId Long，表单主键
     * @return void，无返回值
     */
    public void setFormId(Long formId)
    {
        this.formId = formId;
    }

    /**
     * 获取表单名称。
     * @return String，表单名称
     */
    public String getFormName()
    {
        return formName;
    }

    /**
     * 设置表单名称。
     * @param formName String，表单名称
     * @return void，无返回值
     */
    public void setFormName(String formName)
    {
        this.formName = formName;
    }

    /**
     * 获取表单 JSON 内容。
     * @return String，表单 JSON 内容
     */
    public String getContent()
    {
        return content;
    }

    /**
     * 设置表单 JSON 内容。
     * @param content String，表单 JSON 内容
     * @return void，无返回值
     */
    public void setContent(String content)
    {
        this.content = content;
    }

    /**
     * 获取逻辑删除标志。
     * @return String，0 表示有效，2 表示已删除
     */
    public String getDelFlag()
    {
        return delFlag;
    }

    /**
     * 设置逻辑删除标志。
     * @param delFlag String，逻辑删除标志
     * @return void，无返回值
     */
    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }
}
