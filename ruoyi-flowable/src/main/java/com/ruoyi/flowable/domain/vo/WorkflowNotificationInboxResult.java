package com.ruoyi.flowable.domain.vo;

import java.util.List;

/**
 * 统一用户通知收件箱分页结果。
 *
 * @param items List&lt;WorkflowNotificationItem&gt;，当前页通知
 * @param total long，当前阅读状态筛选条件下的通知总数
 * @param unreadCount long，当前用户全部未读通知数
 */
public record WorkflowNotificationInboxResult(List<WorkflowNotificationItem> items,
        long total, long unreadCount)
{
    /**
     * 创建不可变收件箱结果，避免页面或 Controller 修改服务层集合。
     * @param items List&lt;WorkflowNotificationItem&gt;，当前页通知
     * @param total long，筛选后的总数
     * @param unreadCount long，全局未读数
     */
    public WorkflowNotificationInboxResult
    {
        items = List.copyOf(items);
    }
}
