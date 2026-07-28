package com.ruoyi.flowable.domain;

/**
 * 批量查询多实例成员名称的最小数据库投影。
 *
 * @param userId Long，若依用户主键
 * @param name String，昵称优先、账号兜底的页面辨识名称
 */
public record WorkflowMultiInstanceUserRow(Long userId, String name)
{
}
