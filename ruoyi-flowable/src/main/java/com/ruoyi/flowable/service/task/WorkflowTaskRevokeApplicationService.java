package com.ruoyi.flowable.service.task;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.springframework.stereotype.Service;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowProcessRevokeRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.service.task.WorkflowTaskRevokePreparation.RevokePlan;
import com.ruoyi.flowable.service.task.WorkflowTaskRevokePreparation.RevokePreparation;

/** 撤回写事务、锁后复核、安全路径迁移和审计。 */
@Service
public class WorkflowTaskRevokeApplicationService
{
    private static final String REVOKE_COMMENT_TYPE = "REVOKE";
    private final WorkflowEngineOperations engineOperations;
    private final WorkflowTaskRevokePreparation revokePreparation;
    private final WorkflowTaskActionAuditWriter auditWriter;
    private final WorkflowTaskConcurrencyExecutor concurrencyExecutor;
    private final RuntimeService runtimeService;
    private final TaskService taskService;

    public WorkflowTaskRevokeApplicationService(WorkflowEngineOperations engineOperations,
            WorkflowTaskRevokePreparation revokePreparation, WorkflowTaskActionAuditWriter auditWriter,
            WorkflowTaskConcurrencyExecutor concurrencyExecutor,
            RuntimeService runtimeService, TaskService taskService)
    {
        this.engineOperations = engineOperations;
        this.revokePreparation = revokePreparation;
        this.auditWriter = auditWriter;
        this.concurrencyExecutor = concurrencyExecutor;
        this.runtimeService = runtimeService;
        this.taskService = taskService;
    }

    /**
     * 将当前用户最近完成且后继尚未处理的任务撤回为新的活动任务。
     *
     * @param request WorkflowProcessRevokeRequest，已通过 DTO Bean Validation 的实例、本人历史任务和撤回原因
     * @return 无返回值，后继关闭、来源重建、审计和验证在同一事务提交
     */
    public void revokeProcess(WorkflowProcessRevokeRequest request)
    {
        String processInstanceId = request.processInstanceId().trim();
        String historicTaskId = request.taskId().trim();
        String opinion = request.comment().trim();
        engineOperations.writeAsCurrentUser(actor ->
        {
            RevokePreparation preparation = revokePreparation.requireRevokePreparation(
                    processInstanceId, historicTaskId, actor);
            HistoricTaskInstance completedTask = preparation.completedTask();
            RevokePlan plan = preparation.revokePlan();
            String audit = auditWriter.build("REVOKE", actor.userId(), opinion,
                    plan.sourceNodeKey(), completedTask.getId());
            concurrencyExecutor.execute(() ->
            {
                // Flowable revision 写锁必须先于迁移，锁后再次验证全部后继未被处理。
                lockAndRevalidateRevokeTasks(plan, completedTask);
                for (Task successor : plan.successorTasks())
                {
                    taskService.addComment(successor.getId(), processInstanceId,
                            REVOKE_COMMENT_TYPE, audit);
                }
                var stateBuilder = runtimeService.createChangeActivityStateBuilder()
                        .processInstanceId(processInstanceId);
                if (plan.executionIds().size() == 1)
                {
                    stateBuilder.moveExecutionToActivityId(
                            plan.executionIds().get(0), plan.sourceNodeKey());
                }
                else
                {
                    stateBuilder.moveExecutionsToSingleActivityId(
                            plan.executionIds(), plan.sourceNodeKey());
                }
                stateBuilder.changeState();
            });
            return null;
        });
    }

    /**
     * 取得全部后继任务行锁并用锁后事实再次验证冻结计划。
     *
     * @param plan RevokePlan，写前后继任务和 execution 计划
     * @param completedTask HistoricTaskInstance，撤回来源任务
     * @return 无返回值，锁前竞争或锁后漂移时抛出 HTTP 409
     */
    private void lockAndRevalidateRevokeTasks(RevokePlan plan,
            HistoricTaskInstance completedTask)
    {
        List<Task> frozenTasks = new ArrayList<>(plan.successorTasks());
        frozenTasks.sort(Comparator.comparing(Task::getId,
                Comparator.nullsFirst(String::compareTo)));
        for (Task frozenTask : frozenTasks)
        {
            taskService.saveTask(frozenTask);
        }
        List<Task> lockedTasks = revokePreparation.requireUntouchedRevokeTasks(
                plan.processInstanceId(), completedTask);
        if (lockedTasks.size() != frozenTasks.size())
        {
            throw conflict();
        }
        for (int index = 0; index < lockedTasks.size(); index++)
        {
            Task frozen = frozenTasks.get(index);
            Task locked = lockedTasks.get(index);
            if (!Objects.equals(frozen.getId(), locked.getId())
                    || !Objects.equals(frozen.getProcessInstanceId(),
                            locked.getProcessInstanceId())
                    || !Objects.equals(frozen.getProcessDefinitionId(),
                            locked.getProcessDefinitionId())
                    || !Objects.equals(frozen.getTaskDefinitionKey(),
                            locked.getTaskDefinitionKey())
                    || !Objects.equals(frozen.getExecutionId(), locked.getExecutionId())
                    || !Objects.equals(frozen.getAssignee(), locked.getAssignee())
                    || !Objects.equals(frozen.getCreateTime(), locked.getCreateTime()))
            {
                throw conflict();
            }
        }
    }

    /**
     * 创建稳定状态冲突错误。
     *
     * @return ServiceException，既有 HTTP 409 错误
     */
    private ServiceException conflict()
    {
        return new ServiceException("工作流状态已发生变化，请刷新后重试", HttpStatus.CONFLICT);
    }

}
