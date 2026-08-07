package com.ruoyi.web.controller.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.flowable.domain.dto.WorkflowDesignerPreferenceRequest;
import com.ruoyi.flowable.domain.vo.WorkflowDesignerPreferenceView;
import com.ruoyi.flowable.service.model.WorkflowDesignerPreferenceService;

/**
 * WfDesignerController 的正式偏好响应和参数校验测试。
 */
class WfDesignerControllerTest
{
    private WorkflowDesignerPreferenceService preferenceService;

    private MockMvc mockMvc;

    /**
     * 为每个测试创建独立服务替身和真实 Spring MVC 序列化链路。
     * @return void，无返回值，初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        preferenceService = mock(WorkflowDesignerPreferenceService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WfDesignerController(preferenceService)).build();
    }

    /**
     * 验证查询接口返回服务层正式偏好，不暴露用户主键和数据库字段名。
     * @return void，无返回值，响应协议漂移时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void returnsCurrentPreference() throws Exception
    {
        WorkflowDesignerPreferenceView view = new WorkflowDesignerPreferenceView(
                "DARK", false, true, true, false, true);
        when(preferenceService.getCurrentPreference()).thenReturn(view);

        mockMvc.perform(get("/workflow/designer/preference"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value("DARK"))
                .andExpect(jsonPath("$.data.gridEnabled").value(false))
                .andExpect(jsonPath("$.data.propertiesCollapsed").value(true));

        verify(preferenceService).getCurrentPreference();
    }

    /**
     * 验证保存接口使用完整强类型偏好，并返回数据库回读后的服务结果。
     * @return void，无返回值，参数或响应协议漂移时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void savesCompletePreference() throws Exception
    {
        WorkflowDesignerPreferenceRequest request = new WorkflowDesignerPreferenceRequest(
                "LIGHT", true, false, true, false, false);
        WorkflowDesignerPreferenceView saved = new WorkflowDesignerPreferenceView(
                "LIGHT", true, false, true, false, false);
        when(preferenceService.saveCurrentPreference(request)).thenReturn(saved);

        mockMvc.perform(put("/workflow/designer/preference")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"LIGHT\",\"gridEnabled\":true,"
                                + "\"minimapEnabled\":false,\"lintEnabled\":true,"
                                + "\"tokenSimulationEnabled\":false,"
                                + "\"propertiesCollapsed\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value("LIGHT"))
                .andExpect(jsonPath("$.data.minimapEnabled").value(false));

        verify(preferenceService).saveCurrentPreference(request);
    }
}
