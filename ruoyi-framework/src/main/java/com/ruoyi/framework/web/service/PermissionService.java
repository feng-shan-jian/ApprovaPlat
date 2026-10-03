package com.ruoyi.framework.web.service;

import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.context.request.RequestContextHolder;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.security.context.PermissionContextHolder;
import com.ruoyi.system.service.SysPermissionEvaluator;

/**
 * RuoYi首创 自定义权限实现，ss取自SpringSecurity首字母
 * 
 * @author ruoyi
 */
@Service("ss")
public class PermissionService
{
    private final SysPermissionEvaluator permissionEvaluator;

    /**
     * 创建权限校验服务。
     *
     * 构造后由 Spring 以 ss 名称管理
     */
    public PermissionService(SysPermissionEvaluator permissionEvaluator)
    {
        this.permissionEvaluator = permissionEvaluator;
    }

    /**
     * 验证用户是否具备某权限；工作流权限同时复核实时主数据。
     * 
     * @param permission String，待校验的权限字符串
     * @return boolean，Token 权限与必要的实时权限均通过时返回 true
     */
    public boolean hasPermi(String permission)
    {
        if (StringUtils.isEmpty(permission))
        {
            return false;
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (StringUtils.isNull(loginUser) || CollectionUtils.isEmpty(loginUser.getPermissions()))
        {
            return false;
        }
        bindPermissionContext(permission);
        return permissionEvaluator.hasPermi(loginUser, permission);
    }

    /**
     * 验证用户是否具有以下任意一个权限
     *
     * @param permissions String，以 PERMISSION_DELIMITER 为分隔符的权限列表
     * @return boolean，用户是否具有以下任意一个当前有效权限
     */
    public boolean hasAnyPermi(String permissions)
    {
        if (StringUtils.isEmpty(permissions))
        {
            return false;
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (StringUtils.isNull(loginUser) || CollectionUtils.isEmpty(loginUser.getPermissions()))
        {
            return false;
        }
        bindPermissionContext(permissions);
        return permissionEvaluator.hasAnyPermi(loginUser, permissions);
    }

    /**
     * 判断用户是否拥有某个角色
     * 
     * @param role 角色字符串
     * @return 用户是否具备某角色
     */
    public boolean hasRole(String role)
    {
        if (StringUtils.isEmpty(role))
        {
            return false;
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (StringUtils.isNull(loginUser) || CollectionUtils.isEmpty(loginUser.getUser().getRoles()))
        {
            return false;
        }
        for (SysRole sysRole : loginUser.getUser().getRoles())
        {
            String roleKey = sysRole.getRoleKey();
            if (Constants.SUPER_ADMIN.equals(roleKey) || roleKey.equals(StringUtils.trim(role)))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 在真实 HTTP 请求中记录数据权限表达式，领域线程和调度线程则跳过请求级副作用。
     *
     * @param permission String，当前正在判定的单个或多个权限表达式
     * @return 无返回值；没有 Servlet 请求上下文时保持无副作用
     */
    private void bindPermissionContext(String permission)
    {
        // 非 HTTP 的表达式求值没有请求级数据权限上下文。
        if (RequestContextHolder.getRequestAttributes() != null)
        {
            PermissionContextHolder.setContext(permission);
        }
    }

}
