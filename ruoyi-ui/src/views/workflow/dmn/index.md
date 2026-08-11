# WorkflowDmn

## 组件简介

`WorkflowDmn` 是 Flowable 官方 DMN 来源版本管理页面。页面通过真实后端 API 查询、部署和删除 DMN 资源，不在浏览器中伪造决策目录。BusinessRuleTask 设计器选项来自同一正式目录，并持久化精确 `decisionId`。

## 使用方式

页面由动态菜单组件路径 `workflow/dmn/index` 加载，无 props、emits 或公开方法。访问需要 `workflow:dmn:list`；部署和删除按钮分别需要 `workflow:dmn:add`、`workflow:dmn:remove`。

## Props

无。

## Emits

无。

## 公开方法

无。页面内部在首次挂载和缓存页重新激活时刷新正式目录。

## 关键设计

- 文件和文本输入都提交完整 DMN XML；服务端统一执行大小、DTD、实体及官方解析门禁。
- 目录只展示来源部署，流程冻结子部署不会成为新的设计选项。
- 删除按部署执行；只要 Flowable 业务制品 `approvaplat/dmn-v1.json` 仍包含引用，服务端返回冲突且不产生副作用。
- 页面不提供简化决策表编辑器，避免把不完整的前端配置冒充官方 DMN XML。

## 最小接入示例

```sql
INSERT INTO sys_menu (menu_name, parent_id, path, component, route_name, menu_type, perms)
VALUES ('DMN 决策', 1, 'dmn', 'workflow/dmn/index', 'WorkflowDmn', 'C', 'workflow:dmn:list');
```
