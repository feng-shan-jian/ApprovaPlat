package com.ruoyi.web.controller.workflow;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceStateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceTerminateRequest;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;

/**
 * Flowable 8 流程实例激活、挂起、发起人取消和管理员终止接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/instance")
public class WfInstanceController extends BaseController
{
    private final WorkflowProcessInstanceService processInstanceService;

    /**
     * 创建流程实例管理 Controller。
     *
     * @param processInstanceService WorkflowProcessInstanceService，受控实例写操作服务
     * @return 无返回值，构造后由 Spring 管理该 Controller
     */
    public WfInstanceController(WorkflowProcessInstanceService processInstanceService)
    {
        this.processInstanceService = processInstanceService;
    }

    /**
     * 由流程管理员把运行实例切换为 active 或 suspended。
     *
     * @param request WorkflowInstanceStateRequest，实例主键和枚举目标状态
     * @return AjaxResult，真实状态及 changed 幂等标志
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:state')")
    @Log(title = "更新流程实例状态", businessType = BusinessType.UPDATE)
    @PostMapping("/updateState")
    public AjaxResult updateState(@Valid @RequestBody WorkflowInstanceStateRequest request)
    {
        return success(processInstanceService.updateState(request));
    }

    /**
     * 由真实发起人取消本人实例，或由流程管理员终止任意运行实例。
     *
     * @param request WorkflowInstanceTerminateRequest，实例主键和受控业务原因
     * @return AjaxResult，canceled/terminated 状态、操作人和原挂起状态
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:process:cancel,workflow:process:terminate')")
    @Log(title = "结束流程实例", businessType = BusinessType.UPDATE)
    @PostMapping("/terminate")
    public AjaxResult terminate(@Valid @RequestBody WorkflowInstanceTerminateRequest request)
    {
        return success(processInstanceService.terminate(request));
    }
}
