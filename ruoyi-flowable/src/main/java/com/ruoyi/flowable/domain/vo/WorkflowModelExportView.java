package com.ruoyi.flowable.domain.vo;

import java.util.Date;
import com.ruoyi.common.annotation.Excel;

/**
 * 流程模型元数据导出视图，不包含 BPMN XML 或表单正文。
 */
public class WorkflowModelExportView
{
    /** Flowable 模型主键。 */
    @Excel(name = "模型ID")
    private final String modelId;

    /** 模型标识。 */
    @Excel(name = "模型Key")
    private final String modelKey;

    /** 模型名称。 */
    @Excel(name = "模型名称")
    private final String modelName;

    /** 分类编码。 */
    @Excel(name = "分类编码")
    private final String category;

    /** 分类名称。 */
    @Excel(name = "流程分类")
    private final String categoryName;

    /** 模型版本。 */
    @Excel(name = "模型版本", cellType = Excel.ColumnType.NUMERIC)
    private final Integer version;

    /** 模型描述。 */
    @Excel(name = "模型描述")
    private final String description;

    /** 模型创建时间。 */
    @Excel(name = "创建时间", dateFormat = "yyyy-MM-dd HH:mm:ss")
    private final Date createTime;

    /**
     * 创建流程模型导出视图并复制可变时间对象。
     *
     * @param modelId String，Flowable 模型主键
     * @param modelKey String，模型标识
     * @param modelName String，模型名称
     * @param category String，分类编码
     * @param categoryName String，分类名称
     * @param version Integer，模型版本
     * @param description String，模型描述
     * @param createTime Date，模型创建时间
     * @return 无返回值，构造后得到只读导出对象
     */
    public WorkflowModelExportView(String modelId, String modelKey, String modelName,
            String category, String categoryName, Integer version, String description,
            Date createTime)
    {
        this.modelId = modelId;
        this.modelKey = modelKey;
        this.modelName = modelName;
        this.category = category;
        this.categoryName = categoryName;
        this.version = version;
        this.description = description;
        this.createTime = copyDate(createTime);
    }

    /**
     * 获取模型主键。
     *
     * @return String，模型主键
     */
    public String getModelId()
    {
        return modelId;
    }

    /**
     * 获取模型标识。
     *
     * @return String，模型标识
     */
    public String getModelKey()
    {
        return modelKey;
    }

    /**
     * 获取模型名称。
     *
     * @return String，模型名称
     */
    public String getModelName()
    {
        return modelName;
    }

    /**
     * 获取分类编码。
     *
     * @return String，分类编码
     */
    public String getCategory()
    {
        return category;
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
     * 获取模型版本。
     *
     * @return Integer，模型版本
     */
    public Integer getVersion()
    {
        return version;
    }

    /**
     * 获取模型描述。
     *
     * @return String，模型描述
     */
    public String getDescription()
    {
        return description;
    }

    /**
     * 获取模型创建时间副本。
     *
     * @return Date，模型创建时间副本
     */
    public Date getCreateTime()
    {
        return copyDate(createTime);
    }

    /**
     * 复制可变时间对象，避免导出调用方改变内部状态。
     *
     * @param value Date，待复制时间，允许为空
     * @return Date，时间副本或 null
     */
    private static Date copyDate(Date value)
    {
        return value == null ? null : new Date(value.getTime());
    }
}
