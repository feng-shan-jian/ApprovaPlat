package com.ruoyi.flowable.listener;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.service.task.WorkflowTaskSlaRuntimeService;

/**
 * 由部署编译器生成的审批 SLA 定时动作固定入口。
 */
@Component("workflowSlaTimerDelegate")
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class WorkflowSlaTimerDelegate implements JavaDelegate
{
    private final WorkflowTaskSlaRuntimeService slaRuntimeService;

    /**
     * 创建固定 SLA 定时 delegate。
     * @param slaRuntimeService WorkflowTaskSlaRuntimeService，事务内运行状态服务
     * @return 无返回值，由 Spring 以固定 Bean 名管理
     */
    public WorkflowSlaTimerDelegate(WorkflowTaskSlaRuntimeService slaRuntimeService)
    {
        this.slaRuntimeService = slaRuntimeService;
    }

    /**
     * 执行部署时冻结的提醒或升级动作。
     * @param execution DelegateExecution，Flowable 真实异步定时作业执行上下文
     * @return void，业务写入失败时抛出并由 Flowable 回滚和重试
     */
    @Override
    public void execute(DelegateExecution execution)
    {
        throw new ServiceException("审批 SLA 定时器必须使用编译后的固定方法入口", HttpStatus.ERROR);
    }

    /**
     * 由编译后的只读 EL 参数执行 SLA 动作，避免向 Spring 单例注入可变字段造成并发串值。
     * @param execution DelegateExecution，当前执行上下文
     * @param taskDefinitionKey String，原审批节点标识
     * @param action String，REMINDER 或 ESCALATE
     * @param ordinal Integer，动作序号
     * @param escalationRecipient String，可空升级办理人
     * @return Object，固定返回 null 供 Flowable 表达式活动消费
     */
    public Object executeTimer(DelegateExecution execution, String taskDefinitionKey,
            String action, Integer ordinal, String escalationRecipient)
    {
        if (execution == null || taskDefinitionKey == null || taskDefinitionKey.isBlank()
                || action == null || action.isBlank() || ordinal == null)
        {
            throw new ServiceException("审批 SLA 定时上下文不完整", HttpStatus.ERROR);
        }
        slaRuntimeService.handleTimer(execution.getProcessInstanceId(),
                execution.getProcessDefinitionId(), taskDefinitionKey.trim(), action.trim(),
                ordinal, escalationRecipient == null ? null : escalationRecipient.trim());
        return null;
    }
}
