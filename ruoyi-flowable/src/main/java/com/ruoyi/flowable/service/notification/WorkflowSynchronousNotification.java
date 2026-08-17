package com.ruoyi.flowable.service.notification;

/**
 * SLA 或 BPMN 事件在当前业务事务内同步登记的站内通知事实。
 *
 * @param sourceType String，SLA 或 BPMN_EVENT
 * @param sourceId String，来源审计主键
 * @param eventType String，通知事件类型
 * @param recipientUserId String，接收用户主键
 * @param processDefinitionKey String，流程定义 key
 * @param processInstanceId String，流程实例主键
 * @param taskId String，可空任务主键
 * @param taskDefinitionKey String，可空任务节点 key
 * @param title String，通知标题
 * @param content String，脱敏通知正文
 * @param routePath String，站内安全相对路由
 */
public record WorkflowSynchronousNotification(String sourceType, String sourceId,
        String eventType, String recipientUserId, String processDefinitionKey,
        String processInstanceId, String taskId, String taskDefinitionKey,
        String title, String content, String routePath)
{
}
