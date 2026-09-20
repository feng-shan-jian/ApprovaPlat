package com.ruoyi.flowable.service.task;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowTaskReturnRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;

/** 普通串行退回与受控 ALL/ANY 整组退回的唯一应用命令服务。 */
@Service
public class WorkflowTaskReturnApplicationService
{
    /** 能力投影可安全归一为 false 的既有业务状态码。 */
    private static final Set<Integer> RETURN_INELIGIBLE_STATUS_CODES = Set.of(
            HttpStatus.BAD_REQUEST, HttpStatus.FORBIDDEN,
            HttpStatus.NOT_FOUND, HttpStatus.CONFLICT);

    private final WorkflowEngineOperations engineOperations;
    private final WorkflowTaskRequestValidator requestValidator;
    private final WorkflowTaskRuntimeReader runtimeReader;
    private final WorkflowTaskBpmnReader bpmnReader;
    private final WorkflowTaskMovementPolicy movementPolicy;
    private final WorkflowReturnedTaskStateService returnedTaskStateService;
    private final WorkflowTaskActionAuditWriter auditWriter;
    private final WorkflowTaskConcurrencyExecutor concurrencyExecutor;
    private final WorkflowMultiInstanceGroupTransitionService groupTransitionService;
    private final WorkflowTaskCopyService taskCopyService;
    private final WorkflowNotificationService notificationService;
    private final RuntimeService runtimeService;

    /**
     * 创建独立退回应用服务。
     * 构造后由 Spring 管理
     */
    public WorkflowTaskReturnApplicationService(
            WorkflowEngineOperations engineOperations,
            WorkflowTaskRequestValidator requestValidator,
            WorkflowTaskRuntimeReader runtimeReader,
            WorkflowTaskBpmnReader bpmnReader,
            WorkflowTaskMovementPolicy movementPolicy,
            WorkflowReturnedTaskStateService returnedTaskStateService,
            WorkflowTaskActionAuditWriter auditWriter,
            WorkflowTaskConcurrencyExecutor concurrencyExecutor,
            WorkflowMultiInstanceGroupTransitionService groupTransitionService,
            WorkflowTaskCopyService taskCopyService,
            WorkflowNotificationService notificationService,
            RuntimeService runtimeService)
    {
        this.engineOperations = engineOperations;
        this.requestValidator = requestValidator;
        this.runtimeReader = runtimeReader;
        this.bpmnReader = bpmnReader;
        this.movementPolicy = movementPolicy;
        this.returnedTaskStateService = returnedTaskStateService;
        this.auditWriter = auditWriter;
        this.concurrencyExecutor = concurrencyExecutor;
        this.groupTransitionService = groupTransitionService;
        this.taskCopyService = taskCopyService;
        this.notificationService = notificationService;
        this.runtimeService = runtimeService;
    }

    /**
     * 将普通串行任务或受控多实例整组退回服务端确定的首审批节点。
     * @param request WorkflowTaskReturnRequest，已通过 DTO Bean Validation 的任务、退回原因和可选抄送人
     * @return 无返回值，迁移、状态、副作用和对账在同一事务提交
     */
    public void returnTask(WorkflowTaskReturnRequest request)
    {
        String taskId = request.taskId().trim();
        String opinion = request.comment().trim();
        engineOperations.writeAsCurrentUser(actor ->
        {
            ReturnPreparation preparation = requireReturnPreparation(taskId, actor);
            concurrencyExecutor.execute(() -> executeReturn(
                    preparation, actor, opinion, request.copyUserIds()));
            return null;
        });
    }

    /** 同次详情事务复用已读取对象，正式写入入口仍自行获取当前任务和实例。 */
    public boolean isTaskReturnAllowed(Task task, ProcessInstance instance,
            WorkflowCurrentIdentity actor, WorkflowTaskBpmnSnapshot bpmn,
            WorkflowMultiInstanceService.MultiInstanceContext multiInstance,
            Supplier<Collection<HistoricTaskInstance>> historicTasks, List<Task> activeTasks)
    {
        if (task == null || instance == null || task.isSuspended() || instance.isSuspended())
        {
            return false;
        }
        return engineOperations.readWithServiceExceptionHandler(() ->
        {
            requireReturnIdentity(task, instance, actor);
            prepareReturn(task, instance,
                    groupTransitionService.prepareGroupReturn(multiInstance, instance, actor.userId()),
                    bpmn, historicTasks,
                    () -> runtimeReader.requireOnlyActiveExecution(task, activeTasks));
            return true;
        }, exception ->
        {
            if (RETURN_INELIGIBLE_STATUS_CODES.contains(exception.getCode()))
            {
                return false;
            }
            throw exception;
        });
    }

