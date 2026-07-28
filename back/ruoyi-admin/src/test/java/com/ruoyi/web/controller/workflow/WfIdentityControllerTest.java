package com.ruoyi.web.controller.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import com.ruoyi.flowable.domain.vo.WorkflowIdentityOptionView;
import com.ruoyi.flowable.domain.vo.WorkflowPageResult;
import com.ruoyi.flowable.service.identity.WorkflowIdentityDirectoryService;

/**
 * WfIdentityController 的分页协议和专用权限入口契约测试。
 */
class WfIdentityControllerTest
{
    private WorkflowIdentityDirectoryService identityDirectoryService;

    private MockMvc mockMvc;

    /**
     * 为每个测试创建独立服务替身和真实 Spring MVC 请求绑定链路。
     *
     * @return 无返回值，初始化失败时测试失败
     */
    @BeforeEach
    void setUp()
    {
        identityDirectoryService = mock(WorkflowIdentityDirectoryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                new WfIdentityController(identityDirectoryService)).build();
    }

    /**
     * 验证查询参数被完整传入领域服务并返回若依分页协议。
     *
     * @return 无返回值，请求绑定或最小身份响应漂移时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void returnsMinimalPagedIdentityOptions() throws Exception
    {
        WorkflowIdentityOptionView option = new WorkflowIdentityOptionView(
                "ROLE7", "角色: 财务审批", "role");
        when(identityDirectoryService.listOptions("role", "财务", 2, 10, null))
                .thenReturn(new WorkflowPageResult<>(List.of(option), 11L));

        mockMvc.perform(get("/workflow/identity/options")
                        .param("type", "role")
                        .param("keyword", "财务")
                        .param("pageNum", "2")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.rows[0].value").value("ROLE7"))
                .andExpect(jsonPath("$.rows[0].label").value("角色: 财务审批"))
                .andExpect(jsonPath("$.rows[0].type").value("role"));

        verify(identityDirectoryService).listOptions("role", "财务", 2, 10, null);
    }

    /**
     * 验证 approval 能力参数原样传入服务端资格目录且响应字段保持兼容。
     *
     * @return 无返回值；请求参数、响应结构或服务调用漂移时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void returnsApprovalEligibleUserOptions() throws Exception
    {
        WorkflowIdentityOptionView option = new WorkflowIdentityOptionView(
                "21", "张三 (zhangsan)", "user");
        when(identityDirectoryService.listOptions(
                "user", null, 1, 20, "approval"))
                .thenReturn(new WorkflowPageResult<>(List.of(option), 1L));

        mockMvc.perform(get("/workflow/identity/options")
                        .param("type", "user")
                        .param("capability", "approval"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].value").value("21"))
                .andExpect(jsonPath("$.rows[0].label").value("张三 (zhangsan)"))
                .andExpect(jsonPath("$.rows[0].type").value("user"));

        verify(identityDirectoryService).listOptions(
                "user", null, 1, 20, "approval");
    }

    /**
     * 验证 claim 能力参数可用于候选角色并保持最小分页响应协议。
     *
     * @return 无返回值；claim 参数绑定或响应字段漂移时测试失败
     * @throws Exception MockMvc 执行请求失败时抛出
     */
    @Test
    void returnsClaimEligibleRoleOptions() throws Exception
    {
        WorkflowIdentityOptionView option = new WorkflowIdentityOptionView(
                "ROLE7", "角色: 财务审批", "role");
        when(identityDirectoryService.listOptions(
                "role", null, 1, 20, "claim"))
                .thenReturn(new WorkflowPageResult<>(List.of(option), 1L));

        mockMvc.perform(get("/workflow/identity/options")
                        .param("type", "role")
                        .param("capability", "claim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.rows[0].value").value("ROLE7"))
                .andExpect(jsonPath("$.rows[0].type").value("role"));

        verify(identityDirectoryService).listOptions(
                "role", null, 1, 20, "claim");
    }

    /**
     * 验证身份目录不依赖系统用户管理权限，仅允许模型设计者或任务办理人访问。
     *
     * @return 无返回值，路径或权限表达式漂移时测试失败
     * @throws NoSuchMethodException Controller 方法签名不存在时抛出
     */
    @Test
    void keepsWorkflowSpecificReadPermissionContract() throws NoSuchMethodException
    {
        RequestMapping controllerMapping = WfIdentityController.class
                .getAnnotation(RequestMapping.class);
        assertThat(controllerMapping.value()).containsExactly("/workflow/identity");

        Method options = WfIdentityController.class.getDeclaredMethod(
                "options", String.class, String.class, String.class,
                int.class, int.class);
        assertThat(options.getAnnotation(GetMapping.class).value())
                .containsExactly("/options");
        assertThat(options.getAnnotation(PreAuthorize.class).value()).isEqualTo(
                "@ss.hasAnyPermi('workflow:model:designer,workflow:process:approval,workflow:process:manageList')");
    }
}
