package com.ruoyi.web.controller.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
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
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.flowable.domain.dto.WorkflowManualUrgeRequest;
import com.ruoyi.flowable.domain.dto.WorkflowNotificationPolicyRequest;
import com.ruoyi.flowable.domain.dto.WorkflowNotificationPreferenceRequest;
import com.ruoyi.flowable.service.notification.WorkflowNotificationService;

/**
 * 普通审批通知、偏好、策略、outbox 和人工催办正式 API。
 */
@Validated
@RestController
@RequestMapping("/workflow/notification")
public class WfNotificationController extends BaseController
{
    private final WorkflowNotificationService notificationService;

    /**
     * 创建审批通知 Controller。
     * @param notificationService WorkflowNotificationService，通知领域服务
     * @return void，构造后由 Spring 管理
     */
    public WfNotificationController(WorkflowNotificationService notificationService)
    {
        this.notificationService = notificationService;
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
        return success(notificationService.inbox(readStatus, limit));
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
        notificationService.markRead(notificationId);
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
        return success(notificationService.markAllRead());
    }

    /**
     * 查询当前用户通知偏好。
     * @return AjaxResult，data 为通道开关和 revision
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:list')")
    @GetMapping("/preference")
    public AjaxResult preference()
    {
        return success(notificationService.preference());
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
        return success(notificationService.savePreference(request));
    }

    /**
     * 由发起人或具备跨实例权限的管理员催办真实活动待办。
     * @param request WorkflowManualUrgeRequest，流程实例和原因
     * @return AjaxResult，data 为审计和投递数量
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:urge')")
    @Log(title = "人工催办审批", businessType = BusinessType.INSERT)
    @PostMapping("/urge")
    public AjaxResult urge(@Valid @RequestBody WorkflowManualUrgeRequest request)
    {
        return success(notificationService.urge(request));
    }

    /**
     * 查询流程、节点通知策略。
     * @return AjaxResult，data 为全部策略
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:manage')")
    @GetMapping("/policies")
    public AjaxResult policies()
    {
        return success(notificationService.policies());
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
        return success(notificationService.savePolicy(request));
    }

    /**
     * 查询脱敏通知 outbox 运维状态。
     * @return AjaxResult，data 为最近 500 条记录
     */
    @PreAuthorize("@ss.hasPermi('workflow:notification:audit')")
    @GetMapping("/outbox")
    public AjaxResult outbox()
    {
        return success(notificationService.outbox());
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
        notificationService.compensate(outboxId);
        return success();
    }
}
