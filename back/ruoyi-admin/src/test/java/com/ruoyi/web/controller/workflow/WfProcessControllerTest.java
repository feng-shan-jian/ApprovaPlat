package com.ruoyi.web.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.MediaType;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.exception.ServiceException;
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
import com.ruoyi.flowable.domain.dto.StartProcessRequest;
import com.ruoyi.flowable.domain.vo.WorkflowHistoryDeletionView;
import com.ruoyi.flowable.domain.vo.WorkflowAssignedTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowClaimableTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowCompletedTaskView;
import com.ruoyi.flowable.domain.vo.WorkflowCopyView;
import com.ruoyi.flowable.domain.vo.WorkflowManagedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowOwnedProcessView;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.domain.vo.WorkflowProcessDetailView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessFormSnapshotView;
import com.ruoyi.flowable.domain.vo.WorkflowProcessViewerView;
import com.ruoyi.flowable.domain.vo.WorkflowStartableDefinitionView;
import com.ruoyi.flowable.engine.WorkflowProcessInstanceSnapshot;
import com.ruoyi.flowable.service.process.WorkflowProcessDetailService;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;
import com.ruoyi.flowable.service.process.WorkflowProcessQueryService;
import com.ruoyi.flowable.service.process.WorkflowProcessStartService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WfProcessController 的旧接口契约、真实 MVC 参数绑定和有界导出测试。
 */
class WfProcessControllerTest
{
    private WorkflowProcessQueryService processQueryService;

    private WorkflowProcessDetailService processDetailService;

    private WorkflowProcessStartService processStartService;

    private WorkflowProcessInstanceService processInstanceService;

    private WfProcessController controller;

    private MockMvc mockMvc;

