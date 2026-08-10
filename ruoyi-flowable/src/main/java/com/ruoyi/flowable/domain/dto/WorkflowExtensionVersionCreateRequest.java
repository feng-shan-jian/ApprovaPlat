package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 发布受控 BPMN 扩展不可变版本请求。
 *
 * @param implementationKey String，服务端已安装 Java 处理器稳定键
 */
public record WorkflowExtensionVersionCreateRequest(
        @NotBlank(message = "处理器标识不能为空")
        @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}", message = "处理器标识格式不合法")
        String implementationKey)
{
}
