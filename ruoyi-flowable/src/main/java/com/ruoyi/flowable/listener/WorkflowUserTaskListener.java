package com.ruoyi.flowable.listener;

import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Component;
import com.ruoyi.flowable.service.task.WorkflowUserTaskAuditService;
import com.ruoyi.flowable.service.task.WorkflowTaskSlaRuntimeService;
import com.ruoyi.flowable.service.identity.WorkflowParticipantRuleRuntimeService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationRegistrar;
import com.ruoyi.flowable.service.task.WorkflowAutomaticCopyService;

/**
 * BPMN 用户任务固定监听入口，只转发批准事件，不执行脚本、字段注入或业务状态改写。
 */
@Component("userTaskListener")
public class WorkflowUserTaskListener implements TaskListener
{
    private static final long serialVersionUID = 1L;

    private final WorkflowUserTaskAuditService auditService;

    /** SLA 生命周期服务，任务事件必须同步维护正式 SLA 状态。 */
    private final WorkflowTaskSlaRuntimeService slaRuntimeService;

    /** 单实例参与者规则运行服务，create 事件必须完成正式身份解析。 */
    private final WorkflowParticipantRuleRuntimeService participantRuleRuntimeService;

    /** 自动抄送生命周期服务，任务事件必须写入正式抄送事实。 */
    private final WorkflowAutomaticCopyService automaticCopyService;

    /** 普通审批生命周期通知服务，任务事实与 outbox 必须同事务提交。 */
    private final WorkflowNotificationRegistrar notificationService;

    /**
     * 创建受控用户任务监听器。
     *
     * @param auditService WorkflowUserTaskAuditService，身份校验和结构化 comment 领域服务
     * @param slaRuntimeService WorkflowTaskSlaRuntimeService，任务 SLA 正式状态服务
     * @param participantRuleRuntimeService WorkflowParticipantRuleRuntimeService，实时组织解析服务
     * @param automaticCopyService WorkflowAutomaticCopyService，自动抄送运行时服务
     * @param notificationService WorkflowNotificationRegistrar，事务 outbox 服务
     * @return 无返回值，构造后由 Spring 以 userTaskListener 名称管理
     */
    public WorkflowUserTaskListener(WorkflowUserTaskAuditService auditService,
            WorkflowTaskSlaRuntimeService slaRuntimeService,
            WorkflowParticipantRuleRuntimeService participantRuleRuntimeService,
            WorkflowAutomaticCopyService automaticCopyService,
            WorkflowNotificationRegistrar notificationService)
    {
        this.auditService = auditService;
        this.slaRuntimeService = slaRuntimeService;
        this.participantRuleRuntimeService = participantRuleRuntimeService;
        this.automaticCopyService = automaticCopyService;
        this.notificationService = notificationService;
    }

    /**
     * 仅接受 create、assignment 和 complete 三种固定 Flowable 事件并转发不可变任务上下文。
     *
     * @param delegateTask DelegateTask，Flowable 当前命令提供的用户任务上下文
     * @return void，无返回值；未知事件或缺失上下文会中止当前引擎事务
     */
    @Override
    public void notify(DelegateTask delegateTask)
    {
        if (delegateTask == null)
        {
            throw new FlowableIllegalArgumentException("用户任务监听上下文不能为空");
        }

        String eventName = delegateTask.getEventName();
        switch (eventName == null ? "" : eventName)
        {
            case EVENTNAME_CREATE, EVENTNAME_ASSIGNMENT, EVENTNAME_COMPLETE ->
            {
                if (EVENTNAME_CREATE.equals(eventName))
                {
                    // create 事务内先按部署快照和实时组织解析，使后续身份审计看到最终 assignee/candidate。
                    participantRuleRuntimeService.resolveCreatedTask(delegateTask);
                }
                // 固定监听入口只负责编排，规则解析和任务审计分别由独立领域服务维护。
                auditService.recordAudit(eventName, delegateTask.getId(),
                        delegateTask.getProcessInstanceId(),
                        delegateTask.getProcessDefinitionId(),
                        delegateTask.getTaskDefinitionKey(),
                        delegateTask.getAssignee(), delegateTask.getOwner());
                // 自动抄送失败必须抛回 Flowable 命令，不能提交仅完成任务的半状态。
                automaticCopyService.onTaskEvent(eventName, delegateTask.getId(),
                        delegateTask.getProcessInstanceId(),
                        delegateTask.getProcessDefinitionId(),
                        delegateTask.getTaskDefinitionKey(), delegateTask.getName(),
                        delegateTask.getVariables());
                // SLA 与固定任务审计共享当前 Flowable 命令事务，任一失败都会回滚任务状态。
                slaRuntimeService.onTaskEvent(eventName, delegateTask.getId(),
                        delegateTask.getProcessInstanceId(),
                        delegateTask.getProcessDefinitionId(),
                        delegateTask.getTaskDefinitionKey(), delegateTask.getAssignee());
                // 普通通知 outbox 与任务状态、监听审计共用事务，回滚时不会产生孤立通知。
                notificationService.onTaskEvent(eventName, delegateTask.getId(),
                        delegateTask.getProcessInstanceId(), delegateTask.getProcessDefinitionId(),
                        delegateTask.getTaskDefinitionKey(), delegateTask.getName(),
                        delegateTask.getAssignee(), delegateTask.getOwner());
            }
            default -> throw new FlowableIllegalArgumentException(
                    "用户任务监听事件不受支持");
        }
    }
}
