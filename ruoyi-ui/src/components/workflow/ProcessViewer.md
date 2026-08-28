# ProcessViewer

## 组件简介

`ProcessViewer` 使用 `bpmn-js` 只读加载后端授权后返回的 BPMN XML，并按后端 `WorkflowProcessViewerView` 投影展示已完成、当前、驳回和退回轨迹。流程状态完全来自服务端投影，父组件负责访问流程接口并传入数据。

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
- 后端轨迹引用先前部署版本且当前 XML 缺少对应节点时，Viewer 跳过该节点并继续展示其余有效轨迹。
- XML 为空或导入失败时清空画布并显示当前空状态。
- 组件位于待激活 tab 或待展开弹窗时延后缩放计算；容器可见后通过 `ResizeObserver` 自动适配视口。
- minimap 控件与 `bpmn.io` 署名保持独立位置，窄视口下分别占用固定区域。