    /**
     * 按冻结类型执行普通退回或受控整组退回。
     * @param preparation ReturnPreparation，事务内已核验退回计划
     * @param actor WorkflowCurrentIdentity，事务内正式身份
     * @param opinion String，已校验退回原因
     * @param copyUserIds List&lt;Long&gt;，可选抄送用户主键
     * @return 无返回值，任一步失败由外层事务回滚
     */
    private void executeReturn(ReturnPreparation preparation,
            WorkflowCurrentIdentity actor, String opinion, List<Long> copyUserIds)
    {
        WorkflowTaskCopyService.CopyPlan copyPlan = taskCopyService.prepare(
                WorkflowTaskCopyAction.RETURN, preparation.task(), actor, copyUserIds);
        if (preparation instanceof OrdinaryReturnPreparation ordinary)
        {
            executeOrdinaryReturn(ordinary, actor.userId(), opinion, copyPlan);
            return;
        }
        if (preparation instanceof GroupReturnPreparation group)
        {
            executeGroupReturn(group, actor.userId(), opinion, copyPlan);
            return;
        }
        throw dataError();
    }

    /**
     * 保持普通串行退回的审计、迁移、状态、抄送、通知和核验顺序。
     * @param preparation OrdinaryReturnPreparation，唯一普通 execution 计划
     * @param actorUserId String，事务内正式用户主键
     * @param opinion String，已校验退回原因
     * @param copyPlan CopyPlan，迁移前冻结的抄送计划
     * @return 无返回值，任一步失败由外层事务回滚
     */
    private void executeOrdinaryReturn(OrdinaryReturnPreparation preparation,
            String actorUserId, String opinion,
            WorkflowTaskCopyService.CopyPlan copyPlan)
    {
        Task task = preparation.task();
        String processInstanceId = task.getProcessInstanceId();
        auditWriter.write(task, "RETURN", actorUserId,
                opinion, preparation.targetNodeKey(), null);
        returnedTaskStateService.markTransition(processInstanceId,
                WorkflowReturnedApplicationProtocol.RETURN_TRANSITION_MARKER);
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstanceId)
                .moveExecutionToActivityId(preparation.executionId(),
                        preparation.targetNodeKey()).changeState();
        Task returnedTask = runtimeReader.requireSingleActiveTask(
                processInstanceId, preparation.targetNodeKey());
        returnedTaskStateService.enterReturned(returnedTask.getId(),
                processInstanceId, preparation.applicantUserId());
        taskCopyService.persist(copyPlan);
        notificationService.onStableTaskEvent("TASK_RETURNED", returnedTask);
        returnedTaskStateService.clearTransition(processInstanceId);
    }

    /**
     * 保持整组退回的审计、标记、撤销通知、Flowable、SLA、状态、CAS 和对账顺序。
     * @param preparation GroupReturnPreparation，受控整组计划
     * @param actorUserId String，事务内正式用户主键
     * @param opinion String，已校验退回原因
     * @param copyPlan CopyPlan，迁移前冻结的抄送计划
     * @return 无返回值，任一步失败由外层事务回滚
     */
    private void executeGroupReturn(GroupReturnPreparation preparation,
            String actorUserId, String opinion,
            WorkflowTaskCopyService.CopyPlan copyPlan)
    {
        MultiInstanceGroupReturnExecutionPlan executionPlan =
                preparation.groupExecutionPlan();
        MultiInstanceGroupReturnPlan plan = executionPlan.source();
        String processInstanceId = plan.round().processInstanceId();
        auditWriter.write(preparation.task(), "RETURN",
                actorUserId, opinion, preparation.targetNodeKey(), null);
        returnedTaskStateService.markTransition(processInstanceId,
                WorkflowReturnedApplicationProtocol.RETURN_TRANSITION_MARKER);
        notificationService.onTasksWithdrawn(processInstanceId,
                plan.activeTaskIds());
        WorkflowMultiInstanceGroupTransitionService.GroupReturnResult result =
                groupTransitionService.returnGroup(executionPlan,
                        actorUserId);
        taskCopyService.persist(copyPlan);
        Task applicantTask = runtimeReader.requireActiveTask(result.applicantTaskId());
        notificationService.onStableTaskEvent("TASK_RETURNED", applicantTask);
        returnedTaskStateService.clearTransition(processInstanceId);
    }

    /**
     * 在同一只读快照中准备普通或受控整组退回计划。
     * @param taskId String，待退回活动任务主键
     * @param actor WorkflowCurrentIdentity，事务内正式身份
     * @return ReturnPreparation，服务端可信首审节点和普通 execution 或整组计划
     */
    private ReturnPreparation requireReturnPreparation(String taskId,
            WorkflowCurrentIdentity actor)
    {
        Task task = runtimeReader.requireActiveTask(taskId);
        ProcessInstance instance = runtimeReader.requireActiveProcessInstance(
                task.getProcessInstanceId());
        requireReturnIdentity(task, instance, actor);
        MultiInstanceGroupReturnPlan groupPlan =
                groupTransitionService.prepareGroupReturn(task, instance, actor.userId());
        WorkflowTaskBpmnSnapshot context = bpmnReader.require(task.getProcessDefinitionId());
        return prepareReturn(task, instance, groupPlan, context,
                () -> runtimeReader.readHistoricTasksAscending(task.getProcessInstanceId(), 501),
                () -> runtimeReader.requireOnlyActiveExecution(task));
    }

    private void requireReturnIdentity(Task task, ProcessInstance instance,
            WorkflowCurrentIdentity actor)
    {
        runtimeReader.requireCurrentAssignee(task, actor);
        runtimeReader.requireUnownedTask(task);
        returnedTaskStateService.requireRunning(instance);
        if (!StringUtils.hasText(instance.getStartUserId()))
        {
            throw dataError();
        }
    }

    private ReturnPreparation prepareReturn(Task task, ProcessInstance instance,
            MultiInstanceGroupReturnPlan groupPlan, WorkflowTaskBpmnSnapshot context,
            Supplier<Collection<HistoricTaskInstance>> historicTasks, Supplier<String> executionId)
    {
        UserTask currentNode = groupPlan == null
                ? movementPolicy.requireMainProcessReturnSource(context.model(),
                        context.definition().getKey(), task.getTaskDefinitionKey())
                : movementPolicy.requireMainProcessControlledReturnSource(context.model(),
                        context.definition().getKey(), task.getTaskDefinitionKey());
        FirstApprovalTarget target = requireFirstApprovalNode(historicTasks.get(), context,
                currentNode, groupPlan != null);
        if (groupPlan != null)
        {
            MultiInstanceGroupReturnExecutionPlan executionPlan =
                    groupTransitionService.prepareGroupReturnExecution(
                            groupPlan, target.nodeKey(), target.controlledPath(), context);
            return new GroupReturnPreparation(task, instance.getStartUserId(),
                    target.nodeKey(), executionPlan);
        }
        return new OrdinaryReturnPreparation(task, instance.getStartUserId(),
                target.nodeKey(), executionId.get());
    }

    /**
     * 从真实历史确定首个审批节点并验证当前返回路径安全。
     * @param historicTasks Collection&lt;HistoricTaskInstance&gt;，按开始时间和任务 ID 排序的历史任务
     * @param context WorkflowTaskBpmnSnapshot，部署 BPMN 事实
     * @param currentNode UserTask，当前普通或受控多实例来源节点
     * @param controlledGroupReturn boolean，是否按受控端点规则验证
     * @return FirstApprovalTarget，首审批节点 key 和受控路径计划
     */
    private FirstApprovalTarget requireFirstApprovalNode(Collection<HistoricTaskInstance> historicTasks,
            WorkflowTaskBpmnSnapshot context, UserTask currentNode,
            boolean controlledGroupReturn)
    {
        if (historicTasks.isEmpty() || historicTasks.size() > 500)
        {
            throw conflict();
        }
        for (HistoricTaskInstance historicTask : historicTasks)
        {
            if (historicTask == null
                    || !StringUtils.hasText(historicTask.getTaskDefinitionKey())
                    || !(context.process().getFlowElement(
                            historicTask.getTaskDefinitionKey(), false)
                            instanceof UserTask targetNode))
            {
                continue;
            }
            if (controlledGroupReturn)
            {
                WorkflowTaskMovementPolicy.ControlledReturnPathPlan path =
                        movementPolicy.requireSafeControlledReturnPath(
                        context.process(), targetNode, currentNode);
                return new FirstApprovalTarget(
                        historicTask.getTaskDefinitionKey(), path);
            }
            else
            {
                movementPolicy.requireSafeDirectReturnPath(
                        context.process(), targetNode, currentNode);
                return new FirstApprovalTarget(
                        historicTask.getTaskDefinitionKey(), null);
            }
        }
        throw conflict();
    }

    /** @return ServiceException，稳定 HTTP 409。 */
    private ServiceException conflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试",
                HttpStatus.CONFLICT);
    }

    /** @return ServiceException，稳定 HTTP 500。 */
    private ServiceException dataError()
    {
        return new ServiceException("工作流对象关联数据异常", HttpStatus.ERROR);
    }

    /** 普通串行退回与受控整组退回共享的只读门面事实。 */
    private sealed interface ReturnPreparation
            permits OrdinaryReturnPreparation, GroupReturnPreparation
    {
        Task task();
        String applicantUserId();
        String targetNodeKey();
    }

    /** 普通串行退回计划，仅携带唯一普通 execution。 */
    private record OrdinaryReturnPreparation(Task task, String applicantUserId,
            String targetNodeKey, String executionId) implements ReturnPreparation
    {
    }

    /** 受控多实例整组退回计划，不展开轮次内部字段。 */
    private record GroupReturnPreparation(Task task, String applicantUserId,
            String targetNodeKey,
            MultiInstanceGroupReturnExecutionPlan groupExecutionPlan)
            implements ReturnPreparation
    {
    }

    /**
     * 历史首审批节点及其到受控来源之间的安全路径。
     *
     * @param nodeKey String，服务端历史确定的首审批节点 key
     * @param controlledPath ControlledReturnPathPlan，整组退回路径；普通退回为空
     */
    private record FirstApprovalTarget(String nodeKey,
            WorkflowTaskMovementPolicy.ControlledReturnPathPlan controlledPath)
    {
    }
}