    /**
     * 为每个测试创建独立服务替身、Controller 和真实 Spring MVC 参数绑定链路。
     *
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        processQueryService = mock(WorkflowProcessQueryService.class);
        processDetailService = mock(WorkflowProcessDetailService.class);
        processStartService = mock(WorkflowProcessStartService.class);
        processInstanceService = mock(WorkflowProcessInstanceService.class);
        controller = new WfProcessController(processQueryService, processDetailService,
                processStartService, processInstanceService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * 验证六类列表均通过真实 ModelAttribute 构造不可变 record，并转换为若依分页协议。
     *
     * @return 无返回值；任一字段、时间、分页参数或响应协议绑定错误时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void bindsAllListRecordsAndReturnsTableDataProtocol() throws Exception
    {
        WorkflowStartableDefinitionView definition = definitionRow();
        when(processQueryService.listStartable(any(WorkflowStartableProcessQueryDto.class),
                eq(2), eq(20))).thenReturn(new WorkflowPageResult<>(List.of(definition), 7));
        when(processQueryService.listOwned(any(WorkflowOwnedProcessQueryDto.class),
                eq(3), eq(25))).thenReturn(new WorkflowPageResult<WorkflowOwnedProcessView>(
                        List.of(), 0));
        when(processQueryService.listAssigned(any(WorkflowAssignedTaskQueryDto.class),
                eq(4), eq(30))).thenReturn(new WorkflowPageResult<WorkflowAssignedTaskView>(
                        List.of(), 0));
        when(processQueryService.listClaimable(any(WorkflowClaimableTaskQueryDto.class),
                eq(5), eq(35))).thenReturn(new WorkflowPageResult<WorkflowClaimableTaskView>(
                        List.of(), 0));
        when(processQueryService.listCompleted(any(WorkflowCompletedTaskQueryDto.class),
                eq(6), eq(40))).thenReturn(new WorkflowPageResult<WorkflowCompletedTaskView>(
                        List.of(), 0));
        when(processQueryService.listCopies(any(WorkflowCopyQueryDto.class),
                eq(7), eq(45))).thenReturn(new WorkflowPageResult<WorkflowCopyView>(
                        List.of(), 0));

        mockMvc.perform(get("/workflow/process/list")
                        .param("processKey", "leave")
                        .param("processName", "请假")
                        .param("category", "hr")
                        .param("pageNum", "2")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.SUCCESS))
                .andExpect(jsonPath("$.msg").value("查询成功"))
                .andExpect(jsonPath("$.total").value(7))
                .andExpect(jsonPath("$.rows[0].definitionId").value("definition-1"));
        mockMvc.perform(get("/workflow/process/ownList")
                        .param("processKey", "leave")
                        .param("processName", "请假")
                        .param("category", "hr")
                        .param("businessKey", "business-1")
                        .param("params[beginTime]", "2026-07-25 16:00:00")
                        .param("params[endTime]", "2026-07-25 18:00:00")
                        .param("pageNum", "3")
                        .param("pageSize", "25"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/workflow/process/todoList")
                        .param("processKey", "leave")
                        .param("processName", "请假")
                        .param("category", "hr")
                        .param("taskName", "审批")
                        .param("params[beginTime]", "2026-07-25 16:00:00")
                        .param("params[endTime]", "2026-07-25 18:00:00")
                        .param("pageNum", "4")
                        .param("pageSize", "30"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/workflow/process/claimList")
                        .param("processKey", "leave")
                        .param("processName", "请假")
                        .param("category", "hr")
                        .param("taskName", "认领")
                        .param("params[beginTime]", "2026-07-25 16:00:00")
                        .param("params[endTime]", "2026-07-25 18:00:00")
                        .param("pageNum", "5")
                        .param("pageSize", "35"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/workflow/process/finishedList")
                        .param("processKey", "leave")
                        .param("processName", "请假")
                        .param("category", "hr")
                        .param("taskName", "审批")
                        .param("completedAfter", "2026-07-25T08:00:00Z")
                        .param("completedBefore", "2026-07-25T10:00:00Z")
                        .param("pageNum", "6")
                        .param("pageSize", "40"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/workflow/process/copyList")
                        .param("title", "抄送标题")
                        .param("processId", "definition-1")
                        .param("processName", "请假")
                        .param("originatorName", "张三")
                        .param("instanceId", "instance-1")
                        .param("taskId", "task-1")
                        .param("categoryId", "hr")
                        .param("deploymentId", "deployment-1")
                        .param("pageNum", "7")
                        .param("pageSize", "45"))
                .andExpect(status().isOk());

        verify(processQueryService).listStartable(
                new WorkflowStartableProcessQueryDto("leave", "请假", "hr"), 2, 20);
        verify(processQueryService).listOwned(new WorkflowOwnedProcessQueryDto(
                "leave", "请假", "hr", "business-1",
                Instant.parse("2026-07-25T08:00:00Z"),
                Instant.parse("2026-07-25T10:00:00Z")), 3, 25);
        verify(processQueryService).listAssigned(new WorkflowAssignedTaskQueryDto(
                "leave", "请假", "hr", "审批",
                Instant.parse("2026-07-25T08:00:00Z"),
                Instant.parse("2026-07-25T10:00:00Z")), 4, 30);
        verify(processQueryService).listClaimable(new WorkflowClaimableTaskQueryDto(
                "leave", "请假", "hr", "认领",
                Instant.parse("2026-07-25T08:00:00Z"),
                Instant.parse("2026-07-25T10:00:00Z")), 5, 35);
        verify(processQueryService).listCompleted(new WorkflowCompletedTaskQueryDto(
                "leave", "请假", "hr", "审批",
                Instant.parse("2026-07-25T08:00:00Z"),
                Instant.parse("2026-07-25T10:00:00Z")), 6, 40);
        verify(processQueryService).listCopies(new WorkflowCopyQueryDto(
                "抄送标题", "definition-1", "请假", "张三", "instance-1", "task-1", "hr",
                "deployment-1"), 7, 45);
    }

    /**
     * 验证实例运维入口通过真实 MVC 绑定全部跨用户筛选条件，并委托管理员专用查询。
     *
     * @return 无返回值；筛选、分页、响应字段或服务委托漂移时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void bindsManagedListAndDelegatesCrossUserQuery() throws Exception
    {
        WorkflowManagedProcessQueryDto filter = new WorkflowManagedProcessQueryDto(
                "instance-9", "leave", "请假", "hr", "business-9", "009",
                Instant.parse("2026-07-25T08:00:00Z"),
                Instant.parse("2026-07-26T08:00:00Z"));
        when(processQueryService.listManaged(filter, 8, 50)).thenReturn(
                new WorkflowPageResult<>(List.of(managedRow()), 12));

        mockMvc.perform(get("/workflow/process/manageList")
                        .param("processInstanceId", "instance-9")
                        .param("processKey", "leave")
                        .param("processName", "请假")
                        .param("category", "hr")
                        .param("businessKey", "business-9")
                        .param("startUserId", "009")
                        .param("startedAfter", "2026-07-25T08:00:00Z")
                        .param("startedBefore", "2026-07-26T08:00:00Z")
                        .param("pageNum", "8")
                        .param("pageSize", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.SUCCESS))
                .andExpect(jsonPath("$.total").value(12))
                .andExpect(jsonPath("$.rows[0].processInstanceId").value("instance-9"))
                .andExpect(jsonPath("$.rows[0].startUserName").value("跨用户发起人"))
                .andExpect(jsonPath("$.rows[0].currentTaskNames[0]").value("部门审批"));

        verify(processQueryService).listManaged(filter, 8, 50);
    }

    /**
     * 验证旧参数名经真实 MVC 路由转换为关系核验 DTO，客户端不能绕过对象关系字段。
     *
     * @return 无返回值；定义、部署、实例或任务主键映射错误时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void mapsLegacyFormBpmnAndDetailParameters() throws Exception
    {
        when(processQueryService.getBpmnXml(new WorkflowBpmnXmlQueryDto(
                "definition-1", "instance-1")))
                .thenReturn("<definitions/>");

        mockMvc.perform(get("/workflow/process/getProcessForm")
                        .param("definitionId", "definition-1")
                        .param("deployId", "deployment-1")
                        .param("procInsId", "instance-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.SUCCESS));
        mockMvc.perform(get("/workflow/process/bpmnXml/definition-1")
                        .param("procInsId", "instance-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("<definitions/>"));
        mockMvc.perform(get("/workflow/process/detail")
                        .param("procInsId", "instance-1")
                        .param("taskId", "task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.SUCCESS));

        verify(processQueryService).getProcessForm(new WorkflowProcessFormQueryDto(
                "definition-1", "deployment-1", "instance-1"));
        verify(processQueryService).getBpmnXml(new WorkflowBpmnXmlQueryDto(
                "definition-1", "instance-1"));
        verify(processDetailService).getDetail(new WorkflowProcessDetailQueryDto(
                "instance-1", "task-1"));
    }

    /**
     * 验证 Boot 4 MVC 通过正式双 Jackson 桥接把表单值输出为原生 JSON，而不是 JsonNode Bean 属性。
     *
     * @return 无返回值；字符串、附件数组或嵌套对象在 Controller 响应中退化时测试失败
     * @throws Exception MockMvc 执行请求或 JSON 序列化失败时抛出
     */
    @Test
    void serializesLegacyJacksonFormValuesAsNativeJson() throws Exception
    {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        values.put("reason", StringNode.valueOf("真实审批原因"));
        ArrayNode files = JsonNodeFactory.instance.arrayNode().add("attachment-uuid-1");
        values.put("files", files);
        ObjectNode decision = JsonNodeFactory.instance.objectNode();
        decision.put("approved", true);
        decision.put("score", 95);
        values.put("decision", decision);

        WorkflowProcessFormSnapshotView form = new WorkflowProcessFormSnapshotView(
                "approvalTask", "task-1", 1L, "key_1", "approvalTask",
                "审批表单", "审批", "{\"fields\":[]}", false, values, null);
        WorkflowProcessDetailView detail = new WorkflowProcessDetailView(
                "instance-1", "definition-1", "approval", "审批流程", 1,
                "default", "deployment-1", "business-1", "7", "发起人",
                null, null, null, "running", null, form, List.of(), List.of(),
                "<definitions/>", new WorkflowProcessViewerView(
                        Set.of(), Set.of(), Set.of("approvalTask"), Set.of(), Set.of()));
        when(processDetailService.getDetail(new WorkflowProcessDetailQueryDto(
                "instance-1", "task-1"))).thenReturn(detail);

        workflowJsonMockMvc().perform(get("/workflow/process/detail")
                        .param("procInsId", "instance-1")
                        .param("taskId", "task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentTaskForm.values.reason")
                        .value("真实审批原因"))
                .andExpect(jsonPath("$.data.currentTaskForm.values.files[0]")
                        .value("attachment-uuid-1"))
                .andExpect(jsonPath("$.data.currentTaskForm.values.decision.approved")
                        .value(true))
                .andExpect(jsonPath("$.data.currentTaskForm.values.decision.score")
                        .value(95))
                .andExpect(jsonPath("$.data.currentTaskForm.values.reason.nodeType")
                        .doesNotExist());
    }

