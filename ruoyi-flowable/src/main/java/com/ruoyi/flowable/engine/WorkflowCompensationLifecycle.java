package com.ruoyi.flowable.engine;

import java.util.List;
import org.flowable.bpmn.model.BoundaryEvent;
import org.flowable.bpmn.model.CancelEventDefinition;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.impl.agenda.DefaultFlowableEngineAgenda;
import org.flowable.engine.impl.agenda.TakeOutgoingSequenceFlowsOperation;
import org.flowable.engine.impl.bpmn.behavior.BoundaryCancelEventActivityBehavior;
import org.flowable.engine.impl.bpmn.helper.ScopeUtil;
import org.flowable.engine.impl.bpmn.parser.factory.DefaultActivityBehaviorFactory;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.util.CommandContextUtil;

/**
 * Flowable 8 取消边界的补偿生命周期适配。
 * 原生边界在补偿操作入 agenda 后立即删除快照执行，异步补偿因此引用已删除父执行。
 * 本适配保留执行树，所有补偿成功后才离开取消边界；失败继续由原生 Job 重试。
 * 关联仅使用新建执行的内部 reference 字段，不接受流程变量或作者扩展注入。
 */
public final class WorkflowCompensationLifecycle
{
    private static final String WAIT = "approva:cancel-compensation-wait";
    private static final String SCOPE = "approva:cancel-compensation-scope";

    private WorkflowCompensationLifecycle() { }

    public static void configure(ProcessEngineConfigurationImpl configuration)
    {
        if (configuration.getActivityBehaviorFactory() != null
                || configuration.getAgendaFactory() != null)
        {
            throw new IllegalStateException("补偿生命周期适配要求默认 Flowable 行为与 agenda 工厂");
        }
        configuration.setActivityBehaviorFactory(new BehaviorFactory());
        configuration.setAgendaFactory(Agenda::new);
    }

    private static final class BehaviorFactory extends DefaultActivityBehaviorFactory
    {
        @Override
        public BoundaryCancelEventActivityBehavior createBoundaryCancelEventActivityBehavior(
                CancelEventDefinition definition)
        {
            return new CancelBoundary();
        }
    }

    private static final class CancelBoundary extends BoundaryCancelEventActivityBehavior
    {
        private static final long serialVersionUID = 1L;

        @Override
        public void trigger(DelegateExecution delegate, String name, Object data)
        {
            ExecutionEntity execution = (ExecutionEntity) delegate;
            if (WAIT.equals(execution.getReferenceType()))
            {
                throw new FlowableException("取消边界仍在等待补偿完成");
            }
            BoundaryEvent boundary = (BoundaryEvent) execution.getCurrentFlowElement();
            var manager = CommandContextUtil.getExecutionEntityManager();
            var subscriptions = CommandContextUtil.getProcessEngineConfiguration()
                    .getEventSubscriptionServiceConfiguration().getEventSubscriptionService()
                    .findCompensateEventSubscriptionsByProcessInstanceIdAndActivityId(
                            execution.getProcessInstanceId(), boundary.getAttachedToRefId());
            if (subscriptions.isEmpty())
            {
                leave(execution);
                return;
            }
            // CancelEnd 已为本事务建立独立变量快照；只消费该事务，不能补偿其他已完成事务。
            execution.setReferenceType(WAIT);
            ScopeUtil.throwCompensationEvent(subscriptions, execution, false);
            for (var subscription : subscriptions)
            {
                ExecutionEntity scope = manager.findById(subscription.getConfiguration());
                if (scope == null || scope.isDeleted())
                {
                    throw new FlowableException("取消补偿执行快照缺失");
                }
                scope.setParent(execution);
                scope.setReferenceType(SCOPE);
                scope.setReferenceId(execution.getId());
            }
        }
    }

    private static final class Agenda extends DefaultFlowableEngineAgenda
    {
        Agenda(CommandContext context) { super(context); }

        @Override
        public void planTakeOutgoingSequenceFlowsOperation(ExecutionEntity execution, boolean evaluate)
        {
            planOperation(new LeaveOperation(commandContext, execution, evaluate, false), execution);
        }

        @Override
        public void planTakeOutgoingSequenceFlowsSynchronousOperation(ExecutionEntity execution, boolean evaluate)
        {
            planOperation(new LeaveOperation(commandContext, execution, evaluate, true), execution);
        }
    }

    private static final class LeaveOperation extends TakeOutgoingSequenceFlowsOperation
    {
        LeaveOperation(CommandContext context, ExecutionEntity execution, boolean evaluate, boolean synchronous)
        {
            super(context, execution, evaluate, synchronous);
        }

        @Override
        protected void cleanupCompensation()
        {
            ExecutionEntity scope = execution.getParent();
            while (scope != null && !SCOPE.equals(scope.getReferenceType())) scope = scope.getParent();
            if (scope == null)
            {
                super.cleanupCompensation();
                return;
            }
            var manager = CommandContextUtil.getExecutionEntityManager(commandContext);
            ExecutionEntity waiter = manager.findById(scope.getReferenceId());
            if (waiter == null || !WAIT.equals(waiter.getReferenceType()))
            {
                throw new FlowableException("取消补偿等待执行缺失");
            }
            CommandContextUtil.getActivityInstanceEntityManager(commandContext).recordActivityEnd(execution, null);
            ExecutionEntity parent = execution.getParent();
            manager.deleteExecutionAndRelatedData(execution, null, false);
            // 清理已完成的补偿分支；有未完成的兄弟任务/作业时保留完整快照和等待节点。
            while (parent != null && !parent.getId().equals(waiter.getId())
                    && liveChildren(parent).isEmpty())
            {
                ExecutionEntity next = parent.getParent();
                manager.deleteExecutionAndRelatedData(parent, null, false);
                parent = next;
            }
            if (liveChildren(waiter).isEmpty())
            {
                waiter.setReferenceType(null);
                waiter.setReferenceId(null);
                agenda.planTakeOutgoingSequenceFlowsOperation(waiter, true);
            }
        }

        private List<ExecutionEntity> liveChildren(ExecutionEntity parent)
        {
            return CommandContextUtil.getExecutionEntityManager(commandContext)
                    .findChildExecutionsByParentExecutionId(parent.getId()).stream()
                    .filter(child -> !child.isDeleted() && !child.isEnded()).toList();
        }
    }
}
