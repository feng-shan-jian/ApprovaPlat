package com.ruoyi.system.mapper;

import java.util.List;
import com.ruoyi.system.domain.SysRoleMenu;

/**
 * 角色与菜单关联表 数据层
 * 
 * @author ruoyi
 */
public interface SysRoleMenuMapper
{
    /**
     * 查询菜单使用数量
     * 
     * @param menuId 菜单ID
     * @return 结果
     */
    public int checkMenuExistRole(Long menuId);

    /**
     * 通过角色ID删除角色和菜单关联
     * 
     * @param roleId 角色ID
     * @return 结果
     */
    public int deleteRoleMenuByRoleId(Long roleId);

    /**
     * 批量删除角色菜单关联信息
     * 
     * @param ids 需要删除的数据ID
     * @return 结果
     */
    public int deleteRoleMenu(Long[] ids);

    /**
     * 批量新增角色菜单信息
     * 
     * @param roleMenuList 角色菜单列表
     * @return 结果
     */
    public int batchRoleMenu(List<SysRoleMenu> roleMenuList);

    /**
     * 幂等批量追加角色菜单关联，已存在的复合主键保持不变。
     *
     * @param roleMenuList List&lt;SysRoleMenu&gt;，待追加的角色菜单关联
     * @return int，本次真实插入的关联数
     */
    public int batchRoleMenuIgnore(List<SysRoleMenu> roleMenuList);
}
