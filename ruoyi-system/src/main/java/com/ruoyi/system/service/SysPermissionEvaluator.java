package com.ruoyi.system.service;

import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import com.ruoyi.common.constant.Constants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;

/** 当前登录用户的 Token 权限与必要实时主数据权限判定，不读写 Web 请求上下文。 */
@Service
public class SysPermissionEvaluator
{
    private static final String WORKFLOW_PERMISSION_PREFIX = "workflow:";
    private final ISysMenuService menuService;

    public SysPermissionEvaluator(ISysMenuService menuService)
    {
        this.menuService = menuService;
    }

    /** 领域调用继续使用 Spring Security 登录人，不使用 Flowable 代执行身份。 */
    public boolean hasPermi(String permission)
    {
        if (StringUtils.isEmpty(permission))
        {
            return false;
        }
        return hasPermi(SecurityUtils.getLoginUser(), permission);
    }

    /** Web 入口可复用已经检查并绑定请求上下文的同一登录快照。 */
    public boolean hasPermi(LoginUser loginUser, String permission)
    {
        if (StringUtils.isEmpty(permission) || loginUser == null
                || CollectionUtils.isEmpty(loginUser.getPermissions()))
        {
            return false;
        }
        return hasPermissions(loginUser.getPermissions(), permission)
                && hasCurrentWorkflowPermission(loginUser, permission);
    }

    public boolean hasAnyPermi(LoginUser loginUser, String permissions)
    {
        if (StringUtils.isEmpty(permissions) || loginUser == null
                || CollectionUtils.isEmpty(loginUser.getPermissions()))
        {
            return false;
        }
        for (String permission : permissions.split(Constants.PERMISSION_DELIMITER))
        {
            if (permission != null && hasPermissions(loginUser.getPermissions(), permission)
                    && hasCurrentWorkflowPermission(loginUser, permission))
            {
                return true;
            }
        }
        return false;
    }

    private boolean hasPermissions(Set<String> permissions, String permission)
    {
        return permissions.contains(Constants.ALL_PERMISSION)
                || permissions.contains(StringUtils.trim(permission));
    }

    private boolean hasCurrentWorkflowPermission(LoginUser loginUser, String permission)
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
