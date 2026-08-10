# PermissionService

## 组件作用

`PermissionService` 是 Spring Security `@PreAuthorize` 中 `@ss` 表达式的统一权限边界。它保留若依原有 Token 权限语义，并对 `workflow:*` 权限增加当前用户-角色-菜单正式数据的实时复核。

## 使用方式

Controller 继续使用既有表达式，不直接调用服务：

```java
@PreAuthorize("@ss.hasPermi('workflow:process:approval')")
```

`hasPermi(String)` 校验单个权限，`hasAnyPermi(String)` 按若依分隔符校验任一权限；角色方法保持既有语义。

## 关键约束

- 普通用户的工作流权限必须同时存在于登录 Token 快照和 `ISysMenuService.selectMenuPermsByUserId(...)` 实时结果中。
- 撤销 `sys_user_role` 关系、停用角色或停用菜单后，旧 Token 不能继续访问 `workflow:*` 入口。
- 新增权限不会自动扩大旧 Token 权限；用户仍需重新登录取得新快照。
- 用户 `1` 保留若依超级管理员语义；非工作流权限不增加数据库查询。
- 主数据查询异常不降级为放行，由全局异常链记录并拒绝当前请求。

## 最小接入示例

```java
@PreAuthorize("@ss.hasAnyPermi('workflow:process:approval,workflow:process:terminate')")
public AjaxResult updateWorkflowState()
{
    return success();
}
```

入口权限只是第一道门禁。业务 Service 仍必须在同一事务内完成对象授权、任务办理人、流程状态和数据一致性校验。
