package com.ruoyi.flowable.service.task;

import org.springframework.stereotype.Service;
import com.ruoyi.flowable.domain.dto.WorkflowApplicationResubmitRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessCancelRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessRevokeRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskCompleteRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskRejectRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskReturnRequest;

/**
 * 保持既有 Controller 契约并委派五个任务生命周期应用用例的稳定门面。
 */
@Service
public class WorkflowTaskLifecycleService
{
    private final WorkflowProcessCancelApplicationService cancelApplicationService;
    private final WorkflowTaskRevokeApplicationService revokeApplicationService;
    private final WorkflowTaskCompletionApplicationService completionApplicationService;
    private final WorkflowTaskRejectionApplicationService rejectionApplicationService;
    private final WorkflowTaskReturnApplicationService returnApplicationService;
    private final WorkflowApplicationResubmitApplicationService resubmitApplicationService;

    /**
     * 创建稳定任务生命周期门面。
     *
     * @param cancelApplicationService WorkflowProcessCancelApplicationService，流程取消用例
     * @param revokeApplicationService WorkflowTaskRevokeApplicationService，已办撤回用例
     * @param completionApplicationService WorkflowTaskCompletionApplicationService，任务完成用例
     * @param rejectionApplicationService WorkflowTaskRejectionApplicationService，任务驳回用例
     * @param returnApplicationService WorkflowTaskReturnApplicationService，退回与重提用例
     * @param resubmitApplicationService WorkflowApplicationResubmitApplicationService，申请重提用例
     * @return 无返回值，构造后由 Spring 管理
     */
    public WorkflowTaskLifecycleService(
            WorkflowProcessCancelApplicationService cancelApplicationService,
            WorkflowTaskRevokeApplicationService revokeApplicationService,
            WorkflowTaskCompletionApplicationService completionApplicationService,
            WorkflowTaskRejectionApplicationService rejectionApplicationService,
            WorkflowTaskReturnApplicationService returnApplicationService,
            WorkflowApplicationResubmitApplicationService resubmitApplicationService)
    {
        this.cancelApplicationService = cancelApplicationService;
        this.revokeApplicationService = revokeApplicationService;
        this.completionApplicationService = completionApplicationService;
        this.rejectionApplicationService = rejectionApplicationService;
        this.returnApplicationService = returnApplicationService;
        this.resubmitApplicationService = resubmitApplicationService;
    }

    /**
     * 委派发起人或管理员取消完整流程树。
     *
     * @param request WorkflowProcessCancelRequest，既有取消请求 DTO
     * @return 无返回值，结果和异常由取消应用服务保持原契约
     */
    public void cancelProcess(WorkflowProcessCancelRequest request)
    {
        cancelApplicationService.cancelProcess(request);
    }

    /**
     * 委派已办任务撤回能力判断。
     *
     * @param processInstanceId String，既有流程实例主键
     * @param historicTaskId String，既有历史任务主键
     * @return boolean，当前快照满足正式撤回条件时返回 true
     */
    public boolean isProcessRevocable(String processInstanceId, String historicTaskId)
    {
        return revokeApplicationService.isProcessRevocable(
                processInstanceId, historicTaskId);
    }

    /**
     * 委派已办任务撤回命令。
     *
     * @param request WorkflowProcessRevokeRequest，既有撤回请求 DTO
     * @return 无返回值，结果和异常由撤回应用服务保持原契约
     */
    public void revokeProcess(WorkflowProcessRevokeRequest request)
    {
        revokeApplicationService.revokeProcess(request);
    }

    /**
     * 委派普通或受控多实例任务完成命令。
     *
     * @param request WorkflowTaskCompleteRequest，既有完成请求 DTO
     * @return 无返回值，结果和异常由完成应用服务保持原契约
     */
    public void completeTask(WorkflowTaskCompleteRequest request)
    {
        completionApplicationService.completeTask(request);
    }

    /**
     * 委派任务驳回和完整流程树终止命令。
     *
     * @param request WorkflowTaskRejectRequest，既有驳回请求 DTO
     * @return 无返回值，结果和异常由驳回应用服务保持原契约
     */
    public void rejectTask(WorkflowTaskRejectRequest request)
    {
        rejectionApplicationService.rejectTask(request);
    }

    /**
     * 委派普通退回或受控多实例整组退回命令。
     *
     * @param request WorkflowTaskReturnRequest，既有退回请求 DTO
     * @return 无返回值，结果和异常由退回应用服务保持原契约
     */
    public void returnTask(WorkflowTaskReturnRequest request)
    {
        returnApplicationService.returnTask(request);
    }

    /**
     * 委派普通或受控多实例申请人重提命令。
     *
     * @param request WorkflowApplicationResubmitRequest，既有重提请求 DTO
     * @return 无返回值，结果和异常由退回应用服务保持原契约
     */
    public void resubmitApplication(WorkflowApplicationResubmitRequest request)
    {
        resubmitApplicationService.resubmitApplication(request);
    }

    /**
     * 委派当前任务正式退回能力判断。
     *
     * @param taskId String，既有活动任务主键
     * @return boolean，当前快照满足正式退回条件时返回 true
     */
    public boolean isTaskReturnAllowed(String taskId)
    {
        return returnApplicationService.isTaskReturnAllowed(taskId);
    }
}
