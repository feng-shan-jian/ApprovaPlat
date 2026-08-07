package com.ruoyi.web.controller.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowIntegrationCredentialRotateRequest;
import com.ruoyi.flowable.service.process.WorkflowIntegrationCredentialService;

/**
 * 工作流集成账号创建、轮换、吊销和脱敏查询接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/integration-credential")
public class WfIntegrationCredentialController extends BaseController
{
    private final WorkflowIntegrationCredentialService credentialService;

    /**
     * 创建集成账号管理 Controller。
     * @param credentialService WorkflowIntegrationCredentialService，正式凭据领域服务
     * @return void，构造后由 Spring 管理
     */
    public WfIntegrationCredentialController(
            WorkflowIntegrationCredentialService credentialService)
    {
        this.credentialService = credentialService;
    }

    /**
     * 查询不包含 Token 正文和哈希的集成账号清单。
     * @return AjaxResult，脱敏账号视图
     */
    @PreAuthorize("@ss.hasPermi('workflow:integrationCredential:list')")
    @GetMapping("/list")
    public AjaxResult list()
    {
        return success(credentialService.list());
    }

    /**
     * 创建集成账号并只在本次响应返回一次明文 Token。
     * @param request WorkflowIntegrationCredentialCreateRequest，范围、白名单和限流
     * @return AjaxResult，一次性 Token 和脱敏账号视图
     */
    @PreAuthorize("@ss.hasPermi('workflow:integrationCredential:add')")
    @Log(title = "工作流集成账号", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public AjaxResult create(@Valid @RequestBody WorkflowIntegrationCredentialCreateRequest request)
    {
        return success(credentialService.create(request));
    }

    /**
     * 原子轮换 Token，旧 Token 提交后立即失效。
     * @param credentialId Long，集成账号主键
     * @param request WorkflowIntegrationCredentialRotateRequest，可选新到期时间
     * @return AjaxResult，仅本次可见的新 Token
     */
    @PreAuthorize("@ss.hasPermi('workflow:integrationCredential:rotate')")
    @Log(title = "工作流集成 Token 轮换", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{credentialId}/rotate")
    public AjaxResult rotate(@PathVariable @Positive Long credentialId,
            @Valid @RequestBody WorkflowIntegrationCredentialRotateRequest request)
    {
        return success(credentialService.rotate(credentialId, request));
    }

    /**
     * 吊销集成账号，保留历史运行事件外键和审计记录。
     * @param credentialId Long，集成账号主键
     * @return AjaxResult，成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:integrationCredential:revoke')")
    @Log(title = "工作流集成账号吊销", businessType = BusinessType.UPDATE)
    @DeleteMapping("/{credentialId}")
    public AjaxResult revoke(@PathVariable @Positive Long credentialId)
    {
        credentialService.revoke(credentialId);
        return success();
    }
}
