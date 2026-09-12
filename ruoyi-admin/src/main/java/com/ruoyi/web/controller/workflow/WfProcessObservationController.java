package com.ruoyi.web.controller.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDetailQueryDto;
import com.ruoyi.flowable.service.process.WorkflowProcessObservationService;

@Validated
@RestController
public class WfProcessObservationController
{
    private final WorkflowProcessObservationService observations;
    public WfProcessObservationController(WorkflowProcessObservationService observations) { this.observations = observations; }

    @GetMapping("/workflow/process/observation")
    @PreAuthorize("@ss.hasPermi('workflow:process:query')")
    public AjaxResult query(@RequestParam @NotBlank @Size(max = 255) String processInstanceId,
            @RequestParam(required = false) @Size(max = 255) String taskId)
    {
        return AjaxResult.success(observations.query(new WorkflowProcessDetailQueryDto(processInstanceId, taskId)));
    }
}
