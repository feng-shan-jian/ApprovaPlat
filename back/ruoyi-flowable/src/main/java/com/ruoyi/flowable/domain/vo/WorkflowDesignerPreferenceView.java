package com.ruoyi.flowable.domain.vo;

/**
 * 当前用户的设计器偏好视图。
 *
 * @param theme String，LIGHT、DARK 或 SYSTEM
 * @param gridEnabled boolean，是否显示并启用网格吸附
 * @param minimapEnabled boolean，是否显示小地图
 * @param lintEnabled boolean，是否启用客户端 Lint
 * @param tokenSimulationEnabled boolean，是否启用 Token 流程模拟
 * @param propertiesCollapsed boolean，是否折叠右侧属性面板
 */
public record WorkflowDesignerPreferenceView(String theme, boolean gridEnabled,
        boolean minimapEnabled, boolean lintEnabled,
        boolean tokenSimulationEnabled, boolean propertiesCollapsed)
{
}
