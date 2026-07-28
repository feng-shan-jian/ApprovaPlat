package com.ruoyi.flowable.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 修改工作流分类请求，主键与可修改字段均由请求体明确提供。
 *
 * @param categoryId Long，待修改分类主键
 * @param categoryName String，分类名称
 * @param code String，Flowable 模型和流程定义使用的分类编码
 * @param remark String，分类备注，允许为空
 */
public record WfCategoryUpdateRequest(
        @NotNull(message = "流程分类主键不能为空")
        @Positive(message = "流程分类主键必须为正数")
        Long categoryId,
        @NotBlank(message = "流程分类名称不能为空")
        @Size(max = 64, message = "流程分类名称长度不能超过64个字符")
        String categoryName,
        @NotBlank(message = "流程分类编码不能为空")
        @Size(max = 64, message = "流程分类编码长度不能超过64个字符")
        String code,
        @Size(max = 500, message = "流程分类备注长度不能超过500个字符")
        String remark)
{
}
