package com.ruoyi.web.controller.workflow;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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
import com.ruoyi.flowable.domain.dto.WorkflowBpmnEventCodeRequest;
import com.ruoyi.flowable.domain.dto.WorkflowBpmnEventCodeStatusRequest;
import com.ruoyi.flowable.service.model.WorkflowBpmnEventCodeService;

/**
 * BPMN 错误与升级编码、运行审计和用户通知接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/bpmn-event")
public class WfBpmnEventController extends BaseController
{
    private final WorkflowBpmnEventCodeService eventService;

    /**
     * 创建 BPMN 事件 Controller。
     * @param eventService WorkflowBpmnEventCodeService，目录、审计和通知领域服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WfBpmnEventController(WorkflowBpmnEventCodeService eventService)
    {
        this.eventService = eventService;
    }

    /** @return AjaxResult，全部错误与升级目录。 */
    @PreAuthorize("@ss.hasPermi('workflow:bpmnEvent:list')")
    @GetMapping("/codes")
    public AjaxResult listCodes()
    {
        return success(eventService.listManagement());
    }

    /**
     * 查询设计器可选择的启用编码。
     * @param eventType String，ERROR 或 ESCALATION
     * @return AjaxResult，真实数据库目录选项
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:bpmnEvent:list,workflow:model:designer')")
    @GetMapping("/codes/options/{eventType}")
    public AjaxResult codeOptions(@PathVariable
            @Pattern(regexp = "ERROR|ESCALATION", message = "事件类型不受支持") String eventType)
    {
        return success(eventService.listEnabled(eventType));
    }

    /** @param request WorkflowBpmnEventCodeRequest，新增目录；@return AjaxResult，生成主键。 */
    @PreAuthorize("@ss.hasPermi('workflow:bpmnEvent:add')")
    @Log(title = "BPMN 错误升级编码", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/codes")
    public AjaxResult createCode(@Valid @RequestBody WorkflowBpmnEventCodeRequest request)
    {
        return success(Map.of("eventCodeId", eventService.create(request)));
    }

    /**
     * 修改编码元数据。
     * @param eventCodeId Long，目录主键
     * @param request WorkflowBpmnEventCodeRequest，完整字段
     * @return AjaxResult，成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:bpmnEvent:edit')")
    @Log(title = "BPMN 错误升级编码", businessType = BusinessType.UPDATE)
    @PutMapping("/codes/{eventCodeId}")
    public AjaxResult updateCode(@PathVariable @Positive Long eventCodeId,
            @Valid @RequestBody WorkflowBpmnEventCodeRequest request)
    {
        eventService.update(eventCodeId, request);
        return success();
    }

    /**
     * 启停编码目录。
     * @param eventCodeId Long，目录主键
     * @param request WorkflowBpmnEventCodeStatusRequest，目标状态
     * @return AjaxResult，成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:bpmnEvent:edit')")
    @Log(title = "BPMN 错误升级编码状态", businessType = BusinessType.UPDATE)
    @PutMapping("/codes/{eventCodeId}/status")
    public AjaxResult changeCodeStatus(@PathVariable @Positive Long eventCodeId,
            @Valid @RequestBody WorkflowBpmnEventCodeStatusRequest request)
    {
        eventService.changeStatus(eventCodeId, request.enabled());
        return success();
    }

    /** @return AjaxResult，最近 500 条专用运行审计。 */
    @PreAuthorize("@ss.hasPermi('workflow:bpmnEvent:audit')")
    @GetMapping("/audit")
    public AjaxResult audit()
    {
        return success(eventService.listAudit());
    }

    /** @return AjaxResult，当前用户真实站内通知。 */
    @PreAuthorize("@ss.hasAnyPermi('workflow:process:approval,workflow:process:start,workflow:bpmnEvent:list')")
    @GetMapping("/notifications/my")
    public AjaxResult myNotifications()
    {
        return success(eventService.myNotifications());
    }

    /** @param notificationId Long，当前用户通知主键；@return AjaxResult，已读结果。 */
    @PreAuthorize("@ss.hasAnyPermi('workflow:process:approval,workflow:process:start,workflow:bpmnEvent:list')")
    @PutMapping("/notifications/{notificationId}/read")
    public AjaxResult markRead(@PathVariable @Positive Long notificationId)
    {
        eventService.markNotificationRead(notificationId);
        return success();
    }
}
