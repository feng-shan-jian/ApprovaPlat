package com.ruoyi.web.controller.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.flowable.domain.dto.WorkflowModelDesignDraftRequest;
import com.ruoyi.flowable.service.model.WorkflowModelDesignDraftService;

@Validated
@RestController
@RequestMapping("/workflow/model/{modelId}/design-draft")
public class WfModelDesignDraftController extends BaseController
{
    private final WorkflowModelDesignDraftService drafts;
    public WfModelDesignDraftController(WorkflowModelDesignDraftService drafts) { this.drafts = drafts; }

    @PreAuthorize("@ss.hasPermi('workflow:model:designer')")
    @GetMapping
    public AjaxResult read(@PathVariable @Size(min=1,max=64) String modelId)
    {
        return success(drafts.read(modelId));
    }

    @PreAuthorize("@ss.hasPermi('workflow:model:designer') and @ss.hasPermi('workflow:model:save')")
    @PutMapping
    public AjaxResult save(@PathVariable @Size(min=1,max=64) String modelId,
            @Valid @RequestBody WorkflowModelDesignDraftRequest request)
    {
        return success(drafts.save(modelId, request));
    }

    @PreAuthorize("@ss.hasPermi('workflow:model:designer') and @ss.hasPermi('workflow:model:save')")
    @DeleteMapping
    public AjaxResult discard(@PathVariable @Size(min=1,max=64) String modelId,
            @RequestParam @Min(1) int expectedRevision)
    {
        return success(drafts.discard(modelId, expectedRevision));
    }
}
