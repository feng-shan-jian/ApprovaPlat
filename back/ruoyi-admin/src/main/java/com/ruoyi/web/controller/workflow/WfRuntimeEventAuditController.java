package com.ruoyi.web.controller.workflow;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.flowable.service.process.WorkflowRuntimeEventService;

/**
 * 不含变量正文和 Token 的运行事件审计查询接口。
 */
@RestController
@RequestMapping("/workflow/runtime-event-audit")
public class WfRuntimeEventAuditController extends BaseController
{
    private final WorkflowRuntimeEventService runtimeEventService;

    /**
     * 创建运行事件审计 Controller。
     * @param runtimeEventService WorkflowRuntimeEventService，运行事件领域服务
     * @return void，构造后由 Spring 管理
     */
    public WfRuntimeEventAuditController(WorkflowRuntimeEventService runtimeEventService)
    {
        this.runtimeEventService = runtimeEventService;
    }

    /**
     * 查询最近 1000 条运行事件成功、失败和幂等结果。
     * @return AjaxResult，脱敏审计清单
     */
    @PreAuthorize("@ss.hasPermi('workflow:runtimeEvent:list')")
    @GetMapping("/list")
    public AjaxResult list()
    {
        return success(runtimeEventService.list());
    }
}
