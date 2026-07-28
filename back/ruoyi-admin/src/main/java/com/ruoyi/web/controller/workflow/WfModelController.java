package com.ruoyi.web.controller.workflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
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
import com.ruoyi.flowable.domain.dto.WorkflowModelCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowModelDto;
import com.ruoyi.flowable.domain.dto.WorkflowModelSaveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowModelUpdateRequest;
import com.ruoyi.flowable.domain.vo.WorkflowModelExportView;
import com.ruoyi.flowable.domain.vo.WorkflowModelView;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.service.IWfCategoryService;
import com.ruoyi.flowable.service.model.WorkflowModelService;

/**
 * Flowable 8 流程模型管理接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/model")
public class WfModelController extends BaseController
{
    /** Flowable 查询单页上限，与领域服务门禁保持一致。 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 模型元数据单次导出上限。 */
    private static final int MAX_EXPORT_ROWS = 5000;

    private final WorkflowModelService modelService;

    private final IWfCategoryService categoryService;

    /**
     * 创建流程模型 Controller。
     *
     * @param modelService WorkflowModelService，模型版本和部署业务服务
     * @param categoryService IWfCategoryService，导出时解析分类名称的业务服务
     * @return 无返回值，构造后由 Spring 管理该 Controller
     */
    public WfModelController(WorkflowModelService modelService,
            IWfCategoryService categoryService)
    {
        this.modelService = modelService;
        this.categoryService = categoryService;
    }

    /**
     * 查询每个模型标识的最新版本。
     *
     * @param filter WorkflowModelDto，模型名称、标识和分类过滤条件
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，最新模型分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:model:list')")
    @GetMapping("/list")
    public TableDataInfo list(WorkflowModelDto filter,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        return toTableData(modelService.list(filter, pageNum, pageSize));
    }

    /**
     * 查询指定模型标识的旧版本。
     *
     * @param filter WorkflowModelDto，必须包含模型标识的过滤条件
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，旧版本模型分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:model:list')")
    @GetMapping("/historyList")
    public TableDataInfo historyList(WorkflowModelDto filter,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        return toTableData(modelService.historyList(filter, pageNum, pageSize));
    }

    /**
     * 查询模型详情和受控设计资源。
     * 查询、元数据编辑和流程设计入口均需要先读取同一模型详情。
     *
     * @param modelId String，Flowable 模型主键
     * @return AjaxResult，模型详情
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:model:query,workflow:model:edit,workflow:model:designer')")
    @GetMapping("/{modelId}")
    public AjaxResult getInfo(@PathVariable @NotBlank(message = "模型主键不能为空") String modelId)
    {
        return success(modelService.getModel(modelId));
    }

    /**
     * 查询经过服务端安全校验的模型 BPMN XML。
     * 流程设计权限包含设计页所需的 XML 读取能力，保存仍由独立权限控制。
     *
     * @param modelId String，Flowable 模型主键
     * @return AjaxResult，UTF-8 BPMN XML
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:model:query,workflow:model:designer')")
    @GetMapping("/bpmnXml/{modelId}")
    public AjaxResult getBpmnXml(@PathVariable @NotBlank(message = "模型主键不能为空") String modelId)
    {
        // String 同时匹配成功消息重载，必须显式按数据返回，避免前端收到空 data 后生成默认流程。
        return success((Object) modelService.getBpmnXml(modelId));
    }

    /**
     * 新增尚未部署的流程模型。
     *
     * @param request WorkflowModelCreateRequest，模型业务元数据
     * @return AjaxResult，包含真实模型主键的成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:model:add')")
    @Log(title = "流程模型", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public AjaxResult add(@Valid @RequestBody WorkflowModelCreateRequest request)
    {
        String modelId = modelService.createModel(toModelDto(request));
        return success(Map.of("modelId", modelId));
    }

    /**
     * 修改未部署模型的受控元数据。
     *
     * @param request WorkflowModelUpdateRequest，模型主键和待修改元数据
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:model:edit')")
    @Log(title = "流程模型", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public AjaxResult edit(@Valid @RequestBody WorkflowModelUpdateRequest request)
    {
        modelService.updateModel(toModelDto(request));
        return success();
    }

    /**
     * 原子保存模型 BPMN，并按请求决定是否创建新版本。
     *
     * @param request WorkflowModelSaveRequest，模型主键、BPMN XML 和版本策略
     * @return AjaxResult，包含实际保存模型主键的响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:model:save')")
    @Log(title = "保存流程模型", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/save")
    public AjaxResult save(@Valid @RequestBody WorkflowModelSaveRequest request)
    {
        String savedModelId = modelService.saveModel(toModelDto(request));
        return success(Map.of("modelId", savedModelId));
    }

    /**
     * 将历史模型复制为新的最高版本。
     *
     * @param modelId String，待提升历史模型主键
     * @return AjaxResult，包含新模型主键的响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:model:save')")
    @Log(title = "设为最新流程模型", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/latest")
    public AjaxResult latest(@RequestParam @NotBlank(message = "模型主键不能为空") String modelId)
    {
        String latestModelId = modelService.promoteToLatest(modelId);
        return success(Map.of("modelId", latestModelId));
    }

    /**
     * 删除未部署且没有流程定义引用的模型。
     *
     * @param modelIds String[]，待删除模型主键数组
     * @return AjaxResult，操作成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:model:remove')")
    @Log(title = "删除流程模型", businessType = BusinessType.DELETE)
    @DeleteMapping("/{modelIds}")
    public AjaxResult remove(
            @PathVariable @NotEmpty(message = "模型主键不能为空")
            @Size(max = 100, message = "单次最多删除100个模型") String[] modelIds)
    {
        modelService.deleteModels(Arrays.asList(modelIds));
        return success();
    }

    /**
     * 部署模型并在同一事务固化节点表单快照。
     *
     * @param modelId String，待部署模型主键
     * @return AjaxResult，包含真实 Flowable 部署主键的响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:model:deploy')")
    @Log(title = "部署流程模型", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/deploy")
    public AjaxResult deployModel(
            @RequestParam @NotBlank(message = "模型主键不能为空") String modelId)
    {
        String deploymentId = modelService.deployModel(modelId);
        return success(Map.of("deploymentId", deploymentId));
    }

    /**
     * 导出有权限查看的模型元数据，拒绝超量导出且不包含 BPMN 和表单正文。
     *
     * @param filter WorkflowModelDto，模型查询条件
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:model:export')")
    @Log(title = "导出流程模型", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(WorkflowModelDto filter, HttpServletResponse response)
    {
        List<WorkflowModelView> models = loadModelsForExport(filter);
        Map<String, String> categoryNames = activeCategoryNames();
        List<WorkflowModelExportView> exports = models.stream()
                .map(model -> new WorkflowModelExportView(model.modelId(), model.modelKey(),
                        model.modelName(), model.category(), categoryNames.get(model.category()),
                        model.version(), model.description(), model.createTime()))
                .toList();
        new ExcelUtil<>(WorkflowModelExportView.class)
                .exportExcel(response, exports, "流程模型数据");
    }

    /**
     * 把领域分页结果转换为若依稳定分页协议。
     *
     * @param page WorkflowPageResult&lt;?&gt;，Flowable 原生 count/listPage 查询结果
     * @return TableDataInfo，若依前端可直接消费的分页响应
     */
    private TableDataInfo toTableData(WorkflowPageResult<?> page)
    {
        TableDataInfo result = new TableDataInfo(page.rows(), page.total());
        result.setCode(HttpStatus.SUCCESS);
        result.setMsg("查询成功");
        return result;
    }

    /**
     * 在确认总量不超过门禁后，按 Flowable 服务上限分批读取全部导出行。
     *
     * @param filter WorkflowModelDto，模型查询条件
     * @return List&lt;WorkflowModelView&gt;，有界模型元数据列表
     */
    private List<WorkflowModelView> loadModelsForExport(WorkflowModelDto filter)
    {
        WorkflowPageResult<WorkflowModelView> firstPage = modelService.list(filter, 1, MAX_PAGE_SIZE);
        if (firstPage.total() > MAX_EXPORT_ROWS)
        {
            throw new ServiceException("模型导出数据不能超过5000条，请缩小查询范围",
                    HttpStatus.BAD_REQUEST);
        }
        List<WorkflowModelView> rows = new ArrayList<>((int) firstPage.total());
        rows.addAll(firstPage.rows());
        int totalPages = (int) ((firstPage.total() + MAX_PAGE_SIZE - 1) / MAX_PAGE_SIZE);
        for (int pageNum = 2; pageNum <= totalPages; pageNum++)
        {
            rows.addAll(modelService.list(filter, pageNum, MAX_PAGE_SIZE).rows());
        }
        if (rows.size() != firstPage.total())
        {
            throw new ServiceException("模型导出期间数据已变化，请重试", HttpStatus.CONFLICT);
        }
        return List.copyOf(rows);
    }

    /**
     * 查询有效分类并构造编码到名称的导出映射。
     *
     * @return Map&lt;String, String&gt;，有效分类编码到名称的只读映射
     */
    private Map<String, String> activeCategoryNames()
    {
        Map<String, String> names = new HashMap<>();
        for (WfCategory category : categoryService.queryList(new WfCategory()))
        {
            names.put(category.getCode(), category.getCategoryName());
        }
        return Map.copyOf(names);
    }

    /**
     * 将新增请求映射为模型领域 DTO，不复制任何服务端字段。
     *
     * @param request WorkflowModelCreateRequest，已通过 Web 校验的新增请求
     * @return WorkflowModelDto，模型服务新增参数
     */
    private WorkflowModelDto toModelDto(WorkflowModelCreateRequest request)
    {
        WorkflowModelDto dto = new WorkflowModelDto();
        dto.setModelName(request.modelName());
        dto.setModelKey(request.modelKey());
        dto.setCategory(request.category());
        dto.setDescription(request.description());
        dto.setFormType(request.formType());
        dto.setFormId(request.formId());
        return dto;
    }

    /**
     * 将修改请求映射为模型领域 DTO，不允许写入 BPMN 或部署关系。
     *
     * @param request WorkflowModelUpdateRequest，已通过 Web 校验的修改请求
     * @return WorkflowModelDto，模型服务修改参数
     */
    private WorkflowModelDto toModelDto(WorkflowModelUpdateRequest request)
    {
        WorkflowModelDto dto = new WorkflowModelDto();
        dto.setModelId(request.modelId());
        dto.setModelName(request.modelName());
        dto.setModelKey(request.modelKey());
        dto.setCategory(request.category());
        dto.setDescription(request.description());
        dto.setFormType(request.formType());
        dto.setFormId(request.formId());
        return dto;
    }

    /**
     * 将设计保存请求映射为只含模型主键和 BPMN 的领域 DTO。
     *
     * @param request WorkflowModelSaveRequest，已通过 Web 校验的设计保存请求
     * @return WorkflowModelDto，模型服务保存参数
     */
    private WorkflowModelDto toModelDto(WorkflowModelSaveRequest request)
    {
        WorkflowModelDto dto = new WorkflowModelDto();
        dto.setModelId(request.modelId());
        dto.setBpmnXml(request.bpmnXml());
        dto.setNewVersion(request.newVersion());
        return dto;
    }
}
