package com.ruoyi.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ruoyi.common.constant.UserConstants;
import com.ruoyi.common.core.domain.entity.SysMenu;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysRoleMenu;
import com.ruoyi.system.mapper.SysMenuMapper;
import com.ruoyi.system.mapper.SysRoleDeptMapper;
import com.ruoyi.system.mapper.SysRoleMapper;
import com.ruoyi.system.mapper.SysRoleMenuMapper;
import com.ruoyi.system.mapper.SysUserRoleMapper;

/**
 * 角色菜单增量授权领域测试。
 */
@ExtendWith(MockitoExtension.class)
class SysRoleServiceImplTest
{
    @Mock
    private SysRoleMapper roleMapper;

    @Mock
    private SysMenuMapper menuMapper;

    @Mock
    private SysRoleMenuMapper roleMenuMapper;

    @Mock
    private SysUserRoleMapper userRoleMapper;

    @Mock
    private SysRoleDeptMapper roleDeptMapper;

    @InjectMocks
    private SysRoleServiceImpl roleService;

    /**
     * 验证重复菜单会去重并只调用增量插入，绝不触发角色菜单删除。
     *
     * @return void，授权集合、返回数量或 Mapper 调用不符合契约时测试失败
     */
    @Test
    void grantsOnlyMissingRoleMenusWithoutDeletingExistingPermissions()
    {
        long roleId = 20L;
        when(roleMapper.selectRoleById(roleId)).thenReturn(activeRole(roleId));
        when(menuMapper.selectMenuById(101L)).thenReturn(activeMenu(101L));
        when(menuMapper.selectMenuById(102L)).thenReturn(activeMenu(102L));
        when(roleMenuMapper.batchRoleMenuIgnore(anyList())).thenReturn(1);

        int added = roleService.grantRoleMenus(roleId, List.of(101L, 102L, 101L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SysRoleMenu>> menus = ArgumentCaptor.forClass(List.class);
        verify(roleMenuMapper).batchRoleMenuIgnore(menus.capture());
        verify(roleMenuMapper, never()).deleteRoleMenuByRoleId(roleId);
        assertThat(menus.getValue()).extracting(SysRoleMenu::getMenuId)
                .containsExactly(101L, 102L);
        assertThat(added).isEqualTo(1);
    }

    /**
     * 验证不存在的菜单在任何角色菜单写入之前被拒绝。
     *
     * @return void，非法菜单未被拒绝或发生写入时测试失败
     */
    @Test
    void rejectsUnknownMenuBeforeGrantingRolePermissions()
    {
        long roleId = 20L;
        when(roleMapper.selectRoleById(roleId)).thenReturn(activeRole(roleId));
        when(menuMapper.selectMenuById(999L)).thenReturn(null);

        assertThatThrownBy(() -> roleService.grantRoleMenus(roleId, List.of(999L)))
                .isInstanceOf(ServiceException.class)
                .hasMessage("菜单不存在或已停用");
        verify(roleMenuMapper, never()).batchRoleMenuIgnore(anyList());
        verify(roleMenuMapper, never()).deleteRoleMenuByRoleId(roleId);
    }

    /**
     * 验证逻辑删除角色在任何菜单查询和写入之前被拒绝。
     *
     * @return void，删除角色未被拒绝或发生菜单访问时测试失败
     */
    @Test
    void rejectsDeletedRoleBeforeGrantingRolePermissions()
    {
        long roleId = 20L;
        SysRole deletedRole = activeRole(roleId);
        deletedRole.setDelFlag("2");
        when(roleMapper.selectRoleById(roleId)).thenReturn(deletedRole);

        assertThatThrownBy(() -> roleService.grantRoleMenus(roleId, List.of(101L)))
                .isInstanceOf(ServiceException.class)
                .hasMessage("角色不存在或已删除");
        verify(menuMapper, never()).selectMenuById(101L);
        verify(roleMenuMapper, never()).batchRoleMenuIgnore(anyList());
    }

    /**
     * 创建启用且未删除的角色测试夹具。
     *
     * @param roleId long，角色主键
     * @return SysRole，具有正式启用状态且未逻辑删除的角色对象
     */
    private SysRole activeRole(long roleId)
    {
        SysRole role = new SysRole(roleId);
        role.setStatus(UserConstants.ROLE_NORMAL);
        role.setDelFlag(UserConstants.NORMAL);
        return role;
    }

    /**
     * 创建启用状态的菜单测试夹具。
     *
     * @param menuId long，菜单主键
     * @return SysMenu，具有正式启用状态的菜单对象
     */
    private SysMenu activeMenu(long menuId)
    {
        SysMenu menu = new SysMenu();
        menu.setMenuId(menuId);
        menu.setStatus(UserConstants.NORMAL);
        return menu;
    }
}
