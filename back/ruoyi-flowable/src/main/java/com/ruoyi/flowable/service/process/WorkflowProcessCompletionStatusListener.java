package com.ruoyi.flowable.service.process;

import java.util.Date;
import java.util.List;
import java.util.Set;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.delegate.event.AbstractFlowableEngineEventListener;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.variable.service.VariableServiceConfiguration;
import org.flowable.variable.service.impl.persistence.entity.HistoricVariableInstanceEntity;
import org.flowable.variable.service.impl.persistence.entity.HistoricVariableInstanceEntityManager;

/**
 * 在 Flowable 确认流程结束时，将自然运行状态收敛为 completed，并保留显式业务终态。
 *
 * <p>PROCESS_COMPLETED 事件发生在运行时变量清理之后、同一引擎命令提交之前，因此这里使用
 * Flowable 自身的历史变量实体管理器更新最终值。这样不会在任务流转前把 processStatus
 * 暂时改成 completed，也不会影响网关表达式、并行分支或后继任务的路由判断。
 * 驳回等动作也可能通过移动到结束节点触发该事件，因此 canceled/terminated 的业务终态
 * 必须优先于引擎 completed 语义，不能被监听器覆盖。</p>
 */
public final class WorkflowProcessCompletionStatusListener
        extends AbstractFlowableEngineEventListener
{
    /** 自然完成实例的正式业务状态。 */
    static final String COMPLETED_STATUS = "completed";

    /** 尚未被业务动作终止、允许由流程完成事件收敛的运行状态。 */
    private static final String RUNNING_STATUS = "running";

    /** 由取消、驳回或管理员终止明确写入且优先于引擎完成事件的业务终态。 */
    private static final Set<String> BUSINESS_TERMINAL_STATUSES = Set.of(
            "canceled", "rejected", "terminated");

    /** Flowable 字符串变量的稳定类型名。 */
    private static final String STRING_VARIABLE_TYPE = "string";

    /**
     * 创建仅监听流程完成事件的引擎监听器。
     *
     * @return 无返回值，构造后仅接收 PROCESS_COMPLETED 事件
     */
    public WorkflowProcessCompletionStatusListener()
    {
        super(Set.of(FlowableEngineEventType.PROCESS_COMPLETED));
    }

    /**
     * 处理流程完成事件并在当前引擎事务内更新历史状态变量。
     *
     * @param event FlowableEngineEntityEvent，携带已自然结束的流程实例实体
     * @return void，无返回值；仅将 running 更新为 completed，保留显式业务终态，损坏状态会回滚事务
     */
    @Override
    protected void processCompleted(FlowableEngineEntityEvent event)
    {
        Object eventEntity = event == null ? null : event.getEntity();
        if (!(eventEntity instanceof ExecutionEntity processInstance)
                || !processInstance.isProcessInstanceType()
                || processInstance.getId() == null || processInstance.getId().isBlank())
        {
            throw new FlowableException("流程自然完成事件缺少有效的流程实例实体");
        }

        updateHistoricProcessStatus(processInstance.getId());
    }

    /**
     * 使用当前 Flowable CommandContext 按终态优先级更新已存在的 processStatus 历史变量。
     *
     * @param processInstanceId String，已自然完成的 Flowable 流程实例主键
     * @return void，无返回值；旧实例没有该变量时兼容跳过，重复、类型或状态值异常时阻止错误数据提交
     */
    private void updateHistoricProcessStatus(String processInstanceId)
    {
        ProcessEngineConfigurationImpl engineConfiguration =
                CommandContextUtil.getProcessEngineConfiguration();
        if (engineConfiguration == null)
        {
            throw new FlowableException("流程自然完成事件缺少 Flowable 引擎上下文");
        }

        VariableServiceConfiguration variableConfiguration =
                engineConfiguration.getVariableServiceConfiguration();
        HistoricVariableInstanceEntityManager historicVariableManager =
                variableConfiguration.getHistoricVariableInstanceEntityManager();
        List<HistoricVariableInstanceEntity> statusVariables = historicVariableManager
                .findHistoricalVariableInstancesByProcessInstanceId(processInstanceId,
                        Set.of(WorkflowProcessStartService.PROCESS_STATUS_VARIABLE));

        // 兼容没有 processStatus 的存量或引擎外部实例；新服务发起的实例必须命中下面的正式更新链路。
        if (statusVariables == null || statusVariables.isEmpty())
        {
            return;
        }
        if (statusVariables.size() != 1)
        {
            throw new FlowableException("流程历史状态变量存在重复记录: " + processInstanceId);
        }

        HistoricVariableInstanceEntity statusVariable = statusVariables.get(0);
        if (statusVariable == null
                || !STRING_VARIABLE_TYPE.equals(statusVariable.getVariableTypeName()))
        {
            throw new FlowableException("流程历史状态变量类型异常: " + processInstanceId);
        }
        // 当前历史值决定终态优先级：业务显式终态和已完成值均不得被二次覆盖。
        String currentStatus = statusVariable.getTextValue();
        if (COMPLETED_STATUS.equals(currentStatus)
                || BUSINESS_TERMINAL_STATUSES.contains(currentStatus))
        {
            return;
        }
        if (!RUNNING_STATUS.equals(currentStatus))
        {
            throw new FlowableException("流程历史状态变量值异常: " + processInstanceId);
        }

        // 只有自然运行状态可收敛为 completed，并同步清理存储槽和缓存，避免最终记录不一致。
        statusVariable.setTextValue(COMPLETED_STATUS);
        statusVariable.setTextValue2(null);
        statusVariable.setLongValue(null);
        statusVariable.setDoubleValue(null);
        statusVariable.setCachedValue(COMPLETED_STATUS);
        Date completedTime = engineConfiguration.getClock().getCurrentTime();
        statusVariable.setLastUpdatedTime(completedTime);
        historicVariableManager.update(statusVariable, false);
    }
}
