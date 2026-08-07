package com.ruoyi.web.controller.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.flowable.domain.dto.WorkflowDmnDeploymentRequest;
import com.ruoyi.flowable.domain.vo.WorkflowDmnDecisionView;
import com.ruoyi.flowable.service.model.WorkflowDmnDecisionService;

/**
 * DMN 目录、部署和删除 Controller 参数绑定与响应契约测试。
 */
class WfDmnControllerTest
{
    private WorkflowDmnDecisionService decisionService;
    private MockMvc mockMvc;

    /**
     * 创建独立领域服务替身和真实 MVC 参数校验链路。
     * @return void，初始化后可执行接口契约测试
     */
    @BeforeEach
    void setUp()
    {
        decisionService = mock(WorkflowDmnDecisionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WfDmnController(decisionService)).build();
    }

    /**
     * 验证管理清单和设计器选项分别请求全部来源版本与每 key 最新版本。
     * @return void，响应字段或 latestOnly 参数漂移时测试失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void returnsManagementListAndLatestOptions() throws Exception
    {
        WorkflowDmnDecisionView decision = new WorkflowDmnDecisionView(
                "risk:3:id", "risk", "风险决策", 3, "finance",
                "decision-table", "deployment-1", "risk.dmn", new Date(0));
        when(decisionService.list(false)).thenReturn(List.of(decision));
        when(decisionService.list(true)).thenReturn(List.of(decision));

        mockMvc.perform(get("/workflow/dmn/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].decisionId").value("risk:3:id"))
                .andExpect(jsonPath("$.data[0].version").value(3));
        mockMvc.perform(get("/workflow/dmn/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].decisionKey").value("risk"));

        verify(decisionService).list(false);
        verify(decisionService).list(true);
    }

    /**
     * 验证部署强类型请求和删除部署主键完整传递到领域服务。
     * @return void，写接口参数或返回协议漂移时测试失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void deploysAndDeletesSourceDeployment() throws Exception
    {
        WorkflowDmnDeploymentRequest request = new WorkflowDmnDeploymentRequest(
                "risk.dmn", "finance", "<definitions />");
        when(decisionService.deploy(request)).thenReturn("deployment-1");

        mockMvc.perform(post("/workflow/dmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceName\":\"risk.dmn\",\"category\":\"finance\","
                                + "\"dmnXml\":\"<definitions />\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deploymentId").value("deployment-1"));
        mockMvc.perform(delete("/workflow/dmn/{deploymentId}", "deployment-1"))
                .andExpect(status().isOk());

        verify(decisionService).deploy(request);
        verify(decisionService).delete("deployment-1");
    }

    /**
     * 验证缺失资源名或 DMN 正文的请求在 MVC 参数校验层失败关闭。
     * @return void，非法部署请求进入领域服务时测试失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void rejectsInvalidDeploymentRequest() throws Exception
    {
        mockMvc.perform(post("/workflow/dmn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceName\":\"\",\"dmnXml\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
