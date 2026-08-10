package com.ruoyi.flowable.domain.dto;

/**
 * 流程部署和定义列表的查询条件。
 */
public class WorkflowDeploymentQueryDto
{
    /** 流程定义标识。 */
    private String processKey;

    /** 流程定义名称。 */
    private String processName;

    /** 工作流分类编码。 */
    private String category;

    /** 定义状态编码：active 或 suspended。 */
    private String state;

    /**
     * 获取流程定义标识。
     *
     * @return String，流程定义标识
     */
    public String getProcessKey()
    {
        return processKey;
    }

    /**
     * 设置流程定义标识。
     *
     * @param processKey String，流程定义标识
     * @return 无返回值
     */
    public void setProcessKey(String processKey)
    {
        this.processKey = processKey;
    }

    /**
     * 获取流程定义名称。
     *
     * @return String，流程定义名称
     */
    public String getProcessName()
    {
        return processName;
    }

    /**
     * 设置流程定义名称。
     *
     * @param processName String，流程定义名称
     * @return 无返回值
     */
    public void setProcessName(String processName)
    {
        this.processName = processName;
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
     * 获取定义状态编码。
     *
     * @return String，active、suspended 或空值
     */
    public String getState()
    {
        return state;
    }

    /**
     * 设置定义状态编码。
     *
     * @param state String，active、suspended 或空值
     * @return 无返回值
     */
    public void setState(String state)
    {
        this.state = state;
    }
}
