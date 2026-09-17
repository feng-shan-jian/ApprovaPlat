package com.ruoyi.flowable.domain;

/**
 * 工作流用户名称的两列数据库投影。
 *
 * @param userId Long，若依用户主键
 * @param name String，具体查询产生的名称；多实例为昵称优先的辨识名，工作台为原始昵称
 */
public record WorkflowMultiInstanceUserRow(Long userId, String name)
{
}
