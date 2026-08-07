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
import com.ruoyi.flowable.domain.dto.WorkflowSqlDataSourceRequest;
import com.ruoyi.flowable.domain.vo.WorkflowSqlDataSourceView;
import com.ruoyi.flowable.service.model.WorkflowSqlDataSourceService;

/**
 * SQL 数据源管理 Controller 参数绑定和响应契约测试。
 */
class WfSqlDataSourceControllerTest
{
    private WorkflowSqlDataSourceService dataSourceService;
    private MockMvc mockMvc;

    /**
     * 创建独立领域服务替身和真实 MVC 参数校验链路。
     * @return void，初始化后可执行接口契约测试
     */
    @BeforeEach
    void setUp()
    {
        dataSourceService = mock(WorkflowSqlDataSourceService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WfSqlDataSourceController(dataSourceService)).build();
    }

    /**
     * 验证管理清单和设计器选项只返回环境引用及不可回退修订。
     * @return void，响应字段漂移时测试失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void returnsManagementListAndOptions() throws Exception
    {
        WorkflowSqlDataSourceView source = new WorkflowSqlDataSourceView(1L,
                "approva.primary", "主业务库", "PRIMARY", null, null, null,
                List.of("wf_copy"), 1000, 10, 2, "ENABLED", "a".repeat(64),
                LocalDateTime.of(2026, 8, 4, 0, 0), null);
        when(dataSourceService.list()).thenReturn(List.of(source));
        when(dataSourceService.listOptions()).thenReturn(List.of(source));

        mockMvc.perform(get("/workflow/sql-datasource/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].dataSourceKey").value("approva.primary"))
                .andExpect(jsonPath("$.data[0].revisionNo").value(2));
        mockMvc.perform(get("/workflow/sql-datasource/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("ENABLED"));
    }

    /**
     * 验证新增、修订和状态变更完整传递强类型请求。
     * @return void，写入口参数或返回协议漂移时测试失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void createsUpdatesAndDisablesDataSource() throws Exception
    {
        WorkflowSqlDataSourceRequest request = request();
        when(dataSourceService.create(request)).thenReturn(11L);
        when(dataSourceService.update(11L, request)).thenReturn(2);
        String body = requestJson();

        mockMvc.perform(post("/workflow/sql-datasource")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dataSourceId").value(11));
        mockMvc.perform(put("/workflow/sql-datasource/{dataSourceId}", 11L)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revisionNo").value(2));
        mockMvc.perform(put("/workflow/sql-datasource/{dataSourceId}/status", 11L)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        verify(dataSourceService).create(request);
        verify(dataSourceService).update(11L, request);
        verify(dataSourceService).changeStatus(11L, false);
    }

    /**
     * 验证不完整数据源请求在 MVC 参数绑定层失败关闭；主键边界由领域服务测试覆盖。
     * @return void，非法请求进入领域层时测试失败
     * @throws Exception MockMvc 执行失败
     */
    @Test
    void rejectsInvalidRequests() throws Exception
    {
        mockMvc.perform(post("/workflow/sql-datasource")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dataSourceKey\":\"bad key\"}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * 构造控制器写入口使用的强类型请求。
     * @return WorkflowSqlDataSourceRequest，字段完整的主库目录
     */
    private WorkflowSqlDataSourceRequest request()
    {
        return new WorkflowSqlDataSourceRequest("approva.primary", "主业务库", "PRIMARY",
                null, null, null, List.of("wf_copy"), 1000, 10);
    }

    /**
     * 构造与强类型请求等价的 JSON 正文。
     * @return String，可直接提交给 MockMvc 的 JSON
     */
    private String requestJson()
    {
        return "{\"dataSourceKey\":\"approva.primary\",\"dataSourceName\":\"主业务库\","
                + "\"connectionType\":\"PRIMARY\",\"allowedTables\":[\"wf_copy\"],"
                + "\"connectTimeoutMs\":1000,\"queryTimeoutSeconds\":10}";
    }
}
