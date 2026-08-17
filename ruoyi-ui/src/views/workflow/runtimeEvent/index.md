# 运行事件审计页面

## 作用

`index.vue` 分页查询消息、信号和 ReceiveTask 外部运行事件的正式审计台账，用于排查关联、幂等和处理结果。

## 使用方式

路由组件名为 `WorkflowRuntimeEvent`，正式菜单路径为 `workflow/runtimeEvent/index`。页面支持按事件类型、状态、关联来源、关键字段及请求时间筛选，并在缓存页重新激活时刷新。

## Props 与 Emits

本页面无 props、emits 或公开方法。

## 关键设计

- 页面只展示服务端脱敏视图，不获取变量正文、Token 正文或 Token 哈希。
- `requestId`、凭据主键、匹配类型、结果码和完成时间来自正式数据库台账。
- 页面默认每页 20 条，通过若依标准分页器展示后端 `total`；筛选与翻页均由服务端执行，可以访问固定 1000 条边界之外的旧记录。

## 最小接入

```js
{
  path: 'runtimeEvent',
  component: () => import('@/views/workflow/runtimeEvent/index.vue'),
  name: 'WorkflowRuntimeEvent'
}
```
