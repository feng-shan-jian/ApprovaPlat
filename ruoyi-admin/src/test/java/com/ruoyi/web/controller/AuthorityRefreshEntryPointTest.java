package com.ruoyi.web.controller;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.domain.SysUserRole;
import com.ruoyi.system.service.ISysMenuService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;
import com.ruoyi.web.controller.system.SysMenuController;
import com.ruoyi.web.controller.system.SysRoleController;
import com.ruoyi.web.controller.system.SysUserController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorityRefreshEntryPointTest
{
    private final TokenService tokens = mock(TokenService.class);
    private final ISysUserService users = mock(ISysUserService.class);
    private final ISysRoleService roles = mock(ISysRoleService.class);
    private final SysUser user = new SysUser(42L);
    private final SysRole role = new SysRole();

    @BeforeEach
    void authenticate()
    {
        SysUser actor = new SysUser(1L);
        actor.setUserName("admin");
        LoginUser login = new LoginUser(1L, 100L, actor, Set.of("*:*:*"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, login.getAuthorities()));
        role.setRoleId(9L);
        user.setRoleIds(new Long[] { 9L });
        when(roles.selectRoleById(9L)).thenReturn(role);
        when(users.selectUserIdsByRoleIds(any())).thenReturn(List.of(42L));
        when(users.selectUserByIdInScope(42L)).thenReturn(user);
        when(roles.selectRolesInScope(any(), any())).thenReturn(List.of(role));
    }

    @AfterEach
    void clear()
    {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = { "edit", "scope", "status", "remove", "revoke", "revokeAll", "grant" })
    void roleMutationRefreshesActualAffectedUserIds(String action)
    {
        SysRoleController controller = new SysRoleController();
        ReflectionTestUtils.setField(controller, "roleService", roles);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "tokenService", tokens);
        switch (action)
        {
            case "edit" -> {
                when(roles.checkRoleNameUnique(role)).thenReturn(true);
                when(roles.checkRoleKeyUnique(role)).thenReturn(true);
                when(roles.updateRole(role)).thenReturn(1);
                controller.edit(role);
            }
            case "scope" -> {
                when(roles.authDataScope(role)).thenReturn(1);
                controller.dataScope(role);
            }
            case "status" -> {
                when(roles.updateRoleStatus(role)).thenReturn(1);
                controller.changeStatus(role);
            }
            case "remove" -> {
                when(roles.deleteRoleByIds(any())).thenReturn(1);
                controller.remove(new Long[] { 9L });
            }
            case "revoke" -> {
                SysUserRole assignment = new SysUserRole();
                assignment.setUserId(42L);
                assignment.setRoleId(9L);
                when(roles.deleteAuthUser(assignment)).thenReturn(1);
                controller.cancelAuthUser(assignment);
            }
            case "revokeAll" -> {
                when(roles.deleteAuthUsers(eq(9L), any())).thenReturn(1);
                controller.cancelAuthUserAll(9L, new Long[] { 42L });
            }
            case "grant" -> {
                when(roles.insertAuthUsers(eq(9L), any())).thenReturn(1);
                controller.selectAuthUserAll(9L, new Long[] { 42L });
            }
            default -> fail(action);
        }
        verify(tokens).refreshUsersAfterCommit(List.of(42L));
    }

    @ParameterizedTest
    @ValueSource(strings = { "edit", "status", "remove", "roles" })
    void userMutationRefreshesEveryTokenForTheSelectedUser(String action)
    {
        SysUserController controller = new SysUserController();
        ReflectionTestUtils.setField(controller, "roleService", roles);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "tokenService", tokens);
        switch (action)
        {
            case "edit" -> {
                when(users.updateUser(user)).thenReturn(1);
                controller.edit(user);
            }
            case "status" -> {
                when(users.updateUserStatus(user)).thenReturn(1);
                controller.changeStatus(user);
            }
            case "remove" -> {
                when(users.deleteUserByIds(any())).thenReturn(1);
                controller.remove(new Long[] { 42L });
            }
            case "roles" -> controller.insertAuthRole(42L, new Long[] { 9L });
            default -> fail(action);
        }
        verify(tokens).refreshUsersAfterCommit(List.of(42L));
    }

    @Test
    void databaseFailureAndNoOpDoNotRefresh()
    {
        SysRoleController controller = new SysRoleController();
        ReflectionTestUtils.setField(controller, "roleService", roles);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "tokenService", tokens);
        controller.dataScope(role);
        when(roles.authDataScope(role)).thenThrow(new IllegalStateException("controlled write failure"));
        assertThrows(IllegalStateException.class, () -> controller.dataScope(role));
        verifyNoInteractions(tokens);
    }

    @Test
    void menuPermissionEditRefreshesItsAssignedUsersButSortDoesNot()
    {
        ISysMenuService menus = mock(ISysMenuService.class);
        SysMenuController controller = new SysMenuController();
        ReflectionTestUtils.setField(controller, "menuService", menus);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "tokenService", tokens);
        SysMenu after = new SysMenu();
        after.setMenuId(100L);
        after.setParentId(0L);
        after.setPerms("system:user:list");
        after.setStatus("0");
        when(menus.checkMenuNameUnique(after)).thenReturn(true);
        when(menus.checkRouteConfigUnique(after)).thenReturn(true);
        when(menus.updateMenu(after)).thenReturn(1);
        when(users.selectUserIdsByMenuId(100L)).thenReturn(List.of(42L));
        controller.edit(after);
        verify(tokens).refreshUsersAfterCommit(List.of(42L));
        clearInvocations(tokens);
        controller.updateSort(Map.of("menuIds", "100", "orderNums", "1"));
        verifyNoInteractions(tokens);
    }

    @Test
    void menuFailedOrEmptyWriteDoesNotRefresh()
    {
        ISysMenuService menus = mock(ISysMenuService.class);
        SysMenuController controller = new SysMenuController();
        ReflectionTestUtils.setField(controller, "menuService", menus);
        ReflectionTestUtils.setField(controller, "userService", users);
        ReflectionTestUtils.setField(controller, "tokenService", tokens);
        SysMenu menu = new SysMenu();
        menu.setMenuId(100L);
        menu.setParentId(0L);
        when(menus.checkMenuNameUnique(menu)).thenReturn(true);
        when(menus.checkRouteConfigUnique(menu)).thenReturn(true);
        controller.edit(menu);
        when(menus.updateMenu(menu)).thenThrow(new IllegalStateException("controlled menu write failure"));
        assertThrows(IllegalStateException.class, () -> controller.edit(menu));
        verifyNoInteractions(tokens);
    }
}
