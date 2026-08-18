package com.ruoyi.web.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.common.constant.HttpStatus;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.flowable.domain.dto.WorkflowTaskCompleteRequest;
import com.ruoyi.flowable.domain.dto.WorkflowMultiInstanceAdjustmentRequest;
import com.ruoyi.flowable.domain.dto.WorkflowTaskReturnRequest;
import com.ruoyi.flowable.domain.dto.WorkflowApplicationResubmitRequest;
import com.ruoyi.flowable.domain.vo.WorkflowMultiInstanceMemberView;
import com.ruoyi.flowable.domain.vo.WorkflowMultiInstanceStateView;
import com.ruoyi.flowable.service.task.WorkflowMultiInstanceService;
import com.ruoyi.flowable.service.task.WorkflowTaskActionService;
import com.ruoyi.flowable.service.task.WorkflowTaskLifecycleService;
import com.ruoyi.flowable.service.task.WorkflowTaskReadService;
import com.ruoyi.framework.web.exception.GlobalExceptionHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.StringNode;
import tools.jackson.databind.json.JsonMapper;

class WfTaskControllerTest
{
    private WorkflowTaskActionService taskActionService;

    private WorkflowTaskLifecycleService taskLifecycleService;

    private WorkflowTaskReadService taskReadService;

    private WorkflowMultiInstanceService multiInstanceService;

    private MockMvc mockMvc;