    /**
     * 验证新旧发起请求体均使用路径定义主键，并返回真实实例 ID 与旧字段别名。
     *
     * @return 无返回值；协议归一、路径优先级或响应字段错误时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void startsFromDirectAndWrappedBodiesWithPathDefinitionAuthority() throws Exception
    {
        when(processStartService.start(any(StartProcessRequest.class)))
                .thenReturn(new WorkflowProcessInstanceSnapshot("instance-9",
                        "definition-path", "business-1", false));

        mockMvc.perform(post("/workflow/process/start/definition-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"采购","processDefinitionId":"definition-body"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("流程启动成功"))
                .andExpect(jsonPath("$.data.processInstanceId").value("instance-9"))
                .andExpect(jsonPath("$.data.procInsId").value("instance-9"));
        mockMvc.perform(post("/workflow/process/start/definition-path")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessKey":"business-1","variables":{"reason":"采购"},
                                 "processDefId":"definition-body"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processDefinitionId").value("definition-path"));

        ArgumentCaptor<StartProcessRequest> requests =
                ArgumentCaptor.forClass(StartProcessRequest.class);
        verify(processStartService, org.mockito.Mockito.times(2)).start(requests.capture());
        assertThat(requests.getAllValues()).containsExactly(
                new StartProcessRequest("definition-path", null, Map.of("reason", "采购")),
                new StartProcessRequest("definition-path", "business-1",
                        Map.of("reason", "采购")));
    }

    /**
     * 验证包装协议与直接变量混用会在进入发起服务前被拒绝。
     *
     * @return 无返回值；歧义请求未被拒绝或触发真实服务调用时测试失败
     */
    @Test
    void rejectsMixedStartBodyBeforeServiceCall()
    {
        assertThatThrownBy(() -> controller.start("definition-path", Map.of(
                "variables", Map.of("reason", "采购"), "amount", 100)))
                .isInstanceOf(ServiceException.class)
                .satisfies(exception -> assertThat(((ServiceException) exception).getCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(processStartService);
    }

    /**
     * 验证旧逗号路径批量删除协议会进入受控历史删除服务并返回真实计数。
     *
     * @return 无返回值；路径拆分或响应计数错误时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void delegatesLegacyHistoryDeletePath() throws Exception
    {
        when(processInstanceService.deleteCompletedHistory(any()))
                .thenReturn(new WorkflowHistoryDeletionView(2, 3, 4));

        mockMvc.perform(delete("/workflow/process/instance/instance-1,instance-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedCount").value(2))
                .andExpect(jsonPath("$.data.deletedHistoryCount").value(3))
                .andExpect(jsonPath("$.data.deletedCopyCount").value(4));

        verify(processInstanceService).deleteCompletedHistory(
                List.of("instance-1", "instance-2"));
    }

    /**
     * 验证导出在同一筛选范围内逐页读取，并生成包含全部 201 条业务数据的真实 Excel。
     *
     * @return 无返回值；分页缺失、分页大小漂移或 Excel 未真实写出时测试失败
     * @throws Exception Apache POI 解析响应工作簿失败时抛出
     */
    @Test
    void exportsAllRowsAcrossTwoBoundedPages() throws Exception
    {
        WorkflowStartableProcessQueryDto filter =
                new WorkflowStartableProcessQueryDto("leave", "请假", "hr");
        WorkflowStartableDefinitionView row = definitionRow();
        when(processQueryService.listStartable(filter, 1, 200)).thenReturn(
                new WorkflowPageResult<>(Collections.nCopies(200, row), 201));
        when(processQueryService.listStartable(filter, 2, 200)).thenReturn(
                new WorkflowPageResult<>(List.of(row), 201));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.startExport(filter, response);

        assertThat(response.getContentType()).startsWith(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.getContentAsByteArray()).isNotEmpty();
        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(response.getContentAsByteArray())))
        {
            // 标题行之外必须保留两个分页返回的全部 201 条正式业务记录。
            assertThat(workbook.getSheetAt(0).getPhysicalNumberOfRows()).isGreaterThanOrEqualTo(202);
        }
        verify(processQueryService).listStartable(filter, 1, 200);
        verify(processQueryService).listStartable(filter, 2, 200);
    }

