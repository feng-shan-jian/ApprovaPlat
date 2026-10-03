package com.ruoyi.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.framework.aspectj.DataScopeAspect;
import com.ruoyi.framework.security.context.PermissionContextHolder;
import com.ruoyi.framework.web.service.PermissionService;
import com.ruoyi.system.mapper.SysUserMapper;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.system.service.SysPermissionEvaluator;
import com.ruoyi.system.service.impl.SysUserServiceImpl;

/** 迁移前后共同执行的 Token/实时权限矩阵及真实 Web 数据范围切面合同。 */
class WorkflowPermissionContractTest
{
    private static final String PERMISSION = "workflow:process:state";
    private final ISysMenuService menus = mock(ISysMenuService.class);
    private final SysPermissionEvaluator evaluator = new SysPermissionEvaluator(menus);
    private final PermissionService service = new PermissionService(evaluator);

    @AfterEach
    void clearContext()
    {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    static Stream<Arguments> permissions()
    {
        return Stream.of(
                Arguments.of(7L, Set.of(PERMISSION), Set.of(PERMISSION), PERMISSION, true, 1),
                Arguments.of(7L, Set.of(PERMISSION), Set.of(), PERMISSION, false, 1),
                Arguments.of(7L, Set.of("other"), Set.of(PERMISSION), PERMISSION, false, 0),
                Arguments.of(7L, Set.of("*:*:*"), Set.of(), PERMISSION, false, 1),
                Arguments.of(7L, Set.of(PERMISSION), Set.of("*:*:*"), PERMISSION, true, 1),
                Arguments.of(1L, Set.of("*:*:*"), Set.of(), PERMISSION, true, 0),
                Arguments.of(1L, Set.of(), Set.of(PERMISSION), PERMISSION, false, 0),
                Arguments.of(null, Set.of(PERMISSION), Set.of(PERMISSION), PERMISSION, false, 0),
                Arguments.of(7L, Set.of(PERMISSION), Set.of(PERMISSION), " " + PERMISSION + " ", true, 1),
                Arguments.of(7L, Set.of("system:user:list"), Set.of(), "system:user:list", true, 0),
                Arguments.of(7L, Set.of(PERMISSION), Set.of(PERMISSION), null, false, 0),
                Arguments.of(7L, Set.of(PERMISSION), Set.of(PERMISSION), "", false, 0));
    }

    @ParameterizedTest
    @MethodSource("permissions")
    void preservesTokenAndCurrentPermissionIntersection(Long userId, Set<String> token,
            Set<String> current, String requested, boolean allowed, int reads)
    {
        authenticate(userId, token);
        when(menus.selectMenuPermsByUserId(userId)).thenReturn(current);
        assertThat(service.hasPermi(requested)).isEqualTo(allowed);
        verify(menus, times(reads)).selectMenuPermsByUserId(userId);
        clearInvocations(menus);
        assertThat(evaluator.hasPermi(requested)).isEqualTo(allowed);
        verify(menus, times(reads)).selectMenuPermsByUserId(userId);
    }

    @Test
    void hasAnyUsesCommaTrimAndBindsWholeExpressionBeforeCurrentPermissionRead()
    {
        authenticate(7L, Set.of(PERMISSION));
        request();
        String expression = "missing, " + PERMISSION + " ";
        when(menus.selectMenuPermsByUserId(7L)).thenAnswer(invocation -> {
            assertThat(PermissionContextHolder.getContext()).isEqualTo(expression);
            return Set.of(PERMISSION);
        });
        assertThat(service.hasAnyPermi(expression)).isTrue();
        verify(menus).selectMenuPermsByUserId(7L);
    }

    @Test
    void invalidLoginDoesNotBindButDeniedValidLoginStillBinds()
    {
        request();
        PermissionContextHolder.setContext("before");
        assertThatThrownBy(() -> service.hasPermi(PERMISSION))
                .isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(401));
        assertThat(PermissionContextHolder.getContext()).isEqualTo("before");
        authenticate(7L, Set.of());
        assertThat(service.hasPermi(PERMISSION)).isFalse();
        assertThat(PermissionContextHolder.getContext()).isEqualTo("before");
        authenticate(7L, Set.of("different"));
        assertThat(service.hasPermi(PERMISSION)).isFalse();
        assertThat(PermissionContextHolder.getContext()).isEqualTo(PERMISSION);
    }

    @Test
    void webBindingStillSelectsMatchingRoleInRealDataScopePath()
    {
        LoginUser login = authenticate(7L, Set.of("system:user:list"));
        SysRole matching = role("system:user:list", "5");
        SysRole unrelated = role("system:role:list", "1");
        login.getUser().setRoles(List.of(matching, unrelated));
        request();
        assertThat(service.hasPermi("system:user:list")).isTrue();
        var mapper = mock(SysUserMapper.class);
        var target = new SysUserServiceImpl();
        ReflectionTestUtils.setField(target, "userMapper", mapper);
        var proxy = new AspectJProxyFactory(target);
        proxy.addAspect(new DataScopeAspect());
        ISysUserService users = proxy.getProxy();
        var filter = new SysUser();
        users.selectUserList(filter);
        assertThat(filter.getParams().get("dataScope").toString()).contains("u.user_id = 7");
        verify(mapper).selectUserList(filter);
    }

    @Test
    void roleRulesRetainTrimAndSuperAdministratorRole()
    {
        LoginUser login = authenticate(7L, Set.of("system:user:list"));
        SysRole userRole = role("system:user:list", "5");
        userRole.setRoleKey("operator");
        login.getUser().setRoles(List.of(userRole));
        assertThat(service.hasRole(" operator ")).isTrue();
        assertThat(service.hasRole("missing")).isFalse();
        userRole.setRoleKey("admin");
        assertThat(service.hasRole("missing")).isTrue();
    }

    @Test
    void domainPermissionReadUsesLoginUserWithoutOverwritingWebDataScope()
    {
        authenticate(7L, Set.of(PERMISSION));
        request();
        PermissionContextHolder.setContext("system:user:list");
        when(menus.selectMenuPermsByUserId(7L)).thenReturn(Set.of(PERMISSION));
        assertThat(evaluator.hasPermi(PERMISSION)).isTrue();
        assertThat(PermissionContextHolder.getContext()).isEqualTo("system:user:list");
        verify(menus).selectMenuPermsByUserId(7L);
    }

    private SysRole role(String permission, String scope)
    {
        var role = new SysRole();
        role.setRoleId(2L);
        role.setStatus("0");
        role.setDataScope(scope);
        role.setPermissions(Set.of(permission));
        return role;
    }

    private LoginUser authenticate(Long id, Set<String> permissions)
    {
        var user = new SysUser();
        user.setUserId(id);
        user.setDeptId(10L);
        var login = new LoginUser(id, 10L, user, permissions);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, List.of()));
        return login;
    }

    private void request()
    {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }
}
