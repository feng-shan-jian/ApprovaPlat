package com.ruoyi.flowable.service.task;

import java.util.List;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowTaskRejectRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;

/**
 * 任务驳回权限、整树终止、审计、通知和抄送应用服务。
 */
@Service
public class WorkflowTaskRejectionApplicationService
{
    /** 驳回意见类型，与旧系统 FlowComment.REJECT 保持兼容。 */
    private static final String REJECT_COMMENT_TYPE = "3";

    /** 驳回时允许原子写入意见的最大活动任务数量。 */
    private static final int MAX_ACTIVE_TASKS_FOR_REJECT = 2_000;

    /** 驳回后的稳定流程状态。 */
    private static final String REJECTED_STATUS = "rejected";

    private final WorkflowEngineOperations engineOperations;
    private final WorkflowTaskRequestValidator requestValidator;
    private final WorkflowTaskRuntimeReader runtimeReader;
    private final WorkflowProcessInstanceService processInstanceService;
    private final WorkflowTaskCopyService taskCopyService;
    private final WorkflowTaskActionAuditWriter auditWriter;
    private final TaskService taskService;

    /**
     * 创建任务驳回应用服务。
     *
     * @param engineOperations WorkflowEngineOperations，正式事务和身份入口
     * @param requestValidator WorkflowTaskRequestValidator，请求字段门禁
     * @param runtimeReader WorkflowTaskRuntimeReader，活动任务和实例事实
     * @param processInstanceService WorkflowProcessInstanceService，完整执行树终止写链
     * @param taskCopyService WorkflowTaskCopyService，驳回抄送计划和持久化服务
     * @param auditWriter WorkflowTaskActionAuditWriter，结构化驳回审计构造器
     * @param taskService TaskService，活动任务查询和 comment 写入服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowTaskRejectionApplicationService(
            WorkflowEngineOperations engineOperations,
            WorkflowTaskRequestValidator requestValidator,
            WorkflowTaskRuntimeReader runtimeReader,
            WorkflowProcessInstanceService processInstanceService,
            WorkflowTaskCopyService taskCopyService,
            WorkflowTaskActionAuditWriter auditWriter,
            TaskService taskService)
    {
        this.engineOperations = engineOperations;
        this.requestValidator = requestValidator;
        this.runtimeReader = runtimeReader;
        this.processInstanceService = processInstanceService;
        this.taskCopyService = taskCopyService;
        this.auditWriter = auditWriter;
        this.taskService = taskService;
    }

    /**
     * 由当前办理人将普通、并行或多实例流程原子驳回为 rejected 终态。
     *
     * @param request WorkflowTaskRejectRequest，任务、驳回原因和可选抄送人
     * @return 无返回值，整树状态、意见、通知和抄送在同一事务提交
     */
    public void rejectTask(WorkflowTaskRejectRequest request)
    {
        if (request == null)
        {
            throw requestValidator.invalidArgument();
        }
        String taskId = requestValidator.requireId(request.taskId());
        String opinion = requestValidator.requireOpinion(request.comment());
        engineOperations.writeAsCurrentUser(actor ->
        {
            Task task = runtimeReader.requireActiveTask(taskId);
            ProcessInstance instance = runtimeReader.requireActiveProcessInstance(
                    task.getProcessInstanceId());
            runtimeReader.requireCurrentAssignee(task, actor);
            runtimeReader.requireUnownedTask(task);
            WorkflowTaskCopyService.CopyPlan[] copyPlanHolder =
                    new WorkflowTaskCopyService.CopyPlan[1];
            processInstanceService.terminateRootProcessInstance(instance,
                    REJECTED_STATUS, context ->
                    {
                        String audit = auditWriter.build("REJECT", actor.userId(),
                                opinion, task.getTaskDefinitionKey(), null);
                        List<Task> activeTasks = taskService.createTaskQuery()
                                .processInstanceIdIn(context.processTreeInstanceIds())
                                .active().list();
                        if (activeTasks == null || activeTasks.isEmpty()
                                || activeTasks.size() > MAX_ACTIVE_TASKS_FOR_REJECT
                                || activeTasks.stream().noneMatch(active -> active != null
                                        && taskId.equals(active.getId())))
                        {
                            throw conflict();
                        }
                        copyPlanHolder[0] = taskCopyService.prepare(
                                WorkflowTaskCopyAction.REJECT, task, actor,
                                request.copyUserIds());
                        for (Task activeTask : activeTasks)
                        {
                            if (activeTask == null
                                    || !StringUtils.hasText(activeTask.getId())
                                    || !StringUtils.hasText(
                                            activeTask.getProcessInstanceId()))
                            {
                                throw dataError();
                            }
                            taskService.addComment(activeTask.getId(),
                                    activeTask.getProcessInstanceId(),
                                    REJECT_COMMENT_TYPE, audit);
                        }
                        return new WorkflowProcessInstanceService
                                .RootTerminationInstruction(audit, null);
                    });
            if (copyPlanHolder[0] == null)
            {
                throw dataError();
            }
            // 抄送保持在根终止通知之后持久化，任一失败由同一外层事务整体回滚。
            taskCopyService.persist(copyPlanHolder[0]);
            return null;
        });
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

    /**
     * 创建稳定关联数据错误。
     *
     * @return ServiceException，既有 HTTP 500 错误
     */
    private ServiceException dataError()
    {
        return new ServiceException("工作流对象关联数据异常", HttpStatus.ERROR);
    }
}
