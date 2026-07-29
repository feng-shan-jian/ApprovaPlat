# TreePanel

## 组件简介

`TreePanel` 是管理页面使用的可搜索树形侧栏。组件封装了树节点筛选、展开/收起、侧栏折叠、桌面端拖拽调宽和宽度持久化，并将 Element Plus 树事件透传给业务页面。

在 `768px` 以下的窄屏中，外层使用 `.tree-sidebar-manage-wrap` 时，树面板会显示在业务内容上方；桌面端仍使用可拖拽的左右分栏。

## 使用方式

```vue
<template>
  <div class="app-container tree-sidebar-manage-wrap">
    <tree-panel
      ref="treeRef"
      title="组织机构"
      :tree-data="deptOptions"
      search-placeholder="请输入部门名称"
      storage-key="dept-sidebar-width"
      :default-expand-all="true"
      @node-click="handleNodeClick"
      @refresh="loadTree"
    />

    <div class="tree-sidebar-content">
      <div class="content-inner">
        <!-- 业务查询区和数据区 -->
      </div>
    </div>
  </div>
</template>
```

## Props

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `treeData` | `Array` | `[]` | 树节点数据。 |
| `title` | `String` | `树形结构` | 面板标题。 |
| `titleIcon` | `String \| Object` | `OfficeBuilding` | 标题图标名称或组件。 |
| `showSearch` | `Boolean` | `true` | 是否显示树搜索框。 |
| `searchPlaceholder` | `String` | `请输入名称` | 搜索框占位文本。 |
| `defaultCollapsed` | `Boolean` | `false` | 是否默认折叠面板。 |
| `treeProps` | `Object` | `{ children: 'children', label: 'label' }` | Element Plus 树字段映射。 |
| `nodeKey` | `String` | `id` | 节点唯一键字段。 |
| `expandOnClickNode` | `Boolean` | `false` | 点击节点时是否切换展开状态。 |
| `showCheckbox` | `Boolean` | `false` | 是否显示节点复选框。 |
| `checkStrictly` | `Boolean` | `false` | 父子节点勾选是否互不关联。 |
| `defaultExpandAll` | `Boolean` | `false` | 是否默认展开全部节点。 |
| `defaultExpandedKeys` | `Array` | `[]` | 默认展开的节点键数组。 |
| `defaultWidth` | `Number` | `220` | 桌面端默认宽度，单位为像素。 |
| `collapsedWidth` | `Number` | `20` | 折叠状态内部记录的宽度，单位为像素。 |
| `minWidth` | `Number` | `180` | 桌面端拖拽最小宽度。 |
| `maxWidth` | `Number` | `400` | 桌面端拖拽最大宽度。 |
| `storageKey` | `String` | `tree-sidebar-width` | 持久化侧栏宽度的本地存储键。多页面接入时应使用不同键名。 |
| `enableStorage` | `Boolean` | `true` | 是否持久化桌面端侧栏宽度。 |
| `filterMethod` | `Function \| null` | `null` | 自定义节点过滤函数，签名为 `(keyword, nodeData) => boolean`。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `collapsed-change` | `(collapsed: boolean)` | 面板折叠状态变化。 |
| `expanded-all-change` | `(expanded: boolean)` | 全部节点展开状态变化。 |
| `refresh` | `()` | 点击刷新按钮。 |
| `node-click` | `(data, node, event)` | 点击树节点。 |
| `check` | `(data, checkedInfo)` | 节点勾选状态变化。 |
| `node-expand` | `(data, node, event)` | 节点展开。 |
| `node-collapse` | `(data, node, event)` | 节点收起。 |
| `search` | `(keyword: string)` | 搜索关键词变化。 |

```vue
<tree-panel
  :tree-data="treeData"
  @node-click="(data) => selectNode(data.id)"
  @search="(keyword) => recordSearch(keyword)"
/>
```

## 公开方法

| 方法 | 说明 |
| --- | --- |
| `setCurrentKey(key)` | 设置当前选中节点。 |
| `getCurrentNode()` | 获取当前选中节点数据。 |
| `getCurrentKey()` | 获取当前选中节点键。 |
| `setCheckedKeys(keys)` | 设置已勾选节点键。 |
| `getCheckedKeys()` | 获取已勾选节点键。 |
| `getCheckedNodes()` | 获取已勾选节点数据。 |
| `clearSearch()` | 清空搜索关键词。 |
| `filter(keyword)` | 主动执行树过滤。 |
| `resetWidth()` | 恢复桌面端默认宽度。 |
| `getCurrentWidth()` | 获取当前内部宽度。 |
| `setWidth(width)` | 在最小和最大宽度范围内设置桌面端宽度。 |
| `expandAllNodes()` | 展开全部节点。 |
| `collapseAllNodes()` | 收起全部节点。 |
| `toggleCollapsed()` | 切换面板折叠状态。 |

```js
const treeRef = ref()

treeRef.value?.setCurrentKey(targetId)
treeRef.value?.filter('研发')
```

## 关键设计

- 桌面端宽度在 `minWidth` 与 `maxWidth` 之间拖拽，并按 `storageKey` 持久化。
- 窄屏布局只改变面板与内容区的排列方式，不改变节点数据、事件和持久化宽度；返回桌面宽度后继续使用已保存宽度。
- 页面应使用 `.tree-sidebar-manage-wrap`、`.tree-sidebar-content` 和 `.content-inner` 结构，以获得统一的分栏、滚动和响应式行为。
- `storageKey` 必须按业务页面区分，避免多个树面板互相覆盖宽度设置。
