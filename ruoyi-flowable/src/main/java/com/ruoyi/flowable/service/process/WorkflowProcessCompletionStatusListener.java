package com.ruoyi.flowable.service.process;

import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEntityEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.delegate.event.AbstractFlowableEngineEventListener;
import org.flowable.engine.impl.persistence.entity.HistoricProcessInstanceEntity;
import org.flowable.engine.impl.persistence.entity.HistoricProcessInstanceEntityManager;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.springframework.util.StringUtils;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationOutboxService;
import com.ruoyi.flowable.service.task.WorkflowAutomaticCopyService;

/**
 * 在 Flowable 确认流程结束时，将自然运行状态收敛为 completed，并保留显式业务终态。
 *
 * <p>PROCESS_COMPLETED 发生在运行时清理后、同一引擎命令提交前，因此直接更新
 * Flowable 历史实例的 businessStatus。取消、驳回、终止优先，重复完成事件无副作用；
 * 完成通知和自动抄送与状态变更处于同一事务。</p>
 */
public final class WorkflowProcessCompletionStatusListener
        extends AbstractFlowableEngineEventListener
{
    /** 自动抄送服务提供器；引擎完成初始化前不得提前解析其 Flowable 服务依赖。 */
    private final ObjectProvider<WorkflowAutomaticCopyService> automaticCopyServiceProvider;

    /** 引擎初始化结束后再解析通知服务，避免通知服务反向依赖 Flowable 公共服务形成启动环。 */
    private final ObjectProvider<WorkflowNotificationService> notificationServiceProvider;
    /** CallActivity 子流程完成时只取消其催办，不经过事件登记服务。 */
    private final ObjectProvider<WorkflowNotificationOutboxService> notificationOutboxServiceProvider;
    /** 自然完成实例的正式业务状态。 */
    static final String COMPLETED_STATUS = "completed";

    /** 尚未被业务动作终止、允许由流程完成事件收敛的运行状态。 */
    private static final String RUNNING_STATUS = "running";

    /** 由取消、驳回或管理员终止明确写入且优先于引擎完成事件的业务终态。 */
    private static final Set<String> BUSINESS_TERMINAL_STATUSES = Set.of(
            "canceled", "rejected", "terminated");

    /**
     * 创建延迟解析自动抄送与普通审批通知服务的监听器，供 Flowable 引擎初始化配置使用。
     * 配置后只处理 PROCESS_COMPLETED
     */
    public WorkflowProcessCompletionStatusListener(
            ObjectProvider<WorkflowAutomaticCopyService> automaticCopyServiceProvider,
            ObjectProvider<WorkflowNotificationService> notificationServiceProvider,
            ObjectProvider<WorkflowNotificationOutboxService> notificationOutboxServiceProvider)
    {
        super(Set.of(FlowableEngineEventType.PROCESS_COMPLETED));
        this.automaticCopyServiceProvider = automaticCopyServiceProvider;
        this.notificationServiceProvider = notificationServiceProvider;
        this.notificationOutboxServiceProvider = notificationOutboxServiceProvider;
    }

    /**
     * 处理流程完成事件并在当前引擎事务内更新历史业务状态。
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
        boolean rootBusinessProcess = isRootBusinessProcessInstance(processInstance);
        boolean naturallyCompleted = updateHistoricProcessStatus(processInstance.getId());
        if (!rootBusinessProcess)
        {
            // 子流程维护自己的 completed 业务状态，但不得触发根业务流程级抄送或结果通知。
            if (!naturallyCompleted)
            {
                return;
            }
            WorkflowNotificationOutboxService outboxService =
                    notificationOutboxServiceProvider.getObject();
            outboxService.schedulePendingUrgeCancellation(processInstance.getId(), null,
                    "子流程已结束，取消未投递催办");
            return;
        }

        // 通知服务只处理根业务流程结果，子流程完成不得解析无关依赖。
        WorkflowNotificationService notificationService = notificationServiceProvider.getObject();
        if (!naturallyCompleted)
        {
            return;
        }
        // 引擎配置阶段仅持有提供器；这里已进入运行命令，才可解析依赖 Flowable 服务的业务 Bean。
        WorkflowAutomaticCopyService automaticCopyService =
                automaticCopyServiceProvider.getObject();
        // 只有 running 收敛为 completed 才触发，驳回、取消和终止不得误发流程完成抄送。
        automaticCopyService.onProcessCompleted(processInstance.getId(),
                processInstance.getProcessDefinitionId());
        // 只有 running 真正收敛为 completed 才登记结果，显式业务终态不会重复通知。
        notificationService.onProcessResult("PROCESS_COMPLETED",
                processInstance.getProcessDefinitionId(), processInstance.getId());
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
     * 完成时运行实体已被清理，历史实例持有该命令最终的业务状态。
     * 只允许 running 自然完成；明确终态与重复事件不产生通知。
     */
    private boolean updateHistoricProcessStatus(String processInstanceId)
    {
        HistoricProcessInstanceEntityManager manager =
                CommandContextUtil.getHistoricProcessInstanceEntityManager();
        HistoricProcessInstanceEntity historicProcess = manager.findById(processInstanceId);
        if (historicProcess == null)
        {
            throw new FlowableException("流程历史实例缺失: " + processInstanceId);
        }
        String status = historicProcess.getBusinessStatus();
        if (COMPLETED_STATUS.equals(status) || (status != null && BUSINESS_TERMINAL_STATUSES.contains(status)))
        {
            return false;
        }
        if (!RUNNING_STATUS.equals(status))
        {
            throw new FlowableException("流程历史业务状态异常: " + processInstanceId);
        }
        historicProcess.setBusinessStatus(COMPLETED_STATUS);
        manager.update(historicProcess, false);
        return true;
    }
}
