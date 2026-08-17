package com.ruoyi.flowable.service.notification;

import java.util.Set;

/**
 * 人工催办在完成权限、状态和接收人锁定后交给登记服务的不可变命令。
 *
 * @param processDefinitionId String，任务所属流程定义主键
 * @param processInstanceId String，任务所属根或 CallActivity 子流程实例主键
 * @param startUserId String，根业务流程发起用户主键
 * @param taskId String，活动任务主键
 * @param taskDefinitionKey String，任务节点 key
 * @param taskName String，任务显示名称
 * @param actorUserId String，当前已授权催办用户主键
 * @param recipientUserIds Set&lt;String&gt;，锁定快照内有效接收用户
 * @param sourceId String，本次任务催办稳定来源键
 * @param reason String，已校验催办原因
 */
public record WorkflowManualUrgeRegistration(String processDefinitionId,
        String processInstanceId, String startUserId, String taskId,
        String taskDefinitionKey, String taskName, String actorUserId,
        Set<String> recipientUserIds, String sourceId, String reason)
{
}
