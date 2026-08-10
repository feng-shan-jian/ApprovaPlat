package com.ruoyi.web.controller.workflow;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.flowable.domain.dto.WorkflowIdentitySelectionRequest;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.service.identity.WorkflowIdentityDirectoryService;

/**
 * 工作流设计、发起时选人、委派和转办使用的最小身份目录接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/identity")
public class WfIdentityController extends BaseController
{
    private final WorkflowIdentityDirectoryService identityDirectoryService;

    /**
     * 创建工作流身份目录 Controller。
     *
     * @param identityDirectoryService WorkflowIdentityDirectoryService，正式身份主数据只读服务
     * @return 无返回值，构造后由 Spring 管理该 Controller
     */
    public WfIdentityController(WorkflowIdentityDirectoryService identityDirectoryService)
    {
        this.identityDirectoryService = identityDirectoryService;
    }

    /**
     * 分页查询流程设计、发起时选人或任务转办允许使用的有效身份选项。
     *
     * @param type String，user、role 或 dept
     * @param capability String，可为空；approval 返回直接办理用户，claim 返回完整认领资格身份，
     *        copy 返回同时具备抄送列表和流程详情权限的身份
     * @param keyword String，可为空的名称、账号或编码检索词
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，仅包含 value、label 和 type 的分页响应
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:model:designer,workflow:process:start,workflow:process:approval,workflow:process:manageList')")
    @GetMapping("/options")
    public TableDataInfo options(
            @RequestParam
            @NotBlank(message = "工作流身份类型不能为空")
            @Pattern(regexp = "user|role|dept", message = "工作流身份类型必须为 user、role 或 dept")
            String type,
            @RequestParam(required = false)
            @Pattern(regexp = "approval|claim|copy|",
                    message = "工作流身份目录能力必须为 approval、claim 或 copy")
            String capability,
            @RequestParam(required = false)
            @Size(max = WorkflowIdentityDirectoryService.MAX_KEYWORD_LENGTH,
                    message = "工作流身份检索词不能超过64个字符")
            String keyword,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = "页码必须大于0")
            @Max(value = WorkflowIdentityDirectoryService.MAX_PAGE_NUMBER,
                    message = "页码不能超过1000000")
            int pageNum,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = WorkflowIdentityDirectoryService.MAX_PAGE_SIZE,
                    message = "每页记录数不能超过200")
            int pageSize)
    {
        WorkflowPageResult<?> page = identityDirectoryService.listOptions(
                type, keyword, pageNum, pageSize, capability);
        TableDataInfo result = new TableDataInfo(page.rows(), page.total());
        result.setCode(HttpStatus.SUCCESS);
        result.setMsg("查询成功");
        return result;
    }

    /**
     * 批量回显作者 BPMN 中已保存的目录对象，停用或删除对象只用于提示和替换。
     *
     * @param request WorkflowIdentitySelectionRequest，身份类型、能力与已保存目录值
     * @return AjaxResult，按请求顺序返回正式名称和实时可用状态
     */
    @PreAuthorize("@ss.hasPermi('workflow:model:designer')")
    @PostMapping("/options/resolve")
    public AjaxResult resolveOptions(
            @Valid @RequestBody WorkflowIdentitySelectionRequest request)
    {
        return success(identityDirectoryService.resolveSelections(
                request.type(), request.capability(), request.values()));
    }
}
