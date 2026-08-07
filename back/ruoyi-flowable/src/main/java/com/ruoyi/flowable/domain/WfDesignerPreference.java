package com.ruoyi.flowable.domain;

import java.util.Date;

/**
 * 当前用户的 BPMN 设计器偏好，对应正式业务表 {@code wf_designer_preference}。
 */
public class WfDesignerPreference
{
    /** 用户主键，每名用户只保留一份当前偏好。 */
    private Long userId;

    /** 设计器主题：LIGHT、DARK 或 SYSTEM。 */
    private String theme;

    /** 是否显示并启用网格吸附。 */
    private Boolean gridEnabled;

    /** 是否显示小地图。 */
    private Boolean minimapEnabled;

    /** 是否启用客户端 Lint。 */
    private Boolean lintEnabled;

    /** 是否启用 Token 流程模拟。 */
    private Boolean tokenSimulationEnabled;

    /** 是否折叠右侧属性面板。 */
    private Boolean propertiesCollapsed;

    /** 首次创建时间。 */
    private Date createTime;

    /** 最近更新时间。 */
    private Date updateTime;

    /**
     * 获取用户主键。
     * @return Long，正式用户主键
     */
    public Long getUserId()
    {
        return userId;
    }

    /**
     * 设置用户主键。
     * @param userId Long，正式用户主键
     * @return void，无返回值
     */
    public void setUserId(Long userId)
    {
        this.userId = userId;
    }

    /**
     * 获取设计器主题。
     * @return String，LIGHT、DARK 或 SYSTEM
     */
    public String getTheme()
    {
        return theme;
    }

    /**
     * 设置设计器主题。
     * @param theme String，LIGHT、DARK 或 SYSTEM
     * @return void，无返回值
     */
    public void setTheme(String theme)
    {
        this.theme = theme;
    }

    /**
     * 获取网格开关。
     * @return Boolean，true 表示显示并启用网格吸附
     */
    public Boolean getGridEnabled()
    {
        return gridEnabled;
    }

    /**
     * 设置网格开关。
     * @param gridEnabled Boolean，网格显示和吸附状态
     * @return void，无返回值
     */
    public void setGridEnabled(Boolean gridEnabled)
    {
        this.gridEnabled = gridEnabled;
    }

    /**
     * 获取小地图开关。
     * @return Boolean，true 表示显示小地图
     */
    public Boolean getMinimapEnabled()
    {
        return minimapEnabled;
    }

    /**
     * 设置小地图开关。
     * @param minimapEnabled Boolean，小地图显示状态
     * @return void，无返回值
     */
    public void setMinimapEnabled(Boolean minimapEnabled)
    {
        this.minimapEnabled = minimapEnabled;
    }

    /**
     * 获取客户端校验开关。
     * @return Boolean，true 表示启用客户端 Lint
     */
    public Boolean getLintEnabled()
    {
        return lintEnabled;
    }

    /**
     * 设置客户端校验开关。
     * @param lintEnabled Boolean，客户端 Lint 状态
     * @return void，无返回值
     */
    public void setLintEnabled(Boolean lintEnabled)
    {
        this.lintEnabled = lintEnabled;
    }

    /**
     * 获取 Token 模拟开关。
     * @return Boolean，true 表示启用 Token 流程模拟
     */
    public Boolean getTokenSimulationEnabled()
    {
        return tokenSimulationEnabled;
    }

    /**
     * 设置 Token 模拟开关。
     * @param tokenSimulationEnabled Boolean，Token 流程模拟状态
     * @return void，无返回值
     */
    public void setTokenSimulationEnabled(Boolean tokenSimulationEnabled)
    {
        this.tokenSimulationEnabled = tokenSimulationEnabled;
    }

    /**
     * 获取属性面板折叠状态。
     * @return Boolean，true 表示折叠右侧属性面板
     */
    public Boolean getPropertiesCollapsed()
    {
        return propertiesCollapsed;
    }

    /**
     * 设置属性面板折叠状态。
     * @param propertiesCollapsed Boolean，属性面板折叠状态
     * @return void，无返回值
     */
    public void setPropertiesCollapsed(Boolean propertiesCollapsed)
    {
        this.propertiesCollapsed = propertiesCollapsed;
    }

    /**
     * 获取创建时间副本。
     * @return Date，创建时间或 null
     */
    public Date getCreateTime()
    {
        return copyDate(createTime);
    }

    /**
     * 设置创建时间副本。
     * @param createTime Date，首次创建时间
     * @return void，无返回值
     */
    public void setCreateTime(Date createTime)
    {
        this.createTime = copyDate(createTime);
    }

    /**
     * 获取更新时间副本。
     * @return Date，最近更新时间或 null
     */
    public Date getUpdateTime()
    {
        return copyDate(updateTime);
    }

    /**
     * 设置更新时间副本。
     * @param updateTime Date，最近更新时间
     * @return void，无返回值
     */
    public void setUpdateTime(Date updateTime)
    {
        this.updateTime = copyDate(updateTime);
    }

    /**
     * 复制可变时间对象，避免外部修改领域对象内部时间。
     * @param value Date，待复制时间，允许为空
     * @return Date，时间副本或 null
     */
    private Date copyDate(Date value)
    {
        return value == null ? null : new Date(value.getTime());
    }
}
