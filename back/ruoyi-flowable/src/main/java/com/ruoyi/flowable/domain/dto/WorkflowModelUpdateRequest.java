package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 修改流程模型元数据请求，不允许通过该入口覆盖 BPMN 或部署关系。
 *
 * @param modelId String，Flowable 模型主键
 * @param modelName String，模型显示名称，允许为空表示保持不变
 * @param modelKey String，兼容旧客户端回传的模型标识，只允许与原值一致
 * @param category String，工作流分类编码，允许为空表示保持不变
 * @param description String，模型业务描述，允许为空
 * @param formType Integer，表单模式编码，允许为空
 * @param formId Long，流程级表单主键，允许为空
 */
public record WorkflowModelUpdateRequest(
        @NotBlank(message = "模型主键不能为空")
        @Size(max = 64, message = "模型主键长度不能超过64个字符")
        String modelId,
        @Pattern(regexp = "(?s).*\\S.*", message = "模型名称不能为空")
        @Size(max = 255, message = "模型名称长度不能超过255个字符")
        String modelName,
        @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_.-]{0,127}", message = "模型标识格式不合法")
        String modelKey,
        @Pattern(regexp = "(?s).*\\S.*", message = "流程分类不能为空")
        @Size(max = 64, message = "流程分类编码长度不能超过64个字符")
        String category,
        @Size(max = 1000, message = "模型描述长度不能超过1000个字符")
        String description,
        @Min(value = 0, message = "表单类型不合法")
        @Max(value = 2, message = "表单类型不合法")
        Integer formType,
        @Positive(message = "流程表单主键必须为正数")
        Long formId)
{
}
