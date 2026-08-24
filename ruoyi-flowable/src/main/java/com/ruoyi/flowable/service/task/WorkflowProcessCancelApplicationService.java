package com.ruoyi.flowable.service.task;

import java.util.List;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.flowable.domain.dto.WorkflowProcessCancelRequest;
import com.ruoyi.flowable.engine.WorkflowEngineOperations;
import com.ruoyi.flowable.identity.WorkflowCurrentIdentity;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;

/**
 * 发起人或管理员取消完整流程树的应用服务。
 */
@Service
public class WorkflowProcessCancelApplicationService
{
    /** 取消流程意见类型，与旧系统 FlowComment.STOP 保持兼容。 */
    private static final String CANCEL_COMMENT_TYPE = "6";

    /** 整实例取消时允许原子写入意见的最大活动任务数量。 */
    private static final int MAX_ACTIVE_TASKS_FOR_CANCEL = 2_000;

    /** 取消后的稳定流程状态。 */
    private static final String CANCELED_STATUS = "canceled";

    private final WorkflowEngineOperations engineOperations;
    private final WorkflowTaskRequestValidator requestValidator;
    private final WorkflowTaskRuntimeReader runtimeReader;
    private final WorkflowProcessInstanceService processInstanceService;
    private final TaskService taskService;
    private final WorkflowTaskActionAuditWriter auditWriter;

    /**
     * 创建流程取消应用服务。
     *
     * @param engineOperations WorkflowEngineOperations，正式事务、认证和异常翻译入口
     * @param requestValidator WorkflowTaskRequestValidator，请求 ID 和意见门禁
     * @param runtimeReader WorkflowTaskRuntimeReader，运行实例只读事实
     * @param processInstanceService WorkflowProcessInstanceService，完整流程树终止写链
     * @param taskService TaskService，活动任务意见写入服务
     * @param auditWriter WorkflowTaskActionAuditWriter，结构化动作审计构造器
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowProcessCancelApplicationService(
            WorkflowEngineOperations engineOperations,
            WorkflowTaskRequestValidator requestValidator,
            WorkflowTaskRuntimeReader runtimeReader,
            WorkflowProcessInstanceService processInstanceService,
            TaskService taskService, WorkflowTaskActionAuditWriter auditWriter)
    {
        this.engineOperations = engineOperations;
        this.requestValidator = requestValidator;
        this.runtimeReader = runtimeReader;
        this.processInstanceService = processInstanceService;
        this.taskService = taskService;
        this.auditWriter = auditWriter;
    }

    /**
     * 由流程发起人或受控超级管理员取消 active 或 suspended 的完整业务执行树。
     *
     * @param request WorkflowProcessCancelRequest，流程实例和取消原因
     * @return 无返回值，挂起实例会在同一事务内激活后完成审计、状态和整树终止
     */
    public void cancelProcess(WorkflowProcessCancelRequest request)
    {
        if (request == null)
        {
            throw requestValidator.invalidArgument();
        }
        String processInstanceId = requestValidator.requireId(request.processInstanceId());
        String opinion = requestValidator.requireOpinion(request.comment());
        engineOperations.writeAsCurrentUser(actor ->
        {
            ProcessInstance requested = runtimeReader
                    .requireRunningProcessInstanceForCancellation(processInstanceId);
            ProcessInstance root = processInstanceService
                    .resolveRootProcessInstanceForTermination(requested);
            if (!actor.userId().equals(root.getStartUserId()) && !isAdministrator(actor))
            {
                throw forbidden();
            }
            processInstanceService.terminateRootProcessInstance(root, CANCELED_STATUS,
                    context ->
                    {
                        String audit = auditWriter.buildCancellation(actor.userId(),
                                opinion, context.wasSuspended());
                        List<String> processTreeInstanceIds = context.processTreeInstanceIds();
                        List<Task> activeTasks = taskService.createTaskQuery()
                                .processInstanceIdIn(processTreeInstanceIds).active().list();
                        if (activeTasks == null
                                || activeTasks.size() > MAX_ACTIVE_TASKS_FOR_CANCEL)
                        {
                            throw conflict();
                        }
                        for (Task activeTask : activeTasks)
                        {
                            if (activeTask == null
                                    || !StringUtils.hasText(activeTask.getId())
                                    || !StringUtils.hasText(
                                            activeTask.getProcessInstanceId())
                                    || !processTreeInstanceIds.contains(
                                            activeTask.getProcessInstanceId().trim()))
                            {
                                throw dataError();
                            }
                            // comment 必须关联任务真实所属子实例，保持 Flowable 历史外键语义。
                            taskService.addComment(activeTask.getId(),
                                    activeTask.getProcessInstanceId(),
                                    CANCEL_COMMENT_TYPE, audit);
                        }
                        return new WorkflowProcessInstanceService
                                .RootTerminationInstruction(audit, null);
                    });
            return null;
        });
    }

    /**
     * 判断当前身份是否为若依受控超级管理员。
     *
     * @param actor WorkflowCurrentIdentity，事务内正式身份
     * @return boolean，用户主键命中超级管理员规则时返回 true
     */
    private boolean isAdministrator(WorkflowCurrentIdentity actor)
    {
        try
        {
            return SecurityUtils.isAdmin(Long.valueOf(actor.userId()));
        }
        catch (NumberFormatException exception)
        {
            return false;
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

    /**
     * 创建稳定对象级权限错误。
     *
     * @return ServiceException，既有 HTTP 403 错误
     */
    private ServiceException forbidden()
    {
        return new ServiceException("无权执行当前工作流操作", HttpStatus.FORBIDDEN);
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
