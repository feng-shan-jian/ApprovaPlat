package com.ruoyi.web.controller.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.flowable.domain.vo.WorkflowCallActivityOptionView;
import com.ruoyi.flowable.domain.vo.WorkflowCallActivityVariableView;
import com.ruoyi.flowable.service.model.WorkflowCallActivityReferenceService;

/**
 * WfCallActivityController 正式授权目录协议测试。
 */
class WfCallActivityControllerTest
{
    /**
     * 验证正式 catalog 路径返回授权定义、版本、状态和变量字段，不接受客户端定义正文。
     *
     * @return void，无返回值，路径或响应契约漂移时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void returnsAuthorizedPublishedCatalog() throws Exception
    {
        WorkflowCallActivityReferenceService referenceService =
                mock(WorkflowCallActivityReferenceService.class);
        WorkflowCallActivityVariableView amount = new WorkflowCallActivityVariableView(
                "amount", "金额", "NUMBER", true, true, true);
        WorkflowCallActivityOptionView option = new WorkflowCallActivityOptionView(
                "child:3:definition", "child", "费用复核", 3, "finance",
                "deployment-child-3", "ACTIVE", List.of(amount), List.of(amount));
        when(referenceService.listReferenceOptions("费用")).thenReturn(List.of(option));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                new WfCallActivityController(referenceService)).build();

        mockMvc.perform(get("/workflow/call-activity/catalog").param("keyword", "费用"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].definitionId").value("child:3:definition"))
                .andExpect(jsonPath("$.data[0].processKey").value("child"))
                .andExpect(jsonPath("$.data[0].processName").value("费用复核"))
                .andExpect(jsonPath("$.data[0].version").value(3))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].inputFields[0].name").value("amount"))
                .andExpect(jsonPath("$.data[0].outputFields[0].type").value("NUMBER"));

        verify(referenceService).listReferenceOptions("费用");
    }
}
