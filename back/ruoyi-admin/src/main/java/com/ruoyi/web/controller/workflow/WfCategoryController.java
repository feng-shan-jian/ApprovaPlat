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
import com.ruoyi.flowable.domain.WfCategory;
import com.ruoyi.flowable.domain.dto.WfCategoryCreateRequest;
import com.ruoyi.flowable.domain.dto.WfCategoryUpdateRequest;
import com.ruoyi.flowable.domain.vo.WfCategoryExportView;
import com.ruoyi.flowable.domain.vo.WfCategoryOptionView;
import com.ruoyi.flowable.service.IWfCategoryService;

/**
 * 工作流分类管理接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/category")
public class WfCategoryController extends BaseController
{
    /** 分类列表单页上限。 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 分类选择项上限，超量时要求管理员先治理分类数据。 */
    private static final int MAX_OPTION_ROWS = 1000;

    /** 分类导出上限，多取一条用于准确判断超量。 */
    private static final int MAX_EXPORT_ROWS = 10000;

    private final IWfCategoryService categoryService;

    /**
     * 创建工作流分类 Controller。
     *
     * @param categoryService IWfCategoryService，分类领域服务
     * @return 无返回值，构造后由 Spring 管理该 Controller
     */
    public WfCategoryController(IWfCategoryService categoryService)
    {
        this.categoryService = categoryService;
    }

    /**
     * 分页查询未逻辑删除的工作流分类。
     *
     * @param filter WfCategory，只读取分类名称和编码过滤字段
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，分类分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:category:list')")
    @GetMapping("/list")
    public TableDataInfo list(WfCategory filter,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        PageHelper.startPage(pageNum, pageSize);
        try
        {
            return getDataTable(categoryService.queryList(filter));
        }
        finally
        {
            // 无论查询或响应映射是否失败，都清理线程分页状态，避免污染同线程后续 SQL。
            PageHelper.clearPage();
        }
    }

    /**
     * 返回登录用户可用于选择器的全部有效分类最小视图。
     *
     * @param filter WfCategory，只读取分类名称和编码过滤字段
     * @return AjaxResult，不含审计和逻辑删除字段的分类选择项
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/listAll")
    public AjaxResult listAll(WfCategory filter)
    {
        List<WfCategory> categories = categoryService.queryExportList(filter, MAX_OPTION_ROWS + 1);
        if (categories.size() > MAX_OPTION_ROWS)
        {
            throw new ServiceException("有效流程分类不能超过1000条，请先缩小查询范围",
                    HttpStatus.BAD_REQUEST);
        }
        List<WfCategoryOptionView> options = categories.stream()
                .map(category -> new WfCategoryOptionView(category.getCategoryId(),
                        category.getCategoryName(), category.getCode()))
                .toList();
        return success(options);
    }

    /**
     * 导出有界分类数据。
     *
     * @param filter WfCategory，只读取分类名称和编码过滤字段
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:category:export')")
    @Log(title = "流程分类", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(WfCategory filter, HttpServletResponse response)
    {
        List<WfCategory> categories = categoryService.queryExportList(filter, MAX_EXPORT_ROWS + 1);
        if (categories.size() > MAX_EXPORT_ROWS)
        {
            throw new ServiceException("流程分类导出数据不能超过10000条，请缩小查询范围",
                    HttpStatus.BAD_REQUEST);
        }
        List<WfCategoryExportView> exports = categories.stream()
                .map(category -> new WfCategoryExportView(category.getCategoryId(),
                        category.getCategoryName(), category.getCode(), category.getRemark()))
                .toList();
        new ExcelUtil<>(WfCategoryExportView.class)
                .exportExcel(response, exports, "流程分类");
    }

    /**
     * 查询单个有效分类详情。
     *
     * @param categoryId Long，分类主键
     * @return AjaxResult，分类详情
     */
    @PreAuthorize("@ss.hasPermi('workflow:category:query')")
    @GetMapping("/{categoryId}")
    public AjaxResult getInfo(
            @PathVariable @Positive(message = "流程分类主键必须为正数") Long categoryId)
    {
        WfCategory category = categoryService.queryById(categoryId);
        if (category == null)
        {
            throw new ServiceException("流程分类不存在或已删除", HttpStatus.NOT_FOUND);
        }
        return success(category);
    }

    /**
     * 新增工作流分类并由服务端写入创建审计人。
     *
     * @param request WfCategoryCreateRequest，允许客户端维护的分类字段
     * @return AjaxResult，包含真实分类主键的成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:category:add')")
    @Log(title = "流程分类", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public AjaxResult add(@Valid @RequestBody WfCategoryCreateRequest request)
    {
        WfCategory category = new WfCategory();
        category.setCategoryName(request.categoryName());
        category.setCode(request.code());
        category.setRemark(request.remark());
        category.setCreateBy(getUsername());
        categoryService.insertCategory(category);
        return success(Map.of("categoryId", category.getCategoryId()));
    }

    /**
     * 修改有效分类并由服务端写入更新审计人。
     *
     * @param request WfCategoryUpdateRequest，分类主键和允许修改的字段
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:category:edit')")
    @Log(title = "流程分类", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public AjaxResult edit(@Valid @RequestBody WfCategoryUpdateRequest request)
    {
        WfCategory category = new WfCategory();
        category.setCategoryId(request.categoryId());
        category.setCategoryName(request.categoryName());
        category.setCode(request.code());
        category.setRemark(request.remark());
        category.setUpdateBy(getUsername());
        categoryService.updateCategory(category);
        return success();
    }

    /**
     * 在模型和流程定义引用检查通过后批量逻辑删除分类。
     *
     * @param categoryIds Long[]，待删除分类主键数组
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:category:remove')")
    @Log(title = "流程分类", businessType = BusinessType.DELETE)
    @DeleteMapping("/{categoryIds}")
    public AjaxResult remove(
            @PathVariable @NotEmpty(message = "流程分类主键不能为空")
            @Size(max = 100, message = "单次最多删除100个流程分类") Long[] categoryIds)
    {
        categoryService.deleteWithValidByIds(Arrays.asList(categoryIds), getUsername());
        return success();
    }
}
