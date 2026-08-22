package com.ruoyi.flowable.domain.dto;

/**
 * 当前用户抄送记录的查询条件，不包含可由客户端伪造的接收用户主键。
 *
 * @param title String，抄送标题模糊条件，允许为空
 * @param processName String，流程名称模糊条件，允许为空
 * @param originatorName String，发起人名称模糊条件，允许为空
 * @param categoryId String，流程分类编码精确条件，允许为空
 * @param readStatus String，0 未读、1 已读，允许为空
 */
public record WorkflowCopyQueryDto(
        String title,
        String processName,
        String originatorName,
        String categoryId,
        String readStatus)
{
}
