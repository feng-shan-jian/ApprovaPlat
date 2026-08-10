<template>
  <div class="workflow-viewer" :style="viewerStyle">
    <div class="workflow-viewer__toolbar">
      <el-tooltip content="缩小" placement="bottom">
        <el-button circle text icon="ZoomOut" aria-label="缩小流程图" @click="zoomBy(0.85)" />
      </el-tooltip>
      <el-tooltip content="适应窗口" placement="bottom">
        <el-button circle text icon="FullScreen" aria-label="适应窗口" @click="fitViewport" />
      </el-tooltip>
      <el-tooltip content="放大" placement="bottom">
        <el-button circle text icon="ZoomIn" aria-label="放大流程图" @click="zoomBy(1.15)" />
      </el-tooltip>
      <el-tooltip content="下载 SVG" placement="bottom">
        <el-button circle text icon="Download" aria-label="下载流程图" :disabled="!ready" @click="downloadSvg" />
      </el-tooltip>
    </div>
    <div ref="canvasRef" class="workflow-viewer__canvas" v-loading="loading" />
    <el-empty v-if="errorMessage && !loading" class="workflow-viewer__empty" :description="errorMessage" :image-size="72" />
  </div>
</template>

<script setup name="ProcessViewer">
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer'
import minimapModule from 'diagram-js-minimap'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'
import 'diagram-js-minimap/assets/diagram-js-minimap.css'
import Download from '@/plugins/download'
import flowableModdle from './bpmn/flowableModdle'

const props = defineProps({
  /** 后端授权后返回的完整 BPMN XML。 */
  xml: { type: String, default: '' },
  /** 后端计算的流程轨迹集合，键名与 WorkflowProcessViewerView 一致。 */
  state: { type: Object, default: () => ({}) },
  /** 查看器稳定高度，可使用任意合法 CSS 长度。 */
  height: { type: String, default: '440px' },
  /** 导出 SVG 时使用的文件名前缀。 */
  fileName: { type: String, default: 'workflow' }
})

const emit = defineEmits(['loaded', 'error'])
const canvasRef = ref(null)
const loading = ref(false)
const ready = ref(false)
const errorMessage = ref('')
const viewerStyle = computed(() => ({ height: props.height }))
let viewer
let resizeObserver
let fitAnimationFrame

/**
 * 初始化 BPMN 只读查看器。
 * @returns {void} 查看器实例保存在组件内部。
 */
function createViewer() {
  if (!canvasRef.value || viewer) return
  viewer = new NavigatedViewer({
    container: canvasRef.value,
    additionalModules: [minimapModule],
    moddleExtensions: { flowable: flowableModdle }
  })
}

/**
 * 导入后端 BPMN XML，并在成功后应用服务端计算的轨迹标记。
 * @param {string} xml BPMN 2.0 XML 正文。
 * @returns {Promise<void>} 导入完成后触发 loaded，失败触发 error。
 */
async function importXml(xml) {
  createViewer()
  ready.value = false
  errorMessage.value = ''
  if (!xml || !xml.trim()) {
    clearDiagram()
    errorMessage.value = '暂无流程图'
    return
  }
  loading.value = true
  try {
    await viewer.importXML(xml)
    applyMarkers()
    ready.value = true
    // XML 导入成功不代表隐藏容器已经完成布局；等待 Vue 刷新后再尝试适配视口。
    await nextTick()
    fitViewport()
    emit('loaded')
  } catch (error) {
    clearDiagram()
    errorMessage.value = '流程图加载失败'
    emit('error', error)
  } finally {
    loading.value = false
  }
}

/**
 * 清空当前画布，避免切换到非法或空 XML 时继续展示上一张流程图。
 * @returns {void} 无返回值。
 */
function clearDiagram() {
  ready.value = false
  if (fitAnimationFrame) cancelAnimationFrame(fitAnimationFrame)
  fitAnimationFrame = undefined
  if (viewer) viewer.clear()
}

/**
 * 根据后端轨迹投影给存在的 BPMN 元素添加状态标记。
 * @returns {void} 不存在的历史元素会被忽略，避免旧模型版本导致页面崩溃。
 */
function applyMarkers() {
  if (!viewer) return
  const canvas = viewer.get('canvas')
  const registry = viewer.get('elementRegistry')
  // 后端轨迹字段只允许映射到预定义 CSS 标记，避免历史状态被解释为任意类名。
  const markerMap = {
    finishedActivityIds: 'workflow-finished',
    finishedSequenceFlowIds: 'workflow-finished-flow',
    unfinishedActivityIds: 'workflow-current',
    rejectedActivityIds: 'workflow-rejected',
    returnedActivityIds: 'workflow-returned'
  }
  Object.entries(markerMap).forEach(([key, marker]) => {
    Array.from(props.state?.[key] || []).forEach(elementId => {
      if (registry.get(elementId)) canvas.addMarker(elementId, marker)
    })
  })
}

