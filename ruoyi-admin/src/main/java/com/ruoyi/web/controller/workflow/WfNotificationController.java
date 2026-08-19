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
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.dto.WorkflowManualUrgeRequest;
import com.ruoyi.flowable.domain.dto.WorkflowNotificationPolicyRequest;
import com.ruoyi.flowable.domain.dto.WorkflowNotificationPreferenceRequest;
import com.ruoyi.flowable.domain.dto.WorkflowOperationsQuery;
import com.ruoyi.flowable.service.notification.WorkflowManualUrgeService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationAdminService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationInboxService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationOutboxService;
import com.ruoyi.flowable.service.notification.WorkflowNotificationPolicyService;

/**
 * 普通审批通知、偏好、策略、outbox 和人工催办正式 API。
 */
@Validated
@RestController
@RequestMapping("/workflow/notification")
public class WfNotificationController extends BaseController
{
    private final WorkflowNotificationInboxService notificationInboxService;
    private final WorkflowNotificationPolicyService notificationPolicyService;
    private final WorkflowManualUrgeService manualUrgeService;
    private final WorkflowNotificationOutboxService notificationOutboxService;
    private final WorkflowNotificationAdminService notificationAdminService;

    /**
     * 创建审批通知 Controller。
     * @param notificationInboxService WorkflowNotificationInboxService，当前用户收件箱服务
     * @param notificationPolicyService WorkflowNotificationPolicyService，通知偏好与策略服务
     * @param manualUrgeService WorkflowManualUrgeService，人工催办业务服务
     * @param notificationOutboxService WorkflowNotificationOutboxService，死信补偿状态服务
     * @param notificationAdminService WorkflowNotificationAdminService，通知运维分页查询服务
     * @return void，构造后由 Spring 管理
     */
    public WfNotificationController(WorkflowNotificationInboxService notificationInboxService,
            WorkflowNotificationPolicyService notificationPolicyService,
            WorkflowManualUrgeService manualUrgeService,
            WorkflowNotificationOutboxService notificationOutboxService,
            WorkflowNotificationAdminService notificationAdminService)
    {
        this.notificationInboxService = notificationInboxService;
        this.notificationPolicyService = notificationPolicyService;
        this.manualUrgeService = manualUrgeService;
        this.notificationOutboxService = notificationOutboxService;
        this.notificationAdminService = notificationAdminService;
    }

    /**
     * 查询当前用户审批通知及未读数。
     * @param readStatus ALL、UNREAD 或 READ
     * @param limit 返回数量上限
     * @return AjaxResult，data 含 items 和 unreadCount
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:list')")
    @GetMapping("/inbox")
    public AjaxResult inbox(@RequestParam(defaultValue = "ALL") String readStatus,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit)
    {
        return success(notificationInboxService.inbox(readStatus, limit));
    }

    /**
     * 标记当前用户一条审批通知已读。
     * @param notificationId 站内通知主键
     * @return AjaxResult，成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:list')")
    @PostMapping("/inbox/{notificationId}/read")
    public AjaxResult markRead(@PathVariable @Positive long notificationId)
    {
        notificationInboxService.markRead(notificationId);
        return success();
    }

    /**
     * 标记当前用户全部审批通知已读。
     * @return AjaxResult，data 为实际变更数量
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:list')")
    @PostMapping("/inbox/read-all")
    public AjaxResult markAllRead()
    {
        return success(notificationInboxService.markAllRead());
    }

    /**
     * 查询当前用户通知偏好。
     * @return AjaxResult，data 为通道开关和 revision
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:list')")
    @GetMapping("/preference")
    public AjaxResult preference()
    {
        return success(notificationPolicyService.preference());
    }

    /**
     * 乐观锁保存当前用户通知偏好。
     * @param request WorkflowNotificationPreferenceRequest，通道开关和版本
     * @return AjaxResult，data 为写后偏好
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:list')")
    @PutMapping("/preference")
    public AjaxResult savePreference(@Valid @RequestBody WorkflowNotificationPreferenceRequest request)
    {
        return success(notificationPolicyService.savePreference(request));
    }

    /**
     * 由发起人或具备跨实例权限的管理员催办真实活动待办。
     * @param request WorkflowManualUrgeRequest，流程实例和原因
     * @return AjaxResult，data 为催办事件键、真实接收人数和 outbox 数量
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:urge')")
    @Log(title = "人工催办审批", businessType = BusinessType.INSERT)
    @PostMapping("/urge")
    public AjaxResult urge(@Valid @RequestBody WorkflowManualUrgeRequest request)
    {
        return success(manualUrgeService.urge(request));
    }

    /**
     * 查询流程、节点通知策略。
     * @return AjaxResult，data 为全部策略
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:manage')")
    @GetMapping("/policies")
    public AjaxResult policies()
    {
        return success(notificationPolicyService.policies());
    }

    /**
     * 新增或更新流程、节点通知策略。
     * @param request WorkflowNotificationPolicyRequest，完整策略和乐观锁版本
     * @return AjaxResult，data 为写后策略
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:manage')")
    @Log(title = "维护审批通知策略", businessType = BusinessType.UPDATE)
    @PutMapping("/policies")
    public AjaxResult savePolicy(@Valid @RequestBody WorkflowNotificationPolicyRequest request)
    {
        return success(notificationPolicyService.savePolicy(request));
    }

    /**
     * 分页查询脱敏通知 outbox 运维状态。
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，每页记录数，最大 100
     * @param status String，通知投递状态
     * @param sourceType String，APPROVAL、SLA 或 BPMN_EVENT
     * @param eventType String，通知业务事件类型
     * @param channel String，INBOX、EMAIL 或 SMS
     * @param keyword String，outbox、来源、流程、任务或错误码关键字
     * @param beginTime LocalDateTime，创建时间下界
     * @param endTime LocalDateTime，创建时间上界
     * @return TableDataInfo，若依标准 rows、total 分页响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:audit')")
    @GetMapping("/outbox")
    public TableDataInfo outbox(
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = 100, message = "每页记录数不能超过100") int pageSize,
            @RequestParam(required = false)
            @Pattern(regexp = "PENDING|RETRYING|DELIVERING|PROCESSED|DEAD_LETTER|CANCELLED",
                    message = "通知状态不受支持") String status,
            @RequestParam(required = false)
            @Pattern(regexp = "APPROVAL|SLA|BPMN_EVENT", message = "通知来源类型不受支持") String sourceType,
            @RequestParam(required = false)
            @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}", message = "通知事件类型不受支持") String eventType,
            @RequestParam(required = false)
            @Pattern(regexp = "INBOX|EMAIL|SMS", message = "通知通道不受支持") String channel,
            @RequestParam(required = false) @Size(max = 128, message = "检索关键字过长") String keyword,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime beginTime,
            @RequestParam(required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime)
    {
        return getDataTable(notificationAdminService.listOutbox(
                new WorkflowOperationsQuery.NotificationOutbox(status, sourceType, eventType,
                        channel, keyword, beginTime, endTime), pageNum, pageSize));
    }

    /**
     * 管理员补偿一条通知死信。
     * @param outboxId outbox 主键
     * @return AjaxResult，成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:retry')")
    @Log(title = "补偿审批通知死信", businessType = BusinessType.UPDATE)
    @PostMapping("/outbox/{outboxId}/compensate")
    public AjaxResult compensate(@PathVariable @Positive long outboxId)
    {
        notificationOutboxService.compensate(outboxId);
        return success();
    }
}
