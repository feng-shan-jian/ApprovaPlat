package com.ruoyi.web.controller.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.flowable.service.integration.WorkflowConnectorCredentialService;

@Validated
@RestController
@RequestMapping("/workflow/connector/{endpointId}/credential")
@PreAuthorize("@ss.hasPermi('workflow:connector:edit')")
public class WfConnectorCredentialController extends BaseController
{
    private final WorkflowConnectorCredentialService credentials;
    public WfConnectorCredentialController(WorkflowConnectorCredentialService credentials) { this.credentials = credentials; }
    public record Binding(@NotNull @Min(1) Integer endpointRevision, @NotNull @Min(0) Integer credentialRevision,
            @NotBlank @Size(max=8192) String secret, @NotBlank @Pattern(regexp="BEARER|API_KEY") String authType,
            @Size(max=128) String apiKeyHeader)
    { @Override public String toString() { return "ConnectorCredentialBinding[REDACTED]"; } }

    @GetMapping public AjaxResult state(@PathVariable @Positive long endpointId) { return success(credentials.state(endpointId)); }

    @Log(title="受管连接凭据绑定或轮换", businessType=BusinessType.UPDATE, isSaveRequestData=false, isSaveResponseData=false)
    @PutMapping public AjaxResult bind(@PathVariable @Positive long endpointId, @Valid @RequestBody Binding request)
    {
        return success(credentials.bind(endpointId, request.endpointRevision(), request.credentialRevision(),
                request.secret(), request.authType(), request.apiKeyHeader()));
    }

    @Log(title="受管连接凭据撤销", businessType=BusinessType.UPDATE)
    @DeleteMapping public AjaxResult revoke(@PathVariable @Positive long endpointId,
            @RequestParam @Min(1) int endpointRevision, @RequestParam @Min(1) int credentialRevision)
    {
        credentials.revoke(endpointId, endpointRevision, credentialRevision);
        return success();
    }
}
