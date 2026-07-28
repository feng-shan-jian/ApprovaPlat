package com.ruoyi.flowable.domain;

import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 工作流分类，对应业务表 {@code wf_category}。
 */
public class WfCategory extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 分类主键。 */
    private Long categoryId;

    /** 分类名称。 */
    private String categoryName;

    /** Flowable 模型及流程定义使用的分类编码。 */
    private String code;

    /** 逻辑删除标志：0 表示有效，2 表示已删除。 */
    private String delFlag;

    /**
     * 获取分类主键。
     * @return Long，分类主键
     */
    public Long getCategoryId()
    {
        return categoryId;
    }

    /**
     * 设置分类主键。
     * @param categoryId Long，分类主键
     * @return void，无返回值
     */
    public void setCategoryId(Long categoryId)
    {
        this.categoryId = categoryId;
    }

    /**
     * 获取分类名称。
     * @return String，分类名称
     */
    public String getCategoryName()
    {
        return categoryName;
    }

    /**
     * 设置分类名称。
     * @param categoryName String，分类名称
     * @return void，无返回值
     */
    public void setCategoryName(String categoryName)
    {
        this.categoryName = categoryName;
    }

    /**
     * 获取分类编码。
     * @return String，分类编码
     */
    public String getCode()
    {
        return code;
    }

    /**
     * 设置分类编码。
     * @param code String，分类编码
     * @return void，无返回值
     */
    public void setCode(String code)
    {
        this.code = code;
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
