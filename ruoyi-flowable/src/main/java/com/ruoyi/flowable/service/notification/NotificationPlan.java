package com.ruoyi.flowable.service.notification;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通知规划器生成的不可变写入计划。
 *
 * @param notifications List&lt;Notification&gt;，已经完成策略、接收人、偏好和模板解析的通知记录
 */
public record NotificationPlan(List<Notification> notifications)
{
    /**
     * 冻结通知记录集合，防止规划完成后被调用方修改。
     * @param notifications List&lt;Notification&gt;，待写入通知记录
     * @return void，构造后的集合不可修改
     */
    public NotificationPlan
    {
        notifications = notifications == null ? List.of() : List.copyOf(notifications);
    }

    /**
     * 创建不产生任何通知表写入的空计划。
     * @return NotificationPlan，不包含通知记录的不可变计划
     */
    public static NotificationPlan empty()
    {
        return new NotificationPlan(List.of());
    }

    /**
     * 判断当前计划是否没有可登记通知。
     * @return boolean，没有通知记录时为 true
     */
    public boolean isEmpty()
    {
        return notifications.isEmpty();
    }

    /**
     * 单个接收人的最终通知写入事实。
     *
     * @param sourceType String，APPROVAL
     * @param sourceId String，稳定业务来源键
     * @param eventType String，审批通知事件类型
     * @param recipientUserId String，正式接收用户主键
     * @param processDefinitionKey String，流程定义 key
     * @param processInstanceId String，流程实例主键
     * @param taskId String，可空任务主键
     * @param taskDefinitionKey String，可空任务节点 key
     * @param actorUserId String，可空操作人用户主键
     * @param title String，最终通知标题
     * @param content String，最终通知正文
     * @param smsTemplateId String，可空短信模板 ID
     * @param routePath String，站内安全相对路由
     * @param channels Set&lt;String&gt;，该接收人实际启用的 INBOX、EMAIL、SMS 通道
     * @param maxAttempts int，外部通道最大投递次数
     */
    public record Notification(String sourceType, String sourceId, String eventType,
            String recipientUserId, String processDefinitionKey,
            String processInstanceId, String taskId, String taskDefinitionKey,
            String actorUserId, String title, String content, String smsTemplateId,
            String routePath, Set<String> channels, int maxAttempts)
    {
        /**
         * 冻结通道集合，保证 Writer 看到的计划不会发生事后变化。
         * @param channels Set&lt;String&gt;，已经过偏好过滤的通道集合
         * @return void，构造后的通道集合不可修改
         */
        public Notification
        {
            channels = channels == null ? Set.of()
                    : Collections.unmodifiableSet(new LinkedHashSet<>(channels));
        }
    }
}
