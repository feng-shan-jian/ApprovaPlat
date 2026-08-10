package com.ruoyi.web.controller.workflow;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.flowable.service.model.WorkflowCallActivityReferenceService;

/**
 * 调用活动已发布子流程目录接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/call-activity")
public class WfCallActivityController extends BaseController
{
    /** 调用活动正式流程目录、对象权限和字段契约服务。 */
    private final WorkflowCallActivityReferenceService referenceService;

    /**
     * 创建调用活动目录 Controller。
     *
     * @param referenceService WorkflowCallActivityReferenceService，服务端授权目录与字段契约服务
     * @return 无返回值，构造后由 Spring 管理该 Controller
     */
    public WfCallActivityController(WorkflowCallActivityReferenceService referenceService)
    {
        this.referenceService = referenceService;
    }

    /**
     * 查询当前设计者有权引用的已发布子流程版本和正式变量字段。
     *
     * @param keyword String，可选流程名称或 key 检索词
     * @return AjaxResult，仅包含服务端按对象权限过滤后的目录元数据，不返回 BPMN 定义正文
     */
    @PreAuthorize("@ss.hasPermi('workflow:model:designer')")
    @GetMapping("/catalog")
    public AjaxResult catalog(@RequestParam(required = false) String keyword)
    {
        // 目录始终从当前登录身份和 Flowable 正式定义重建，禁止客户端夹带定义绕过权限。
        return success(referenceService.listReferenceOptions(keyword));
    }
}
