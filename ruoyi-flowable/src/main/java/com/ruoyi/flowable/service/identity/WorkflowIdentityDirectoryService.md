# WorkflowIdentityDirectoryService

## 组件简介与作用

`WorkflowIdentityDirectoryService` 从 `sys_user`、`sys_role`、`sys_dept` 及正式关联表提供有界分页的工作流身份目录。它为通用主数据、直接办理人和候选认领身份提供不同查询契约，使每个目录项明确表达对应执行资格。

对外 HTTP 入口为 `GET /workflow/identity/options`，由 `WfIdentityController` 执行菜单权限和参数注解校验；本服务再执行服务层边界和实时 RBAC 查询。

## 公开方法

| 方法 | 入参 | 返回值 |
| --- | --- | --- |
| `listOptions(type, keyword, pageNum, pageSize)` | `type` 为 `user`/`role`/`dept`，可选检索词和分页 | 启用且未删除的通用身份页 |
| `listOptions(type, keyword, pageNum, pageSize, capability)` | 在通用参数上增加 `approval` 或 `claim` | 按正式资格过滤的最小身份页 |

返回行采用 `value`、`label` 和 `type` 三字段白名单；密码、手机、邮箱、角色菜单明细及其他组织敏感字段保留在主数据边界。

## 能力与类型组合

| capability | 允许类型 | 服务端资格 |
| --- | --- | --- |
| 空 | `user` / `role` / `dept` | 主数据启用且未删除 |
| `approval` | 仅 `user` | 用户具备 `todoList`、`query`、`approval` |
| `claim` | `user` / `role` / `dept` | 用户具备完整五项权限；角色或部门至少有一名这样的有效成员 |

普通用户的权限按全部启用、未删除、非管理员角色并集计算；只有 `user_id=1` 保留超级管理员语义。返回角色和部门值分别使用规范 `ROLE<id>` 和 `DEPT<id>`。

## 分页、检索与异常

- `pageNum` 为 `1..1000000`，`pageSize` 为 `1..200`；偏移量使用 `long` 计算。
- `keyword` 去除首尾空白后最长 `64` 字符；用户匹配姓名或账号，角色匹配名称或 `role_key`，部门匹配名称。
- 非法类型、能力组合、检索长度或分页返回 `400`。Mapper 返回 `null` 或超出请求页大小视为主数据异常并返回 `500`。

## 关键设计

计数和分页列表使用同一组 SQL 资格谓词，保证 `total` 与 `rows` 一致。目录只用于改善选择交互，不是写命令授权缓存；部署、认领、动态加签和委派/转办仍必须在写入前实时重查。

## 最小接入示例

```java
PageResult<WorkflowIdentityOptionView> assignees =
        identityDirectoryService.listOptions("user", keyword, 1, 50, "approval");

PageResult<WorkflowIdentityOptionView> candidateRoles =
        identityDirectoryService.listOptions("role", keyword, 1, 50, "claim");
```

HTTP 查询示例：

```text
GET /workflow/identity/options?type=dept&capability=claim&pageNum=1&pageSize=50
```
