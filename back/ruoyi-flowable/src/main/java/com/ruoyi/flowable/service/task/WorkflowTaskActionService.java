package com.ruoyi.flowable.service.task;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowTaskClaimRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskDelegateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskResolveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskTransferRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskUnclaimRequest;
import com.ruoyi.flowable.engine.WorkflowProcessEngineAdapter;

/**
 * 任务认领、取消认领、完成委派、委派和转办的应用服务。
 */
@Service
public class WorkflowTaskActionService
{
    /** 服务层允许写入审计记录的最大意见长度。 */
    private static final int MAX_OPINION_LENGTH = 500;

    /** 参数校验失败时返回的稳定业务提示。 */
    private static final String INVALID_ARGUMENT_MESSAGE = "工作流请求参数不合法";

    private final WorkflowProcessEngineAdapter processEngineAdapter;

    private final WorkflowTaskCopyService taskCopyService;

    /**
     * 创建任务动作服务。
     *
     * @param processEngineAdapter WorkflowProcessEngineAdapter，Flowable 事务、身份和状态适配器
     * @param taskCopyService WorkflowTaskCopyService，动作成功后同事务写入抄送记录的领域服务
     * @return 无返回值，构造后由 Spring 管理该服务
     */
    public WorkflowTaskActionService(WorkflowProcessEngineAdapter processEngineAdapter,
            WorkflowTaskCopyService taskCopyService)
    {
        this.processEngineAdapter = processEngineAdapter;
        this.taskCopyService = taskCopyService;
    }

    /**
     * 由事务内重新解析的当前用户认领候选任务。
     *
     * @param request WorkflowTaskClaimRequest，仅包含任务主键的认领请求
     * @return 无返回值，成功时任务和审计 comment 已在同一事务持久化
     */
    public void claim(WorkflowTaskClaimRequest request)
    {
        if (request == null)
        {
            throw invalidArgument();
        }
        processEngineAdapter.claimTaskForCurrentUser(requireTaskId(request.taskId()));
    }

    /**
     * 由事务内重新解析的当前办理人取消本人真实认领。
     *
     * @param request WorkflowTaskUnclaimRequest，仅包含任务主键的取消认领请求
     * @return 无返回值，成功时任务和审计 comment 已在同一事务持久化
     */
    public void unclaim(WorkflowTaskUnclaimRequest request)
    {
        if (request == null)
        {
            throw invalidArgument();
        }
        processEngineAdapter.unclaimTaskForCurrentUser(requireTaskId(request.taskId()));
    }

    /**
     * 由当前受托人提交真实办理意见并完成 PENDING 委派，任务按标准语义退回原 owner。
     *
     * @param request WorkflowTaskResolveRequest，任务、受托人意见和可选抄送人
     * @return 无返回值，成功时状态、审计 comment 和抄送记录在同一事务持久化
     */
    public void resolve(WorkflowTaskResolveRequest request)
    {
        if (request == null)
        {
            throw invalidArgument();
        }
        processEngineAdapter.resolveTaskForCurrentUser(requireTaskId(request.taskId()),
                normalizeOpinion(request.comment()), (actor, task) ->
                {
                    WorkflowTaskCopyService.CopyPlan copyPlan = taskCopyService.prepare(
                            WorkflowTaskCopyAction.RESOLVE, task, actor, request.copyUserIds());
                    return () -> taskCopyService.persist(copyPlan);
                });
    }

    /**
     * 由当前办理人把普通活动任务委派给正式启用用户。
     *
     * @param request WorkflowTaskDelegateRequest，任务、目标用户和受控委派意见
     * @return 无返回值，成功时委派状态和审计 comment 已在同一事务持久化
     */
    public void delegate(WorkflowTaskDelegateRequest request)
    {
        if (request == null || request.userId() == null || request.userId() <= 0)
        {
            throw invalidArgument();
        }
        processEngineAdapter.delegateTaskForCurrentUser(requireTaskId(request.taskId()),
                String.valueOf(request.userId()), normalizeOpinion(request.comment()),
                (actor, task) ->
                {
                    WorkflowTaskCopyService.CopyPlan copyPlan = taskCopyService.prepare(
                            WorkflowTaskCopyAction.DELEGATE, task, actor, request.copyUserIds());
                    return () -> taskCopyService.persist(copyPlan);
                });
    }

    /**
     * 由当前办理人把普通活动任务永久转办给正式启用用户。
     *
     * @param request WorkflowTaskTransferRequest，任务、目标用户和受控转办意见
     * @return 无返回值，成功时办理人状态和审计 comment 已在同一事务持久化
     */
    public void transfer(WorkflowTaskTransferRequest request)
    {
        if (request == null || request.userId() == null || request.userId() <= 0)
        {
            throw invalidArgument();
        }
        processEngineAdapter.transferTaskForCurrentUser(requireTaskId(request.taskId()),
                String.valueOf(request.userId()), normalizeOpinion(request.comment()),
                (actor, task) ->
                {
                    WorkflowTaskCopyService.CopyPlan copyPlan = taskCopyService.prepare(
                            WorkflowTaskCopyAction.TRANSFER, task, actor, request.copyUserIds());
                    return () -> taskCopyService.persist(copyPlan);
                });
    }

    /**
     * 二次校验任务主键，防止绕过 Controller 直接调用服务时把非法参数传给引擎。
     *
     * @param taskId String，客户端提交的任务主键
     * @return String，去除首尾空白后的任务主键
     */
    private String requireTaskId(String taskId)
    {
        if (!StringUtils.hasText(taskId))
        {
            throw invalidArgument();
        }
        String normalizedTaskId = taskId.trim();
        if (normalizedTaskId.length() > 64)
        {
            throw invalidArgument();
        }
        return normalizedTaskId;
    }

    /**
     * 规范化委派、委派办结或转办意见，JSON 转义由适配器统一完成以保护审计结构。
     *
     * @param opinion String，客户端提交的业务意见
     * @return String，非空且长度受控的意见正文
     */
    private String normalizeOpinion(String opinion)
    {
        if (!StringUtils.hasText(opinion))
        {
            throw invalidArgument();
        }
        String normalizedOpinion = opinion.trim();
        if (normalizedOpinion.length() > MAX_OPINION_LENGTH)
        {
            throw invalidArgument();
        }
        return normalizedOpinion;
    }

    /**
     * 创建稳定的参数错误异常，禁止暴露底层身份或引擎解析细节。
     *
     * @return ServiceException，HTTP 400 参数错误
     */
    private ServiceException invalidArgument()
    {
        return new ServiceException(INVALID_ARGUMENT_MESSAGE, HttpStatus.BAD_REQUEST);
    }
}
