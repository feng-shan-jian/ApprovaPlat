# L05 / BS-042：部门与菜单树组装

## 责任与行为

`SysDeptServiceImpl` 和 `SysMenuServiceImpl` 在当前调用内按 `parentId` 分组，使用 ID 集合判断编辑树根，再按原输入顺序展开可达子项。删除两处 `hasChild`、`getChildList` 及重复全表扫描；不增加公共树框架、缓存、跨请求状态或数据库查询。

| 入口 | 保持的业务语义 |
| --- | --- |
| `buildDeptTree` / `buildMenuTree` | 父 ID 不在本次输入中的节点作为根；根与同层顺序取自输入；没有根时返回原输入列表 |
| `buildDeptTreeSelect` / `buildMenuTreeSelect` | 继续使用正式 `TreeSelect` 转换，保留 `id`、`label`、`disabled` 与非空 `children` JSON |
| `getChildPerms(list, parentId)` | 只从明确指定父 ID 开始；不把授权列表中的孤立节点提升为路由根 |
| `selectMenuTreeByUserId` → `buildMenus` | 保留管理员/用户查询分流、目录/页面/按钮过滤、隐藏标记、缓存、查询参数、路由名称、内链与组件转换 |

组装保留输入对象及其业务字段，不排序或修改输入列表。每次调用先将输入对象的 `children` 设为空，再连接本次可达子项，避免复用相同对象进行权限裁剪时泄漏上次的子节点。无根回退保持平面列表；不可达闭环不连接为循环对象。

正式查询提供主键唯一的部门/菜单行，并负责权限过滤与数据库排序。部门关系合法性仍由原 C07 事务写入口维护；菜单写入与权限规则沿用原有边界。本单元不增加循环检测，也不把树渲染扩展为关系合法性巡检。递归深度与合法树的层级一致。

组装不再为每个节点扫描整个输入；分组、根判断和可达节点遍历使用局部索引。这里描述算法和调用关系，未进行耗时基准测试，不宣称实际响应时间提升。

## 调用方核对

- 部门：`SysUserController.deptTree`、`SysRoleController.deptTree` → `selectDeptTreeList` → 经原 DataScope 代理的 `selectDeptList` → `buildDeptTreeSelect` → `buildDeptTree`。
- 菜单编辑：`SysMenuController.treeselect` / `roleMenuTreeselect` → `selectMenuList` → `buildMenuTreeSelect` → `buildMenuTree`。
- 授权路由：`SysLoginController.getRouters` → `selectMenuTreeByUserId` → `getChildPerms(..., 0)` → `buildMenus`。
- 前端消费者：用户页的部门树与禁用项过滤、角色页的菜单/部门授权树、`store/modules/permission.js` 中的 `Layout` / `ParentView` / `InnerLink` 解析均使用原 JSON 字段。
- 部门管理页仍消费平面 `/system/dept/list`；本次不改变该接口或前端 `handleTree`。
- `ISysDeptService`、`ISysMenuService` 的公开签名保持；`getChildPerms` 的公开签名保持。删除的私有辅助方法没有外部、反射或配置调用者。菜单删除约束使用的 `hasChildByMenuId` 保留。

## 正式验证

`ruoyi-flowable` 已有系统服务测试体系，新增 `SysTreeAssemblyTest` 直接运行两份生产服务，覆盖空树、乱序输入的稳定同层顺序、多层/多根、孤立根、显式父级路由、原无根回退、不可达闭环、复用对象后的裁剪，以及完整 TreeSelect/嵌套路由/顶层页面/内链 JSON。

`SysOrganizationScopeMySqlIT` 继续使用真实 DataScope、Spring 事务代理和正式 MyBatis XML，并增加部门树数据范围与菜单授权树用例。部门自定义范围、仅本部门、部门及子部门、仅本人、停用角色的结果在真实 SQL 后组装；菜单用例验证编辑孤立根、未授权父目录不生成路由、SQL 顺序、按钮/停用菜单/未授权菜单排除、管理员和停用角色分支。原 C07 移动、回滚和并发用例不改。

执行入口：

```text
mvn -pl ruoyi-flowable -am -Dtest=SysTreeAssemblyTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl ruoyi-flowable -am -Pworkflow-mysql-it -Dit.test=SysOrganizationScopeMySqlIT verify
mvn clean verify
```

MySQL 回归使用任务独占的 MySQL 8.4.9 / 33347，按 `docker/mysql/init.sh` 的 14 份正式基线初始化 `approvaplat_it`。三个连接环境变量仅注入 Maven 子进程；共享服务及环境变量不改。最终结果与 XML 数量随交付提供，不混合不同运行计数。

浏览器、登录、真实 HTTP 与前端构建未执行；本次没有前端修改，静态核对消费者不代表浏览器验收。

## 恢复

没有数据库结构或数据迁移，也没有接口迁移。恢复两份服务的原实现即可撤回组装调整；对应测试与此说明应同步恢复。清理仅涉及任务生成的构建产物、日志和独占数据库目录，正式测试保留。
