package com.ruoyi.web.controller.workflow;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.flowable.service.process.WorkflowIntegrationCredentialService;

/** 专用认证探测只消费一次认证配额，不关联、投递或推进业务实例。 */
@RestController
@RequestMapping("/workflow/runtime-event/auth-check")
public class WfIntegrationAuthCheckController extends BaseController
{
    private final WorkflowIntegrationCredentialService credentials;
    public WfIntegrationAuthCheckController(WorkflowIntegrationCredentialService credentials) { this.credentials = credentials; }
    public record Request(@NotBlank @Pattern(regexp="[a-f0-9-]{36}") String nonce) { }

    @Anonymous
    @PostMapping
    public AjaxResult check(@RequestHeader(value="X-Integration-Token", required=false) String token,
            @Valid @RequestBody Request request)
    {
        credentials.authenticateAndConsume(token, "MESSAGE");
        return success(Map.of("protocol", "APPROVA_MESSAGE_AUTH_V1", "authenticated", true, "nonce", request.nonce()));
    }
}
