package com.ruoyi.web.controller.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.flowable.service.model.WorkflowDeploymentService;

/**
 * WfDeployController 的 BPMN 资源响应协议测试。
 */
class WfDeployControllerTest
{
    private WorkflowDeploymentService deploymentService;

    private MockMvc mockMvc;

    /**
     * 为每个测试创建独立服务替身和真实 Spring MVC 序列化链路。
     *
     * @return 无返回值，初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        deploymentService = mock(WorkflowDeploymentService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WfDeployController(deploymentService)).build();
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
        String definitionId = "definition-1";
        String bpmnXml = "<definitions><process id=\"leave\" /></definitions>";
        when(deploymentService.getBpmnXml(definitionId)).thenReturn(bpmnXml);

        mockMvc.perform(get("/workflow/deploy/bpmnXml/{definitionId}", definitionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("操作成功"))
                .andExpect(jsonPath("$.data").value(bpmnXml));

        verify(deploymentService).getBpmnXml(definitionId);
    }
}
