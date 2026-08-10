package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建受控 BPMN 扩展目录请求。
 *
 * @param extensionKey String，稳定扩展业务键
 * @param extensionName String，用户可见名称
 * @param extensionType String，当前支持 JAVA、CEL、HTTP、SQL 或 FORM_FIELD
 * @param description String，可选业务说明
 */
public record WorkflowExtensionCreateRequest(
        @NotBlank(message = "扩展标识不能为空")
        @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{0,127}", message = "扩展标识格式不合法")
        String extensionKey,
        @NotBlank(message = "扩展名称不能为空") @Size(max = 128, message = "扩展名称过长")
        String extensionName,
        @NotBlank(message = "扩展类型不能为空")
        @Pattern(regexp = "JAVA|CEL|HTTP|SQL|FORM_FIELD",
                message = "扩展类型必须为 JAVA、CEL、HTTP、SQL 或 FORM_FIELD")
        String extensionType,
        @Size(max = 500, message = "扩展说明过长") String description)
{
}
