# ProcessViewer

## 组件简介

`ProcessViewer` 使用 `bpmn-js` 只读加载后端授权后返回的 BPMN XML，并按后端 `WorkflowProcessViewerView` 投影展示已完成、当前、驳回和退回轨迹。组件不自行推断流程状态，也不直接访问流程接口。

## 使用方式

```vue
<ProcessViewer
  :xml="detail.bpmnXml"
  :state="detail.flowViewer"
  height="480px"
  :file-name="detail.processKey"
  @error="handleViewerError"
/>
```

## Props

| 参数 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `xml` | `string` | `''` | 完整 BPMN 2.0 XML，必须来自已授权后端接口。 |
| `state` | `object` | `{}` | `finishedActivityIds`、`finishedSequenceFlowIds`、`unfinishedActivityIds`、`rejectedActivityIds`、`returnedActivityIds` 集合。 |
| `height` | `string` | `'440px'` | 查看器稳定高度。 |
| `fileName` | `string` | `'workflow'` | 下载 SVG 的文件名前缀。 |

## Emits

| 事件 | 参数 | 说明 |
| --- | --- | --- |
| `loaded` | 无 | XML 成功导入并完成轨迹标记。 |
| `error` | `Error` | XML 导入或 SVG 导出失败。 |

## 公开方法

| 方法 | 返回值 | 说明 |
| --- | --- | --- |
| `fitViewport()` | `void` | 将完整流程图适配到当前画布。 |
| `downloadSvg()` | `Promise<void>` | 下载当前已加载流程图 SVG。 |

## 关键设计

- 流程状态全部由服务端计算，前端只按元素 ID 着色。
- 后端轨迹包含旧版本中已不存在的节点时会忽略该节点，不影响其他轨迹展示。
- XML 为空或导入失败时清空旧画布，避免错误展示上一次流程。
- 组件位于未激活 tab 或尚未展开的弹窗时不会用零尺寸计算缩放比例；容器可见后会通过 `ResizeObserver` 自动适配视口。
- minimap 控件与 `bpmn.io` 署名保持独立位置，窄视口下也不会互相遮挡。
