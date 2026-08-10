package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增工作流表单模板请求，只允许客户端提交模板业务字段。
 *
 * @param formName String，表单名称
 * @param content String，表单设计器产生的 JSON 模板
 * @param remark String，表单备注，允许为空
 */
public record WfFormCreateRequest(
        @NotBlank(message = "流程表单名称不能为空")
        @Size(max = 64, message = "流程表单名称长度不能超过64个字符")
        String formName,
        @NotBlank(message = "流程表单内容不能为空")
        String content,
        @Size(max = 255, message = "流程表单备注长度不能超过255个字符")
        String remark)
{
}
