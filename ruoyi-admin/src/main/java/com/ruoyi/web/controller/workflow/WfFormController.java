package com.ruoyi.web.controller.workflow;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import com.github.pagehelper.PageHelper;
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
import com.ruoyi.common.annotation.RepeatSubmit;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.flowable.domain.WfForm;
import com.ruoyi.flowable.domain.dto.WfFormCreateRequest;
import com.ruoyi.flowable.domain.dto.WfFormUpdateRequest;
import com.ruoyi.flowable.domain.vo.WfFormExportView;
import com.ruoyi.flowable.service.IWfFormService;

/**
 * 可编辑工作流表单模板管理接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/form")
public class WfFormController extends BaseController
{
    /** 表单正文最大 1 MiB，因此列表采用更严格的单页上限。 */
    private static final int MAX_PAGE_SIZE = 50;

    /** 表单摘要导出上限，多取一条用于准确判断超量。 */
    private static final int MAX_EXPORT_ROWS = 10000;

    private final IWfFormService formService;

    /**
     * 创建工作流表单 Controller。
     *
     * @param formService IWfFormService，表单模板领域服务
     * @return 无返回值，构造后由 Spring 管理该 Controller
     */
    public WfFormController(IWfFormService formService)
    {
        this.formService = formService;
    }

    /**
     * 分页查询有效表单模板。
     * 模型列表和设计页需要读取表单选项，但不会因此获得表单修改权限。
     *
     * @param filter WfForm，只读取表单名称过滤字段
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，表单模板分页结果
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:form:list,workflow:model:list,workflow:model:designer')")
    @GetMapping("/list")
    public TableDataInfo list(WfForm filter,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过50") int pageSize)
    {
        PageHelper.startPage(pageNum, pageSize);
        try
        {
            return getDataTable(formService.queryList(filter));
        }
        finally
        {
            // 无论查询或响应映射是否失败，都清理线程分页状态，避免污染同线程后续 SQL。
            PageHelper.clearPage();
        }
    }

    /**
     * 导出不含 JSON 正文的有界表单摘要。
     *
     * @param filter WfForm，只读取表单名称过滤字段
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:form:export')")
    @Log(title = "流程表单", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(WfForm filter, HttpServletResponse response)
    {
        List<WfForm> forms = formService.querySummaryList(filter, MAX_EXPORT_ROWS + 1);
        if (forms.size() > MAX_EXPORT_ROWS)
        {
            throw new ServiceException("流程表单导出数据不能超过10000条，请缩小查询范围",
                    HttpStatus.BAD_REQUEST);
        }
        List<WfFormExportView> exports = forms.stream()
                .map(form -> new WfFormExportView(form.getFormId(), form.getFormName(),
                        form.getRemark()))
                .toList();
        new ExcelUtil<>(WfFormExportView.class)
                .exportExcel(response, exports, "流程表单");
    }

    /**
     * 查询单个有效表单模板，返回当前可编辑正文而不是部署快照。
     * 表单编辑权限包含读取待编辑正文的必要能力，独立预览仍使用查询权限。
     *
     * @param formId Long，表单模板主键
     * @return AjaxResult，当前表单模板详情
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:form:query,workflow:form:edit')")
    @GetMapping("/{formId}")
    public AjaxResult getInfo(@PathVariable @Positive(message = "流程表单主键必须为正数") Long formId)
    {
        WfForm form = formService.queryById(formId);
        if (form == null)
        {
            throw new ServiceException("流程表单不存在或已删除", HttpStatus.NOT_FOUND);
        }
        return success(form);
    }

    /**
     * 校验结构和组件白名单后新增表单模板。
     *
     * @param request WfFormCreateRequest，允许客户端维护的表单字段
     * @return AjaxResult，包含真实表单主键的成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:form:add')")
    @Log(title = "流程表单", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public AjaxResult add(@Valid @RequestBody WfFormCreateRequest request)
    {
        WfForm form = new WfForm();
        form.setFormName(request.formName());
        form.setContent(request.content());
        form.setRemark(request.remark());
        form.setCreateBy(getUsername());
        formService.insertForm(form);
        return success(Map.of("formId", form.getFormId()));
    }

    /**
     * 修改当前可编辑模板，历史部署快照保持不变。
     *
     * @param request WfFormUpdateRequest，表单主键和新的模板字段
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:form:edit')")
    @Log(title = "流程表单", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public AjaxResult edit(@Valid @RequestBody WfFormUpdateRequest request)
    {
        WfForm form = new WfForm();
        form.setFormId(request.formId());
        form.setFormName(request.formName());
        form.setContent(request.content());
        form.setRemark(request.remark());
        form.setUpdateBy(getUsername());
        formService.updateForm(form);
        return success();
    }

    /**
     * 在模型、流程定义和部署快照引用检查通过后批量逻辑删除表单。
     *
     * @param formIds Long[]，待删除表单主键数组
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:form:remove')")
    @Log(title = "流程表单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{formIds}")
    public AjaxResult remove(
            @PathVariable @NotEmpty(message = "流程表单主键不能为空")
            @Size(max = 100, message = "单次最多删除100个流程表单") Long[] formIds)
    {
        formService.deleteWithValidByIds(Arrays.asList(formIds), getUsername());
        return success();
    }
}
