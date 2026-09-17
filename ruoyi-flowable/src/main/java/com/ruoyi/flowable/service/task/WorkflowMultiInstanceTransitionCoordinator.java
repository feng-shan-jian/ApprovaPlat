package com.ruoyi.flowable.service.task;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/** 受控迁移命令持有正式计划；监听器只补充本次真实引擎回调的观测。 */
@Component
public class WorkflowMultiInstanceTransitionCoordinator
        implements WorkflowMultiInstanceTransitionObserver
{
    private final ThreadLocal<Scope> current = new ThreadLocal<>();

    WorkflowMultiInstanceTransitionScope beginReturn(
            MultiInstanceGroupReturnExecutionPlan plan, String actorUserId)
    {
        if (plan == null || !StringUtils.hasText(actorUserId)
                || !actorUserId.equals(plan.source().runtime().sourceTask().assignee())
                || !plan.source().round().members().contains(actorUserId))
        {
            throw dataError();
        }
        return begin(new ReturnScope(this, plan));
    }

    WorkflowMultiInstanceTransitionScope beginReopen(MultiInstanceGroupReopenPlan plan,
            String actorUserId)
    {
        if (plan == null || !StringUtils.hasText(actorUserId)
                || !actorUserId.equals(plan.application().applicantUserId())
                || plan.targetReplay() == null
                || plan.application().sourceKind() != ReturnedApplicationSnapshot.SourceKind
                        .TEMPORARY_MULTI_INSTANCE_ROOT)
        {
            throw dataError();
        }
        return begin(new ReopenScope(this, plan));
    }

    private Scope begin(Scope scope)
    {
        if (current.get() != null) throw dataError();
        current.set(scope);
        return scope;
    }

    /** 集合解析匹配当前目标；REOPEN 首次刷新后复用本命令生成的新成员。 */
    public WorkflowMultiInstanceTransitionMembers resolveTransitionMembers(
            String processInstanceId, String activityId, WorkflowMultiInstanceMode mode)
    {
        Scope scope = current.get();
        if (scope == null) return null;
        ControlledMultiInstanceReplaySnapshot replay = scope.targetReplay();
        if (replay == null
                || !Objects.equals(scope.round().processInstanceId(), processInstanceId)
                || !Objects.equals(replay.definition().activityId(), activityId)
                || replay.definition().mode() != mode)
        {
            throw dataError();
        }
        if (scope instanceof ReopenScope && scope.collectionResolved) return null;
        scope.collectionResolved = true;
        return scope instanceof ReturnScope returned
                ? WorkflowMultiInstanceTransitionMembers.override(
                        returned.plan.source().runtime().sourceTask().assignee())
                : WorkflowMultiInstanceTransitionMembers.refresh();
    }

    /** 引擎运行变量必须与被重放的历史轮次匹配，不能用当前状态覆盖历史事实。 */
    public void requirePersistedSnapshot(String processInstanceId, String activityId,
            WorkflowMultiInstanceMode mode, List<String> persistedMembers,
            int persistedRevision)
    {
        Scope scope = current.get();
        if (scope == null) throw dataError();
        ControlledMultiInstanceReplaySnapshot replay = scope.targetReplay();
        if (replay == null
                || !Objects.equals(scope.round().processInstanceId(), processInstanceId)
                || !Objects.equals(replay.definition().activityId(), activityId)
                || replay.definition().mode() != mode
                || replay.round().revision() != persistedRevision
                || !replay.round().members().equals(persistedMembers))
        {
            throw dataError();
        }
    }

    /** RETURN 只允许目标节点创建一个临时根和任务，初始办理人必须是实际退回者。 */
    public boolean observeTemporaryTask(String processInstanceId, String activityId,
            String rootExecutionId, String taskId, String assignee)
    {
        if (!(current.get() instanceof ReturnScope scope)) return false;
        if (!Objects.equals(scope.plan.source().round().processInstanceId(), processInstanceId)
                || !Objects.equals(scope.plan.targetActivityId(), activityId)
                || !Objects.equals(scope.plan.source().runtime().sourceTask().assignee(), assignee)
                || !StringUtils.hasText(rootExecutionId) || !StringUtils.hasText(taskId)
                || scope.temporaryTaskId != null)
        {
            throw dataError();
        }
        scope.temporaryRootExecutionId = rootExecutionId;
        scope.temporaryTaskId = taskId;
        return true;
    }

    /** REOPEN 记录真实新根与任务办理人，拒绝第二个根或重复成员回调。 */
    public void observeReopenedTask(String processInstanceId, String activityId,
            String rootExecutionId, String assignee)
    {
        if (!(current.get() instanceof ReopenScope scope)) return;
        if (!Objects.equals(scope.plan.round().processInstanceId(), processInstanceId)
                || !Objects.equals(scope.plan.application().activityId(), activityId)
                || !StringUtils.hasText(assignee) || !StringUtils.hasText(rootExecutionId)
                || (scope.reopenedRootExecutionId != null
                        && !scope.reopenedRootExecutionId.equals(rootExecutionId))
                || !scope.reopenedAssignees.add(assignee))
        {
            throw dataError();
        }
        scope.reopenedRootExecutionId = rootExecutionId;
    }

    /** 按计划核对本命令收到的取消事件，再提供轮次生命周期需要的真实取消事实。 */
    public MultiInstanceTransitionPlan observeControlledRootCancellation(
            String processInstanceId, String processDefinitionId, String activityId,
            String rootExecutionId, String authenticatedUserId)
    {
        Scope scope = current.get();
        if (scope == null) return null;
        MultiInstanceRoundSnapshot round = scope.round();
        if (!Objects.equals(round.processInstanceId(), processInstanceId)
                || !Objects.equals(round.processDefinitionId(), processDefinitionId)
                || scope.cancellationObserved)
        {
            throw dataError();
        }
        MultiInstanceTransitionPlan plan;
        if (scope instanceof ReturnScope returned)
        {
            ControlledMultiInstanceSnapshot runtime = returned.plan.source().runtime();
            if (!Objects.equals(round.activityId(), activityId)
                    || !Objects.equals(runtime.rootExecutionId(), rootExecutionId)
                    || !Objects.equals(runtime.sourceTask().assignee(), authenticatedUserId))
            {
                throw dataError();
            }
            plan = returned.plan;
        }
        else
        {
            ReopenScope reopened = (ReopenScope) scope;
            ReturnedApplicationSnapshot application = reopened.plan.application();
            if (!Objects.equals(application.activityId(), activityId)
                    || !Objects.equals(application.sourceExecutionId(), rootExecutionId)
                    || !Objects.equals(application.applicantUserId(), authenticatedUserId))
            {
                throw dataError();
            }
            plan = reopened.plan;
        }
        scope.cancellationObserved = true;
        return plan;
    }

    public void requireReturnCompleted(WorkflowMultiInstanceTransitionScope scope,
            String applicantTaskId, boolean temporaryMultiInstanceTask)
    {
        requireScope(scope);
        if (!(scope instanceof ReturnScope returned)
                || !returned.cancellationObserved
                || (temporaryMultiInstanceTask && (!returned.collectionResolved
                        || !Objects.equals(returned.temporaryTaskId, applicantTaskId)
                        || !StringUtils.hasText(returned.temporaryRootExecutionId)))
                || (!temporaryMultiInstanceTask && returned.temporaryTaskId != null))
        {
            throw dataError();
        }
    }

    public void requireReopenCompleted(WorkflowMultiInstanceTransitionScope scope,
            String newRootExecutionId, List<String> expectedMembers,
            boolean sourceWasMultiInstanceRoot)
    {
        requireScope(scope);
        if (!(scope instanceof ReopenScope reopened)
                || !reopened.collectionResolved
                || !Objects.equals(reopened.reopenedRootExecutionId, newRootExecutionId)
                || expectedMembers == null
                || !reopened.reopenedAssignees.equals(new LinkedHashSet<>(expectedMembers))
                || reopened.reopenedAssignees.size() != expectedMembers.size()
                || reopened.cancellationObserved != sourceWasMultiInstanceRoot)
        {
            throw dataError();
        }
    }

    private void requireScope(WorkflowMultiInstanceTransitionScope scope)
    {
        if (!(scope instanceof Scope owned) || owned.owner != this
                || current.get() != owned || owned.closed) throw dataError();
    }

    private void close(Scope scope)
    {
        requireScope(scope);
        scope.closed = true;
        current.remove();
    }

    private ServiceException dataError()
    {
        return new ServiceException("工作流多实例受控迁移上下文异常", HttpStatus.ERROR);
    }

    /** 命令生命周期和共有的实际观测，不复制计划字段。 */
    private abstract static sealed class Scope implements WorkflowMultiInstanceTransitionScope
            permits ReturnScope, ReopenScope
    {
        final WorkflowMultiInstanceTransitionCoordinator owner;
        boolean collectionResolved;
        boolean cancellationObserved;
        boolean closed;

        Scope(WorkflowMultiInstanceTransitionCoordinator owner) { this.owner = owner; }
        abstract MultiInstanceRoundSnapshot round();
        abstract ControlledMultiInstanceReplaySnapshot targetReplay();
        @Override public final void close() { owner.close(this); }
    }

    /** RETURN 计划及其唯一临时申请人任务的真实创建结果。 */
    private static final class ReturnScope extends Scope
    {
        final MultiInstanceGroupReturnExecutionPlan plan;
        String temporaryRootExecutionId;
        String temporaryTaskId;
        ReturnScope(WorkflowMultiInstanceTransitionCoordinator owner,
                MultiInstanceGroupReturnExecutionPlan plan) { super(owner); this.plan = plan; }
        @Override MultiInstanceRoundSnapshot round() { return plan.source().round(); }
        @Override ControlledMultiInstanceReplaySnapshot targetReplay() { return plan.targetReplay(); }
    }

    /** REOPEN 计划及其新审批根、完整成员回调集合。 */
    private static final class ReopenScope extends Scope
    {
        final MultiInstanceGroupReopenPlan plan;
        String reopenedRootExecutionId;
        final LinkedHashSet<String> reopenedAssignees = new LinkedHashSet<>();
        ReopenScope(WorkflowMultiInstanceTransitionCoordinator owner,
                MultiInstanceGroupReopenPlan plan) { super(owner); this.plan = plan; }
        @Override MultiInstanceRoundSnapshot round() { return plan.round(); }
        @Override ControlledMultiInstanceReplaySnapshot targetReplay() { return plan.targetReplay(); }
    }
}