    /**
     * 验证实例运维导出沿用管理员筛选条件、调用专用查询并真实生成 Excel。
     *
     * @return 无返回值；导出改走本人列表、丢失筛选或未写出工作簿时测试失败
     * @throws Exception Apache POI 解析响应工作簿失败时抛出
     */
    @Test
    void exportsManagedProcessesThroughDedicatedQuery() throws Exception
    {
        WorkflowManagedProcessQueryDto filter = new WorkflowManagedProcessQueryDto(
                "instance-9", "leave", "请假", "hr", "business-9", "9",
                Instant.parse("2026-07-25T08:00:00Z"),
                Instant.parse("2026-07-26T08:00:00Z"));
        when(processQueryService.listManaged(filter, 1, 200)).thenReturn(
                new WorkflowPageResult<>(List.of(managedRow()), 1));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.managedExport(filter, response);

        assertThat(response.getContentType()).startsWith(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(response.getContentAsByteArray())))
        {
            assertThat(workbook.getSheetAt(0).getPhysicalNumberOfRows()).isGreaterThanOrEqualTo(2);
        }
        verify(processQueryService).listManaged(filter, 1, 200);
    }

    /**
     * 验证导出总量超过一万条时在读取后续分页和写响应前明确拒绝。
     *
     * @return 无返回值；未返回 400 或错误进入 Excel 写出时测试失败
     */
    @Test
    void rejectsExportAboveTenThousandRows()
    {
        WorkflowCopyQueryDto filter = new WorkflowCopyQueryDto(
                null, null, null, null, null, null, null, null);
        when(processQueryService.listCopies(filter, 1, 200)).thenReturn(
                new WorkflowPageResult<>(List.of(), 10_001));

        assertThatThrownBy(() -> controller.copyExport(filter, new MockHttpServletResponse()))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /**
     * 验证多页导出期间 total 漂移时返回冲突，防止生成内容不完整却看似成功的文件。
     *
     * @return 无返回值；漂移未返回 409 时测试失败
     */
    @Test
    void rejectsExportWhenPageTotalsDrift()
    {
        WorkflowStartableProcessQueryDto filter =
                new WorkflowStartableProcessQueryDto("leave", null, null);
        WorkflowStartableDefinitionView row = definitionRow();
        when(processQueryService.listStartable(filter, 1, 200)).thenReturn(
                new WorkflowPageResult<>(Collections.nCopies(200, row), 201));
        when(processQueryService.listStartable(filter, 2, 200)).thenReturn(
                new WorkflowPageResult<>(List.of(row), 202));

        assertThatThrownBy(() -> controller.startExport(filter, new MockHttpServletResponse()))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(HttpStatus.CONFLICT));
    }

    /**
     * 验证同一请求中的新旧时间参数不一致时返回 400，且不执行任何领域查询。
     *
     * @return 无返回值；冲突值被静默覆盖或进入服务层时测试失败
     */
    @Test
    void rejectsConflictingModernAndLegacyDateValues()
    {
        WorkflowOwnedProcessQueryDto filter = new WorkflowOwnedProcessQueryDto(
                null, null, null, null, Instant.parse("2026-07-25T08:00:00Z"), null);

        assertThatThrownBy(() -> controller.ownProcessList(filter,
                LocalDateTime.parse("2026-07-25T17:00:00"), null, 1, 10))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(processQueryService);
    }

    /**
     * 验证原有流程路径及两个管理员运维入口的权限码和写操作审计注解保持稳定。
     *
     * @return 无返回值；任一路径、权限、事务或日志契约漂移时测试失败
     * @throws NoSuchMethodException Controller 方法签名不存在时抛出
     */
    @Test
    void keepsLegacyEndpointSecurityAndAuditContracts() throws NoSuchMethodException
    {
        RequestMapping controllerMapping = WfProcessController.class
                .getAnnotation(RequestMapping.class);
        assertThat(controllerMapping.value()).containsExactly("/workflow/process");

        assertGetContract("startProcessList",
                new Class<?>[] { WorkflowStartableProcessQueryDto.class, int.class, int.class },
                "/list", "@ss.hasPermi('workflow:process:startList')", true);
        assertGetContract("ownProcessList",
                new Class<?>[] { WorkflowOwnedProcessQueryDto.class, LocalDateTime.class,
                        LocalDateTime.class, int.class, int.class },
                "/ownList", "@ss.hasPermi('workflow:process:ownList')", true);
        assertGetContract("managedProcessList",
                new Class<?>[] { WorkflowManagedProcessQueryDto.class, int.class, int.class },
                "/manageList", "@ss.hasPermi('workflow:process:manageList')", true);
        assertGetContract("todoProcessList",
                new Class<?>[] { WorkflowAssignedTaskQueryDto.class, LocalDateTime.class,
                        LocalDateTime.class, int.class, int.class },
                "/todoList", "@ss.hasPermi('workflow:process:todoList')", true);
        assertGetContract("claimProcessList",
                new Class<?>[] { WorkflowClaimableTaskQueryDto.class, LocalDateTime.class,
                        LocalDateTime.class, int.class, int.class },
                "/claimList", "@ss.hasPermi('workflow:process:claimList')", true);
        assertGetContract("finishedProcessList",
                new Class<?>[] { WorkflowCompletedTaskQueryDto.class, LocalDateTime.class,
                        LocalDateTime.class, int.class, int.class },
                "/finishedList", "@ss.hasPermi('workflow:process:finishedList')", true);
        assertGetContract("copyProcessList",
                new Class<?>[] { WorkflowCopyQueryDto.class, int.class, int.class },
                "/copyList", "@ss.hasPermi('workflow:process:copyList')", true);

        assertExportContract("startExport",
                new Class<?>[] { WorkflowStartableProcessQueryDto.class,
                        HttpServletResponse.class },
                "/startExport", "@ss.hasPermi('workflow:process:startExport')");
        assertExportContract("ownExport",
                new Class<?>[] { WorkflowOwnedProcessQueryDto.class, LocalDateTime.class,
                        LocalDateTime.class, HttpServletResponse.class },
                "/ownExport", "@ss.hasPermi('workflow:process:ownExport')");
        assertExportContract("managedExport",
                new Class<?>[] { WorkflowManagedProcessQueryDto.class,
                        HttpServletResponse.class },
                "/manageExport", "@ss.hasPermi('workflow:process:manageExport')");
        assertExportContract("todoExport",
                new Class<?>[] { WorkflowAssignedTaskQueryDto.class, LocalDateTime.class,
                        LocalDateTime.class, HttpServletResponse.class },
                "/todoExport", "@ss.hasPermi('workflow:process:todoExport')");
        assertExportContract("claimExport",
                new Class<?>[] { WorkflowClaimableTaskQueryDto.class, LocalDateTime.class,
                        LocalDateTime.class, HttpServletResponse.class },
                "/claimExport", "@ss.hasPermi('workflow:process:claimExport')");
        assertExportContract("finishedExport",
                new Class<?>[] { WorkflowCompletedTaskQueryDto.class, LocalDateTime.class,
                        LocalDateTime.class, HttpServletResponse.class },
                "/finishedExport", "@ss.hasPermi('workflow:process:finishedExport')");
        assertExportContract("copyExport",
                new Class<?>[] { WorkflowCopyQueryDto.class, HttpServletResponse.class },
                "/copyExport", "@ss.hasPermi('workflow:process:copyExport')");

        assertGetContract("getForm",
                new Class<?>[] { String.class, String.class, String.class },
                "/getProcessForm", "@ss.hasPermi('workflow:process:start')", false);
        assertGetContract("getBpmnXml", new Class<?>[] { String.class, String.class },
                "/bpmnXml/{processDefId}",
                "@ss.hasAnyPermi('workflow:process:startList,workflow:process:query')", false);
        assertGetContract("detail", new Class<?>[] { String.class, String.class },
                "/detail", "@ss.hasPermi('workflow:process:query')", false);

        Method start = WfProcessController.class.getDeclaredMethod("start",
                String.class, Map.class);
        assertThat(start.getAnnotation(PostMapping.class).value())
                .containsExactly("/start/{processDefId}");
        assertThat(start.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@ss.hasPermi('workflow:process:start')");
        assertThat(start.getAnnotation(Log.class).businessType())
                .isEqualTo(BusinessType.INSERT);

        Method deleteHistory = WfProcessController.class.getDeclaredMethod("deleteHistory",
                String[].class);
        assertThat(deleteHistory.getAnnotation(DeleteMapping.class).value())
                .containsExactly("/instance/{instanceIds}");
        assertThat(deleteHistory.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("@ss.hasPermi('workflow:process:remove')");
        assertThat(deleteHistory.getAnnotation(Log.class).businessType())
                .isEqualTo(BusinessType.DELETE);

        List<String> mappedPaths = mappedPaths();
        assertThat(mappedPaths).contains(
                "/list", "/ownList", "/todoList", "/claimList", "/finishedList",
                "/copyList", "/manageList", "/startExport", "/ownExport",
                "/manageExport", "/todoExport",
                "/claimExport", "/finishedExport", "/copyExport", "/getProcessForm",
                "/start/{processDefId}", "/instance/{instanceIds}",
                "/bpmnXml/{processDefId}", "/detail");
    }

    /**
     * 核对单个 GET 方法的路径、权限和可选 ModelAttribute 绑定契约。
     *
     * @param methodName String，Controller 方法名
     * @param parameterTypes Class&lt;?&gt;[]，方法参数类型
     * @param path String，期望的相对路径
     * @param permissionExpression String，期望的 Spring Security 权限表达式
     * @param modelAttribute boolean，首个参数是否必须使用 ModelAttribute
     * @return 无返回值；任一注解契约不匹配时测试失败
     * @throws NoSuchMethodException Controller 方法签名不存在时抛出
     */
    private void assertGetContract(String methodName, Class<?>[] parameterTypes, String path,
            String permissionExpression, boolean modelAttribute) throws NoSuchMethodException
    {
        Method method = WfProcessController.class.getDeclaredMethod(methodName, parameterTypes);
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo(permissionExpression);
        if (modelAttribute)
        {
            assertThat(method.getParameters()[0].isAnnotationPresent(ModelAttribute.class)).isTrue();
        }
    }

    /**
     * 使用 Jackson 3 创建 Controller 级 JSON 响应测试链路。
     *
     * @return MockMvc，使用 Jackson 3 原生 JsonNode 的独立 MVC 实例
     */
    private MockMvc workflowJsonMockMvc()
    {
        JsonMapper mapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

    /**
     * 核对单个导出方法的路径、权限、只读事务、审计日志和筛选参数绑定契约。
     *
     * @param methodName String，Controller 方法名
     * @param parameterTypes Class&lt;?&gt;[]，筛选 record、兼容时间和响应参数类型
     * @param path String，期望的相对路径
     * @param permissionExpression String，期望的 Spring Security 权限表达式
     * @return 无返回值；任一注解契约不匹配时测试失败
     * @throws NoSuchMethodException Controller 方法签名不存在时抛出
     */
    private void assertExportContract(String methodName, Class<?>[] parameterTypes, String path,
            String permissionExpression) throws NoSuchMethodException
    {
        Method method = WfProcessController.class.getDeclaredMethod(methodName, parameterTypes);
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        Log log = method.getAnnotation(Log.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(path);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo(permissionExpression);
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(log).isNotNull();
        assertThat(log.businessType()).isEqualTo(BusinessType.EXPORT);
        assertThat(method.getParameters()[0].isAnnotationPresent(ModelAttribute.class)).isTrue();
    }

    /**
     * 收集 Controller 显式声明的 GET、POST 和 DELETE 相对路径，用于防止遗漏路由。
     *
     * @return List&lt;String&gt;，全部显式 GET/POST 相对路径
     */
    private List<String> mappedPaths()
    {
        List<String> paths = new ArrayList<>();
        for (Method method : WfProcessController.class.getDeclaredMethods())
        {
            GetMapping getMapping = method.getAnnotation(GetMapping.class);
            if (getMapping != null)
            {
                paths.addAll(List.of(getMapping.value()));
            }
            PostMapping postMapping = method.getAnnotation(PostMapping.class);
            if (postMapping != null)
            {
                paths.addAll(List.of(postMapping.value()));
            }
            DeleteMapping deleteMapping = method.getAnnotation(DeleteMapping.class);
            if (deleteMapping != null)
            {
                paths.addAll(List.of(deleteMapping.value()));
            }
        }
        return paths;
    }

    /**
     * 创建可同时用于分页协议和真实 Excel 导出的流程定义行。
     *
     * @return WorkflowStartableDefinitionView，字段完整的不可变定义视图
     */
    private WorkflowStartableDefinitionView definitionRow()
    {
        return new WorkflowStartableDefinitionView("definition-1", "leave", "请假流程",
                "hr", 3, "deployment-1", Instant.parse("2026-07-25T08:00:00Z"));
    }

    /**
     * 创建可同时用于管理员分页协议和真实 Excel 导出的跨用户实例行。
     *
     * @return WorkflowManagedProcessView，字段完整且含发起人名称和活动节点的运维视图
     */
    private WorkflowManagedProcessView managedRow()
    {
        return new WorkflowManagedProcessView("instance-9", "definition-9", "leave",
                "请假流程", 3, "hr", "deployment-9", "business-9", "9",
                "跨用户发起人", Instant.parse("2026-07-25T09:00:00Z"), null,
                null, "running", List.of("部门审批"));
    }
}
