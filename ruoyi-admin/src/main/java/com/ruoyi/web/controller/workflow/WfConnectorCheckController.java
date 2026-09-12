package com.ruoyi.web.controller.workflow;

import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.flowable.service.model.WorkflowConnectorCheckService;

@Validated
@RestController
@RequestMapping("/workflow/connector/{endpointId}/check")
public class WfConnectorCheckController extends BaseController
{
    private final WorkflowConnectorCheckService checks;
    public WfConnectorCheckController(WorkflowConnectorCheckService checks) { this.checks = checks; }
    @PreAuthorize("@ss.hasPermi('workflow:connector:edit')")
    @Log(title="消息端点认证检查", businessType=BusinessType.OTHER)
    @PostMapping
    public AjaxResult check(@PathVariable @Positive long endpointId) { return success(checks.check(endpointId)); }
}
