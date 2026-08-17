package com.ruoyi.web.controller.workflow;

import java.time.LocalDateTime;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.service.process.WorkflowRuntimeEventService;

/**
 * 不含变量正文和 Token 的运行事件审计查询接口。
 */
@Validated
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
     * 分页查询运行事件成功、失败和幂等结果。
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @param status String，RECEIVED、PROCESSED 或 FAILED
     * @param eventType String，MESSAGE、SIGNAL 或 RECEIVE
     * @param sourceType String，运行事件关联条件类型
     * @param keyword String，请求、事件、关联值或结果码关键字
     * @param beginTime LocalDateTime，首次请求时间下界
     * @param endTime LocalDateTime，首次请求时间上界
     * @return TableDataInfo，若依标准 rows、total 分页响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:runtimeEvent:list')")
    @GetMapping("/list")
    public TableDataInfo list(
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Pattern(regexp = "RECEIVED|PROCESSED|FAILED") String status,
            @RequestParam(required = false) @Pattern(regexp = "MESSAGE|SIGNAL|RECEIVE") String eventType,
            @RequestParam(required = false) @Size(max = 64) String sourceType,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime beginTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime)
    {
        return toTableData(runtimeEventService.list(new WorkflowOperationsQuery.RuntimeEvent(
                status, eventType, sourceType, keyword, beginTime, endTime), pageNum, pageSize));
    }

    /**
     * 将领域分页结果转换为若依标准列表响应。
     * @param page WorkflowPageResult&lt;?&gt;，服务层当前页和总数
     * @return TableDataInfo，包含 code、msg、rows 和 total
     */
    private TableDataInfo toTableData(WorkflowPageResult<?> page)
    {
        TableDataInfo result = new TableDataInfo(page.rows(), page.total());
        result.setCode(HttpStatus.SUCCESS);
        result.setMsg("查询成功");
        return result;
    }
}
