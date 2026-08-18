package com.ruoyi.web.controller.workflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.flowable.domain.dto.WorkflowRuntimeEventRequest;
import com.ruoyi.flowable.domain.dto.WorkflowCollaborationMessageRequest;
import com.ruoyi.flowable.domain.vo.WorkflowCollaborationMessageView;
import com.ruoyi.flowable.service.process.WorkflowCollaborationMessageService;
import com.ruoyi.flowable.domain.vo.WorkflowRuntimeEventView;
import com.ruoyi.flowable.service.process.WorkflowRuntimeEventService;

/**
 * 匿名运行事件入口的注解、参数绑定和 Token 强认证委托契约测试。
 */
class WfRuntimeEventControllerTest
{
    private WorkflowRuntimeEventService runtimeEventService;
    private WorkflowCollaborationMessageService collaborationMessageService;
    private MockMvc mockMvc;

    /**
     * 创建独立服务替身和真实 Spring MVC 参数校验链路。
     * @return void，初始化后可验证三个发布入口
     */
    @BeforeEach
    void setUp()
    {
        runtimeEventService = mock(WorkflowRuntimeEventService.class);
        collaborationMessageService = mock(WorkflowCollaborationMessageService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WfRuntimeEventController(runtimeEventService, collaborationMessageService)).build();
    }

    /**
     * 验证缺失集成 Token 时 Controller 仍调用领域认证，不得自行建立可信身份。
     * @return void，Token 空值未传入服务或响应协议漂移时失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void delegatesMissingHeaderToMandatoryDomainAuthentication() throws Exception
    {
        WorkflowRuntimeEventView view = new WorkflowRuntimeEventView(
                "5d58c4da-57dd-4f4c-ae67-cea0f9f9b5c1", 8L, "MESSAGE", "approved",
                "PROCESS_INSTANCE", "instance-1", "instance-1", "execution-1",
                "PROCESSED", "EVENT_PROCESSED", "ok", new Date(0), new Date(1));
        when(runtimeEventService.publish(isNull(), org.mockito.ArgumentMatchers.eq("MESSAGE"),
                any(WorkflowRuntimeEventRequest.class))).thenReturn(view);

        mockMvc.perform(post("/workflow/runtime-event/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"5d58c4da-57dd-4f4c-ae67-cea0f9f9b5c1\","
                                + "\"eventName\":\"approved\","
                                + "\"processInstanceId\":\"instance-1\",\"variables\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSED"));

        verify(runtimeEventService).publish(isNull(),
                org.mockito.ArgumentMatchers.eq("MESSAGE"),
                any(WorkflowRuntimeEventRequest.class));
    }

    /**
     * 验证请求头正文和事件类型不经修改地进入领域服务。
     * @return void，Token、类型或请求体绑定漂移时失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void forwardsIntegrationTokenAndFixedEventTypes() throws Exception
    {
        when(runtimeEventService.publish(any(), any(), any())).thenAnswer(invocation ->
        {
            WorkflowRuntimeEventRequest request = invocation.getArgument(2);
            return new WorkflowRuntimeEventView(request.requestId(), 8L,
                    invocation.getArgument(1), request.eventName(), "BUSINESS_KEY",
                    request.businessKey(), "instance", "execution", "PROCESSED",
                    "EVENT_PROCESSED", "ok", new Date(0), new Date(1));
        });
        String body = "{\"requestId\":\"5d58c4da-57dd-4f4c-ae67-cea0f9f9b5c1\","
                + "\"eventName\":\"wait\",\"businessKey\":\"business-1\","
                + "\"variables\":{\"approved\":true}}";

        for (Map.Entry<String, String> endpoint : Map.of(
                "message", "MESSAGE", "signal", "SIGNAL", "receive", "RECEIVE").entrySet())
        {
            mockMvc.perform(post("/workflow/runtime-event/" + endpoint.getKey())
                            .header("X-Integration-Token", "integration-token")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.eventType").value(endpoint.getValue()));
            verify(runtimeEventService).publish(
                    org.mockito.ArgumentMatchers.eq("integration-token"),
                    org.mockito.ArgumentMatchers.eq(endpoint.getValue()),
                    any(WorkflowRuntimeEventRequest.class));
        }
    }

    /** 验证跨流程消息入口把 Token 和完整协作请求交给可靠投递服务。 */
    @Test
    void delegatesCollaborationMessageToReliableService() throws Exception
    {
        WorkflowCollaborationMessageView view = new WorkflowCollaborationMessageView(
                "5d58c4da-57dd-4f4c-ae67-cea0f9f9b5c1", "notice", "source", "target",
                "business-1", null, "instance-1", "execution-1", 1L, "PROCESSED", 1, 5, 0, null, null,
                new Date(0), null, new Date(1));
        when(collaborationMessageService.publish(any(), any(WorkflowCollaborationMessageRequest.class)))
                .thenReturn(view);
        mockMvc.perform(post("/workflow/runtime-event/collaboration/message")
                        .header("X-Integration-Token", "integration-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":\"5d58c4da-57dd-4f4c-ae67-cea0f9f9b5c1\","
                                + "\"messageName\":\"notice\",\"sourceProcessDefinitionKey\":\"source\","
                                + "\"targetProcessDefinitionKey\":\"target\",\"correlationKey\":\"business-1\","
                                + "\"sequenceNo\":1,\"variables\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSED"));
        verify(collaborationMessageService).publish(org.mockito.ArgumentMatchers.eq("integration-token"),
                any(WorkflowCollaborationMessageRequest.class));
    }
}
