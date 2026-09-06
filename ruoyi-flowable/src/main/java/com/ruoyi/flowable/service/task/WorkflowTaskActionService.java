package com.ruoyi.flowable.service.task;

import org.springframework.stereotype.Service;
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
        processEngineAdapter.claimTaskForCurrentUser(request.taskId().trim());
    }

    /**
     * 由事务内重新解析的当前办理人取消本人真实认领。
     *
     * @param request WorkflowTaskUnclaimRequest，仅包含任务主键的取消认领请求
     * @return 无返回值，成功时任务和审计 comment 已在同一事务持久化
     */
    public void unclaim(WorkflowTaskUnclaimRequest request)
    {
        processEngineAdapter.unclaimTaskForCurrentUser(request.taskId().trim());
    }

    /**
     * 由当前受托人提交真实办理意见并完成 PENDING 委派，任务按标准语义退回原 owner。
     *
     * @param request WorkflowTaskResolveRequest，任务、受托人意见和可选抄送人
     * @return 无返回值，成功时状态、审计 comment 和抄送记录在同一事务持久化
     */
    public void resolve(WorkflowTaskResolveRequest request)
    {
        processEngineAdapter.resolveTaskForCurrentUser(request.taskId().trim(),
                request.comment().trim(), (actor, task) ->
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
        processEngineAdapter.delegateTaskForCurrentUser(request.taskId().trim(),
                String.valueOf(request.userId()), request.comment().trim(),
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
        processEngineAdapter.transferTaskForCurrentUser(request.taskId().trim(),
                String.valueOf(request.userId()), request.comment().trim(),
                (actor, task) ->
                {
                    WorkflowTaskCopyService.CopyPlan copyPlan = taskCopyService.prepare(
                            WorkflowTaskCopyAction.TRANSFER, task, actor, request.copyUserIds());
                    return () -> taskCopyService.persist(copyPlan);
                });
    }
}
