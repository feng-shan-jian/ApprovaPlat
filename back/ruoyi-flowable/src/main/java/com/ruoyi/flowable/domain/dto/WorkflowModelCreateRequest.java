package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 新增流程模型请求，不接收模型主键、版本、部署关系或 BPMN 资源。
 *
 * @param modelName String，模型显示名称
 * @param modelKey String，模型版本分组标识
 * @param category String，工作流分类编码
 * @param description String，模型业务描述，允许为空
 * @param formType Integer，表单模式编码，允许为空
 * @param formId Long，流程级表单主键，按表单模式决定是否必填
 */
public record WorkflowModelCreateRequest(
        @NotBlank(message = "模型名称不能为空")
        @Size(max = 255, message = "模型名称长度不能超过255个字符")
        String modelName,
        @NotBlank(message = "模型标识不能为空")
        @Pattern(regexp = "[A-Za-z_][A-Za-z0-9_.-]{0,127}", message = "模型标识格式不合法")
        String modelKey,
        @NotBlank(message = "流程分类不能为空")
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
