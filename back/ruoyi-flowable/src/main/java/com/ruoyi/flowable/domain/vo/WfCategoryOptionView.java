package com.ruoyi.flowable.domain.vo;

/**
 * 登录用户选择流程分类时使用的最小只读视图。
 *
 * @param categoryId Long，分类主键
 * @param categoryName String，分类名称
 * @param code String，分类编码
 */
public record WfCategoryOptionView(Long categoryId, String categoryName, String code)
{
}
