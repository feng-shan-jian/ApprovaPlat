package com.ruoyi.flowable.domain.vo;

/**
 * 动态多实例正式成员状态。
 *
 * @param userId Long，若依正式用户主键
 * @param name String，供审批页面辨识的用户名称快照
 * @param activeTaskId String，成员当前活动任务主键；已完成成员为 null
 * @param executionId String，成员当前活动 execution 主键；已完成成员为 null
 * @param active boolean，成员是否仍有活动任务
 * @param removable boolean，当前操作人能否把该活动 sibling 从本轮实例移除
 */
public record WorkflowMultiInstanceMemberView(Long userId, String name,
        String activeTaskId, String executionId, boolean active, boolean removable)
{
}
