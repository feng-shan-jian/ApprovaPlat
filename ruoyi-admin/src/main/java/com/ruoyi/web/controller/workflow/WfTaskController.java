package com.ruoyi.web.controller.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.dto.WorkflowProcessCancelRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessRevokeRequest;
import com.ruoyi.flowable.domain.dto.WorkflowMultiInstanceAdjustmentRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskClaimRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskCompleteRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskDelegateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskRejectRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskResolveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskReturnRequest;
import com.ruoyi.flowable.domain.dto.WorkflowApplicationResubmitRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskTransferRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskUnclaimRequest;
import com.ruoyi.flowable.service.task.WorkflowTaskActionService;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceService;

/**
 * Flowable 8 任务办理、状态迁移和受控读取接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/task")
public class WfTaskController extends BaseController
{
    private final WorkflowTaskActionService taskActionService;

    private final WorkflowTaskLifecycleService taskLifecycleService;

    private final WorkflowMultiInstanceService multiInstanceService;

    /**
     * 创建任务动作 Controller。
     *
     * @param taskActionService WorkflowTaskActionService，认领、取消认领、完成委派、委派和转办服务
     * @param taskLifecycleService WorkflowTaskLifecycleService，完成、驳回、退回、取消和撤回服务
     * @param multiInstanceService WorkflowMultiInstanceService，动态并行多实例状态和调整服务
     * @return 无返回值，构造后由 Spring 管理该 Controller
     */
    public WfTaskController(WorkflowTaskActionService taskActionService,
            WorkflowTaskLifecycleService taskLifecycleService,
            WorkflowMultiInstanceService multiInstanceService)
    {
        this.taskActionService = taskActionService;
        this.taskLifecycleService = taskLifecycleService;
        this.multiInstanceService = multiInstanceService;
    }

    /**
     * 由流程发起人或受控管理员取消运行中的流程实例。
     *
     * @param request WorkflowProcessCancelRequest，流程实例和取消原因
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:cancel')")
    @Log(title = "取消流程申请", businessType = BusinessType.UPDATE)
    @PostMapping("/stopProcess")
    public AjaxResult stopProcess(@Valid @RequestBody WorkflowProcessCancelRequest request)
    {
        taskLifecycleService.cancelProcess(request);
        return success();
    }

    /**
     * 由当前用户撤回本人最近完成且后继尚未处理的任务。
     *
     * @param request WorkflowProcessRevokeRequest，流程实例、历史任务和撤回原因
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:revoke')")
    @Log(title = "撤回流程审批", businessType = BusinessType.UPDATE)
    @PostMapping("/revokeProcess")
    public AjaxResult revokeProcess(@Valid @RequestBody WorkflowProcessRevokeRequest request)
    {
        taskLifecycleService.revokeProcess(request);
        return success();
    }

    /**
     * 查询当前真实办理人所在并行多实例根的服务端成员状态和 revision。
     *
     * @param taskId String，当前登录用户办理的活动任务主键
     * @return AjaxResult，data 为 ALL/ANY、活动 ID、revision 和有序成员状态
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:approval')")
    @Log(title = "查询动态多实例状态", businessType = BusinessType.OTHER)
    @GetMapping("/multiInstance/{taskId}")
    public AjaxResult getMultiInstanceState(
            @PathVariable("taskId")
            @NotBlank(message = "任务主键不能为空")
            @Size(max = 64, message = "任务主键长度不能超过64个字符")
            String taskId)
    {
        return success(multiInstanceService.getState(taskId));
    }

    /**
     * 由当前真实办理人按 expectedRevision 动态加签或减签同根活动成员。
     *
     * @param request WorkflowMultiInstanceAdjustmentRequest，动作、revision、意见和目标用户或任务
     * @return AjaxResult，data 为写后重新对账的最新多实例状态
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:approval')")
    @Log(title = "调整动态多实例成员", businessType = BusinessType.UPDATE)
    @PostMapping("/multiInstance/adjust")
    public AjaxResult adjustMultiInstance(
            @Valid @RequestBody WorkflowMultiInstanceAdjustmentRequest request)
    {
        return success(multiInstanceService.adjust(request));
    }

    /**
     * 由当前办理人完成活动任务并提交受部署表单约束的变量。
     *
     * @param request WorkflowTaskCompleteRequest，任务、审批意见和表单变量
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:approval')")
    @Log(title = "完成流程任务", businessType = BusinessType.UPDATE)
    @PostMapping("/complete")
    public AjaxResult complete(@Valid @RequestBody WorkflowTaskCompleteRequest request)
    {
        taskLifecycleService.completeTask(request);
        return success();
    }

    /**
     * 由当前办理人将普通、并行或多实例流程整实例原子驳回为 rejected 终态。
     *
     * @param request WorkflowTaskRejectRequest，任务和驳回原因
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:approval')")
    @Log(title = "驳回流程任务", businessType = BusinessType.UPDATE)
    @PostMapping("/reject")
    public AjaxResult reject(@Valid @RequestBody WorkflowTaskRejectRequest request)
    {
        taskLifecycleService.rejectTask(request);
        return success();
    }

    /**
     * 由当前办理人把整条申请直接退回发起人修改。
     *
     * @param request WorkflowTaskReturnRequest，任务、退回原因和可选抄送人
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:approval')")
    @Log(title = "退回流程任务", businessType = BusinessType.UPDATE)
    @PostMapping("/return")
    public AjaxResult returnTask(@Valid @RequestBody WorkflowTaskReturnRequest request)
    {
        taskLifecycleService.returnTask(request);
        return success();
    }

    /**
     * 由流程发起人修改原申请表后恢复首个审批节点的正式办理配置。
     *
     * @param request WorkflowApplicationResubmitRequest，退回任务和原开始表单变量
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:start')")
    @Log(title = "重新提交流程", businessType = BusinessType.UPDATE)
    @PostMapping("/resubmit")
    public AjaxResult resubmit(@Valid @RequestBody WorkflowApplicationResubmitRequest request)
    {
        taskLifecycleService.resubmitApplication(request);
        return success();
    }

    /**
     * 由当前候选用户认领活动任务。
     *
     * @param request WorkflowTaskClaimRequest，仅包含任务主键的请求
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:claim')")
    @Log(title = "认领流程任务", businessType = BusinessType.UPDATE)
    @PostMapping("/claim")
    public AjaxResult claim(@Valid @RequestBody WorkflowTaskClaimRequest request)
    {
        taskActionService.claim(request);
        return success();
    }

    /**
     * 由当前办理人取消本人真实认领。
     *
     * @param request WorkflowTaskUnclaimRequest，仅包含任务主键的请求
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:claim')")
    @Log(title = "取消认领流程任务", businessType = BusinessType.UPDATE)
    @PostMapping("/unClaim")
    public AjaxResult unClaim(@Valid @RequestBody WorkflowTaskUnclaimRequest request)
    {
        taskActionService.unclaim(request);
        return success();
    }

    /**
     * 由当前受托人提交真实办理意见、可选抄送人并完成 PENDING 委派。
     *
     * @param request WorkflowTaskResolveRequest，任务、受托人意见和可选抄送人
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:approval')")
    @Log(title = "完成流程任务委派", businessType = BusinessType.UPDATE)
    @PostMapping("/resolve")
    public AjaxResult resolve(@Valid @RequestBody WorkflowTaskResolveRequest request)
    {
        taskActionService.resolve(request);
        return success();
    }

    /**
     * 由当前办理人把普通活动任务委派给正式启用用户。
     *
     * @param request WorkflowTaskDelegateRequest，目标用户和受控委派意见
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:approval')")
    @Log(title = "委派流程任务", businessType = BusinessType.UPDATE)
    @PostMapping("/delegate")
    public AjaxResult delegate(@Valid @RequestBody WorkflowTaskDelegateRequest request)
    {
        taskActionService.delegate(request);
        return success();
    }

    /**
     * 由当前办理人把普通活动任务永久转办给正式启用用户。
     *
     * @param request WorkflowTaskTransferRequest，目标用户和受控转办意见
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:approval')")
    @Log(title = "转办流程任务", businessType = BusinessType.UPDATE)
    @PostMapping("/transfer")
    public AjaxResult transfer(@Valid @RequestBody WorkflowTaskTransferRequest request)
    {
        taskActionService.transfer(request);
        return success();
    }

}
