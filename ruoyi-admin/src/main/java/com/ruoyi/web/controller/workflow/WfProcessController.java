package com.ruoyi.web.controller.workflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.page.PageResult;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.flowable.domain.dto.WorkflowAssignedTaskQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowBpmnXmlQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowClaimableTaskQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowCompletedTaskQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowCopyQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowManagedProcessQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowOwnedProcessQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDetailQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowProcessFormQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowStartableProcessQueryDto;
import com.ruoyi.flowable.domain.vo.WorkflowAssignedTaskExportView;
import com.ruoyi.flowable.domain.vo.WorkflowAssignedTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowClaimableTaskExportView;
import com.ruoyi.flowable.domain.vo.WorkflowClaimableTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowCompletedTaskExportView;
import com.ruoyi.flowable.domain.vo.WorkflowCopyExportView;
import com.ruoyi.flowable.domain.vo.WorkflowCopyView;
import com.ruoyi.flowable.domain.vo.WorkflowManagedProcessExportView;
import com.ruoyi.flowable.domain.vo.WorkflowManagedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowOwnedProcessExportView;
import com.ruoyi.flowable.domain.vo.WorkflowOwnedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowStartableDefinitionExportView;
import com.ruoyi.flowable.domain.vo.WorkflowStartableDefinitionView;
import com.ruoyi.flowable.service.process.WorkflowProcessDetailService;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;
import com.ruoyi.flowable.service.process.WorkflowProcessQueryService;

/**
 * 流程工作台列表、导出、表单、BPMN 和实例详情接口。
 */
@Validated
@RestController
@RequestMapping("/workflow/process")
public class WfProcessController extends BaseController
{
    /** 工作台列表单页上限，与领域服务保持一致。 */
    private static final int MAX_PAGE_SIZE = 200;

    /** 导出总量上限，防止下载接口退化为无界查询。 */
    private static final int MAX_EXPORT_ROWS = 10_000;

    /** 导出逐页读取大小，复用领域服务的分页门禁。 */
    private static final int EXPORT_PAGE_SIZE = 200;

    private final WorkflowProcessQueryService processQueryService;

    private final WorkflowProcessDetailService processDetailService;

    private final WorkflowProcessInstanceService processInstanceService;

    /**
     * 创建流程工作台 Controller。
     *
     * @param processQueryService WorkflowProcessQueryService，七类身份受控列表与快照查询服务
     * @param processDetailService WorkflowProcessDetailService，完整对象授权详情服务
     * @param processInstanceService WorkflowProcessInstanceService，已结束历史删除服务
     * @return 无返回值，构造后由 Spring 管理该 Controller
     */
    public WfProcessController(WorkflowProcessQueryService processQueryService,
            WorkflowProcessDetailService processDetailService,
            WorkflowProcessInstanceService processInstanceService)
    {
        this.processQueryService = processQueryService;
        this.processDetailService = processDetailService;
        this.processInstanceService = processInstanceService;
    }

