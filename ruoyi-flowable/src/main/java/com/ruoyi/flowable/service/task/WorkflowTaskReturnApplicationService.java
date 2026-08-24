package com.ruoyi.flowable.service.task;

import java.util.List;
import java.util.Set;
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
    /** 退回意见类型，与旧系统 FlowComment.REBACK 保持兼容。 */
    private static final String RETURN_COMMENT_TYPE = "2";
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
     * @param engineOperations WorkflowEngineOperations，正式事务、身份和异常翻译入口
     * @param requestValidator WorkflowTaskRequestValidator，请求字段门禁
     * @param runtimeReader WorkflowTaskRuntimeReader，活动任务、实例和历史事实读取器
     * @param bpmnReader WorkflowTaskBpmnReader，部署 BPMN 事实读取器
     * @param movementPolicy WorkflowTaskMovementPolicy，普通与受控返回路径策略
     * @param returnedTaskStateService WorkflowReturnedTaskStateService，退回任务状态边界
     * @param auditWriter WorkflowTaskActionAuditWriter，结构化动作审计写入器
     * @param concurrencyExecutor WorkflowTaskConcurrencyExecutor，并发对象消失翻译器
     * @param groupTransitionService WorkflowMultiInstanceGroupTransitionService，整组迁移边界
     * @param taskCopyService WorkflowTaskCopyService，退回抄送服务
     * @param notificationService WorkflowNotificationService，任务通知服务
     * @param runtimeService RuntimeService，普通任务 Flowable 状态迁移服务
     * @return 无返回值，构造后由 Spring 管理
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
     * @param request WorkflowTaskReturnRequest，任务、退回原因和可选抄送人
     * @return 无返回值，迁移、状态、副作用和对账在同一事务提交
     */
    public void returnTask(WorkflowTaskReturnRequest request)
    {
        if (request == null)
        {
            throw requestValidator.invalidArgument();
        }
        String taskId = requestValidator.requireId(request.taskId());
        String opinion = requestValidator.requireOpinion(request.comment());
        engineOperations.writeAsCurrentUser(actor ->
        {
            ReturnPreparation preparation = requireReturnPreparation(taskId, actor);
            concurrencyExecutor.execute(() -> executeReturn(
                    preparation, actor, opinion, request.copyUserIds()));
            return null;
        });
    }

    /**
     * 判断当前用户能否对指定任务执行真实退回。
     * @param taskId String，详情页真实任务主键
     * @return boolean，全部正式前置条件满足时返回 true
     */
    public boolean isTaskReturnAllowed(String taskId)
    {
        return engineOperations.readAsCurrentUserWithServiceExceptionHandler(actor ->
        {
            requireReturnPreparation(requestValidator.requireId(taskId), actor);
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
        auditWriter.write(task, RETURN_COMMENT_TYPE, "RETURN", actorUserId,
                opinion, preparation.targetNodeKey(), null);
        returnedTaskStateService.markTransition(processInstanceId,
                WorkflowReturnedApplicationProtocol.RETURN_TRANSITION_MARKER);
        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(processInstanceId)
                .moveExecutionToActivityId(preparation.executionId(),
                        preparation.targetNodeKey()).changeState();
        Task returnedTask = runtimeReader.requireSingleActiveTask(
                processInstanceId, preparation.targetNodeKey());
        returnedTaskStateService.enterOrdinaryReturned(returnedTask.getId(),
                processInstanceId, preparation.applicantUserId());
        taskCopyService.persist(copyPlan);
        returnedTaskStateService.requireReturnedApplicant(returnedTask.getId(),
                processInstanceId, preparation.applicantUserId());
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
        MultiInstanceGroupReturnPlan plan = preparation.groupPlan();
        String processInstanceId = plan.round().processInstanceId();
        auditWriter.write(preparation.task(), RETURN_COMMENT_TYPE, "RETURN",
                actorUserId, opinion, preparation.targetNodeKey(), null);
        returnedTaskStateService.markTransition(processInstanceId,
                WorkflowReturnedApplicationProtocol.RETURN_TRANSITION_MARKER);
        notificationService.onTasksWithdrawn(processInstanceId,
                plan.activeTaskIds());
        WorkflowMultiInstanceGroupTransitionService.GroupReturnMigration migration =
                groupTransitionService.migrateReturn(plan,
                        preparation.targetNodeKey(), actorUserId);
        returnedTaskStateService.enterGroupReturned(migration.applicantTaskId(),
                processInstanceId, preparation.applicantUserId());
        WorkflowMultiInstanceGroupTransitionService.GroupReturnResult result =
                groupTransitionService.completeReturn(plan, migration,
                        preparation.task().getId(), actorUserId);
        taskCopyService.persist(copyPlan);
        returnedTaskStateService.requireReturnedApplicant(result.applicantTaskId(),
                processInstanceId, preparation.applicantUserId());
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
        runtimeReader.requireCurrentAssignee(task, actor);
        runtimeReader.requireUnownedTask(task);
        returnedTaskStateService.requireRunning(instance.getId());
        if (!StringUtils.hasText(instance.getStartUserId()))
        {
            throw dataError();
        }
        MultiInstanceGroupReturnPlan groupPlan =
                groupTransitionService.prepareGroupReturn(task.getId(), actor.userId());
        WorkflowTaskBpmnSnapshot context = bpmnReader.require(
                task.getProcessDefinitionId());
        UserTask currentNode = groupPlan == null
                ? movementPolicy.requireMainProcessReturnSource(context.model(),
                        context.definition().getKey(), task.getTaskDefinitionKey())
                : movementPolicy.requireMainProcessControlledReturnSource(context.model(),
                        context.definition().getKey(), task.getTaskDefinitionKey());
        String targetNodeKey = requireFirstApprovalNode(task, context,
                currentNode, groupPlan != null);
        if (groupPlan != null)
        {
            return new GroupReturnPreparation(task, instance.getStartUserId(),
                    targetNodeKey, groupPlan);
        }
        return new OrdinaryReturnPreparation(task, instance.getStartUserId(),
                targetNodeKey, runtimeReader.requireOnlyActiveExecution(task));
    }

    /**
     * 从真实历史确定首个审批节点并验证当前返回路径安全。
     * @param task Task，当前活动任务
     * @param context WorkflowTaskBpmnSnapshot，部署 BPMN 事实
     * @param currentNode UserTask，当前普通或受控多实例来源节点
     * @param controlledGroupReturn boolean，是否按受控端点规则验证
     * @return String，实例最早创建的主流程用户任务节点 key
     */
    private String requireFirstApprovalNode(Task task,
            WorkflowTaskBpmnSnapshot context, UserTask currentNode,
            boolean controlledGroupReturn)
    {
        List<HistoricTaskInstance> historicTasks = runtimeReader
                .readHistoricTasksAscending(task.getProcessInstanceId(), 501);
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
                movementPolicy.requireSafeControlledReturnPath(
                        context.process(), targetNode, currentNode);
            }
            else
            {
                movementPolicy.requireSafeDirectReturnPath(
                        context.process(), targetNode, currentNode);
            }
            return historicTask.getTaskDefinitionKey();
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
            String targetNodeKey, MultiInstanceGroupReturnPlan groupPlan)
            implements ReturnPreparation
    {
    }
}