/**
 * 判断画布是否已经具备可供 bpmn-js 计算缩放比例的有效尺寸。
 * @returns {boolean} 画布宽高均为正数时返回 true。
 */
function hasMeasurableCanvas() {
  if (!canvasRef.value) return false
  const { width, height } = canvasRef.value.getBoundingClientRect()
  return Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0
}

/**
 * 在下一帧重试视口适配，避免 tab 或弹窗刚显示时读取到过渡中的尺寸。
 * @returns {void} 重复调度时只保留最后一次任务。
 */
function scheduleFitViewport() {
  if (fitAnimationFrame) cancelAnimationFrame(fitAnimationFrame)
  fitAnimationFrame = requestAnimationFrame(() => {
    fitAnimationFrame = undefined
    fitViewport()
  })
}

/**
 * 将流程图缩放到完整可见区域。
 * @returns {void} 查看器未就绪或画布仍隐藏时延后到尺寸变化后执行。
 */
function fitViewport() {
  if (!viewer || !ready.value || !hasMeasurableCanvas()) return
  const canvas = viewer.get('canvas')
  canvas.resized()
  canvas.zoom('fit-viewport')
}

/**
 * 基于当前缩放比例增量缩放并保持视口中心。
 * @param {number} factor 正数缩放倍率。
 * @returns {void} 无返回值。
 */
function zoomBy(factor) {
  if (!viewer || !ready.value || !hasMeasurableCanvas()) return
  const canvas = viewer.get('canvas')
  const current = canvas.zoom()
  if (!Number.isFinite(current) || current <= 0) {
    fitViewport()
    return
  }
  canvas.zoom(Math.min(4, Math.max(0.2, current * factor)))
}

/**
 * 监听画布从隐藏到可见及容器尺寸变化，并在布局稳定后重新适配流程图。
 * @returns {void} 浏览器不支持 ResizeObserver 时保留首次渲染的降级行为。
 */
function observeCanvasSize() {
  if (!canvasRef.value || typeof ResizeObserver === 'undefined') return
  resizeObserver = new ResizeObserver(() => {
    if (ready.value && hasMeasurableCanvas()) scheduleFitViewport()
  })
  resizeObserver.observe(canvasRef.value)
}

/**
 * 导出当前已授权流程图为 SVG 文件。
 * @returns {Promise<void>} 导出失败时触发 error。
 */
async function downloadSvg() {
  if (!viewer || !ready.value) return
  try {
    const { svg } = await viewer.saveSVG()
    Download.saveAs(new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }), `${props.fileName}.svg`)
  } catch (error) {
    emit('error', error)
  }
}

watch(() => props.xml, importXml)
watch(() => props.state, () => importXml(props.xml), { deep: true })

onMounted(() => {
  observeCanvasSize()
  importXml(props.xml)
})
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = undefined
  if (fitAnimationFrame) cancelAnimationFrame(fitAnimationFrame)
  fitAnimationFrame = undefined
  if (viewer) viewer.destroy()
  viewer = undefined
})

defineExpose({ fitViewport, downloadSvg })
</script>

<style scoped lang="scss">
.workflow-viewer {
  position: relative;
  min-height: 320px;
  overflow: hidden;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
}

.workflow-viewer__toolbar {
  position: absolute;
  top: 8px;
  right: 10px;
  z-index: 5;
  display: flex;
  gap: 2px;
  padding: 2px;
  background: color-mix(in srgb, var(--el-bg-color) 92%, transparent);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.workflow-viewer__canvas {
  width: 100%;
  height: 100%;
}

.workflow-viewer__empty {
  position: absolute;
  inset: 42px 0 0;
  background: var(--el-bg-color);
}

:deep(.djs-minimap) {
  top: auto;
  right: 10px;
  bottom: 44px;
  border-color: var(--el-border-color-light);
}

:deep(.djs-element.workflow-finished .djs-visual > :first-child) {
  stroke: #2f7d5f !important;
  fill: #e7f5ef !important;
}

:deep(.djs-element.workflow-current .djs-visual > :first-child) {
  stroke: #2563a5 !important;
  stroke-width: 3px !important;
  fill: #e8f1fb !important;
}

:deep(.djs-element.workflow-rejected .djs-visual > :first-child) {
  stroke: #c2413a !important;
  fill: #fbeceb !important;
}

:deep(.djs-element.workflow-returned .djs-visual > :first-child) {
  stroke: #a86414 !important;
  fill: #fff4df !important;
}

:deep(.djs-element.workflow-finished-flow .djs-visual > path) {
  stroke: #2f7d5f !important;
  stroke-width: 2.5px !important;
}
</style>
