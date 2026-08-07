package com.ruoyi.web.controller.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.springframework.http.MediaType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.flowable.service.IWfCategoryService;
import com.ruoyi.flowable.service.model.WorkflowModelService;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnValidationIssue;
import com.ruoyi.flowable.domain.vo.WorkflowBpmnValidationReport;

/**
 * WfModelController 的 BPMN 资源响应协议测试。
 */
class WfModelControllerTest
{
    private WorkflowModelService modelService;

    private MockMvc mockMvc;

    /**
     * 为每个测试创建独立服务替身和真实 Spring MVC 序列化链路。
     *
     * @return 无返回值，初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        modelService = mock(WorkflowModelService.class);
        IWfCategoryService categoryService = mock(IWfCategoryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WfModelController(modelService, categoryService)).build();
    }

    /**
     * 验证字符串类型 BPMN XML 固定写入 data，不能被重载为成功消息。
     *
     * @return 无返回值，响应字段或服务调用漂移时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void returnsBpmnXmlAsResponseData() throws Exception
    {
        String modelId = "model-1";
        String bpmnXml = "<definitions><process id=\"leave\" /></definitions>";
        when(modelService.getBpmnXml(modelId)).thenReturn(bpmnXml);

        mockMvc.perform(get("/workflow/model/bpmnXml/{modelId}", modelId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("操作成功"))
                .andExpect(jsonPath("$.data").value(bpmnXml));

        verify(modelService).getBpmnXml(modelId);
    }

    /**
     * 验证显式校验接口完整返回结构化问题，不把无效 BPMN 伪装为 HTTP 异常。
     *
     * @return void，无返回值，响应字段或服务调用漂移时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void returnsStructuredBpmnValidationReport() throws Exception
    {
        String bpmnXml = "<definitions><process id=\"leave\" /></definitions>";
        WorkflowBpmnValidationReport report = new WorkflowBpmnValidationReport(false,
                List.of(new WorkflowBpmnValidationIssue("BPMN_PARSE_ERROR", "ERROR",
                        "leave", "流程缺少开始事件")));
        when(modelService.validateBpmn(bpmnXml)).thenReturn(report);

        mockMvc.perform(post("/workflow/model/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bpmnXml\":\"<definitions><process id=\\\"leave\\\" /></definitions>\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.issues[0].code").value("BPMN_PARSE_ERROR"))
                .andExpect(jsonPath("$.data.issues[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.data.issues[0].elementId").value("leave"));

        verify(modelService).validateBpmn(bpmnXml);
    }
}
