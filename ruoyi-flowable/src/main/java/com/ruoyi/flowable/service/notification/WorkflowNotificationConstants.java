package com.ruoyi.flowable.service.notification;

import java.util.Set;

/**
 * 通知策略和 Flowable 受控迁移共享的稳定领域常量。
 */
public final class WorkflowNotificationConstants
{
    /** 服务端允许进入模板的固定变量。 */
    public static final Set<String> TEMPLATE_VARIABLES = Set.of(
            "processName", "processDefinitionKey", "processInstanceId",
            "taskName", "taskDefinitionKey", "eventType");

    /** 可以由策略管理接口配置的普通生命周期事件。 */
    public static final Set<String> EVENT_TYPES = Set.of(
            "TASK_ARRIVED", "TASK_CLAIMED", "TASK_UNCLAIMED", "TASK_DELEGATED",
            "TASK_DELEGATION_RESOLVED", "TASK_TRANSFERRED", "TASK_RETURNED",
            "TASK_RESUBMITTED", "TASK_COMPLETED", "PROCESS_COMPLETED",
            "PROCESS_CANCELED", "PROCESS_REJECTED", "PROCESS_TERMINATED", "MANUAL_URGE",
            "COPY_CREATED");

    /** 退回和重提在最终任务归属稳定前使用的流程变量。 */
    public static final String CONTROLLED_TRANSITION_VARIABLE =
            "__ruoyi_workflow_notification_transition";

    private WorkflowNotificationConstants()
    {
    }
}
