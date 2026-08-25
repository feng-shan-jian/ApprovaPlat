# PermissionService

## 组件作用

`PermissionService` 是 Spring Security `@PreAuthorize` 中 `@ss` 表达式的统一权限边界。它保留若依原有 Token 权限语义，并对 `workflow:*` 权限增加当前用户-角色-菜单正式数据的实时复核。

## 使用方式

Controller 继续使用既有 SpEL 表达式，由表达式统一调用本服务：

```java
@PreAuthorize("@ss.hasPermi('workflow:process:approval')")
```

`hasPermi(String)` 校验单个权限，`hasAnyPermi(String)` 按若依分隔符校验任一权限；角色方法保持既有语义。

## 关键约束

- 普通用户的工作流权限必须同时存在于登录 Token 快照和 `ISysMenuService.selectMenuPermsByUserId(...)` 实时结果中。
- 撤销 `sys_user_role` 关系、停用角色或停用菜单后，下一次请求按实时权限返回 403。
- 权限快照在登录时生成；新增权限生效后，用户重新登录即可取得新快照。
- 用户 `1` 保留若依超级管理员语义；非工作流权限不增加数据库查询。
- 主数据查询异常由全局异常链记录，并以失败关闭语义终止当前请求。

## 最小接入示例

```java
@PreAuthorize("@ss.hasAnyPermi('workflow:process:approval,workflow:process:terminate')")
public AjaxResult updateWorkflowState()
{
    return success();
}
```

入口权限只是第一道门禁。业务 Service 仍必须在同一事务内完成对象授权、任务办理人、流程状态和数据一致性校验。
