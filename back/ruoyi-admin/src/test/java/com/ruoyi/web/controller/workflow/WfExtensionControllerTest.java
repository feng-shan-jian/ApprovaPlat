package com.ruoyi.web.controller.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.ruoyi.flowable.domain.dto.WorkflowExtensionCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowExtensionVersionCreateRequest;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionOptionView;
import com.ruoyi.flowable.domain.vo.WorkflowExtensionManagementView;
import com.ruoyi.flowable.domain.vo.WorkflowInstalledJavaHandlerView;
import com.ruoyi.flowable.service.model.WorkflowExtensionRegistryService;

/**
 * WfExtensionController 的响应、校验和服务调用契约测试。
 */
class WfExtensionControllerTest
{
    private WorkflowExtensionRegistryService extensionService;
    private MockMvc mockMvc;

    /**
     * 创建独立服务替身和真实 Spring MVC 参数绑定链路。
     * @return 无返回值；初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        extensionService = mock(WorkflowExtensionRegistryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WfExtensionController(extensionService)).build();
    }

    /**
     * 验证设计选项和已安装处理器均返回服务端真实注册表字段。
     * @return 无返回值；只读响应协议漂移时测试失败
     * @throws Exception MockMvc 执行请求失败
     */
    @Test
    void returnsJavaOptionsAndInstalledHandlers() throws Exception
    {
        WorkflowExtensionOptionView option = new WorkflowExtensionOptionView(
                1L, "approva.set-variable", "设置流程变量", "JAVA", 2L, 1,
                "SET_VARIABLE", "{\"type\":\"object\"}", "0".repeat(64));
        when(extensionService.listJavaOptions()).thenReturn(List.of(option));
        WorkflowExtensionOptionView celOption = new WorkflowExtensionOptionView(
                2L, "approva.cel-expression", "CEL 表达式", "CEL", 3L, 1,
                "CEL_EXPRESSION_V1", "{\"type\":\"object\"}", "1".repeat(64));
        when(extensionService.listCelOptions()).thenReturn(List.of(celOption));
        WorkflowExtensionOptionView httpOption = new WorkflowExtensionOptionView(
                3L, "approva.http-connector", "HTTP 连接器", "HTTP", 4L, 1,
                "HTTP_CONNECTOR_V1", "{\"type\":\"object\"}", "2".repeat(64));
        when(extensionService.listHttpOptions()).thenReturn(List.of(httpOption));
        WorkflowExtensionOptionView formFieldOption = new WorkflowExtensionOptionView(
                4L, "approva.form.textarea", "多行文本", "FORM_FIELD", 5L, 1,
                "FORM_FIELD_TEXTAREA_V1", "{\"type\":\"object\"}", "3".repeat(64));
        when(extensionService.listFormFieldOptions()).thenReturn(List.of(formFieldOption));
        when(extensionService.listManagement()).thenReturn(List.of(
                new WorkflowExtensionManagementView(1L, "approva.set-variable",
                        "设置流程变量", "JAVA", "ENABLED", "内置扩展", 2L, 1,
                        "SET_VARIABLE", "0".repeat(64), new Date(0L))));
        when(extensionService.listInstalledJavaHandlers()).thenReturn(List.of(
                new WorkflowInstalledJavaHandlerView("SET_VARIABLE", "设置流程变量",
                        "{\"type\":\"object\"}")));

        mockMvc.perform(get("/workflow/extension/options/java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].extensionKey").value("approva.set-variable"))
                .andExpect(jsonPath("$.data[0].versionNo").value(1));
        mockMvc.perform(get("/workflow/extension/installed-handlers/java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].implementationKey").value("SET_VARIABLE"));
        mockMvc.perform(get("/workflow/extension/options/cel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].extensionKey").value("approva.cel-expression"))
                .andExpect(jsonPath("$.data[0].implementationKey").value("CEL_EXPRESSION_V1"));
        mockMvc.perform(get("/workflow/extension/options/http"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].extensionKey").value("approva.http-connector"))
                .andExpect(jsonPath("$.data[0].implementationKey").value("HTTP_CONNECTOR_V1"));
        mockMvc.perform(get("/workflow/extension/options/form-field"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].extensionKey").value("approva.form.textarea"))
                .andExpect(jsonPath("$.data[0].implementationKey")
                        .value("FORM_FIELD_TEXTAREA_V1"));
        mockMvc.perform(get("/workflow/extension/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ENABLED"))
                .andExpect(jsonPath("$.data[0].implementationKey").value("SET_VARIABLE"));
    }

    /**
     * 验证创建目录和发布版本使用强类型请求并返回数据库生成主键。
     * @return 无返回值；写入响应或服务参数漂移时测试失败
     * @throws Exception MockMvc 执行请求失败
     */
    @Test
    void createsDirectoryAndPublishesVersion() throws Exception
    {
        WorkflowExtensionCreateRequest createRequest = new WorkflowExtensionCreateRequest(
                "approva.route-marker", "路由标记", "JAVA", "写入受控路由标记");
        WorkflowExtensionVersionCreateRequest versionRequest =
                new WorkflowExtensionVersionCreateRequest("SET_VARIABLE");
        when(extensionService.createExtension(createRequest)).thenReturn(11L);
        when(extensionService.createVersion(11L, versionRequest)).thenReturn(21L);
        WorkflowExtensionVersionCreateRequest celVersionRequest =
                new WorkflowExtensionVersionCreateRequest("CEL_EXPRESSION_V1");
        when(extensionService.createVersion(12L, celVersionRequest)).thenReturn(22L);
        WorkflowExtensionVersionCreateRequest httpVersionRequest =
                new WorkflowExtensionVersionCreateRequest("HTTP_CONNECTOR_V1");
        when(extensionService.createVersion(13L, httpVersionRequest)).thenReturn(23L);

        mockMvc.perform(post("/workflow/extension")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extensionKey\":\"approva.route-marker\"," +
                                "\"extensionName\":\"路由标记\",\"extensionType\":\"JAVA\"," +
                                "\"description\":\"写入受控路由标记\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.extensionId").value(11));
        mockMvc.perform(post("/workflow/extension/{extensionId}/versions", 11L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"implementationKey\":\"SET_VARIABLE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionId").value(21));
        mockMvc.perform(post("/workflow/extension/{extensionId}/versions", 12L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"implementationKey\":\"CEL_EXPRESSION_V1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionId").value(22));
        mockMvc.perform(post("/workflow/extension/{extensionId}/versions", 13L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"implementationKey\":\"HTTP_CONNECTOR_V1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionId").value(23));

        verify(extensionService).createExtension(createRequest);
        verify(extensionService).createVersion(11L, versionRequest);
        verify(extensionService).createVersion(12L, celVersionRequest);
        verify(extensionService).createVersion(13L, httpVersionRequest);
    }

    /**
     * 验证启停和删除接口把目录主键与显式目标状态交给领域服务。
     * @return 无返回值；状态请求绑定漂移时测试失败
     * @throws Exception MockMvc 执行请求失败
     */
    @Test
    void changesDirectoryStatusAndRemovesDirectory() throws Exception
    {
        mockMvc.perform(put("/workflow/extension/{extensionId}/status", 11L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/workflow/extension/{extensionId}", 11L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(extensionService).changeStatus(11L, false);
        verify(extensionService).removeExtension(11L);
    }

    /**
     * 验证非法稳定键、类型、处理器键、主键和缺失状态均被 MVC 参数门禁拒绝。
     * @return 无返回值；非法请求进入服务层时测试失败
     * @throws Exception MockMvc 执行请求失败
     */
    @Test
    void rejectsInvalidManagementRequests() throws Exception
    {
        mockMvc.perform(post("/workflow/extension")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extensionKey\":\"bad key\",\"extensionName\":\"扩展\"," +
                                "\"extensionType\":\"HTTP\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/workflow/extension/{extensionId}/versions", 0L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"implementationKey\":\"bad.bean\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/workflow/extension/{extensionId}/status", 11L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
