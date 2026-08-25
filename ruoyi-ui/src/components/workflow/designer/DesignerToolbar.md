# DesignerToolbar

## 组件简介与作用

`DesignerToolbar` 是 BPMN 设计器的无状态命令工具栏，统一承载导入导出、源码预览、清空、撤销重做、缩放、对齐分布、Token 模拟、校验、设置、属性面板和保存入口。全部动作通过事件交由父设计器操作 Modeler 并调用后端。

## 使用方式

```vue
<DesignerToolbar
  :locked="saving"
  :can-undo="canUndo"
  :can-redo="canRedo"
  :selection-count="selection.length"
  :issue-count="issues.length"
  @undo="undo"
  @export="exportDiagram"
  @save="requestSave"
/>
```

## Props

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `locked` | `boolean` | 序列化或后端保存期间锁定全部命令。 |
| `canUndo` / `canRedo` | `boolean` | 命令栈状态。 |
| `selectionCount` | `number` | 用于控制对齐、分布命令可用性。 |
| `simulationActive` | `boolean` | Token 模拟运行状态。 |
| `propertiesCollapsed` | `boolean` | 属性面板折叠状态。 |
| `issueCount` | `number` | 客户端与服务端诊断数量。 |
| `validating` | `boolean` | 服务端校验加载状态。 |

## Emits

`import`、`clear`、`undo`、`redo`、`fit`、`toggle-simulation`、`validate`、`settings`、`toggle-properties` 和 `save` 无参数；`export`、`preview`、`align`、`distribute` 传递对应命令字符串；`zoom` 传递缩放倍率。

## 公开方法

无。所有调用通过 Props 与 Emits 完成。

## 关键设计思路

- 保存按钮使用独立 `workflow:model:save` 权限门禁，设计与保存权限分别校验。
- 工具栏只负责可用状态和命令分发，真实 XML、SVG、校验与持久化仍由父组件和页面处理。
- 对齐在至少两个元素选中时启用，等距分布在至少三个元素选中时启用。
- 工具栏使用容器查询在紧凑工作区隐藏低优先级文字标签，保留图标、`aria-label` 和 tooltip；左右命令组采用弹性列宽，使属性面板持续位于视口内。