    /**
     * 查询当前用户可发起的最新激活流程定义。
     *
     * @param filter WorkflowStartableProcessQueryDto，流程定义筛选条件
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，当前用户可发起定义分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:startList')")
    @GetMapping("/list")
    public TableDataInfo startProcessList(
            @ModelAttribute WorkflowStartableProcessQueryDto filter,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        return getDataTable(processQueryService.listStartable(filter, pageNum, pageSize));
    }

    /**
     * 查询当前用户真实发起的流程实例。
     *
     * @param filter WorkflowOwnedProcessQueryDto，流程实例筛选条件
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，当前用户发起实例分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:ownList')")
    @GetMapping("/ownList")
    public TableDataInfo ownProcessList(@ModelAttribute WorkflowOwnedProcessQueryDto filter,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        return getDataTable(processQueryService.listOwned(filter, pageNum, pageSize));
    }

    /**
     * 查询流程管理员可运维的全部用户流程实例。
     *
     * @param filter WorkflowManagedProcessQueryDto，实例、定义、发起人及开始时间筛选条件
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，跨用户流程实例的管理员分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:manageList')")
    @GetMapping("/manageList")
    public TableDataInfo managedProcessList(
            @ModelAttribute WorkflowManagedProcessQueryDto filter,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        return getDataTable(processQueryService.listManaged(filter, pageNum, pageSize));
    }

    /**
     * 查询当前用户作为 assignee 的活动待办。
     *
     * @param filter WorkflowAssignedTaskQueryDto，活动任务筛选条件
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，当前办理人的活动任务分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:todoList')")
    @GetMapping("/todoList")
    public TableDataInfo todoProcessList(@ModelAttribute WorkflowAssignedTaskQueryDto filter,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        return getDataTable(processQueryService.listAssigned(filter, pageNum, pageSize));
    }

    /**
     * 查询当前用户或其有效角色、部门可认领的未分配任务。
     *
     * @param filter WorkflowClaimableTaskQueryDto，可认领任务筛选条件
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，当前身份可认领任务分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:claimList')")
    @GetMapping("/claimList")
    public TableDataInfo claimProcessList(@ModelAttribute WorkflowClaimableTaskQueryDto filter,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        return getDataTable(processQueryService.listClaimable(filter, pageNum, pageSize));
    }

    /**
     * 查询 Flowable 记录为当前用户真实完成的历史任务。
     *
     * @param filter WorkflowCompletedTaskQueryDto，已办任务筛选条件
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，当前用户真实已办任务分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:finishedList')")
    @GetMapping("/finishedList")
    public TableDataInfo finishedProcessList(
            @ModelAttribute WorkflowCompletedTaskQueryDto filter,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        return getDataTable(processQueryService.listCompleted(filter, pageNum, pageSize));
    }

    /**
     * 查询正式业务表中抄送给当前用户的记录。
     *
     * @param filter WorkflowCopyQueryDto，不包含可伪造接收人主键的业务筛选条件
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，当前用户抄送记录分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:copyList')")
    @GetMapping("/copyList")
    public TableDataInfo copyProcessList(@ModelAttribute WorkflowCopyQueryDto filter,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        return getDataTable(processQueryService.listCopies(filter, pageNum, pageSize));
    }

    /**
     * 原子标记当前用户抄送记录的首次阅读时间。
     *
     * @param copyId Long，抄送记录主键
     * @return AjaxResult，数据库最终阅读状态；不存在和越权使用同一结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:copyList')")
    @PutMapping("/copy/{copyId}/read")
    public AjaxResult markCopyRead(@PathVariable Long copyId)
    {
        return success(processQueryService.markCopyRead(copyId));
    }

    /**
     * 导出当前用户可发起的有界流程定义。
     *
     * @param filter WorkflowStartableProcessQueryDto，流程定义筛选条件
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:startExport')")
    @Log(title = "可发起流程", businessType = BusinessType.EXPORT)
    @PostMapping("/startExport")
    @Transactional(readOnly = true)
    public void startExport(@ModelAttribute WorkflowStartableProcessQueryDto filter,
            HttpServletResponse response)
    {
        // 可发起定义由领域服务一次解析身份并完成整批授权扫描，禁止按导出页重复全量扫描。
        List<WorkflowStartableDefinitionView> rows =
                processQueryService.listStartableForExport(filter);
        List<WorkflowStartableDefinitionExportView> exports = rows.stream()
                .map(row -> new WorkflowStartableDefinitionExportView(row.definitionId(),
                        row.processName(), row.processKey(), row.category(), row.version(),
                        row.deploymentId(), row.deploymentTime()))
                .toList();
        new ExcelUtil<>(WorkflowStartableDefinitionExportView.class)
                .exportExcel(response, exports, "可发起流程");
    }

    /**
     * 导出当前用户真实发起的有界流程实例。
     *
     * @param filter WorkflowOwnedProcessQueryDto，流程实例筛选条件
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:ownExport')")
    @Log(title = "我发起的流程", businessType = BusinessType.EXPORT)
    @PostMapping("/ownExport")
    @Transactional(readOnly = true)
    public void ownExport(@ModelAttribute WorkflowOwnedProcessQueryDto filter,
            HttpServletResponse response)
    {
        List<WorkflowOwnedProcessView> rows = collectForExport(
                page -> processQueryService.listOwned(filter, page, EXPORT_PAGE_SIZE),
                "我发起的流程");
        List<WorkflowOwnedProcessExportView> exports = rows.stream()
                .map(row -> new WorkflowOwnedProcessExportView(row.processInstanceId(),
                        row.processName(), row.category(), row.version(), row.startTime(),
                        row.endTime(), row.processStatus(), row.durationMillis(),
                        String.join("、", row.currentTaskNames())))
                .toList();
        new ExcelUtil<>(WorkflowOwnedProcessExportView.class)
                .exportExcel(response, exports, "我发起的流程");
    }

    /**
     * 导出流程管理员筛选范围内的有界跨用户实例。
     *
     * @param filter WorkflowManagedProcessQueryDto，实例、定义、发起人及开始时间筛选条件
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:manageExport')")
    @Log(title = "流程实例运维", businessType = BusinessType.EXPORT)
    @PostMapping("/manageExport")
    @Transactional(readOnly = true)
    public void managedExport(@ModelAttribute WorkflowManagedProcessQueryDto filter,
            HttpServletResponse response)
    {
        List<WorkflowManagedProcessView> rows = collectForExport(
                page -> processQueryService.listManaged(filter, page, EXPORT_PAGE_SIZE),
                "流程实例运维");
        List<WorkflowManagedProcessExportView> exports = rows.stream()
                .map(row -> new WorkflowManagedProcessExportView(row.processInstanceId(),
                        row.processName(), row.category(), row.version(), row.businessKey(),
                        row.startUserId(), row.startUserName(), row.startTime(), row.endTime(),
                        row.processStatus(), row.durationMillis(),
                        String.join("、", row.currentTaskNames())))
                .toList();
        new ExcelUtil<>(WorkflowManagedProcessExportView.class)
                .exportExcel(response, exports, "流程实例运维");
    }

    /**
     * 导出当前用户作为 assignee 的有界活动待办。
     *
     * @param filter WorkflowAssignedTaskQueryDto，活动任务筛选条件
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:todoExport')")
    @Log(title = "待办流程", businessType = BusinessType.EXPORT)
    @PostMapping("/todoExport")
    @Transactional(readOnly = true)
    public void todoExport(@ModelAttribute WorkflowAssignedTaskQueryDto filter,
            HttpServletResponse response)
    {
        List<WorkflowAssignedTaskView> rows = collectForExport(
                page -> processQueryService.listAssigned(filter, page, EXPORT_PAGE_SIZE),
                "待办流程");
        List<WorkflowAssignedTaskExportView> exports = rows.stream()
                .map(row -> new WorkflowAssignedTaskExportView(row.taskId(), row.processName(),
                        row.taskName(), row.version(), row.startUserName(), row.createTime(),
                        row.dueTime()))
                .toList();
        new ExcelUtil<>(WorkflowAssignedTaskExportView.class)
                .exportExcel(response, exports, "待办流程");
    }

    /**
     * 导出当前用户或其有效角色、部门可认领的有界任务。
     *
     * @param filter WorkflowClaimableTaskQueryDto，可认领任务筛选条件
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:claimExport')")
    @Log(title = "待签流程", businessType = BusinessType.EXPORT)
    @PostMapping("/claimExport")
    @Transactional(readOnly = true)
    public void claimExport(@ModelAttribute WorkflowClaimableTaskQueryDto filter,
            HttpServletResponse response)
    {
        List<WorkflowClaimableTaskView> rows = collectForExport(
                page -> processQueryService.listClaimable(filter, page, EXPORT_PAGE_SIZE),
                "待签流程");
        List<WorkflowClaimableTaskExportView> exports = rows.stream()
                .map(row -> new WorkflowClaimableTaskExportView(row.taskId(), row.processName(),
                        row.taskName(), row.version(), row.startUserName(), row.createTime(),
                        row.dueTime()))
                .toList();
        new ExcelUtil<>(WorkflowClaimableTaskExportView.class)
                .exportExcel(response, exports, "待签流程");
    }

    /**
     * 导出当前用户真实完成的有界历史任务。
     *
     * @param filter WorkflowCompletedTaskQueryDto，已办任务筛选条件
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:finishedExport')")
    @Log(title = "已办流程", businessType = BusinessType.EXPORT)
    @PostMapping("/finishedExport")
    @Transactional(readOnly = true)
    public void finishedExport(@ModelAttribute WorkflowCompletedTaskQueryDto filter,
            HttpServletResponse response)
    {
        List<WorkflowCompletedTaskExportView> exports = collectForExport(
                page -> processQueryService.listCompletedForExport(
                        filter, page, EXPORT_PAGE_SIZE),
                "已办流程");
        new ExcelUtil<>(WorkflowCompletedTaskExportView.class)
                .exportExcel(response, exports, "已办流程");
    }

    /**
     * 导出正式业务表中抄送给当前用户的有界记录。
     *
     * @param filter WorkflowCopyQueryDto，不含接收用户主键的业务筛选条件
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:copyExport')")
    @Log(title = "抄送流程", businessType = BusinessType.EXPORT)
    @PostMapping("/copyExport")
    @Transactional(readOnly = true)
    public void copyExport(@ModelAttribute WorkflowCopyQueryDto filter,
            HttpServletResponse response)
    {
        List<WorkflowCopyView> rows = collectForExport(
                page -> processQueryService.listCopies(filter, page, EXPORT_PAGE_SIZE),
                "抄送流程");
        List<WorkflowCopyExportView> exports = rows.stream()
                .map(row -> new WorkflowCopyExportView(row.copyId(), row.title(),
                        row.processName(), row.categoryId(), row.deploymentId(), row.instanceId(),
                        row.taskId(), row.originatorId(), row.originatorName(), row.createTime()))
                .toList();
        new ExcelUtil<>(WorkflowCopyExportView.class)
                .exportExcel(response, exports, "抄送流程");
    }

    /**
     * 查询定义、部署及可选实例关系核验后的开始表单快照。
     *
     * @param definitionId String，流程定义主键
     * @param deploymentId String，流程定义所属的 Flowable 部署主键
     * @param processInstanceId String，可选流程实例主键，首次发起时为空
     * @return AjaxResult，不回连当前模板的部署表单快照
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:start')")
    @GetMapping("/getProcessForm")
    public AjaxResult getForm(
            @RequestParam @NotBlank(message = "流程定义主键不能为空") String definitionId,
            @RequestParam("deploymentId")
            @NotBlank(message = "流程部署主键不能为空") String deploymentId,
            @RequestParam(value = "processInstanceId", required = false)
            String processInstanceId)
    {
        return success(processQueryService.getProcessForm(
                new WorkflowProcessFormQueryDto(definitionId, deploymentId,
                        processInstanceId)));
    }

    /**
     * 由受控流程管理员批量删除已结束实例历史和关联抄送记录。
     *
     * @param instanceIds String[]，旧逗号路径协议绑定的流程实例主键集合
     * @return AjaxResult，去重请求数及真实历史、抄送删除数量
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:remove')")
    @Log(title = "删除流程历史", businessType = BusinessType.DELETE)
    @DeleteMapping("/instance/{instanceIds}")
    public AjaxResult deleteHistory(@PathVariable String[] instanceIds)
    {
        return success(processInstanceService.deleteCompletedHistory(Arrays.asList(instanceIds)));
    }

    /**
     * 按可发起权限或实例对象权限读取安全 BPMN XML。
     *
     * @param processDefId String，旧接口兼容的流程定义路径主键
     * @param processInstanceId String，详情场景可选流程实例主键
     * @return AjaxResult，经过大小、UTF-8、安全 XML 和 Flowable 校验的 BPMN XML
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:process:startList,workflow:process:query')")
    @GetMapping("/bpmnXml/{processDefId}")
    public AjaxResult getBpmnXml(
            @PathVariable @NotBlank(message = "流程定义主键不能为空") String processDefId,
            @RequestParam(value = "processInstanceId", required = false)
            String processInstanceId)
    {
        // 显式使用数据响应，避免 BaseController.success(String) 重载把 XML 误写入 msg 字段。
        return AjaxResult.success((Object) processQueryService.getBpmnXml(
                new WorkflowBpmnXmlQueryDto(processDefId, processInstanceId)));
    }

    /**
     * 查询对象授权后的完整流程实例详情。
     *
     * @param processInstanceId String，流程实例主键
     * @param taskId String，可选的活动或历史任务主键
     * @return AjaxResult，表单值、时间线、意见、BPMN 和 Viewer 状态详情
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:query')")
    @GetMapping("/detail")
    public AjaxResult detail(
            @RequestParam("processInstanceId")
            @NotBlank(message = "流程实例主键不能为空") String processInstanceId,
            @RequestParam(value = "taskId", required = false) String taskId)
    {
        return success(processDetailService.getDetail(
                new WorkflowProcessDetailQueryDto(processInstanceId, taskId)));
    }

    /**
     * 在固定总量和分页一致性门禁下收集导出数据。
     *
     * @param pageLoader IntFunction&lt;PageResult&lt;T&gt;&gt;，按页读取同一身份范围的函数
     * @param exportName String，导出业务名称，用于稳定错误提示
     * @param <T> 领域列表视图类型
     * @return List&lt;T&gt;，条数与首个真实 total 完全一致的不可变导出数据
     */
    private <T> List<T> collectForExport(
            IntFunction<PageResult<T>> pageLoader, String exportName)
    {
        PageResult<T> firstPage = pageLoader.apply(1);
        long expectedTotal = firstPage.total();
        if (expectedTotal > MAX_EXPORT_ROWS)
        {
            throw new ServiceException(exportName + "导出数据不能超过10000条，请缩小查询范围",
                    HttpStatus.BAD_REQUEST);
        }
        List<T> rows = new ArrayList<>((int) expectedTotal);
        addExportPage(rows, firstPage, expectedTotal, exportName);
        int totalPages = (int) ((expectedTotal + EXPORT_PAGE_SIZE - 1) / EXPORT_PAGE_SIZE);
        for (int pageNum = 2; pageNum <= totalPages; pageNum++)
        {
            addExportPage(rows, pageLoader.apply(pageNum), expectedTotal, exportName);
        }
        if (rows.size() != expectedTotal)
        {
            throw new ServiceException(exportName + "导出期间数据发生变化，请重试",
                    HttpStatus.CONFLICT);
        }
        return List.copyOf(rows);
    }

    /**
     * 校验一页导出结果的 total 和行数后追加到累计集合。
     *
     * @param target List&lt;T&gt;，当前已收集的导出行
     * @param page PageResult&lt;T&gt;，本次领域分页结果
     * @param expectedTotal long，第一页确定的真实总数
     * @param exportName String，导出业务名称
     * @param <T> 领域列表视图类型
     * @return 无返回值，分页漂移或超量时抛出稳定异常
     */
    private <T> void addExportPage(List<T> target, PageResult<T> page,
            long expectedTotal, String exportName)
    {
        if (page == null || page.total() != expectedTotal
                || target.size() + page.rows().size() > expectedTotal)
        {
            throw new ServiceException(exportName + "导出期间数据发生变化，请重试",
                    HttpStatus.CONFLICT);
        }
        target.addAll(page.rows());
    }

}
