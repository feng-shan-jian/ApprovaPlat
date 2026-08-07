package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 保存当前用户设计器偏好的请求。
 *
 * @param theme String，LIGHT、DARK 或 SYSTEM
 * @param gridEnabled Boolean，是否显示并启用网格吸附
 * @param minimapEnabled Boolean，是否显示小地图
 * @param lintEnabled Boolean，是否启用客户端 Lint
 * @param tokenSimulationEnabled Boolean，是否启用 Token 流程模拟
 * @param propertiesCollapsed Boolean，是否折叠右侧属性面板
 */
public record WorkflowDesignerPreferenceRequest(
        @NotNull(message = "设计器主题不能为空")
        @Pattern(regexp = "LIGHT|DARK|SYSTEM", message = "设计器主题不合法")
        String theme,
        @NotNull(message = "网格设置不能为空") Boolean gridEnabled,
        @NotNull(message = "小地图设置不能为空") Boolean minimapEnabled,
        @NotNull(message = "Lint 设置不能为空") Boolean lintEnabled,
        @NotNull(message = "Token 模拟设置不能为空") Boolean tokenSimulationEnabled,
        @NotNull(message = "属性面板设置不能为空") Boolean propertiesCollapsed)
{
}
