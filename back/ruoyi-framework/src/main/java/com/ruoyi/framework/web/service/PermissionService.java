package com.ruoyi.framework.web.service;

import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.context.request.RequestContextHolder;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.framework.security.context.PermissionContextHolder;
import com.ruoyi.system.service.ISysMenuService;

/**
 * RuoYi首创 自定义权限实现，ss取自SpringSecurity首字母
 * 
 * @author ruoyi
 */
@Service("ss")
public class PermissionService
{
    /** 需要按正式主数据实时复核的工作流权限前缀。 */
    private static final String WORKFLOW_PERMISSION_PREFIX = "workflow:";

    private final ISysMenuService menuService;

    /**
     * 创建权限校验服务。
     *
     * @param menuService ISysMenuService，用于实时查询当前用户的正式菜单权限
     * @return 无返回值，构造后由 Spring 以 ss 名称管理
     */
    public PermissionService(ISysMenuService menuService)
    {
        this.menuService = menuService;
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
        // Token 是登录时快照；工作流权限还必须通过当前数据库授权，撤权才能立即生效。
        return hasPermissions(loginUser.getPermissions(), permission)
                && hasCurrentWorkflowPermission(loginUser, permission);
    }

    /**
     * 验证用户是否不具备某权限，与 hasPermi逻辑相反
     *
     * @param permission 权限字符串
     * @return 用户是否不具备某权限
     */
    public boolean lacksPermi(String permission)
    {
        return hasPermi(permission) != true;
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
        Set<String> authorities = loginUser.getPermissions();
        for (String permission : permissions.split(Constants.PERMISSION_DELIMITER))
        {
            if (permission != null && hasPermissions(authorities, permission)
                    && hasCurrentWorkflowPermission(loginUser, permission))
            {
                return true;
            }
        }
        return false;
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
     * 验证用户是否不具备某角色，与 isRole逻辑相反。
     *
     * @param role 角色名称
     * @return 用户是否不具备某角色
     */
    public boolean lacksRole(String role)
    {
        return hasRole(role) != true;
    }

    /**
     * 验证用户是否具有以下任意一个角色
     *
     * @param roles 以 ROLE_DELIMITER 为分隔符的角色列表
     * @return 用户是否具有以下任意一个角色
     */
    public boolean hasAnyRoles(String roles)
    {
        if (StringUtils.isEmpty(roles))
        {
            return false;
        }
        LoginUser loginUser = SecurityUtils.getLoginUser();
        if (StringUtils.isNull(loginUser) || CollectionUtils.isEmpty(loginUser.getUser().getRoles()))
        {
            return false;
        }
        for (String role : roles.split(Constants.ROLE_DELIMITER))
        {
            if (hasRole(role))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否包含权限
     * 
     * @param permissions 权限列表
     * @param permission 权限字符串
     * @return 用户是否具备某权限
     */
    private boolean hasPermissions(Set<String> permissions, String permission)
    {
        return permissions.contains(Constants.ALL_PERMISSION) || permissions.contains(StringUtils.trim(permission));
    }

    /**
     * 在真实 HTTP 请求中记录数据权限表达式，领域线程和调度线程则跳过请求级副作用。
     *
     * @param permission String，当前正在判定的单个或多个权限表达式
     * @return 无返回值；没有 Servlet 请求上下文时保持无副作用
     */
    private void bindPermissionContext(String permission)
    {
        // 工作流领域服务可在受控并发线程中复用本服务，不能强制依赖 DispatcherServlet 绑定。
        if (RequestContextHolder.getRequestAttributes() != null)
        {
            PermissionContextHolder.setContext(permission);
        }
    }

    /**
     * 对工作流权限实时复核当前用户与有效角色、菜单的正式关系。
     *
     * @param loginUser LoginUser，当前认证 Token 中的用户快照
     * @param permission String，待复核的单个权限字符串
     * @return boolean，非工作流权限或超级管理员返回 true；普通用户仅在数据库仍授权时返回 true
     */
    private boolean hasCurrentWorkflowPermission(LoginUser loginUser,
            String permission)
    {
        String normalizedPermission = StringUtils.trim(permission);
        if (!normalizedPermission.startsWith(WORKFLOW_PERMISSION_PREFIX))
        {
            return true;
        }
        Long userId = loginUser.getUserId();
        if (userId == null)
        {
            return false;
        }
        if (SecurityUtils.isAdmin(userId))
        {
            return true;
        }

        Set<String> currentPermissions = menuService.selectMenuPermsByUserId(userId);
        return !CollectionUtils.isEmpty(currentPermissions)
                && hasPermissions(currentPermissions, normalizedPermission);
    }
}
