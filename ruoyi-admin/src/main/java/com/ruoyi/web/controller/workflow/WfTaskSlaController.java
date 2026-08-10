package com.ruoyi.web.controller.workflow;

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
import com.ruoyi.flowable.domain.dto.WorkflowBusinessCalendarRequest;
import com.ruoyi.flowable.domain.dto.WorkflowEnabledStatusRequest;
import com.ruoyi.flowable.service.model.WorkflowBusinessCalendarService;
import com.ruoyi.flowable.service.task.WorkflowTaskSlaRuntimeService;

/**
 * 审批 SLA 业务日历、运行状态、审计和用户通知真实 API。
 */
@Validated
@RestController
@RequestMapping("/workflow/sla")
public class WfTaskSlaController extends BaseController
{
    private final WorkflowBusinessCalendarService calendarService;
    private final WorkflowTaskSlaRuntimeService slaRuntimeService;

    /**
     * 创建审批 SLA Controller。
     * @param calendarService WorkflowBusinessCalendarService，正式日历管理服务
     * @param slaRuntimeService WorkflowTaskSlaRuntimeService，运行状态、审计和通知服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WfTaskSlaController(WorkflowBusinessCalendarService calendarService,
            WorkflowTaskSlaRuntimeService slaRuntimeService)
    {
        this.calendarService = calendarService;
        this.slaRuntimeService = slaRuntimeService;
    }

    /** @return AjaxResult，全部正式业务日历及日期覆盖。 */
    @PreAuthorize("@ss.hasPermi('workflow:sla:list')")
    @GetMapping("/calendars")
    public AjaxResult listCalendars()
    {
        return success(calendarService.listManagement());
    }

    /** @return AjaxResult，设计器可选的已启用业务日历。 */
    @PreAuthorize("@ss.hasAnyPermi('workflow:sla:list,workflow:model:designer')")
    @GetMapping("/calendars/enabled")
    public AjaxResult listEnabledCalendars()
    {
        return success(calendarService.listEnabled());
    }

    /**
     * 新增正式业务日历。
     * @param request WorkflowBusinessCalendarRequest，完整日历配置
     * @return AjaxResult，生成日历主键
     */
    @PreAuthorize("@ss.hasPermi('workflow:sla:add')")
    @RepeatSubmit
    @Log(title = "新增审批 SLA 业务日历", businessType = BusinessType.INSERT)
    @PostMapping("/calendars")
    public AjaxResult createCalendar(@Valid @RequestBody WorkflowBusinessCalendarRequest request)
    {
        return success(calendarService.create(request));
    }

    /**
     * 修改正式业务日历规则。
     * @param calendarId Long，业务日历主键
     * @param request WorkflowBusinessCalendarRequest，完整替换配置
     * @return AjaxResult，空成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:sla:edit')")
    @RepeatSubmit
    @Log(title = "修改审批 SLA 业务日历", businessType = BusinessType.UPDATE)
    @PutMapping("/calendars/{calendarId}")
    public AjaxResult updateCalendar(@PathVariable @Positive Long calendarId,
            @Valid @RequestBody WorkflowBusinessCalendarRequest request)
    {
        calendarService.update(calendarId, request);
        return success();
    }

    /**
     * 启用或停用业务日历。
     * @param calendarId Long，业务日历主键
     * @param request WorkflowEnabledStatusRequest，目标启停状态
     * @return AjaxResult，空成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:sla:edit')")
    @Log(title = "修改审批 SLA 业务日历状态", businessType = BusinessType.UPDATE)
    @PutMapping("/calendars/{calendarId}/status")
    public AjaxResult changeCalendarStatus(@PathVariable @Positive Long calendarId,
            @Valid @RequestBody WorkflowEnabledStatusRequest request)
    {
        calendarService.changeStatus(calendarId, request.enabled());
        return success();
    }

    /** @return AjaxResult，最近 500 条正式 SLA 执行状态。 */
    @PreAuthorize("@ss.hasPermi('workflow:sla:audit')")
    @GetMapping("/executions")
    public AjaxResult listExecutions()
    {
        return success(slaRuntimeService.listExecutions());
    }

    /** @return AjaxResult，最近 500 条不可变 SLA 生命周期审计。 */
    @PreAuthorize("@ss.hasPermi('workflow:sla:audit')")
    @GetMapping("/audits")
    public AjaxResult listAudits()
    {
        return success(slaRuntimeService.listAudits());
    }

    /** @return AjaxResult，当前用户最近 200 条提醒和升级通知。 */
    @PreAuthorize("@ss.hasPermi('workflow:sla:notification')")
    @GetMapping("/notifications")
    public AjaxResult myNotifications()
    {
        return success(slaRuntimeService.myNotifications());
    }

    /**
     * 将当前用户拥有的一条 SLA 通知标记为已读。
     * @param notificationId Long，通知主键
     * @return AjaxResult，不存在、已读或越权统一返回 404
     */
    @PreAuthorize("@ss.hasPermi('workflow:sla:notification')")
    @Log(title = "处理审批 SLA 通知", businessType = BusinessType.UPDATE)
    @PutMapping("/notifications/{notificationId}/read")
    public AjaxResult markNotificationRead(@PathVariable @Positive Long notificationId)
    {
        slaRuntimeService.markNotificationRead(notificationId);
        return success();
    }
}
