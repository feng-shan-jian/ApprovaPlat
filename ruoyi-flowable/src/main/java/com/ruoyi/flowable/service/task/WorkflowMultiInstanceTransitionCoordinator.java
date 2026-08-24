package com.ruoyi.flowable.service.task;

import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;

/**
 * 受控多实例整组退回与重提的命令内协议，使用线程绑定上下文约束监听器只能放行当前迁移。
 */
@Component
public class WorkflowMultiInstanceTransitionCoordinator
        implements WorkflowMultiInstanceTransitionObserver
{
    /** 当前线程正在执行的唯一受控迁移；成功或异常结束都必须由 Scope 清除。 */
    private final ThreadLocal<TransitionState> current = new ThreadLocal<>();

    /**
     * 由整组迁移服务开启一次 RETURN 命令作用域。
     *
     * @param plan MultiInstanceGroupReturnPlan，已经与实时执行树对账的正式计划
     * @param actorUserId String，来源任务真实办理人
     * @param targetActivityId String，服务端历史确定的退回目标节点
     * @return WorkflowMultiInstanceTransitionScope，调用方必须在同一线程关闭
     */
    WorkflowMultiInstanceTransitionScope beginReturn(MultiInstanceGroupReturnPlan plan,
            String actorUserId, String targetActivityId)
    {
        if (plan == null || !hasText(actorUserId) || !hasText(targetActivityId)
                || !actorUserId.equals(plan.runtime().sourceTask().assignee()))
        {
            throw dataError();
        }
        MultiInstanceRoundSnapshot round = plan.round();
        ControlledMultiInstanceSnapshot runtime = plan.runtime();
        return begin(new TransitionContext(MultiInstanceTransitionAction.RETURN,
                round.roundId(), round.deployId(), round.processDefinitionId(),
                round.processInstanceId(), round.activityId(),
                round.rootExecutionId(), runtime.rootExecutionId(),
                runtime.sourceTaskId(), actorUserId, null, targetActivityId,
                round.mode(), round.members(), round.revision()));
    }

    /**
     * 由整组迁移服务开启一次 REOPEN 命令作用域。
     *
     * @param plan MultiInstanceGroupReopenPlan，RETURNED 轮次和申请人任务计划
     * @param actorUserId String，已经核验为流程发起人的当前用户
     * @return WorkflowMultiInstanceTransitionScope，调用方必须在同一线程关闭
     */
    WorkflowMultiInstanceTransitionScope beginReopen(MultiInstanceGroupReopenPlan plan,
            String actorUserId)
    {
        if (plan == null || !hasText(actorUserId)
                || !actorUserId.equals(plan.application().applicantUserId()))
        {
            throw dataError();
        }
        MultiInstanceRoundSnapshot round = plan.round();
        ReturnedApplicationSnapshot application = plan.application();
        return begin(new TransitionContext(MultiInstanceTransitionAction.REOPEN,
                round.roundId(), round.deployId(), round.processDefinitionId(),
                round.processInstanceId(), round.activityId(),
                round.rootExecutionId(), application.sourceExecutionId(),
                round.returnSourceTaskId(), actorUserId, application.taskId(),
                round.activityId(), round.mode(), round.members(), round.revision()));
    }

    /**
     * 开启一次严格绑定轮次、execution、节点、源任务和操作人的受控迁移。
     *
     * @param context TransitionContext，由轮次服务根据正式快照构造的不可变上下文
     * @return Scope，调用方必须使用 try-with-resources 在命令结束时清除内部标记
     */
    private Scope begin(TransitionContext context)
    {
        TransitionContext validated = requireContext(context);
        if (current.get() != null)
        {
            throw dataError();
        }
        TransitionState state = new TransitionState(validated);
        current.set(state);
        return new Scope(this, state);
    }

    /**
     * 为多实例集合表达式解析当前受控迁移目标成员。
     *
     * @param processInstanceId String，表达式所属流程实例主键
     * @param activityId String，正在创建的受控多实例节点
     * @param mode WorkflowMultiInstanceMode，部署模型固定的 ALL 或 ANY 模式
     * @return List&lt;String&gt;，RETURN 时仅含真实退回人，REOPEN 时为轮次冻结的完整成员；普通进入返回 null
     */
    public List<String> resolveTransitionMembers(String processInstanceId,
            String activityId, WorkflowMultiInstanceMode mode)
    {
        TransitionState state = current.get();
        if (state == null)
        {
            return null;
        }
        TransitionContext context = state.context;
        if (!Objects.equals(context.processInstanceId(), processInstanceId)
                || !Objects.equals(context.targetActivityId(), activityId)
                || context.mode() != mode)
        {
            throw dataError();
        }
        state.collectionResolved = true;
        return context.action() == MultiInstanceTransitionAction.RETURN
                ? List.of(context.operationActorUserId()) : context.members();
    }

    /**
     * 核对集合表达式读取的流程成员和 revision 仍与正式轮次绑定快照完全一致。
     *
     * @param processInstanceId String，当前流程实例主键
     * @param activityId String，当前受控多实例节点
     * @param mode WorkflowMultiInstanceMode，流程变量与部署模型共同确认的模式
     * @param persistedMembers List&lt;String&gt;，流程作用域现存完整成员快照
     * @param persistedRevision int，流程作用域现存 revision
     * @return void，任一事实漂移时中止当前 Flowable 命令
     */
    public void requirePersistedSnapshot(String processInstanceId, String activityId,
            WorkflowMultiInstanceMode mode, List<String> persistedMembers,
            int persistedRevision)
    {
        TransitionState state = current.get();
        if (state == null)
        {
            throw dataError();
        }
        TransitionContext context = state.context;
        if (!Objects.equals(context.processInstanceId(), processInstanceId)
                || !Objects.equals(context.activityId(), activityId)
                || context.mode() != mode || context.revision() != persistedRevision
                || !context.members().equals(persistedMembers))
        {
            throw dataError();
        }
    }

    /**
     * 在临时申请人多实例任务 create 事件中核对新根和初始办理人，并阻止登记正式 ACTIVE 轮次。
     *
     * @param processInstanceId String，临时任务所属流程实例
     * @param activityId String，临时任务所属受控多实例节点
     * @param rootExecutionId String，Flowable 新建的临时单成员根 execution
     * @param taskId String，Flowable 新建的临时任务主键
     * @param assignee String，改派申请人之前由集合表达式赋予的真实退回人
     * @return boolean，当前 create 属于 RETURN 临时任务时返回 true；普通或 REOPEN 创建返回 false
     */
    public boolean observeTemporaryTask(String processInstanceId, String activityId,
            String rootExecutionId, String taskId, String assignee)
    {
        TransitionState state = current.get();
        if (state == null || state.context.action() != MultiInstanceTransitionAction.RETURN)
        {
            return false;
        }
        TransitionContext context = state.context;
        if (!Objects.equals(context.processInstanceId(), processInstanceId)
                || !Objects.equals(context.targetActivityId(), activityId)
                || !Objects.equals(context.operationActorUserId(), assignee)
                || !hasText(rootExecutionId) || !hasText(taskId)
                || state.temporaryTaskId != null)
        {
            throw dataError();
        }
        state.temporaryRootExecutionId = rootExecutionId;
        state.temporaryTaskId = taskId;
        return true;
    }

    /**
     * 在新审批轮次任务 create 事件中记录唯一的新根，并拒绝同一重提产生第二个根。
     *
     * @param processInstanceId String，新任务所属流程实例
     * @param activityId String，新任务所属原多实例节点
     * @param rootExecutionId String，新轮次多实例根 execution
     * @param assignee String，新任务办理人
     * @return void，非 REOPEN 上下文保持无操作
     */
    public void observeReopenedTask(String processInstanceId, String activityId,
            String rootExecutionId, String assignee)
    {
        TransitionState state = current.get();
        if (state == null || state.context.action() != MultiInstanceTransitionAction.REOPEN)
        {
            return;
        }
        TransitionContext context = state.context;
        if (!Objects.equals(context.processInstanceId(), processInstanceId)
                || !Objects.equals(context.targetActivityId(), activityId)
                || !context.members().contains(assignee) || !hasText(rootExecutionId)
                || (state.reopenedRootExecutionId != null
                        && !state.reopenedRootExecutionId.equals(rootExecutionId)))
        {
            throw dataError();
        }
        state.reopenedRootExecutionId = rootExecutionId;
        state.reopenedTaskCount++;
    }

    /**
     * 判断并记录当前多实例根取消是否属于已经严格绑定的退回或重提迁移。
     *
     * @param processInstanceId String，取消事件流程实例主键
     * @param processDefinitionId String，取消事件流程定义主键
     * @param activityId String，被取消的受控多实例节点
     * @param rootExecutionId String，被取消的真实多实例根 execution
     * @param authenticatedUserId String，Flowable 当前命令认证用户
     * @return MultiInstanceTransitionCancellation，当前取消属于受控迁移时返回不可变观察结果；没有受控迁移时返回 null
     */
    public MultiInstanceTransitionCancellation observeControlledRootCancellation(String processInstanceId,
            String processDefinitionId, String activityId, String rootExecutionId,
            String authenticatedUserId)
    {
        TransitionState state = current.get();
        if (state == null)
        {
            return null;
        }
        TransitionContext context = state.context;
        if (!Objects.equals(context.processInstanceId(), processInstanceId)
                || !Objects.equals(context.processDefinitionId(), processDefinitionId)
                || !Objects.equals(context.activityId(), activityId)
                || !Objects.equals(context.sourceExecutionId(), rootExecutionId)
                || !Objects.equals(context.operationActorUserId(), authenticatedUserId)
                || state.cancellationObserved)
        {
            throw dataError();
        }
        state.cancellationObserved = true;
        return new MultiInstanceTransitionCancellation(context.action(), context.roundId(),
                context.deployId(), context.processDefinitionId(),
                context.processInstanceId(), context.activityId(),
                context.roundRootExecutionId(), context.sourceTaskId(),
                context.applicantTaskId(), context.mode(), context.members(),
                context.revision());
    }

    /**
     * 校验整组退回已经经过根取消、集合解析及可选临时任务创建的完整监听链。
     *
     * @param scope Scope，本次 returnTask 持有的命令作用域
     * @param applicantTaskId String，状态迁移后唯一申请人任务主键
     * @param temporaryMultiInstanceTask boolean，首审批节点本身是否为同一受控多实例节点
     * @return MultiInstanceTransitionResult，整组退回命令内汇总的不可变观察结果
     */
    public MultiInstanceTransitionResult requireReturnCompleted(
            WorkflowMultiInstanceTransitionScope scope, String applicantTaskId,
            boolean temporaryMultiInstanceTask)
    {
        TransitionState state = requireScope(scope, MultiInstanceTransitionAction.RETURN);
        if (!state.cancellationObserved
                || (temporaryMultiInstanceTask && (!state.collectionResolved
                        || !Objects.equals(state.temporaryTaskId, applicantTaskId)
                        || !hasText(state.temporaryRootExecutionId)))
                || (!temporaryMultiInstanceTask && state.temporaryTaskId != null))
        {
            throw dataError();
        }
        return result(state);
    }

    /**
     * 校验重提已创建唯一新根和完整成员数量，并按来源结构要求核对旧临时根取消事件。
     *
     * @param scope Scope，本次 resubmitApplication 持有的命令作用域
     * @param newRootExecutionId String，写后对账得到的新轮次根 execution
     * @param sourceWasMultiInstanceRoot boolean，申请人临时任务是否位于受控多实例根
     * @return MultiInstanceTransitionResult，重提命令内汇总的不可变观察结果
     */
    public MultiInstanceTransitionResult requireReopenCompleted(
            WorkflowMultiInstanceTransitionScope scope, String newRootExecutionId,
            boolean sourceWasMultiInstanceRoot)
    {
        TransitionState state = requireScope(scope, MultiInstanceTransitionAction.REOPEN);
        if (!state.collectionResolved
                || !Objects.equals(state.reopenedRootExecutionId, newRootExecutionId)
                || state.reopenedTaskCount != state.context.members().size()
                || state.cancellationObserved != sourceWasMultiInstanceRoot)
        {
            throw dataError();
        }
        return result(state);
    }

    /**
     * 读取并验证调用方持有的作用域仍是当前线程唯一迁移。
     *
     * @param scope Scope，待核验的调用方作用域
     * @param action Action，调用方预期动作
     * @return TransitionState，可继续执行写后观察校验的内部状态
     */
    private TransitionState requireScope(WorkflowMultiInstanceTransitionScope scope,
            MultiInstanceTransitionAction action)
    {
        TransitionState state = current.get();
        if (!(scope instanceof Scope concreteScope)
                || concreteScope.owner != this || concreteScope.state != state
                || state == null || state.context.action() != action
                || concreteScope.closed)
        {
            throw dataError();
        }
        return state;
    }

    /**
     * 清除当前命令标记并拒绝跨线程、嵌套或重复关闭。
     *
     * @param scope Scope，由 begin 返回且仍处于活动态的作用域
     * @return void，清除后后续 Flowable 命令无法读取本次迁移上下文
     */
    private void close(Scope scope)
    {
        TransitionState state = current.get();
        if (scope.owner != this || scope.state != state || scope.closed)
        {
            throw dataError();
        }
        scope.closed = true;
        current.remove();
    }

    /**
     * 对公开迁移上下文字段执行结构和领域约束校验。
     *
     * @param context TransitionContext，轮次服务根据正式快照构造的上下文
     * @return TransitionContext，成员已规范化复制的安全上下文
     */
    private TransitionContext requireContext(TransitionContext context)
    {
        if (context == null || context.action() == null || context.roundId() <= 0
                || !hasText(context.deployId()) || !hasText(context.processDefinitionId())
                || !hasText(context.processInstanceId()) || !hasText(context.activityId())
                || !hasText(context.roundRootExecutionId())
                || !hasText(context.sourceExecutionId()) || !hasText(context.sourceTaskId())
                || !hasText(context.operationActorUserId())
                || !hasText(context.targetActivityId()) || context.mode() == null
                || context.revision() < 0)
        {
            throw dataError();
        }
        List<String> members = context.members() == null
                ? null : List.copyOf(context.members());
        if (members == null || members.isEmpty()
                || members.stream().anyMatch(member -> !hasText(member))
                || members.stream().distinct().count() != members.size())
        {
            throw dataError();
        }
        if (!members.contains(context.operationActorUserId())
                && context.action() == MultiInstanceTransitionAction.RETURN)
        {
            throw dataError();
        }
        if (context.action() == MultiInstanceTransitionAction.REOPEN
                && !hasText(context.applicantTaskId()))
        {
            throw dataError();
        }
        if (context.action() == MultiInstanceTransitionAction.RETURN
                && context.applicantTaskId() != null)
        {
            throw dataError();
        }
        return new TransitionContext(context.action(), context.roundId(),
                context.deployId(), context.processDefinitionId(),
                context.processInstanceId(), context.activityId(),
                context.roundRootExecutionId(), context.sourceExecutionId(),
                context.sourceTaskId(), context.operationActorUserId(),
                context.applicantTaskId(), context.targetActivityId(),
                context.mode(), members, context.revision());
    }

    /**
     * 把命令期间的可变观察状态冻结为只读结果。
     *
     * @param state TransitionState，仍属于当前作用域的内部观察状态
     * @return MultiInstanceTransitionResult，供整组服务执行写后最终核对
     */
    private MultiInstanceTransitionResult result(TransitionState state)
    {
        return new MultiInstanceTransitionResult(state.context.action(),
                state.collectionResolved, state.cancellationObserved,
                state.temporaryRootExecutionId, state.temporaryTaskId,
                state.reopenedRootExecutionId, state.reopenedTaskCount);
    }

    /**
     * 判断内部标识字段是否具备有效文本。
     *
     * @param value String，可空内部字段
     * @return boolean，包含非空白字符时返回 true
     */
    private boolean hasText(String value)
    {
        return StringUtils.hasText(value);
    }

    /**
     * 创建严格协议损坏时的稳定服务端异常。
     *
     * @return ServiceException，HTTP 500 且必须回滚当前 Flowable 命令
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流多实例受控迁移上下文异常", HttpStatus.ERROR);
    }

    /**
     * 绑定一次受控迁移的全部不可变事实。
     *
     * @param action Action，RETURN 或 REOPEN
     * @param roundId long，正式轮次主键
     * @param deployId String，冻结部署主键
     * @param processDefinitionId String，冻结流程定义主键
     * @param processInstanceId String，真实流程实例主键
     * @param activityId String，原受控多实例节点
     * @param roundRootExecutionId String，轮次表冻结的原审批根 execution
     * @param sourceExecutionId String，本次状态迁移实际撤销的 execution 或多实例根
     * @param sourceTaskId String，原退回来源审批任务主键
     * @param operationActorUserId String，本次 API 操作人的规范用户主键
     * @param applicantTaskId String，REOPEN 时唯一申请人任务主键；RETURN 时为空
     * @param targetActivityId String，本次 Flowable 状态迁移目标节点
     * @param mode WorkflowMultiInstanceMode，冻结 ALL/ANY 模式
     * @param members List&lt;String&gt;，冻结有序完整成员
     * @param revision int，冻结 revision
     */
    private record TransitionContext(MultiInstanceTransitionAction action,
            long roundId, String deployId,
            String processDefinitionId, String processInstanceId, String activityId,
            String roundRootExecutionId, String sourceExecutionId,
            String sourceTaskId, String operationActorUserId, String applicantTaskId,
            String targetActivityId, WorkflowMultiInstanceMode mode,
            List<String> members, int revision)
    {
    }

    /**
     * 调用方持有的迁移作用域，只允许同线程单次关闭。
     */
    private static final class Scope implements WorkflowMultiInstanceTransitionScope
    {
        /** 创建该作用域的唯一迁移协调器。 */
        private final WorkflowMultiInstanceTransitionCoordinator owner;

        /** 当前线程绑定的唯一内部状态。 */
        private final TransitionState state;

        /** 防止重复关闭和错误复用。 */
        private boolean closed;

        /**
         * 创建仅供迁移协调器返回的命令作用域。
         *
         * @param owner WorkflowMultiInstanceTransitionCoordinator，作用域所有者
         * @param state TransitionState，本次命令内部状态
         * @return 无返回值，调用方只能通过 begin 获得实例
         */
        private Scope(WorkflowMultiInstanceTransitionCoordinator owner,
                TransitionState state)
        {
            this.owner = owner;
            this.state = state;
        }

        /**
         * 删除线程绑定迁移标识，禁止残留到连接池线程承载的后续命令。
         *
         * @return void，重复关闭或跨线程关闭会作为协议损坏失败
         */
        @Override
        public void close()
        {
            owner.close(this);
        }
    }

    /** 命令执行期间由表达式和监听器共同更新的观察状态。 */
    private static final class TransitionState
    {
        /** 不可变迁移事实。 */
        private final TransitionContext context;

        /** 多实例集合表达式是否已按本协议解析。 */
        private boolean collectionResolved;

        /** 原多实例根取消是否已由全局监听器严格对账。 */
        private boolean cancellationObserved;

        /** RETURN 首节点场景创建的临时单成员根。 */
        private String temporaryRootExecutionId;

        /** RETURN 首节点场景创建的临时申请人任务。 */
        private String temporaryTaskId;

        /** REOPEN 创建的唯一新审批根。 */
        private String reopenedRootExecutionId;

        /** REOPEN 新根实际触发的任务 create 次数。 */
        private int reopenedTaskCount;

        /**
         * 创建一次命令观察状态。
         *
         * @param context TransitionContext，已经规范化的不可变迁移事实
         * @return 无返回值，所有观察字段从未触发状态开始
         */
        private TransitionState(TransitionContext context)
        {
            this.context = Objects.requireNonNull(context);
        }
    }
}
