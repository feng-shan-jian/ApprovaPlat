package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.Pattern;

/**
 * 发布受控 BPMN 扩展不可变版本请求。
 *
 * @param implementationKey String，仅 JAVA 类型提交的已安装处理器稳定键
 */
public record WorkflowExtensionVersionCreateRequest(
        @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}", message = "处理器标识格式不合法")
        String implementationKey)
{
}
