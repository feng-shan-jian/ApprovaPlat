package com.ruoyi.web.controller.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.flowable.service.process.WorkflowProcessWaitingService;

@Validated
@RestController
public class WfProcessWaitingController
{
    private final WorkflowProcessWaitingService waiting;
    public WfProcessWaitingController(WorkflowProcessWaitingService waiting) { this.waiting = waiting; }

    @GetMapping("/workflow/process/waiting")
    @PreAuthorize("@ss.hasPermi('workflow:process:query')")
    public AjaxResult waiting(@RequestParam @NotBlank @Size(max = 255) String processInstanceId)
    {
        return AjaxResult.success(waiting.query(processInstanceId));
    }
}
