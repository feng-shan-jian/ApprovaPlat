package com.ruoyi.web.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.ruoyi.common.core.page.PageResult;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftCreateRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftQueryDto;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSaveRequest;
import com.ruoyi.flowable.domain.dto.WorkflowProcessDraftSubmitRequest;
import com.ruoyi.flowable.service.process.WorkflowProcessDraftService;
import tools.jackson.databind.json.JsonMapper;

/**
 * 流程申请草稿固定路由、权限和 MVC 参数绑定测试。
 */
class WfProcessDraftControllerTest
{
    private static final String DRAFT_ID = "d9428888-122b-4c6f-8f0c-9c3e1dbd3210";
    private WorkflowProcessDraftService draftService;
    private MockMvc mockMvc;

    /**
     * 创建独立服务替身和真实 Spring MVC 参数绑定链。
     *
     * @return void，初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        draftService = mock(WorkflowProcessDraftService.class);
        WfProcessDraftController controller = new WfProcessDraftController(draftService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JsonMapper.shared()))
                .build();
    }

    /**
     * 验证六个正式入口绑定固定权限，防止菜单与 Controller 契约漂移。
     *
     * @return void，任一路由权限变化时测试失败
     * @throws Exception 反射查找方法失败时测试失败
     */
    @Test
    void exposesFixedPermissions() throws Exception
    {
        assertPermission("list", "workflow:process:draftList", String.class, Instant.class,
                Instant.class, int.class, int.class);
        assertPermission("get", "workflow:process:draftQuery", String.class);
        assertPermission("create", "workflow:process:draftSave",
                WorkflowProcessDraftCreateRequest.class);
        assertPermission("save", "workflow:process:draftSave", String.class,
                WorkflowProcessDraftSaveRequest.class);
        assertPermission("delete", "workflow:process:draftRemove", String.class, long.class);
        assertPermission("submit", "workflow:process:draftSubmit", String.class,
                WorkflowProcessDraftSubmitRequest.class);
    }

    /**
     * 验证列表、创建、保存、删除和提交通过真实 MVC 绑定到对应领域服务。
     *
     * @return void，HTTP 协议或请求字段无法绑定时测试失败
     * @throws Exception MockMvc 执行失败时测试失败
     */
    @Test
    void bindsDraftHttpProtocol() throws Exception
    {
        when(draftService.list(any(), eq(2), eq(20)))
                .thenReturn(new PageResult<>(List.of(), 0));
        mockMvc.perform(get("/workflow/process/draft/list")
                        .param("processName", "采购").param("pageNum", "2")
                        .param("pageSize", "20"))
                .andExpect(status().isOk());
        verify(draftService).list(any(WorkflowProcessDraftQueryDto.class), eq(2), eq(20));

        mockMvc.perform(get("/workflow/process/draft/{draftId}", DRAFT_ID))
                .andExpect(status().isOk());
        verify(draftService).get(DRAFT_ID);

        mockMvc.perform(post("/workflow/process/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionId\":\"definition-1\","
                                + "\"businessKey\":\"B-1\",\"variables\":{}}"))
                .andExpect(status().isOk());
        verify(draftService).create(any(WorkflowProcessDraftCreateRequest.class));

        mockMvc.perform(put("/workflow/process/draft/{draftId}", DRAFT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"variables\":{}}"))
                .andExpect(status().isOk());
        verify(draftService).save(eq(DRAFT_ID), any(WorkflowProcessDraftSaveRequest.class));

        mockMvc.perform(delete("/workflow/process/draft/{draftId}", DRAFT_ID)
                        .param("expectedVersion", "2"))
                .andExpect(status().isOk());
        verify(draftService).delete(DRAFT_ID, 2L);

        mockMvc.perform(post("/workflow/process/draft/{draftId}/submit", DRAFT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"businessKey\":\"B-1\","
                                + "\"variables\":{\"reason\":\"采购\"}}"))
                .andExpect(status().isOk());
        verify(draftService).submit(eq(DRAFT_ID), any(WorkflowProcessDraftSubmitRequest.class));
    }

    /**
     * 读取方法权限表达式并与固定契约比较。
     *
     * @param methodName String，Controller 方法名
     * @param permission String，期望权限标识
     * @param parameterTypes Class&lt;?&gt;[]，方法参数类型
     * @return void，权限不一致时断言失败
     * @throws Exception 方法不存在时测试失败
     */
    private void assertPermission(String methodName, String permission,
            Class<?>... parameterTypes) throws Exception
    {
        Method method = WfProcessDraftController.class.getMethod(methodName, parameterTypes);
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo("@ss.hasPermi('" + permission + "')");
    }
}