    /**
     * 为每个测试创建三个独立任务服务替身和真实 Spring MVC 参数绑定链路。
     *
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        taskActionService = mock(WorkflowTaskActionService.class);
        taskLifecycleService = mock(WorkflowTaskLifecycleService.class);
        taskReadService = mock(WorkflowTaskReadService.class);
        multiInstanceService = mock(WorkflowMultiInstanceService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WfTaskController(taskActionService,
                taskLifecycleService, taskReadService, multiInstanceService)).build();
    }

    /**
     * 验证全部任务写接口均通过真实 JSON 绑定和 Bean Validation 拒绝缺失必填字段的请求。
     *
     * @return 无返回值；任一接口未返回 HTTP 400 或调用了业务服务时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void rejectsInvalidBodiesForAllTaskActions() throws Exception
    {
        mockMvc.perform(post("/workflow/task/claim").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workflow/task/unClaim").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workflow/task/resolve").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workflow/task/delegate").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workflow/task/transfer").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workflow/task/stopProcess").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workflow/task/revokeProcess").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workflow/task/complete").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workflow/task/reject").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workflow/task/return").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workflow/task/resubmit").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workflow/task/multiInstance/adjust")
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(taskActionService);
        verifyNoInteractions(taskLifecycleService);
        verifyNoInteractions(taskReadService);
        verifyNoInteractions(multiInstanceService);
    }

    /**
     * 验证退回和重新提交 JSON 契约不再接收用户选择的目标节点或审批意见。
     *
     * @return 无返回值；字段绑定或业务服务入参漂移时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void bindsDirectReturnAndFormOnlyResubmitContracts() throws Exception
    {
        mockMvc.perform(post("/workflow/task/return")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"taskId":"task-return","comment":"请修改资料","copyUserIds":[9]}
                                """))
                .andExpect(status().isOk());
        ArgumentCaptor<WorkflowTaskReturnRequest> returnCaptor =
                ArgumentCaptor.forClass(WorkflowTaskReturnRequest.class);
        verify(taskLifecycleService).returnTask(returnCaptor.capture());
        assertThat(returnCaptor.getValue().taskId()).isEqualTo("task-return");
        assertThat(returnCaptor.getValue().comment()).isEqualTo("请修改资料");
        assertThat(returnCaptor.getValue().copyUserIds()).containsExactly(9L);

        mockMvc.perform(post("/workflow/task/resubmit")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"taskId":"task-returned","variables":{"amount":1200}}
                                """))
                .andExpect(status().isOk());
        ArgumentCaptor<WorkflowApplicationResubmitRequest> resubmitCaptor =
                ArgumentCaptor.forClass(WorkflowApplicationResubmitRequest.class);
        verify(taskLifecycleService).resubmitApplication(resubmitCaptor.capture());
        assertThat(resubmitCaptor.getValue().taskId()).isEqualTo("task-returned");
        assertThat(resubmitCaptor.getValue().variables()).containsEntry("amount", 1200);
    }

    /**
     * 验证完成接口把动态多实例 expectedRevision 通过真实 JSON 绑定传给生命周期服务。
     *
     * @return 无返回值；字段丢失、类型漂移或服务未收到请求时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void bindsDynamicCompletionRevisionFromJson() throws Exception
    {
        mockMvc.perform(post("/workflow/task/complete")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"taskId":"task-8","comment":"会签通过","variables":{},
                                 "copyUserIds":[],"nextUserIds":[],"expectedRevision":3}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<WorkflowTaskCompleteRequest> requestCaptor =
                ArgumentCaptor.forClass(WorkflowTaskCompleteRequest.class);
        verify(taskLifecycleService).completeTask(requestCaptor.capture());
        assertThat(requestCaptor.getValue().expectedRevision()).isEqualTo(3L);
    }

    /**
     * 验证完成接口在进入领域服务前拒绝负数及超出 int 上限的 expectedRevision。
     *
     * @return 无返回值；越界 revision 被截断、接受或触发领域写服务时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void rejectsOutOfRangeDynamicCompletionRevisionDuringJsonValidation() throws Exception
    {
        for (String revision : List.of("-1", "2147483648"))
        {
            mockMvc.perform(post("/workflow/task/complete")
                            .contentType(APPLICATION_JSON)
                            .content("""
                                    {"taskId":"task-8","comment":"会签通过","variables":{},
                                     "copyUserIds":[],"nextUserIds":[],"expectedRevision":%s}
                                    """.formatted(revision)))
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(taskLifecycleService);
    }

    /**
     * 验证变量接口调用受控读取服务并保持若依 JSON data 响应。
     *
     * @return 无返回值；服务未调用或响应类型错误时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void returnsAuthorizedProcessVariablesThroughAjaxResult() throws Exception
    {
        Map<String, JsonNode> variables = new LinkedHashMap<>();
        variables.put("reason", StringNode.valueOf("真实变量"));
        variables.put("files", JsonNodeFactory.instance.arrayNode().add("attachment-uuid-1"));
        when(taskReadService.getProcessVariables("task-1")).thenReturn(variables);

        workflowJsonMockMvc().perform(get("/workflow/task/processVariables/task-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(HttpStatus.SUCCESS))
                .andExpect(jsonPath("$.data.reason").value("真实变量"))
                .andExpect(jsonPath("$.data.files[0]").value("attachment-uuid-1"))
                .andExpect(jsonPath("$.data.reason.nodeType").doesNotExist());

        verify(taskReadService).getProcessVariables("task-1");
    }

    /**
     * 验证活动任务对象授权拒绝会通过真实 Controller 和全局异常协议返回业务 403。
     *
     * @return 无返回值；Controller 吞掉授权异常或伪造成功响应时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void returnsObjectAuthorizationFailureForActiveTaskVariables() throws Exception
    {
        ServiceException forbidden = new ServiceException("无权执行当前工作流操作",
                HttpStatus.FORBIDDEN);
        when(taskReadService.getProcessVariables("task-private")).thenThrow(forbidden);
        MockMvc securedMockMvc = MockMvcBuilders.standaloneSetup(new WfTaskController(
                        taskActionService, taskLifecycleService, taskReadService,
                        multiInstanceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        securedMockMvc.perform(get("/workflow/task/processVariables/task-private"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.FORBIDDEN))
                .andExpect(jsonPath("$.msg").value("无权执行当前工作流操作"));

        verify(taskReadService).getProcessVariables("task-private");
    }

    /**
     * 验证流程图接口固定输出 PNG，并通过 no-store 禁止缓存授权结果。
     *
     * @return 无返回值；媒体类型、缓存头或响应字节错误时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void returnsPngDiagramWithNoStoreCachePolicy() throws Exception
    {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        org.mockito.Mockito.when(taskReadService.generateDiagram("instance-1")).thenReturn(png);

        mockMvc.perform(get("/workflow/task/diagram/instance-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(png));
    }

    /**
     * 验证动态多实例状态和调整接口返回真实领域服务的 revision 与成员状态。
     *
     * @return 无返回值；请求绑定、服务调用或 data 结构不一致时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void exposesMultiInstanceStateAndAdjustmentResult() throws Exception
    {
        WorkflowMultiInstanceStateView state = new WorkflowMultiInstanceStateView(
                "ALL", "approveTask", 3,
                List.of(new WorkflowMultiInstanceMemberView(8L, "审批人甲",
                        "task-8", "execution-8", true, false)));
        when(multiInstanceService.getState("task-8")).thenReturn(state);
        when(multiInstanceService.adjust(org.mockito.ArgumentMatchers.any(
                WorkflowMultiInstanceAdjustmentRequest.class))).thenReturn(state);

        mockMvc.perform(get("/workflow/task/multiInstance/task-8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("ALL"))
                .andExpect(jsonPath("$.data.revision").value(3))
                .andExpect(jsonPath("$.data.members[0].userId").value(8));
        mockMvc.perform(post("/workflow/task/multiInstance/adjust")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"taskId":"task-8","action":"ADD",
                                 "expectedRevision":3,"comment":"增加复核人",
                                 "userIds":[9],"targetTaskId":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activityId").value("approveTask"));

        verify(multiInstanceService).getState("task-8");
        verify(multiInstanceService).adjust(org.mockito.ArgumentMatchers.any(
                WorkflowMultiInstanceAdjustmentRequest.class));
    }

    /**
     * 验证动态加签和下一节点指定的资格失败通过真实 HTTP 异常协议返回业务 400。
     *
     * @return 无返回值；错误码、稳定提示或非目标服务调用发生变化时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void returnsApprovalEligibilityFailureForDynamicAssignments() throws Exception
    {
        ServiceException ineligible = new ServiceException(
                "所选用户不存在、已停用或无流程办理权限", HttpStatus.BAD_REQUEST);
        when(multiInstanceService.adjust(org.mockito.ArgumentMatchers.any(
                WorkflowMultiInstanceAdjustmentRequest.class))).thenThrow(ineligible);
        doThrow(ineligible).when(taskLifecycleService).completeTask(
                org.mockito.ArgumentMatchers.any(WorkflowTaskCompleteRequest.class));
        MockMvc advisedMockMvc = MockMvcBuilders.standaloneSetup(new WfTaskController(
                        taskActionService, taskLifecycleService, taskReadService,
                        multiInstanceService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        advisedMockMvc.perform(post("/workflow/task/multiInstance/adjust")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"taskId":"task-8","action":"ADD",
                                 "expectedRevision":3,"comment":"增加复核人",
                                 "userIds":[9],"targetTaskId":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.BAD_REQUEST))
                .andExpect(jsonPath("$.msg").value(
                        "所选用户不存在、已停用或无流程办理权限"));
        advisedMockMvc.perform(post("/workflow/task/complete")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"taskId":"task-8","comment":"提交复核", "variables":{},
                                 "copyUserIds":[],"nextUserIds":[9]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(HttpStatus.BAD_REQUEST))
                .andExpect(jsonPath("$.msg").value(
                        "所选用户不存在、已停用或无流程办理权限"));

        verify(multiInstanceService).adjust(org.mockito.ArgumentMatchers.any(
                WorkflowMultiInstanceAdjustmentRequest.class));
        verify(taskLifecycleService).completeTask(org.mockito.ArgumentMatchers.any(
                WorkflowTaskCompleteRequest.class));
        verifyNoInteractions(taskActionService, taskReadService);
    }

    /**
     * 使用 Jackson 3 创建旧变量接口的真实 JSON 响应测试链路。
     *
     * @return MockMvc，使用 Jackson 3 原生 JsonNode 的独立 MVC 实例
     */
    private MockMvc workflowJsonMockMvc()
    {
        JsonMapper mapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        return MockMvcBuilders.standaloneSetup(new WfTaskController(taskActionService,
                        taskLifecycleService, taskReadService, multiInstanceService))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

}
