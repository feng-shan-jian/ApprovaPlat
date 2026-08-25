package com.ruoyi.web.controller.workflow;

import java.time.Instant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSaveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSubmitRequest;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDraftSummaryView;
import com.ruoyi.flowable.service.process.WorkflowProcessDraftService;

/**
 * 企业流程申请草稿的本人列表、继续编辑、删除和正式提交接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/process/draft")
public class WfProcessDraftController extends BaseController
{
    /** 草稿列表单页上限。 */
    private static final int MAX_PAGE_SIZE = 200;

    private final WorkflowProcessDraftService draftService;

    /**
     * 创建流程申请草稿 Controller。
     *
     * @param draftService WorkflowProcessDraftService，草稿真实业务闭环服务
     * @return 无返回值，构造后由 Spring 管理
     */
    public WfProcessDraftController(WorkflowProcessDraftService draftService)
    {
        this.draftService = draftService;
    }

    /**
     * 查询当前用户活动草稿，所有者范围由服务端身份固定。
     *
     * @param processName String，流程名称模糊条件
     * @param updatedAfter Instant，更新时间下界
     * @param updatedBefore Instant，更新时间上界
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，本人活动草稿分页
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:draftList')")
    @GetMapping("/list")
    public TableDataInfo list(
            @RequestParam(required = false) String processName,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedAfter,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedBefore,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        return getDataTable(draftService.list(
                new WorkflowProcessDraftQueryDto(processName, updatedAfter, updatedBefore),
                pageNum, pageSize));
    }

    /**
     * 查询当前用户自己的草稿详情和不可变部署表单快照。
     *
     * @param draftId String，草稿 UUID
     * @return AjaxResult，草稿详情
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:draftQuery')")
    @GetMapping("/{draftId}")
    public AjaxResult get(@PathVariable @NotBlank(message = "草稿主键不能为空") String draftId)
    {
        return success(draftService.get(draftId));
    }

    /**
     * 从当前可发起流程定义创建正式持久化草稿。
     *
     * @param request WorkflowProcessDraftCreateRequest，定义、业务主键和草稿字段
     * @return AjaxResult，新建草稿详情
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:draftSave')")
    @Log(title = "流程申请草稿", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult create(@Valid @RequestBody WorkflowProcessDraftCreateRequest request)
    {
        return AjaxResult.success("草稿创建成功", draftService.create(request));
    }

    /**
     * 以乐观锁继续保存本人活动草稿。
     *
     * @param draftId String，草稿 UUID
     * @param request WorkflowProcessDraftSaveRequest，期望版本和草稿字段
     * @return AjaxResult，保存后的最新草稿详情
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:draftSave')")
    @Log(title = "流程申请草稿", businessType = BusinessType.UPDATE)
    @PutMapping("/{draftId}")
    public AjaxResult save(@PathVariable @NotBlank(message = "草稿主键不能为空") String draftId,
            @Valid @RequestBody WorkflowProcessDraftSaveRequest request)
    {
        return AjaxResult.success("草稿保存成功", draftService.save(draftId, request));
    }

    /**
     * 以乐观锁删除本人活动草稿并进入附件清理链。
     *
     * @param draftId String，草稿 UUID
     * @param expectedVersion long，客户端最后读取的草稿版本
     * @return AjaxResult，删除成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:draftRemove')")
    @Log(title = "流程申请草稿", businessType = BusinessType.DELETE)
    @DeleteMapping("/{draftId}")
    public AjaxResult delete(
            @PathVariable @NotBlank(message = "草稿主键不能为空") String draftId,
            @RequestParam @Min(value = 1, message = "草稿版本必须大于0") long expectedVersion)
    {
        draftService.delete(draftId, expectedVersion);
        return success();
    }

    /**
     * 正式提交本人草稿；网络重试和并发重复提交返回同一真实实例。
     *
     * @param draftId String，草稿 UUID
     * @param request WorkflowProcessDraftSubmitRequest，期望版本、业务主键和完整字段
     * @return AjaxResult，唯一真实流程实例主键
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:draftSubmit')")
    @Log(title = "提交流程申请草稿", businessType = BusinessType.INSERT)
    @PostMapping("/{draftId}/submit")
    public AjaxResult submit(
            @PathVariable @NotBlank(message = "草稿主键不能为空") String draftId,
            @Valid @RequestBody WorkflowProcessDraftSubmitRequest request)
    {
        return AjaxResult.success("流程启动成功", draftService.submit(draftId, request));
    }
}
