package com.ruoyi.flowable.listener;

import org.flowable.common.engine.api.FlowableIllegalArgumentException;
import org.flowable.engine.delegate.TaskListener;
import org.flowable.task.service.delegate.DelegateTask;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.ruoyi.flowable.service.task.WorkflowUserTaskAuditService;
import com.ruoyi.flowable.service.task.WorkflowTaskSlaRuntimeService;

/**
 * BPMN 用户任务固定监听入口，只转发批准事件，不执行脚本、字段注入或业务状态改写。
 */
@Component("userTaskListener")
public class WorkflowUserTaskListener implements TaskListener
{
    private static final long serialVersionUID = 1L;

    private final WorkflowUserTaskAuditService auditService;

    /** SLA 生命周期服务；旧纯单元测试直接构造监听器时可为空。 */
    private WorkflowTaskSlaRuntimeService slaRuntimeService;

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
     * 延迟注入 SLA 生命周期服务，保留既有直接构造测试兼容性。
     * @param slaRuntimeService WorkflowTaskSlaRuntimeService，任务 SLA 正式状态服务
     * @return void，生产 Spring 容器完成注入
     */
    @Autowired
    public void setSlaRuntimeService(WorkflowTaskSlaRuntimeService slaRuntimeService)
    {
        this.slaRuntimeService = slaRuntimeService;
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
                if (slaRuntimeService != null)
                {
                    // SLA 与固定任务审计共享当前 Flowable 命令事务，任一失败都会回滚任务状态。
                    slaRuntimeService.onTaskEvent(eventName, delegateTask.getId(),
                            delegateTask.getProcessInstanceId(),
                            delegateTask.getProcessDefinitionId(),
                            delegateTask.getTaskDefinitionKey(), delegateTask.getAssignee());
                }
            }
            default -> throw new FlowableIllegalArgumentException(
                    "用户任务监听事件不受支持");
        }
    }
}
