package com.ruoyi.flowable.listener;

import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Component;
import com.ruoyi.flowable.service.task.WorkflowUserTaskAuditService;

/**
 * BPMN 用户任务固定监听入口，只转发批准事件，不执行脚本、字段注入或业务状态改写。
 */
@Component("userTaskListener")
public class WorkflowUserTaskListener implements TaskListener
{
    private static final long serialVersionUID = 1L;

    private final WorkflowUserTaskAuditService auditService;

    /**
     * 创建受控用户任务监听器。
     *
     * @param auditService WorkflowUserTaskAuditService，身份校验和结构化 comment 领域服务
     * @return 无返回值，构造后由 Spring 以 userTaskListener 名称管理
     */
    public WorkflowUserTaskListener(WorkflowUserTaskAuditService auditService)
    {
        this.auditService = auditService;
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
                // 监听器不读取 BPMN 字段或流程变量，只把 Flowable 固有任务元数据交给领域服务。
                auditService.recordAudit(eventName, delegateTask.getId(),
                        delegateTask.getProcessInstanceId(),
                        delegateTask.getProcessDefinitionId(),
                        delegateTask.getTaskDefinitionKey(),
                        delegateTask.getAssignee(), delegateTask.getOwner());
            }
            default -> throw new FlowableIllegalArgumentException(
                    "用户任务监听事件不受支持");
        }
    }
}
