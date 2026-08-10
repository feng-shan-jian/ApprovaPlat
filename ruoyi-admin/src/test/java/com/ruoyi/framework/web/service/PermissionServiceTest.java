package com.ruoyi.framework.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.system.service.ISysMenuService;

/**
 * 工作流入口权限实时撤权契约测试。
 */
class PermissionServiceTest
{
    /** 每个用例之后清理线程认证，避免身份泄漏到后续测试。 */
    @AfterEach
    void clearSecurityContext()
    {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * 验证 Token 仍含工作流权限时，正式角色关系已撤销必须立即拒绝。
     *
     * @return 无返回值；旧 Token 绕过实时权限时测试失败
     */
    @Test
    void rejectsCachedWorkflowPermissionAfterLiveRevocation()
    {
        ISysMenuService menuService = mock(ISysMenuService.class);
        PermissionService permissionService = new PermissionService(menuService);
        installLoginUser(7L, Set.of("workflow:process:approval"));
        when(menuService.selectMenuPermsByUserId(7L)).thenReturn(Set.of());

        assertThat(permissionService.hasPermi("workflow:process:approval")).isFalse();
        verify(menuService).selectMenuPermsByUserId(7L);
    }

    /**
     * 验证 Token 快照和正式主数据同时授权时工作流入口正常放行。
     *
     * @return 无返回值；有效工作流权限被误拒时测试失败
     */
    @Test
    void allowsWorkflowPermissionPresentInTokenAndMasterData()
    {
        ISysMenuService menuService = mock(ISysMenuService.class);
        PermissionService permissionService = new PermissionService(menuService);
        installLoginUser(7L, Set.of("workflow:process:approval"));
        when(menuService.selectMenuPermsByUserId(7L))
                .thenReturn(Set.of("workflow:process:approval"));

        assertThat(permissionService.hasPermi("workflow:process:approval")).isTrue();
    }

    /**
     * 验证非工作流权限保持既有 Token 快照路径，不引入额外数据库查询。
     *
     * @return 无返回值；非工作流请求误调用实时查询时测试失败
     */
    @Test
    void keepsNonWorkflowPermissionsOnTokenOnlyPath()
    {
        ISysMenuService menuService = mock(ISysMenuService.class);
        PermissionService permissionService = new PermissionService(menuService);
        installLoginUser(7L, Set.of("system:user:list"));

        assertThat(permissionService.hasPermi("system:user:list")).isTrue();
        verify(menuService, never()).selectMenuPermsByUserId(7L);
    }

    /**
     * 验证超级管理员仍按既有全权限语义放行，不要求必须存在角色菜单关系。
     *
     * @return 无返回值；用户 1 被误拒或误查菜单关系时测试失败
     */
    @Test
    void preservesSuperAdministratorWorkflowSemantics()
    {
        ISysMenuService menuService = mock(ISysMenuService.class);
        PermissionService permissionService = new PermissionService(menuService);
        installLoginUser(1L, Set.of(Constants.ALL_PERMISSION));

        assertThat(permissionService.hasPermi("workflow:process:approval")).isTrue();
        verify(menuService, never()).selectMenuPermsByUserId(1L);
    }

    /**
     * 在 Spring SecurityContext 中安装只含指定权限的测试登录用户。
     *
     * @param userId Long，若依用户主键
     * @param permissions Set&lt;String&gt;，Token 中的登录时权限快照
     * @return 无返回值，认证对象直接写入当前测试线程
     */
    private void installLoginUser(Long userId, Set<String> permissions)
    {
        // PermissionContextHolder 依赖当前 servlet 请求保存数据权限上下文，单测也必须保留真实调用形态。
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUserName("permission-test-" + userId);
        LoginUser loginUser = new LoginUser(userId, null, user, permissions);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        loginUser, null, loginUser.getAuthorities()));
    }
}
