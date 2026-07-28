package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 修改工作流表单模板请求，历史部署快照不属于可修改字段。
 *
 * @param formId Long，待修改表单主键
 * @param formName String，表单名称
 * @param content String，新的可编辑 JSON 模板
 * @param remark String，表单备注，允许为空
 */
public record WfFormUpdateRequest(
        @NotNull(message = "流程表单主键不能为空")
        @Positive(message = "流程表单主键必须为正数")
        Long formId,
        @NotBlank(message = "流程表单名称不能为空")
        @Size(max = 64, message = "流程表单名称长度不能超过64个字符")
        String formName,
        @NotBlank(message = "流程表单内容不能为空")
        String content,
        @Size(max = 255, message = "流程表单备注长度不能超过255个字符")
        String remark)
{
}
