package com.ruoyi.flowable.domain.vo;

import com.ruoyi.common.annotation.Excel;

/**
 * 工作流表单导出视图，刻意排除可能含敏感业务字段和规则的 JSON 正文。
 */
public class WfFormExportView
{
    /** 表单主键。 */
    @Excel(name = "表单ID", cellType = Excel.ColumnType.NUMERIC)
    private final Long formId;

    /** 表单名称。 */
    @Excel(name = "表单名称")
    private final String formName;

    /** 表单备注。 */
    @Excel(name = "备注")
    private final String remark;

    /**
     * 创建不含模板正文的表单导出视图。
     *
     * @param formId Long，表单主键
     * @param formName String，表单名称
     * @param remark String，表单备注
     * @return 无返回值，构造后得到只读导出对象
     */
    public WfFormExportView(Long formId, String formName, String remark)
    {
        this.formId = formId;
        this.formName = formName;
        this.remark = remark;
    }

    /**
     * 获取表单主键。
     *
     * @return Long，表单主键
     */
    public Long getFormId()
    {
        return formId;
    }

    /**
     * 获取表单名称。
     *
     * @return String，表单名称
     */
    public String getFormName()
    {
        return formName;
    }

    /**
     * 获取表单备注。
     *
     * @return String，表单备注
     */
    public String getRemark()
    {
        return remark;
    }
}
