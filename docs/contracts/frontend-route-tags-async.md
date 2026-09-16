# 路由和标签操作的异步返回契约

`permission.generateRoutes()` 返回菜单请求及后续转换、注册、状态写入的实际 Promise 链。成功返回扁平化的可注册路由；网络拒绝或转换、写入抛错沿同一链拒绝，不转成空路由成功。侧边栏、默认路由、顶部导航和动态注册树继续保留各自结构、常量路由组合方式及权限／角色筛选。

`src/permission.js` 仍负责既有失败策略：捕获错误后等待 `user.logOut()`，提示该错误并导航至 `/`；无 token 时后续导航进入登录页。本契约只保证该分支可达，不改变认证、退出登录或页面离开确认策略。

标签删除 action 继续返回 Promise，供 tab 插件与 TagsView 组件使用 `.then`。单项动作返回当前数组的浅副本；`delView`、`delOthersViews`、`delAllViews` 返回 `{ visitedViews, cachedViews }`，两个字段也都是浅副本。组合动作按 visited／iframe／持久化、cached 的顺序等待子动作，将子动作异常传给调用方。

左右关闭以目标标签的 path 定位，保持标签顺序、固定标签、缓存和 iframe 的既有处理。目标已经不存在（包括空列表）时，返回当前 `visitedViews` 的浅副本，不删除任何标签、不写持久化。持久化键 `tags-view-visited`、保存字段、固定标签排除、恢复逻辑和清空方式不变。

Storage 写入或移除失败会拒绝当前动作及其组合动作；这不提供事务回滚，失败前已经完成的内存修改仍可能存在，组合动作不会继续执行后续子动作。此处没有增加缓存协调、重试或超时机制。无数据格式变化，也无需迁移；回退代码会恢复旧的 Promise 断链问题。

## 正式验证入口与边界

在 `ruoyi-ui` 下运行：

```powershell
node --test tests/contracts/router-tags-async.test.js
npm run test:contracts
npm run lint
npm run build:prod
```

正式测试复用仓库已有的源码加载方式，只处理 import/export、Vite glob 和自动导入绑定，执行生产 store、菜单 API、auth、cache、tab 插件与完整路由守卫；Pinia 和 Vue Router 的内存历史使用真实依赖，不复制 action 逻辑。

- 菜单请求在 request 边界用可控 Promise 替代，覆盖成功、空树、原错误拒绝和非法响应导致的转换错误。真实 Pinia 同步订阅者抛错验证写入异常传播；没有篡改 store 的内部数据不变量。
- 守卫通过真实 router 导航运行，用户信息／登出、token、进度条与消息 UI 是替身，因此可证明既有 catch、登出调用、错误对象和后续导航，不能宣称真实后端登录或浏览器 UI 已验收。
- Storage 使用内存替身，故障在 `setItem`／`removeItem` 边界抛出 `QuotaExceededError`／`SecurityError`，生产 cache 和子 action 全程执行；这证明存储故障可达调用方，不代表已触发浏览器的真实配额或权限限制。
- 测试覆盖左右关闭存在／缺失／空列表、固定标签、缓存、iframe、持久化及恢复、组合返回值、`.then` 调用和页面离开取消。有限等待只用于判定测试中的 pending，不存在生产超时兜底。
