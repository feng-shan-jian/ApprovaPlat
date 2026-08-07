# 扩展注册表页面

## 页面作用

`index.vue` 是 BPMN 受控扩展目录的正式管理入口。页面读取全部目录、停用状态、最新版和服务端已安装 Java 处理器，不缓存或模拟扩展数据。

## 使用方式

菜单组件路径为 `workflow/extension/index`，路由名为 `WorkflowExtension`。正式菜单权限为 `workflow:extension:list`。

写操作由按钮权限独立控制：

- `workflow:extension:add`：创建尚无版本的目录。
- `workflow:extension:version:add`：从已安装处理器发布下一个不可变版本。
- `workflow:extension:edit`：启用或停用目录。
- `workflow:extension:remove`：删除已停用且从未被部署快照引用的非内置目录。

## Props

页面组件没有 props。当前用户和权限由全局认证状态及 `v-hasPermi` 指令提供。

## Emits

页面组件没有 emits。所有业务变化通过 `src/api/workflow/extension.js` 调用正式后端 API。

## 公开方法

页面不公开实例方法。缓存页重新激活时会自动调用 `loadRegistry()` 回读数据库状态。

## 关键设计

- 管理清单使用 `/workflow/extension/list`，包含停用和尚未发布版本的目录。
- 设计器选项仍使用 `/workflow/extension/options/java`，只返回可部署的已启用最新版。
- 发布版本只能选择 `/workflow/extension/installed-handlers/java` 返回的代码安装处理器。
- 停用目录不删除版本，也不修改既有部署快照和在途实例。
- 删除先由后端锁定目录；系统内置、仍启用或已有部署快照引用的目录均返回冲突，只有未部署目录会在同一事务内按外键顺序删除版本和目录。

## 最小接入示例

```sql
('workflow:extension:list', 'workflow', '扩展注册表', 5, 'extension',
 'workflow/extension/index', 'WorkflowExtension', 'C',
 'workflow:extension:list', 'connection', 'BPMN 受控扩展管理')
```
