package com.ruoyi.web.controller.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.flowable.domain.dto.WorkflowConnectorEndpointRequest;
import com.ruoyi.flowable.domain.vo.WorkflowConnectorEndpointView;
import com.ruoyi.flowable.service.model.WorkflowConnectorEndpointService;

/**
 * HTTP 连接器端点 Controller 的参数绑定和响应契约测试。
 */
class WfConnectorControllerTest
{
    private WorkflowConnectorEndpointService endpointService;
    private MockMvc mockMvc;

    /**
     * 创建独立服务替身和真实 Spring MVC 参数校验链路。
     * @return void，初始化后可执行端点接口测试
     */
    @BeforeEach
    void setUp()
    {
        endpointService = mock(WorkflowConnectorEndpointService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WfConnectorController(endpointService)).build();
    }

    /**
     * 验证管理清单和设计器选项返回修订、摘要和外部密钥引用，不含密钥正文。
     * @return void，只读响应字段漂移时测试失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void returnsManagementListAndDesignerOptions() throws Exception
    {
        WorkflowConnectorEndpointView endpoint = new WorkflowConnectorEndpointView(
                1L, "audit-endpoint", "审计回调", "https://api.example.com", "POST",
                "/api", "BEARER", "WORKFLOW_CONNECTOR_SECRET_AUDIT", null,
                1000, 3000, "PUBLIC", 2, "ENABLED", "a".repeat(64), new Date(0L));
        when(endpointService.list()).thenReturn(List.of(endpoint));
        when(endpointService.listOptions()).thenReturn(List.of(endpoint));

        mockMvc.perform(get("/workflow/connector/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].endpointKey").value("audit-endpoint"))
                .andExpect(jsonPath("$.data[0].revisionNo").value(2));
        mockMvc.perform(get("/workflow/connector/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ENABLED"))
                .andExpect(jsonPath("$.data[0].secretRef")
                        .value("WORKFLOW_CONNECTOR_SECRET_AUDIT"));
    }

    /**
     * 验证新增、修订和状态变更把强类型请求完整传给领域服务。
     * @return void，写入口参数或返回主键协议漂移时测试失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void createsUpdatesAndDisablesEndpoint() throws Exception
    {
        WorkflowConnectorEndpointRequest request = request();
        when(endpointService.create(request)).thenReturn(11L);
        when(endpointService.update(11L, request)).thenReturn(2);
        String body = requestJson();

        mockMvc.perform(post("/workflow/connector")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.endpointId").value(11));
        mockMvc.perform(put("/workflow/connector/{endpointId}", 11L)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revisionNo").value(2));
        mockMvc.perform(put("/workflow/connector/{endpointId}/status", 11L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(endpointService).create(request);
        verify(endpointService).update(11L, request);
        verify(endpointService).changeStatus(11L, false);
    }

    /**
     * 验证非法 URL、方法、超时、网络范围、主键和状态在 MVC 层失败关闭。
     * @return void，非法请求进入领域服务时测试失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void rejectsInvalidEndpointRequests() throws Exception
    {
        mockMvc.perform(post("/workflow/connector")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpointKey\":\"bad key\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/workflow/connector/{endpointId}", 0L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpointKey\":\"bad key\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/workflow/connector/{endpointId}/status", 11L)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 创建控制器写入口使用的强类型请求。
     * @return WorkflowConnectorEndpointRequest，字段完整的 Bearer 端点
     */
    private WorkflowConnectorEndpointRequest request()
    {
        return new WorkflowConnectorEndpointRequest("audit-endpoint", "审计回调",
                "https://api.example.com", List.of("POST"), "/api", "BEARER",
                "WORKFLOW_CONNECTOR_SECRET_AUDIT", null, 1000, 3000, "PUBLIC");
    }

    /**
     * 创建与强类型请求等价的 JSON 正文。
     * @return String，可直接提交给 MockMvc 的 JSON
     */
    private String requestJson()
    {
        return "{\"endpointKey\":\"audit-endpoint\",\"endpointName\":\"审计回调\","
                + "\"baseUrl\":\"https://api.example.com\",\"allowedMethods\":[\"POST\"],"
                + "\"pathPrefix\":\"/api\",\"authType\":\"BEARER\","
                + "\"secretRef\":\"WORKFLOW_CONNECTOR_SECRET_AUDIT\","
                + "\"connectTimeoutMs\":1000,\"requestTimeoutMs\":3000,"
                + "\"networkScope\":\"PUBLIC\"}";
    }
}
