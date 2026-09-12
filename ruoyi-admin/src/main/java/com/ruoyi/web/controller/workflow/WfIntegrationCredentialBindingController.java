package com.ruoyi.web.controller.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialCreateRequest;
import com.ruoyi.flowable.service.model.WorkflowIntegrationCredentialBindingService;

@Validated
@RestController
@RequestMapping("/workflow/connector/{endpointId}/integration-credential")
public class WfIntegrationCredentialBindingController extends BaseController
{
    private final WorkflowIntegrationCredentialBindingService binding;
    public WfIntegrationCredentialBindingController(WorkflowIntegrationCredentialBindingService binding) { this.binding = binding; }
    public record Request(@NotNull @Min(1) Integer endpointRevision, @NotNull @Min(0) Integer credentialRevision,
            @NotNull @Valid WorkflowIntegrationCredentialCreateRequest credential) { }

    @PreAuthorize("@ss.hasPermi('workflow:connector:edit') and @ss.hasPermi('workflow:integrationCredential:add')")
    @Log(title="集成账号创建并绑定端点", businessType=BusinessType.INSERT, isSaveResponseData=false)
    @PostMapping
    public AjaxResult create(@PathVariable @Positive long endpointId, @Valid @RequestBody Request request)
    {
        return success(binding.create(endpointId, request.endpointRevision(), request.credentialRevision(), request.credential()));
    }
}
