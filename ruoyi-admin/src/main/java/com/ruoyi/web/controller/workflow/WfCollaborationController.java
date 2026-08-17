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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.service.process.WorkflowCollaborationAuditService;
import com.ruoyi.flowable.service.process.WorkflowCollaborationMessageService;
import com.ruoyi.flowable.service.process.WorkflowCollaborationOutboxService;

/** Participant/MessageFlow 入站、outbox、审计、死信和人工补偿管理接口。 */
@Validated
@RestController
@RequestMapping("/workflow/collaboration")
public class WfCollaborationController extends BaseController
{
    private final WorkflowCollaborationMessageService messageService;
    private final WorkflowCollaborationOutboxService outboxService;
    private final WorkflowCollaborationAuditService auditService;

    /**
     * 创建多池协作管理 Controller。
     * @param messageService WorkflowCollaborationMessageService，入站可靠消费服务
     * @param outboxService WorkflowCollaborationOutboxService，出站可靠投递服务
     * @param auditService WorkflowCollaborationAuditService，逐次状态审计
     * @return void，构造后由 Spring 管理
     */
    public WfCollaborationController(WorkflowCollaborationMessageService messageService,
            WorkflowCollaborationOutboxService outboxService,
            WorkflowCollaborationAuditService auditService)
    {
        this.messageService = messageService;
        this.outboxService = outboxService;
        this.auditService = auditService;
    }

    /**
     * 分页查询脱敏入站消息台账。
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @param status String，消息正式状态
     * @param keyword String，消息、流程或关联键关键字
     * @param beginTime LocalDateTime，创建时间下界
     * @param endTime LocalDateTime，创建时间上界
     * @return TableDataInfo，若依标准 rows、total 分页响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:collaboration:list')")
    @GetMapping("/inbound")
    public TableDataInfo inbound(
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) @Pattern(regexp = "RECEIVED|RETRYING|PROCESSED|DEAD_LETTER") String status,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime beginTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime)
    {
        return toTableData(messageService.list(new WorkflowOperationsQuery.Collaboration(
                status, keyword, beginTime, endTime), pageNum, pageSize));
    }

    /**
     * 分页查询脱敏事务 outbox 台账。
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @param status String，消息正式状态
     * @param keyword String，消息、流程或关联键关键字
     * @param beginTime LocalDateTime，创建时间下界
     * @param endTime LocalDateTime，创建时间上界
     * @return TableDataInfo，若依标准 rows、total 分页响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:collaboration:list')")
    @GetMapping("/outbox")
    public TableDataInfo outbox(
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false)
            @Pattern(regexp = "PENDING|DELIVERING|RETRYING|PROCESSED|DEAD_LETTER|CANCELLED") String status,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime beginTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime)
    {
        return toTableData(outboxService.list(new WorkflowOperationsQuery.Collaboration(
                status, keyword, beginTime, endTime), pageNum, pageSize));
    }

    /**
     * 查询单条入站或出站消息的完整脱敏审计。
     * @param messageId String，消息主键
     * @return AjaxResult，按时间升序的审计列表
     */
    @PreAuthorize("@ss.hasPermi('workflow:collaboration:audit')")
    @GetMapping("/{messageId}/audit")
    public AjaxResult audit(@PathVariable String messageId)
    {
        return success(auditService.list(messageId));
    }

    /**
     * 管理员重新消费一条入站重试或死信消息。
     * @param messageId String，入站消息主键
     * @return AjaxResult，补偿后的正式状态
     */
    @PreAuthorize("@ss.hasPermi('workflow:collaboration:retry')")
    @Log(title = "协作入站消息补偿", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/inbound/{messageId}/retry")
    public AjaxResult retryInbound(@PathVariable String messageId)
    {
        return success(messageService.retry(messageId));
    }

    /**
     * 管理员为出站死信开启新的有界重试周期。
     * @param messageId String，outbox 消息主键
     * @return AjaxResult，补偿后的正式状态
     */
    @PreAuthorize("@ss.hasPermi('workflow:collaboration:retry')")
    @Log(title = "协作出站消息补偿", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/outbox/{messageId}/retry")
    public AjaxResult retryOutbox(@PathVariable String messageId)
    {
        return success(outboxService.compensate(messageId));
    }

    /**
     * 管理员取消尚未送达的 outbox；已送达消息不可撤销。
     * @param messageId String，outbox 消息主键
     * @return AjaxResult，取消后的正式状态
     */
    @PreAuthorize("@ss.hasPermi('workflow:collaboration:cancel')")
    @Log(title = "协作出站消息取消", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/outbox/{messageId}/cancel")
    public AjaxResult cancelOutbox(@PathVariable String messageId)
    {
        return success(outboxService.cancel(messageId));
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
