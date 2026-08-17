package com.ruoyi.web.controller.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.argThat;
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
import com.ruoyi.flowable.domain.vo.WorkflowModelSaveResult;

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

    /**
     * 验证模型保存请求映射内容基线摘要，并完整返回服务端真实保存结果。
     *
     * @return 无返回值，保存请求字段或响应结果漂移时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void returnsModelVersionAndDigestAfterSave() throws Exception
    {
        String expectedDigest = "a".repeat(64);
        String savedDigest = "b".repeat(64);
        when(modelService.saveModel(argThat(request -> request != null
                && "model-1".equals(request.getModelId())
                && "<definitions/>".equals(request.getBpmnXml())
                && expectedDigest.equals(request.getExpectedBpmnSha256())
                && Boolean.FALSE.equals(request.getNewVersion()))))
                .thenReturn(new WorkflowModelSaveResult("model-1", 3, savedDigest));

        mockMvc.perform(post("/workflow/model/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"model-1\","
                                + "\"bpmnXml\":\"<definitions/>\","
                                + "\"expectedBpmnSha256\":\"" + expectedDigest + "\","
                                + "\"newVersion\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modelId").value("model-1"))
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.bpmnSha256").value(savedDigest));

        verify(modelService).saveModel(argThat(request -> expectedDigest.equals(
                request.getExpectedBpmnSha256())));
    }

    /**
     * 验证非法模型基线摘要由 Web 参数门禁拒绝，不能进入模型保存事务。
     *
     * @return 无返回值，非法摘要进入 Service 时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void rejectsInvalidModelBaselineDigestBeforeService() throws Exception
    {
        mockMvc.perform(post("/workflow/model/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":\"model-1\","
                                + "\"bpmnXml\":\"<definitions/>\","
                                + "\"expectedBpmnSha256\":\"INVALID\","
                                + "\"newVersion\":false}"))
                .andExpect(status().isBadRequest());

        verify(modelService, never()).saveModel(argThat(request -> true));
    }
}
