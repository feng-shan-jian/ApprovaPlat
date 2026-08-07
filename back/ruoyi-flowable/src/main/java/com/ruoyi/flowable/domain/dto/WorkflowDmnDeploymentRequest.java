package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DMN XML 正式部署请求。
 * @param resourceName String，以 .dmn 结尾的受控资源名
 * @param category String，决策分类
 * @param dmnXml String，完整 DMN XML
 */
public record WorkflowDmnDeploymentRequest(
        @NotBlank @Size(max = 255) String resourceName,
        @Size(max = 255) String category,
        @NotBlank String dmnXml)
{
}
