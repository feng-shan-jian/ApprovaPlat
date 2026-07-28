package com.ruoyi.flowable.domain.vo;

import com.ruoyi.common.annotation.Excel;

/**
 * 工作流分类导出视图，仅包含允许离线分发的业务字段。
 */
public class WfCategoryExportView
{
    /** 分类主键。 */
    @Excel(name = "分类ID", cellType = Excel.ColumnType.NUMERIC)
    private final Long categoryId;

    /** 分类名称。 */
    @Excel(name = "分类名称")
    private final String categoryName;

    /** 分类编码。 */
    @Excel(name = "分类编码")
    private final String code;

    /** 分类备注。 */
    @Excel(name = "备注")
    private final String remark;

    /**
     * 创建分类导出视图。
     *
     * @param categoryId Long，分类主键
     * @param categoryName String，分类名称
     * @param code String，分类编码
     * @param remark String，分类备注
     * @return 无返回值，构造后得到只读导出对象
     */
    public WfCategoryExportView(Long categoryId, String categoryName, String code, String remark)
    {
        this.categoryId = categoryId;
        this.categoryName = categoryName;
        this.code = code;
        this.remark = remark;
    }

    /**
     * 获取分类主键。
     *
     * @return Long，分类主键
     */
    public Long getCategoryId()
    {
        return categoryId;
    }

    /**
     * 获取分类名称。
     *
     * @return String，分类名称
     */
    public String getCategoryName()
    {
        return categoryName;
    }

    /**
     * 获取分类编码。
     *
     * @return String，分类编码
     */
    public String getCode()
    {
        return code;
    }

    /**
     * 获取分类备注。
     *
     * @return String，分类备注
     */
    public String getRemark()
    {
        return remark;
    }
}
