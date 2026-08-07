package com.ruoyi.web.controller.workflow;

import java.util.Map;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.dto.WorkflowDmnDeploymentRequest;
import com.ruoyi.flowable.service.model.WorkflowDmnDecisionService;

/**
 * Flowable 官方 DMN 决策版本管理接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/dmn")
public class WfDmnController extends BaseController
{
    private final WorkflowDmnDecisionService decisionService;

    /**
     * 创建 DMN 管理 Controller。
     * @param decisionService WorkflowDmnDecisionService，官方 DMN 目录与冻结服务
     * @return void，构造后由 Spring 管理
     */
    public WfDmnController(WorkflowDmnDecisionService decisionService)
    {
        this.decisionService = decisionService;
    }

    /**
     * 查询全部 DMN 决策版本。
     * @return AjaxResult，按 key 和版本稳定排序的官方目录
     */
    @PreAuthorize("@ss.hasPermi('workflow:dmn:list')")
    @GetMapping("/list")
    public AjaxResult list()
    {
        return success(decisionService.list(false));
    }

    /**
     * 查询设计器默认可选择的每个 DMN 最新版本。
     * @return AjaxResult，包含精确 decisionId 的选项
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:dmn:list,workflow:model:designer')")
    @GetMapping("/options")
    public AjaxResult options()
    {
        return success(decisionService.list(true));
    }

    /**
     * 部署 DMN XML 并产生官方不可回退版本。
     * @param request WorkflowDmnDeploymentRequest，资源名、分类和 XML
     * @return AjaxResult，DMN 部署主键
     */
    @PreAuthorize("@ss.hasPermi('workflow:dmn:add')")
    @Log(title = "DMN 决策部署", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public AjaxResult deploy(@Valid @RequestBody WorkflowDmnDeploymentRequest request)
    {
        return success(Map.of("deploymentId", decisionService.deploy(request)));
    }

    /**
     * 删除未被流程冻结快照引用的 DMN 部署。
     * @param deploymentId String，官方 DMN 部署主键
     * @return AjaxResult，成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:dmn:remove')")
    @Log(title = "DMN 决策部署", businessType = BusinessType.DELETE)
    @DeleteMapping("/{deploymentId}")
    public AjaxResult delete(@PathVariable String deploymentId)
    {
        decisionService.delete(deploymentId);
        return success();
    }
}
