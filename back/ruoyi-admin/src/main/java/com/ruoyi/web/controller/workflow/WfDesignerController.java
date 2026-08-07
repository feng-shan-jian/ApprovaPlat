package com.ruoyi.web.controller.workflow;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.dto.WorkflowDesignerPreferenceRequest;
import com.ruoyi.flowable.service.model.WorkflowDesignerPreferenceService;

/**
 * BPMN 设计器用户配置接口。
 */
@RestController
@RequestMapping("/workflow/designer")
public class WfDesignerController extends BaseController
{
    private final WorkflowDesignerPreferenceService preferenceService;

    /**
     * 创建设计器配置 Controller。
     * @param preferenceService WorkflowDesignerPreferenceService，正式偏好持久化服务
     * @return 无返回值，构造后由 Spring 管理该 Controller
     */
    public WfDesignerController(WorkflowDesignerPreferenceService preferenceService)
    {
        this.preferenceService = preferenceService;
    }

    /**
     * 查询当前用户的正式设计器偏好。
     * @return AjaxResult，偏好视图；无记录时返回服务端默认值
     */
    @PreAuthorize("@ss.hasPermi('workflow:model:designer')")
    @GetMapping("/preference")
    public AjaxResult getPreference()
    {
        return success(preferenceService.getCurrentPreference());
    }

    /**
     * 原子保存当前用户的完整设计器偏好。
     * @param request WorkflowDesignerPreferenceRequest，全部设计器偏好字段
     * @return AjaxResult，数据库回读后的真实偏好
     */
    @PreAuthorize("@ss.hasPermi('workflow:model:designer')")
    @Log(title = "流程设计器偏好", businessType = BusinessType.UPDATE)
    @PutMapping("/preference")
    public AjaxResult savePreference(
            @Valid @RequestBody WorkflowDesignerPreferenceRequest request)
    {
        return success(preferenceService.saveCurrentPreference(request));
    }
}
