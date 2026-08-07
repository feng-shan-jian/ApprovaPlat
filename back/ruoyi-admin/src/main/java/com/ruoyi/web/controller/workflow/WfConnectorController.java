package com.ruoyi.web.controller.workflow;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
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
import com.ruoyi.flowable.domain.dto.WorkflowConnectorEndpointRequest;
import com.ruoyi.flowable.domain.dto.WorkflowConnectorEndpointStatusRequest;
import com.ruoyi.flowable.service.model.WorkflowConnectorEndpointService;

/**
 * HTTP 连接器端点白名单和不可回退修订管理接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/connector")
public class WfConnectorController extends BaseController
{
    private final WorkflowConnectorEndpointService endpointService;

    /**
     * 创建连接器端点 Controller。
     * @param endpointService WorkflowConnectorEndpointService，端点领域服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WfConnectorController(WorkflowConnectorEndpointService endpointService)
    {
        this.endpointService = endpointService;
    }

    /**
     * 查询全部端点管理清单。
     * @return AjaxResult，真实端点和修订状态
     */
    @PreAuthorize("@ss.hasPermi('workflow:connector:list')")
    @GetMapping("/list")
    public AjaxResult list()
    {
        return success(endpointService.list());
    }

    /**
     * 查询设计器可选择的已启用端点。
     * @return AjaxResult，不包含任何密钥正文的端点选项
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:connector:list,workflow:model:designer')")
    @GetMapping("/options")
    public AjaxResult options()
    {
        return success(endpointService.listOptions());
    }

    /**
     * 新增端点白名单并创建修订 1。
     * @param request WorkflowConnectorEndpointRequest，端点业务配置
     * @return AjaxResult，包含生成的 endpointId
     */
    @PreAuthorize("@ss.hasPermi('workflow:connector:add')")
    @Log(title = "HTTP 连接器端点", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public AjaxResult create(@Valid @RequestBody WorkflowConnectorEndpointRequest request)
    {
        return success(Map.of("endpointId", endpointService.create(request)));
    }

    /**
     * 发布端点下一不可回退修订。
     * @param endpointId Long，端点主键
     * @param request WorkflowConnectorEndpointRequest，新修订配置
     * @return AjaxResult，包含新 revisionNo
     */
    @PreAuthorize("@ss.hasPermi('workflow:connector:edit')")
    @Log(title = "HTTP 连接器端点修订", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping("/{endpointId}")
    public AjaxResult update(@PathVariable @Positive Long endpointId,
            @Valid @RequestBody WorkflowConnectorEndpointRequest request)
    {
        return success(Map.of("revisionNo", endpointService.update(endpointId, request)));
    }

    /**
     * 启用或停用端点，历史部署继续使用冻结快照。
     * @param endpointId Long，端点主键
     * @param request WorkflowConnectorEndpointStatusRequest，目标状态
     * @return AjaxResult，操作结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:connector:edit')")
    @Log(title = "HTTP 连接器端点状态", businessType = BusinessType.UPDATE)
    @PutMapping("/{endpointId}/status")
    public AjaxResult status(@PathVariable @Positive Long endpointId,
            @Valid @RequestBody WorkflowConnectorEndpointStatusRequest request)
    {
        endpointService.changeStatus(endpointId, request.enabled());
        return success();
    }
}
