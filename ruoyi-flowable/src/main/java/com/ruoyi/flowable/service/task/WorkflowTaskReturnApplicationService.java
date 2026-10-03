package com.ruoyi.flowable.service.task;

import java.util.List;
import org.flowable.engine.RuntimeService;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowTaskReturnRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;

import com.ruoyi.flowable.service.task.WorkflowTaskReturnPreparation.ReturnPreparation;
import com.ruoyi.flowable.service.task.WorkflowTaskReturnPreparation.OrdinaryReturnPreparation;
import com.ruoyi.flowable.service.task.WorkflowTaskReturnPreparation.GroupReturnPreparation;

/** 普通串行退回与受控 ALL/ANY 整组退回的唯一应用命令服务。 */
@Service
public class WorkflowTaskReturnApplicationService
{

    private final WorkflowTaskReturnPreparation preparation;
    private final WorkflowEngineOperations engineOperations;
    private final WorkflowTaskRuntimeReader runtimeReader;
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
            WorkflowTaskReturnPreparation preparation,
            WorkflowTaskRuntimeReader runtimeReader,
            WorkflowReturnedTaskStateService returnedTaskStateService,
            WorkflowTaskActionAuditWriter auditWriter,
            WorkflowTaskConcurrencyExecutor concurrencyExecutor,
            WorkflowMultiInstanceGroupTransitionService groupTransitionService,
            WorkflowTaskCopyService taskCopyService,
            WorkflowNotificationService notificationService,
            RuntimeService runtimeService)
    {
        this.engineOperations = engineOperations;
        this.preparation = preparation;
        this.runtimeReader = runtimeReader;
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
            ReturnPreparation preparation = this.preparation.requireReturnPreparation(taskId, actor);
            concurrencyExecutor.execute(() -> executeReturn(
                    preparation, actor, opinion, request.copyUserIds()));
            return null;
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

    /** @return ServiceException，稳定 HTTP 500。 */
    private ServiceException dataError()
    {
        return new ServiceException("工作流对象关联数据异常", HttpStatus.ERROR);
    }

}
