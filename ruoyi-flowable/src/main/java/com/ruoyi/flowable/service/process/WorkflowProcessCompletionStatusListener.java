package com.ruoyi.flowable.service.process;

import java.util.Date;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.util.StringUtils;
import com.ruoyi.flowable.service.notification.WorkflowNotificationRegistrar;
import com.ruoyi.flowable.service.notification.WorkflowNotificationOutboxService;
import com.ruoyi.flowable.service.task.WorkflowAutomaticCopyService;

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
    /** 自动抄送服务提供器；引擎完成初始化前不得提前解析其 Flowable 服务依赖。 */
    private final ObjectProvider<WorkflowAutomaticCopyService> automaticCopyServiceProvider;

    /** 引擎初始化结束后再解析通知服务，避免通知服务反向依赖 Flowable 公共服务形成启动环。 */
    private final ObjectProvider<WorkflowNotificationRegistrar> notificationServiceProvider;
    /** 旧直接构造单元测试传入的通知登记服务；生产使用延迟提供器。 */
    private final WorkflowNotificationRegistrar directNotificationRegistrar;
    /** CallActivity 子流程完成时只取消其催办，不经过事件登记服务。 */
    private final ObjectProvider<WorkflowNotificationOutboxService> notificationOutboxServiceProvider;
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
        this(null, null, null, null);
    }

    /**
     * 创建流程完成状态与自动抄送的同事务监听器。
     * @param automaticCopyServiceProvider ObjectProvider&lt;WorkflowAutomaticCopyService&gt;，
     *        引擎事件发生时才解析的自动抄送服务提供器；旧纯单元测试可为空
     * @return void，监听器初始化完成
     */
    public WorkflowProcessCompletionStatusListener(
            ObjectProvider<WorkflowAutomaticCopyService> automaticCopyServiceProvider)
    {
        this(automaticCopyServiceProvider, null, null, null);
    }

    /**
     * 创建同时登记自然完成通知的监听器。
     * @param notificationService WorkflowNotificationRegistrar，可为空的事务 outbox 服务
     * @return void，配置后仅处理 PROCESS_COMPLETED
     */
    public WorkflowProcessCompletionStatusListener(
            WorkflowNotificationRegistrar notificationService)
    {
        this(null, null, null, notificationService);
    }

    /**
     * 创建延迟解析自动抄送与普通审批通知服务的监听器，供 Flowable 引擎初始化配置使用。
     * @param automaticCopyServiceProvider ObjectProvider，流程完成后获取自动抄送服务
     * @param notificationServiceProvider ObjectProvider，流程完成后获取通知服务
     * @return void，配置后仅处理 PROCESS_COMPLETED
     */
    public WorkflowProcessCompletionStatusListener(
            ObjectProvider<WorkflowAutomaticCopyService> automaticCopyServiceProvider,
            ObjectProvider<WorkflowNotificationRegistrar> notificationServiceProvider,
            ObjectProvider<WorkflowNotificationOutboxService> notificationOutboxServiceProvider)
    {
        this(automaticCopyServiceProvider, notificationServiceProvider,
                notificationOutboxServiceProvider, null);
    }

    /**
     * 初始化流程完成监听器持有的延迟依赖。
     * @param automaticCopyServiceProvider ObjectProvider，自动抄送服务延迟提供器，可为空
     * @param notificationServiceProvider ObjectProvider，通知登记服务延迟提供器
     * @param notificationOutboxServiceProvider ObjectProvider，outbox 服务延迟提供器
     * @param directNotificationRegistrar WorkflowNotificationRegistrar，旧单元测试直接依赖
     * @return void，构造后仅处理 PROCESS_COMPLETED
     */
    private WorkflowProcessCompletionStatusListener(
            ObjectProvider<WorkflowAutomaticCopyService> automaticCopyServiceProvider,
            ObjectProvider<WorkflowNotificationRegistrar> notificationServiceProvider,
            ObjectProvider<WorkflowNotificationOutboxService> notificationOutboxServiceProvider,
            WorkflowNotificationRegistrar directNotificationRegistrar)
    {
        super(Set.of(FlowableEngineEventType.PROCESS_COMPLETED));
        this.automaticCopyServiceProvider = automaticCopyServiceProvider;
        this.notificationServiceProvider = notificationServiceProvider;
        this.notificationOutboxServiceProvider = notificationOutboxServiceProvider;
        this.directNotificationRegistrar = directNotificationRegistrar;
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
        if (!isRootBusinessProcessInstance(processInstance))
        {
            // CallActivity 子流程有独立 PROCESS_COMPLETED，但它不是用户发起的根业务实例。
            // 只清理已失去业务对象的催办；不得收敛根业务状态、触发流程级抄送或结果通知。
            WorkflowNotificationOutboxService outboxService =
                    notificationOutboxServiceProvider == null ? null
                            : notificationOutboxServiceProvider.getIfAvailable();
            if (outboxService != null)
            {
                outboxService.schedulePendingUrgeCancellation(processInstance.getId(), null,
                        "子流程已结束，取消未投递催办");
            }
            return;
        }

        // Registrar 只服务根业务流程结果登记，子流程完成不得解析无关依赖。
        WorkflowNotificationRegistrar notificationService = directNotificationRegistrar != null
                ? directNotificationRegistrar
                : notificationServiceProvider == null ? null
                        : notificationServiceProvider.getIfAvailable();
        boolean naturallyCompleted = updateHistoricProcessStatus(processInstance.getId());
        if (!naturallyCompleted)
        {
            return;
        }
        if (automaticCopyServiceProvider != null)
        {
            // 引擎配置阶段仅持有提供器；这里已进入运行命令，才可解析依赖 Flowable 服务的业务 Bean。
            WorkflowAutomaticCopyService automaticCopyService =
                    automaticCopyServiceProvider.getObject();
            // 只有 running 收敛为 completed 才触发，驳回、取消和终止不得误发流程完成抄送。
            automaticCopyService.onProcessCompleted(processInstance.getId(),
                    processInstance.getProcessDefinitionId());
        }
        if (notificationService != null)
        {
            // 只有 running 真正收敛为 completed 才登记结果，显式业务终态不会重复通知。
            notificationService.onProcessResult("PROCESS_COMPLETED",
                    processInstance.getProcessDefinitionId(), processInstance.getId());
        }
    }

    /**
     * 使用 Flowable 执行树的 root 与 super execution 判断当前完成事件是否属于根业务实例。
     * @param processInstance ExecutionEntity，完成事件携带的流程实例执行实体
     * @return boolean，仅没有 super execution 且 root 为空或等于自身时为 true
     */
    private boolean isRootBusinessProcessInstance(ExecutionEntity processInstance)
    {
        String instanceId = processInstance.getId();
        String rootProcessInstanceId = processInstance.getRootProcessInstanceId();
        String superExecutionId = processInstance.getSuperExecutionId();
        if (StringUtils.hasText(superExecutionId))
        {
            if (!StringUtils.hasText(rootProcessInstanceId)
                    || instanceId.equals(rootProcessInstanceId))
            {
                throw new FlowableException("CallActivity 子流程执行树根关系异常: " + instanceId);
            }
            return false;
        }
        if (StringUtils.hasText(rootProcessInstanceId)
                && !instanceId.equals(rootProcessInstanceId))
        {
            throw new FlowableException("根流程实例执行树关系异常: " + instanceId);
        }
        return true;
    }

    /**
     * 使用当前 Flowable CommandContext 按终态优先级更新已存在的 processStatus 历史变量。
     *
     * @param processInstanceId String，已自然完成的 Flowable 流程实例主键
     * @return void，无返回值；旧实例没有该变量时兼容跳过，重复、类型或状态值异常时阻止错误数据提交
     */
    private boolean updateHistoricProcessStatus(String processInstanceId)
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
            return false;
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
            return false;
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
        return true;
    }
}
