package com.ruoyi.flowable.listener;

import java.util.Set;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.event.AbstractFlowableEngineEventListener;
import org.flowable.engine.delegate.event.FlowableActivityCancelledEvent;
import org.flowable.engine.delegate.event.FlowableCancelledEvent;
import org.flowable.engine.delegate.event.FlowableMultiInstanceActivityCancelledEvent;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.common.engine.impl.identity.Authentication;
import org.springframework.beans.factory.ObjectProvider;
import com.ruoyi.flowable.service.task.MultiInstanceRootCancellationEvent;
import com.ruoyi.flowable.service.task.ProcessInstanceCancellationEventSnapshot;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceRoundTerminationService;

/**
 * 接收 Flowable 多实例根异常取消事件，并在当前引擎事务内关闭正式业务轮次。
 */
public final class WorkflowMultiInstanceRoundInterruptionListener
        extends AbstractFlowableEngineEventListener
{
    /** 延迟解析轮次服务，避免流程引擎配置阶段反向创建 RuntimeService。 */
    private final ObjectProvider<WorkflowMultiInstanceRoundTerminationService>
            terminationServiceProvider;

    /**
     * 创建仅处理多实例根取消事件的全局引擎监听器。
     *
     * @param terminationServiceProvider ObjectProvider&lt;WorkflowMultiInstanceRoundTerminationService&gt;，
     *        引擎启动完成后再解析的正式轮次服务
     * @return 无返回值，构造后的监听器由 Flowable 全局事件分发器同步调用
     */
    public WorkflowMultiInstanceRoundInterruptionListener(
            ObjectProvider<WorkflowMultiInstanceRoundTerminationService>
                    terminationServiceProvider)
    {
        super(Set.of(FlowableEngineEventType.ACTIVITY_CANCELLED,
                FlowableEngineEventType.MULTI_INSTANCE_ACTIVITY_CANCELLED,
                FlowableEngineEventType.PROCESS_CANCELLED));
        this.terminationServiceProvider = java.util.Objects.requireNonNull(
                terminationServiceProvider, "多实例轮次终止服务提供器不能为空");
    }

    /**
     * 处理部分 Flowable 删除路径以普通 ACTIVITY_CANCELLED 派发的多实例根取消。
     *
     * @param event FlowableActivityCancelledEvent，可能属于普通 child 或多实例根的取消事实
     * @return void，仅当前 CommandContext 证明 execution 是多实例根时进入轮次关闭
     */
    @Override
    protected void activityCancelled(FlowableActivityCancelledEvent event)
    {
        closeCancelledMultiInstanceRoot(event);
    }

    /**
     * 把多实例根异常取消事实交给正式轮次服务执行严格对账与 CAS 关闭。
     *
     * @param event FlowableMultiInstanceActivityCancelledEvent，根 execution 尚可读取时派发的取消事件
     * @return void，服务异常会由 fail-on-exception 语义回滚当前 Flowable 命令
     */
    @Override
    protected void multiInstanceActivityCancelled(
            FlowableMultiInstanceActivityCancelledEvent event)
    {
        closeCancelledMultiInstanceRoot(event);
    }

    /**
     * 处理已由 Flowable 内部删除的流程实例，关闭 CallActivity 取消后残留的开放轮次。
     *
     * @param event FlowableCancelledEvent，流程实例取消事实
     * @return void，显式 deleteProcessInstance 在派发事件时尚未标记 deleted，保持既有终止链处理
     */
    @Override
    protected void processCancelled(FlowableCancelledEvent event)
    {
        DelegateExecution execution = getExecution(event);
        if (!(execution instanceof ExecutionEntity processInstance)
                || !processInstance.isProcessInstanceType()
                || !processInstance.isDeleted())
        {
            return;
        }
        terminationServiceProvider.getObject().onProcessInstanceCancelled(
                new ProcessInstanceCancellationEventSnapshot(
                        event.getProcessInstanceId(), event.getProcessDefinitionId(),
                        event.getExecutionId(), processInstance.getId(),
                        processInstance.getProcessDefinitionId(),
                        processInstance.isProcessInstanceType(),
                        processInstance.isDeleted()));
    }

    /**
     * 使用当前 Flowable CommandContext 区分多实例根和正常清理的 child execution。
     *
     * @param event FlowableActivityCancelledEvent，普通或专用多实例取消事件
     * @return void，根事件同步关闭轮次；ANY 正常删除的 sibling 因不是根而直接忽略
     */
    private void closeCancelledMultiInstanceRoot(
            FlowableActivityCancelledEvent event)
    {
        DelegateExecution execution = getExecution(event);
        if (!(execution instanceof ExecutionEntity multiInstanceRoot)
                || !multiInstanceRoot.isMultiInstanceRoot())
        {
            return;
        }
        terminationServiceProvider.getObject().onMultiInstanceRootCancelled(
                new MultiInstanceRootCancellationEvent(event.getProcessInstanceId(),
                        event.getProcessDefinitionId(), event.getActivityId(),
                        event.getActivityType(), event.getExecutionId(),
                        multiInstanceRoot.getId(), multiInstanceRoot.getActivityId(),
                        multiInstanceRoot.getProcessInstanceId(),
                        multiInstanceRoot.getProcessDefinitionId(),
                        multiInstanceRoot.isMultiInstanceRoot(),
                        multiInstanceRoot.isSuspended(),
                        Authentication.getAuthenticatedUserId()));
    }
}
