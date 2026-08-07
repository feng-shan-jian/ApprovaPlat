package com.ruoyi.web.controller.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.flowable.domain.WfBpmnEventCode;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnEventNotificationView;
import com.ruoyi.flowable.service.model.WorkflowBpmnEventCodeService;

/**
 * BPMN 错误与升级目录、审计和通知 Controller 的真实 MVC 参数绑定契约。
 */
class WfBpmnEventControllerTest
{
    private WorkflowBpmnEventCodeService eventService;
    private MockMvc mockMvc;

    /**
     * 创建独立领域服务替身和真实 Spring MVC 校验链路。
     * @return void，初始化后可执行事件管理接口测试
     */
    @BeforeEach
    void setUp()
    {
        eventService = mock(WorkflowBpmnEventCodeService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WfBpmnEventController(eventService)).build();
    }

    /**
     * 验证目录清单和设计器选项来自服务层且保留真实编码字段。
     * @return void，响应字段或事件类型参数漂移时失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void listsCodesAndDesignerOptions() throws Exception
    {
        WfBpmnEventCode code = new WfBpmnEventCode();
        code.setEventType("ERROR");
        code.setEventCode("APPROVAL_BUSINESS_ERROR");
        code.setEventName("审批业务错误");
        code.setNotificationPolicy("INITIATOR");
        code.setStatus("ENABLED");
        when(eventService.listManagement()).thenReturn(List.of(code));
        when(eventService.listEnabled("ERROR")).thenReturn(List.of(code));

        mockMvc.perform(get("/workflow/bpmn-event/codes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].eventCode").value("APPROVAL_BUSINESS_ERROR"));
        mockMvc.perform(get("/workflow/bpmn-event/codes/options/ERROR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ENABLED"));
    }

    /**
     * 验证新增、修改、停用、审计和本人通知已读请求完整传递到领域服务。
     * @return void，写入参数或通知越权边界漂移时失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void writesCatalogAndMarksOwnedNotificationRead() throws Exception
    {
        when(eventService.create(org.mockito.ArgumentMatchers.any())).thenReturn(11L);
        when(eventService.myNotifications()).thenReturn(List.of(
                new WorkflowBpmnEventNotificationView(21L, 31L, "审批业务错误",
                        "库存不足", "UNREAD", LocalDateTime.of(2026, 8, 7, 10, 0), null)));
        String body = "{\"eventType\":\"ERROR\",\"eventCode\":\"APPROVAL_BUSINESS_ERROR\","
                + "\"eventName\":\"审批业务错误\",\"notificationPolicy\":\"INITIATOR\"}";

        mockMvc.perform(post("/workflow/bpmn-event/codes")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventCodeId").value(11));
        mockMvc.perform(put("/workflow/bpmn-event/codes/{eventCodeId}", 11L)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(put("/workflow/bpmn-event/codes/{eventCodeId}/status", 11L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/workflow/bpmn-event/notifications/my"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].notificationId").value(21));
        mockMvc.perform(put("/workflow/bpmn-event/notifications/{notificationId}/read", 21L))
                .andExpect(status().isOk());

        verify(eventService).markNotificationRead(21L);
    }

    /**
     * 验证非法事件类型和缺失启停值在 MVC 层失败关闭。
     * @return void，非法目录请求越过参数校验时失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void rejectsInvalidCatalogRequests() throws Exception
    {
        mockMvc.perform(put("/workflow/bpmn-event/codes/{eventCodeId}/status", 11L)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
