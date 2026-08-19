package com.ruoyi.web.controller.workflow;

import java.time.LocalDateTime;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.dto.WorkflowBusinessCalendarRequest;
import com.ruoyi.flowable.domain.dto.WorkflowEnabledStatusRequest;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;
import com.ruoyi.flowable.service.model.WorkflowBusinessCalendarService;
import com.ruoyi.flowable.service.task.WorkflowTaskSlaRuntimeService;

/**
 * 审批 SLA 业务日历、运行状态和审计真实 API。
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

    /**
     * 分页查询正式 SLA 当前执行状态。
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @param status String，ACTIVE、COMPLETED 或 ESCALATED
     * @param keyword String，执行、实例、任务、节点或办理人关键字
     * @param beginTime LocalDateTime，开始时间下界
     * @param endTime LocalDateTime，开始时间上界
     * @return TableDataInfo，若依标准 rows、total 分页响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:sla:audit')")
    @GetMapping("/executions")
    public TableDataInfo listExecutions(
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|COMPLETED|ESCALATED") String status,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime beginTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime)
    {
        return getDataTable(slaRuntimeService.listExecutions(new WorkflowOperationsQuery.SlaExecution(
                status, keyword, beginTime, endTime), pageNum, pageSize));
    }

    /**
     * 分页查询不可变 SLA 生命周期审计。
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @param actionType String，生命周期动作类型
     * @param keyword String，审计、执行、实例、任务、节点或操作人关键字
     * @param beginTime LocalDateTime，动作时间下界
     * @param endTime LocalDateTime，动作时间上界
     * @return TableDataInfo，若依标准 rows、total 分页响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:sla:audit')")
    @GetMapping("/audits")
    public TableDataInfo listAudits(
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false)
            @Pattern(regexp = "CREATE|ASSIGN|REMINDER|ESCALATE|COMPLETE|PAUSE|RESUME") String actionType,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime beginTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime)
    {
        return getDataTable(slaRuntimeService.listAudits(new WorkflowOperationsQuery.SlaAudit(
                actionType, keyword, beginTime, endTime), pageNum, pageSize));
    }

}
