package com.ruoyi.web.controller.workflow;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.flowable.domain.dto.WorkflowAssignedTaskQueryDto;
import com.ruoyi.flowable.domain.dto.StartProcessRequest;
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
import com.ruoyi.flowable.domain.vo.WorkflowCompletedTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowCopyExportView;
import com.ruoyi.flowable.domain.vo.WorkflowCopyView;
import com.ruoyi.flowable.domain.vo.WorkflowManagedProcessExportView;
import com.ruoyi.flowable.domain.vo.WorkflowManagedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowOwnedProcessExportView;
import com.ruoyi.flowable.domain.vo.WorkflowOwnedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.domain.vo.WorkflowProcessStartView;
import com.ruoyi.flowable.domain.vo.WorkflowStartableDefinitionExportView;
import com.ruoyi.flowable.domain.vo.WorkflowStartableDefinitionView;
import com.ruoyi.flowable.engine.WorkflowProcessInstanceSnapshot;
import com.ruoyi.flowable.service.process.WorkflowProcessDetailService;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;
import com.ruoyi.flowable.service.process.WorkflowProcessQueryService;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;

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

    /** 旧前端日期范围格式。 */
    private static final String LEGACY_DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 旧项目及当前 Jackson 配置使用的固定业务时区。 */
    private static final ZoneId LEGACY_DATE_ZONE = ZoneId.of("GMT+8");

    /** 发起请求体中一律忽略、仅允许由路径决定的流程定义字段别名。 */
    private static final Set<String> START_DEFINITION_ALIASES = Set.of(
            "processDefId", "processDefinitionId", "definitionId");

    /** 包装发起协议允许的全部顶层字段，其他字段视为协议混用。 */
    private static final Set<String> START_WRAPPER_FIELDS = Set.of(
            "variables", "businessKey", "multiInstanceUserIds", "processDefId",
            "processDefinitionId", "definitionId");

    private final WorkflowProcessQueryService processQueryService;

    private final WorkflowProcessDetailService processDetailService;

    private final WorkflowProcessStartService processStartService;

    private final WorkflowProcessInstanceService processInstanceService;

    /**
     * 创建流程工作台 Controller。
     *
     * @param processQueryService WorkflowProcessQueryService，七类身份受控列表与快照查询服务
     * @param processDetailService WorkflowProcessDetailService，完整对象授权详情服务
     * @param processStartService WorkflowProcessStartService，真实流程发起服务
     * @param processInstanceService WorkflowProcessInstanceService，已结束历史删除服务
     * @return 无返回值，构造后由 Spring 管理该 Controller
     */
    public WfProcessController(WorkflowProcessQueryService processQueryService,
            WorkflowProcessDetailService processDetailService,
            WorkflowProcessStartService processStartService,
            WorkflowProcessInstanceService processInstanceService)
    {
        this.processQueryService = processQueryService;
        this.processDetailService = processDetailService;
        this.processStartService = processStartService;
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
        return toTableData(processQueryService.listStartable(filter, pageNum, pageSize));
    }

    /**
     * 查询当前用户真实发起的流程实例。
     *
     * @param filter WorkflowOwnedProcessQueryDto，流程实例筛选条件
     * @param legacyBeginTime LocalDateTime，旧前端 params[beginTime] 开始时间
     * @param legacyEndTime LocalDateTime，旧前端 params[endTime] 结束时间
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，当前用户发起实例分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:ownList')")
    @GetMapping("/ownList")
    public TableDataInfo ownProcessList(@ModelAttribute WorkflowOwnedProcessQueryDto filter,
            @RequestParam(value = "params[beginTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyBeginTime,
            @RequestParam(value = "params[endTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyEndTime,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        DateRange range = mergeDateRange(filter.startedAfter(), filter.startedBefore(),
                legacyBeginTime, legacyEndTime);
        WorkflowOwnedProcessQueryDto normalizedFilter = new WorkflowOwnedProcessQueryDto(
                filter.processKey(), filter.processName(), filter.category(), filter.businessKey(),
                range.begin(), range.end());
        return toTableData(processQueryService.listOwned(normalizedFilter, pageNum, pageSize));
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
        return toTableData(processQueryService.listManaged(filter, pageNum, pageSize));
    }

    /**
     * 查询当前用户作为 assignee 的活动待办。
     *
     * @param filter WorkflowAssignedTaskQueryDto，活动任务筛选条件
     * @param legacyBeginTime LocalDateTime，旧前端 params[beginTime] 开始时间
     * @param legacyEndTime LocalDateTime，旧前端 params[endTime] 结束时间
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，当前办理人的活动任务分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:todoList')")
    @GetMapping("/todoList")
    public TableDataInfo todoProcessList(@ModelAttribute WorkflowAssignedTaskQueryDto filter,
            @RequestParam(value = "params[beginTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyBeginTime,
            @RequestParam(value = "params[endTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyEndTime,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        DateRange range = mergeDateRange(filter.createdAfter(), filter.createdBefore(),
                legacyBeginTime, legacyEndTime);
        WorkflowAssignedTaskQueryDto normalizedFilter = new WorkflowAssignedTaskQueryDto(
                filter.processKey(), filter.processName(), filter.category(), filter.taskName(),
                range.begin(), range.end());
        return toTableData(processQueryService.listAssigned(normalizedFilter, pageNum, pageSize));
    }

    /**
     * 查询当前用户或其有效角色、部门可认领的未分配任务。
     *
     * @param filter WorkflowClaimableTaskQueryDto，可认领任务筛选条件
     * @param legacyBeginTime LocalDateTime，旧前端 params[beginTime] 开始时间
     * @param legacyEndTime LocalDateTime，旧前端 params[endTime] 结束时间
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，当前身份可认领任务分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:claimList')")
    @GetMapping("/claimList")
    public TableDataInfo claimProcessList(@ModelAttribute WorkflowClaimableTaskQueryDto filter,
            @RequestParam(value = "params[beginTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyBeginTime,
            @RequestParam(value = "params[endTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyEndTime,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        DateRange range = mergeDateRange(filter.createdAfter(), filter.createdBefore(),
                legacyBeginTime, legacyEndTime);
        WorkflowClaimableTaskQueryDto normalizedFilter = new WorkflowClaimableTaskQueryDto(
                filter.processKey(), filter.processName(), filter.category(), filter.taskName(),
                range.begin(), range.end());
        return toTableData(processQueryService.listClaimable(normalizedFilter, pageNum, pageSize));
    }

    /**
     * 查询 Flowable 记录为当前用户真实完成的历史任务。
     *
     * @param filter WorkflowCompletedTaskQueryDto，已办任务筛选条件
     * @param legacyBeginTime LocalDateTime，旧前端 params[beginTime] 开始时间
     * @param legacyEndTime LocalDateTime，旧前端 params[endTime] 结束时间
     * @param pageNum int，从 1 开始的页码
     * @param pageSize int，单页记录数
     * @return TableDataInfo，当前用户真实已办任务分页结果
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:finishedList')")
    @GetMapping("/finishedList")
    public TableDataInfo finishedProcessList(
            @ModelAttribute WorkflowCompletedTaskQueryDto filter,
            @RequestParam(value = "params[beginTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyBeginTime,
            @RequestParam(value = "params[endTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyEndTime,
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") int pageNum,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页记录数必须大于0")
            @Max(value = MAX_PAGE_SIZE, message = "每页记录数不能超过200") int pageSize)
    {
        DateRange range = mergeDateRange(filter.completedAfter(), filter.completedBefore(),
                legacyBeginTime, legacyEndTime);
        WorkflowCompletedTaskQueryDto normalizedFilter = new WorkflowCompletedTaskQueryDto(
                filter.processKey(), filter.processName(), filter.category(), filter.taskName(),
                range.begin(), range.end());
        return toTableData(processQueryService.listCompleted(normalizedFilter, pageNum, pageSize));
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
        return toTableData(processQueryService.listCopies(filter, pageNum, pageSize));
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
        List<WorkflowStartableDefinitionView> rows = collectForExport(
                page -> processQueryService.listStartable(filter, page, EXPORT_PAGE_SIZE),
                "可发起流程");
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
     * @param legacyBeginTime LocalDateTime，旧前端 params[beginTime] 开始时间
     * @param legacyEndTime LocalDateTime，旧前端 params[endTime] 结束时间
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:ownExport')")
    @Log(title = "我发起的流程", businessType = BusinessType.EXPORT)
    @PostMapping("/ownExport")
    @Transactional(readOnly = true)
    public void ownExport(@ModelAttribute WorkflowOwnedProcessQueryDto filter,
            @RequestParam(value = "params[beginTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyBeginTime,
            @RequestParam(value = "params[endTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyEndTime,
            HttpServletResponse response)
    {
        DateRange range = mergeDateRange(filter.startedAfter(), filter.startedBefore(),
                legacyBeginTime, legacyEndTime);
        WorkflowOwnedProcessQueryDto normalizedFilter = new WorkflowOwnedProcessQueryDto(
                filter.processKey(), filter.processName(), filter.category(), filter.businessKey(),
                range.begin(), range.end());
        List<WorkflowOwnedProcessView> rows = collectForExport(
                page -> processQueryService.listOwned(normalizedFilter, page, EXPORT_PAGE_SIZE),
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
     * @param legacyBeginTime LocalDateTime，旧前端 params[beginTime] 开始时间
     * @param legacyEndTime LocalDateTime，旧前端 params[endTime] 结束时间
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:todoExport')")
    @Log(title = "待办流程", businessType = BusinessType.EXPORT)
    @PostMapping("/todoExport")
    @Transactional(readOnly = true)
    public void todoExport(@ModelAttribute WorkflowAssignedTaskQueryDto filter,
            @RequestParam(value = "params[beginTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyBeginTime,
            @RequestParam(value = "params[endTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyEndTime,
            HttpServletResponse response)
    {
        DateRange range = mergeDateRange(filter.createdAfter(), filter.createdBefore(),
                legacyBeginTime, legacyEndTime);
        WorkflowAssignedTaskQueryDto normalizedFilter = new WorkflowAssignedTaskQueryDto(
                filter.processKey(), filter.processName(), filter.category(), filter.taskName(),
                range.begin(), range.end());
        List<WorkflowAssignedTaskView> rows = collectForExport(
                page -> processQueryService.listAssigned(normalizedFilter, page, EXPORT_PAGE_SIZE),
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
     * @param legacyBeginTime LocalDateTime，旧前端 params[beginTime] 开始时间
     * @param legacyEndTime LocalDateTime，旧前端 params[endTime] 结束时间
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:claimExport')")
    @Log(title = "待签流程", businessType = BusinessType.EXPORT)
    @PostMapping("/claimExport")
    @Transactional(readOnly = true)
    public void claimExport(@ModelAttribute WorkflowClaimableTaskQueryDto filter,
            @RequestParam(value = "params[beginTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyBeginTime,
            @RequestParam(value = "params[endTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyEndTime,
            HttpServletResponse response)
    {
        DateRange range = mergeDateRange(filter.createdAfter(), filter.createdBefore(),
                legacyBeginTime, legacyEndTime);
        WorkflowClaimableTaskQueryDto normalizedFilter = new WorkflowClaimableTaskQueryDto(
                filter.processKey(), filter.processName(), filter.category(), filter.taskName(),
                range.begin(), range.end());
        List<WorkflowClaimableTaskView> rows = collectForExport(
                page -> processQueryService.listClaimable(normalizedFilter, page, EXPORT_PAGE_SIZE),
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
     * @param legacyBeginTime LocalDateTime，旧前端 params[beginTime] 开始时间
     * @param legacyEndTime LocalDateTime，旧前端 params[endTime] 结束时间
     * @param response HttpServletResponse，Excel 下载响应
     * @return 无返回值，Excel 内容直接写入响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:finishedExport')")
    @Log(title = "已办流程", businessType = BusinessType.EXPORT)
    @PostMapping("/finishedExport")
    @Transactional(readOnly = true)
    public void finishedExport(@ModelAttribute WorkflowCompletedTaskQueryDto filter,
            @RequestParam(value = "params[beginTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyBeginTime,
            @RequestParam(value = "params[endTime]", required = false)
            @DateTimeFormat(pattern = LEGACY_DATE_PATTERN) LocalDateTime legacyEndTime,
            HttpServletResponse response)
    {
        DateRange range = mergeDateRange(filter.completedAfter(), filter.completedBefore(),
                legacyBeginTime, legacyEndTime);
        WorkflowCompletedTaskQueryDto normalizedFilter = new WorkflowCompletedTaskQueryDto(
                filter.processKey(), filter.processName(), filter.category(), filter.taskName(),
                range.begin(), range.end());
        List<WorkflowCompletedTaskView> rows = collectForExport(
                page -> processQueryService.listCompleted(normalizedFilter, page, EXPORT_PAGE_SIZE),
                "已办流程");
        List<WorkflowCompletedTaskExportView> exports = rows.stream()
                .map(row -> new WorkflowCompletedTaskExportView(row.taskId(), row.processName(),
                        row.taskName(), row.version(), row.startUserName(), row.completedBy(),
                        row.createTime(), row.finishTime(), row.durationMillis()))
                .toList();
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
     * @param deployId String，旧接口兼容的部署主键参数名
     * @param procInsId String，旧接口兼容的可选流程实例主键参数名
     * @return AjaxResult，不回连当前模板的部署表单快照
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:start')")
    @GetMapping("/getProcessForm")
    public AjaxResult getForm(
            @RequestParam @NotBlank(message = "流程定义主键不能为空") String definitionId,
            @RequestParam("deployId") @NotBlank(message = "流程部署主键不能为空") String deployId,
            @RequestParam(value = "procInsId", required = false) String procInsId)
    {
        return success(processQueryService.getProcessForm(
                new WorkflowProcessFormQueryDto(definitionId, deployId, procInsId)));
    }

    /**
     * 根据路径中的流程定义主键真实发起实例，并兼容旧直接变量和包装请求体。
     *
     * @param processDefId String，服务端唯一采信的 Flowable 流程定义主键
     * @param body Map&lt;String, Object&gt;，旧直接变量或 variables/businessKey 包装对象
     * @return AjaxResult，包含真实实例主键及 procInsId 兼容别名的成功响应
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:start')")
    @Log(title = "发起流程", businessType = BusinessType.INSERT)
    @PostMapping("/start/{processDefId}")
    public AjaxResult start(
            @PathVariable @NotBlank(message = "流程定义主键不能为空")
            @Size(max = 255, message = "流程定义主键长度不能超过255个字符")
            String processDefId,
            @RequestBody(required = false) Map<String, Object> body)
    {
        StartProcessRequest request = normalizeStartRequest(processDefId, body);
        WorkflowProcessInstanceSnapshot processInstance = processStartService.start(request);
        WorkflowProcessStartView result = new WorkflowProcessStartView(processInstance.id(),
                processInstance.id(), processInstance.processDefinitionId(),
                processInstance.businessKey());
        return AjaxResult.success("流程启动成功", result);
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
     * @param procInsId String，详情场景可选流程实例主键
     * @return AjaxResult，经过大小、UTF-8、安全 XML 和 Flowable 校验的 BPMN XML
     */
    @PreAuthorize("@ss.hasAnyPermi('workflow:process:startList,workflow:process:query')")
    @GetMapping("/bpmnXml/{processDefId}")
    public AjaxResult getBpmnXml(
            @PathVariable @NotBlank(message = "流程定义主键不能为空") String processDefId,
            @RequestParam(value = "procInsId", required = false) String procInsId)
    {
        // 显式使用数据响应，避免 BaseController.success(String) 重载把 XML 误写入 msg 字段。
        return AjaxResult.success((Object) processQueryService.getBpmnXml(
                new WorkflowBpmnXmlQueryDto(processDefId, procInsId)));
    }

    /**
     * 查询对象授权后的完整流程实例详情。
     *
     * @param procInsId String，旧接口兼容的流程实例主键参数名
     * @param taskId String，可选的活动或历史任务主键
     * @return AjaxResult，表单值、时间线、意见、BPMN 和 Viewer 状态详情
     */
    @PreAuthorize("@ss.hasPermi('workflow:process:query')")
    @GetMapping("/detail")
    public AjaxResult detail(
            @RequestParam("procInsId") @NotBlank(message = "流程实例主键不能为空") String procInsId,
            @RequestParam(value = "taskId", required = false) String taskId)
    {
        return success(processDetailService.getDetail(
                new WorkflowProcessDetailQueryDto(procInsId, taskId)));
    }

    /**
     * 将新包装协议和旧直接变量协议归一为领域发起请求，路径定义主键始终优先。
     *
     * @param processDefId String，已经过方法参数校验的路径流程定义主键
     * @param body Map&lt;String, Object&gt;，允许为空的 JSON 对象请求体
     * @return StartProcessRequest，仅包含服务端路径定义、可选业务主键和表单变量
     */
    private StartProcessRequest normalizeStartRequest(String processDefId,
            Map<String, Object> body)
    {
        Map<String, Object> source = body == null ? Map.of() : body;
        boolean wrapped = source.containsKey("variables") || source.containsKey("businessKey")
                || source.containsKey("multiInstanceUserIds");
        if (!wrapped)
        {
            // 直接变量协议也忽略客户端夹带的定义 ID，实际定义只能来自受权限保护的路径。
            LinkedHashMap<String, Object> variables = new LinkedHashMap<>(source);
            START_DEFINITION_ALIASES.forEach(variables::remove);
            return new StartProcessRequest(processDefId, null, variables);
        }

        if (source.keySet().stream().anyMatch(key -> !START_WRAPPER_FIELDS.contains(key)))
        {
            throw new ServiceException("流程发起请求不能混用包装字段和直接变量",
                    HttpStatus.BAD_REQUEST);
        }
        Object rawVariables = source.get("variables");
        Map<String, Object> variables = normalizeWrappedVariables(rawVariables);
        Object rawBusinessKey = source.get("businessKey");
        if (rawBusinessKey != null && !(rawBusinessKey instanceof String))
        {
            throw new ServiceException("流程业务主键必须为字符串", HttpStatus.BAD_REQUEST);
        }
        Map<String, List<Long>> multiInstanceUserIds = normalizeStartMultiInstanceUsers(
                source.get("multiInstanceUserIds"));
        return new StartProcessRequest(processDefId, (String) rawBusinessKey, variables,
                multiInstanceUserIds);
    }

    /**
     * 将发起页面会签或或签成员字段转换为严格的活动到用户主键列表。
     *
     * @param rawSelections Object，JSON 反序列化后的 multiInstanceUserIds 字段。
     * @return Map<String,List<Long>> 保持客户端节点和成员顺序的不可变请求数据。
     */
    private Map<String, List<Long>> normalizeStartMultiInstanceUsers(Object rawSelections)
    {
        if (rawSelections == null)
        {
            return Map.of();
        }
        if (!(rawSelections instanceof Map<?, ?> rawMap) || rawMap.size() > 100)
        {
            throw new ServiceException("发起时会签或或签成员格式不合法", HttpStatus.BAD_REQUEST);
        }
        LinkedHashMap<String, List<Long>> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet())
        {
            if (!(entry.getKey() instanceof String activityId) || activityId.isBlank()
                    || activityId.length() > 128
                    || !(entry.getValue() instanceof Collection<?> rawUserIds)
                    || rawUserIds.size() > 100)
            {
                throw new ServiceException("发起时会签或或签成员格式不合法",
                        HttpStatus.BAD_REQUEST);
            }
            List<Long> userIds = new ArrayList<>(rawUserIds.size());
            for (Object rawUserId : rawUserIds)
            {
                if (!(rawUserId instanceof Byte || rawUserId instanceof Short
                        || rawUserId instanceof Integer || rawUserId instanceof Long))
                {
                    throw new ServiceException("发起时会签或或签成员格式不合法",
                            HttpStatus.BAD_REQUEST);
                }
                long userId = ((Number) rawUserId).longValue();
                if (userId <= 0)
                {
                    throw new ServiceException("发起时会签或或签成员格式不合法",
                            HttpStatus.BAD_REQUEST);
                }
                userIds.add(userId);
            }
            result.put(activityId, List.copyOf(userIds));
        }
        return Map.copyOf(result);
    }

    /**
     * 将包装协议中的 variables 安全转换为字符串键映射。
     *
     * @param rawVariables Object，JSON 反序列化后的 variables 字段
     * @return Map&lt;String, Object&gt;，保持客户端字段顺序的变量映射
     */
    private Map<String, Object> normalizeWrappedVariables(Object rawVariables)
    {
        if (rawVariables == null)
        {
            return Map.of();
        }
        if (!(rawVariables instanceof Map<?, ?> rawMap))
        {
            throw new ServiceException("流程变量必须为JSON对象", HttpStatus.BAD_REQUEST);
        }
        LinkedHashMap<String, Object> variables = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet())
        {
            if (!(entry.getKey() instanceof String key))
            {
                throw new ServiceException("流程变量字段名必须为字符串", HttpStatus.BAD_REQUEST);
            }
            variables.put(key, entry.getValue());
        }
        return variables;
    }

    /**
     * 合并新 ISO 时间与旧前端本地时间范围，并校验请求内时间语义唯一且顺序合法。
     *
     * @param modernBegin Instant，新协议开始时间，允许为空
     * @param modernEnd Instant，新协议结束时间，允许为空
     * @param legacyBegin LocalDateTime，旧协议 GMT+8 开始时间，允许为空
     * @param legacyEnd LocalDateTime，旧协议 GMT+8 结束时间，允许为空
     * @return DateRange，统一转换后的 UTC 时间范围
     */
    private DateRange mergeDateRange(Instant modernBegin, Instant modernEnd,
            LocalDateTime legacyBegin, LocalDateTime legacyEnd)
    {
        Instant legacyBeginInstant = legacyBegin == null ? null
                : legacyBegin.atZone(LEGACY_DATE_ZONE).toInstant();
        Instant legacyEndInstant = legacyEnd == null ? null
                : legacyEnd.atZone(LEGACY_DATE_ZONE).toInstant();
        Instant begin = mergeDateValue(modernBegin, legacyBeginInstant, "开始时间");
        Instant end = mergeDateValue(modernEnd, legacyEndInstant, "结束时间");
        if (begin != null && end != null && begin.isAfter(end))
        {
            throw new ServiceException("开始时间不能晚于结束时间", HttpStatus.BAD_REQUEST);
        }
        return new DateRange(begin, end);
    }

    /**
     * 合并同一边界的新旧协议值，拒绝同一请求中的歧义时间。
     *
     * @param modernValue Instant，新 ISO 参数转换结果，允许为空
     * @param legacyValue Instant，旧本地时间转换结果，允许为空
     * @param fieldName String，稳定错误提示中的字段名称
     * @return Instant，唯一有效值或空值
     */
    private Instant mergeDateValue(Instant modernValue, Instant legacyValue, String fieldName)
    {
        if (modernValue != null && legacyValue != null && !modernValue.equals(legacyValue))
        {
            throw new ServiceException(fieldName + "的新旧参数值不一致", HttpStatus.BAD_REQUEST);
        }
        return modernValue == null ? legacyValue : modernValue;
    }

    /**
     * 在固定总量和分页一致性门禁下收集导出数据。
     *
     * @param pageLoader IntFunction&lt;WorkflowPageResult&lt;T&gt;&gt;，按页读取同一身份范围的函数
     * @param exportName String，导出业务名称，用于稳定错误提示
     * @param <T> 领域列表视图类型
     * @return List&lt;T&gt;，条数与首个真实 total 完全一致的不可变导出数据
     */
    private <T> List<T> collectForExport(
            IntFunction<WorkflowPageResult<T>> pageLoader, String exportName)
    {
        WorkflowPageResult<T> firstPage = pageLoader.apply(1);
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
     * @param page WorkflowPageResult&lt;T&gt;，本次领域分页结果
     * @param expectedTotal long，第一页确定的真实总数
     * @param exportName String，导出业务名称
     * @param <T> 领域列表视图类型
     * @return 无返回值，分页漂移或超量时抛出稳定异常
     */
    private <T> void addExportPage(List<T> target, WorkflowPageResult<T> page,
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
     * 新旧协议归一化后的 UTC 查询范围。
     *
     * @param begin Instant，开始时间，允许为空
     * @param end Instant，结束时间，允许为空
     */
    private record DateRange(Instant begin, Instant end)
    {
    }
}
