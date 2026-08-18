package com.ruoyi.web.controller.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.flowable.domain.WorkflowInstanceState;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceStateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowInstanceTerminateRequest;
import com.ruoyi.flowable.domain.vo.WorkflowInstanceStateView;
import com.ruoyi.flowable.domain.vo.WorkflowInstanceTerminateView;
import com.ruoyi.flowable.service.process.WorkflowProcessInstanceService;

/**
 * WfInstanceController 的枚举请求绑定、双权限入口和审计契约测试。
 */
class WfInstanceControllerTest
{
    private WorkflowProcessInstanceService processInstanceService;

    private MockMvc mockMvc;

    /**
     * 为每个测试创建独立服务替身和真实 Spring MVC 请求绑定链路。
     *
     * @return 无返回值，初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        processInstanceService = mock(WorkflowProcessInstanceService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WfInstanceController(processInstanceService))
                .build();
    }

    /**
     * 验证小写枚举状态请求真实绑定并返回 changed 状态。
     *
     * @return 无返回值，请求 DTO 或响应协议错误时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void bindsLowercaseStateEnumAndReturnsChangeResult() throws Exception
    {
        WorkflowInstanceStateRequest request = new WorkflowInstanceStateRequest(
                "instance-1", WorkflowInstanceState.SUSPENDED);
        when(processInstanceService.updateState(request)).thenReturn(
                new WorkflowInstanceStateView("instance-1",
                        WorkflowInstanceState.SUSPENDED, true));

        mockMvc.perform(post("/workflow/instance/updateState")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instanceId":"instance-1","state":"suspended"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.instanceId").value("instance-1"))
                .andExpect(jsonPath("$.data.state").value("suspended"))
                .andExpect(jsonPath("$.data.changed").value(true));

        verify(processInstanceService).updateState(request);
    }

    /**
     * 验证状态协议只接受精确小写枚举值，拒绝模糊大小写输入。
     *
     * @return 无返回值，非法枚举进入领域服务时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void rejectsNonCanonicalStateValue() throws Exception
    {
        mockMvc.perform(post("/workflow/instance/updateState")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instanceId":"instance-1","state":"SUSPENDED"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(processInstanceService);
    }

    /**
     * 验证取消/终止请求真实绑定并回显领域层确定的最终状态。
     *
     * @return 无返回值，请求字段或响应状态错误时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void delegatesTerminateRequestAndReturnsDomainDecision() throws Exception
    {
        WorkflowInstanceTerminateRequest request = new WorkflowInstanceTerminateRequest(
                "instance-1", "申请失效");
        when(processInstanceService.terminate(request)).thenReturn(
                new WorkflowInstanceTerminateView("instance-1", "canceled", "7", false));

        mockMvc.perform(post("/workflow/instance/terminate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instanceId":"instance-1","reason":"申请失效"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processStatus").value("canceled"))
                .andExpect(jsonPath("$.data.actorUserId").value("7"));

        verify(processInstanceService).terminate(request);
    }

}
