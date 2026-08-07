<template>
  <header class="designer-toolbar" aria-label="流程设计工具栏">
    <div class="designer-toolbar__group">
      <el-tooltip content="导入 BPMN" placement="bottom">
        <el-button text circle icon="Upload" aria-label="导入 BPMN" :disabled="locked" @click="$emit('import')" />
      </el-tooltip>
      <el-dropdown trigger="click" :disabled="locked" @command="$emit('export', $event)">
        <el-button text circle icon="Download" aria-label="导出流程" />
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="bpmn">导出 BPMN</el-dropdown-item>
            <el-dropdown-item command="xml">导出 XML</el-dropdown-item>
            <el-dropdown-item command="svg">导出 SVG</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-dropdown trigger="click" :disabled="locked" @command="$emit('preview', $event)">
        <el-button text circle icon="View" aria-label="预览流程源码" />
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="xml">XML 预览</el-dropdown-item>
            <el-dropdown-item command="json">JSON 预览</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-tooltip content="清空画布" placement="bottom">
        <el-button text circle icon="Delete" aria-label="清空画布" :disabled="locked" @click="$emit('clear')" />
      </el-tooltip>
      <el-divider direction="vertical" />
      <el-tooltip content="撤销 Ctrl+Z" placement="bottom">
        <el-button text circle icon="RefreshLeft" aria-label="撤销" :disabled="locked || !canUndo" @click="$emit('undo')" />
      </el-tooltip>
      <el-tooltip content="重做 Ctrl+Y" placement="bottom">
        <el-button text circle icon="RefreshRight" aria-label="重做" :disabled="locked || !canRedo" @click="$emit('redo')" />
      </el-tooltip>
      <el-divider direction="vertical" />
      <el-tooltip content="缩小" placement="bottom">
        <el-button text circle icon="ZoomOut" aria-label="缩小流程图" :disabled="locked" @click="$emit('zoom', 0.85)" />
      </el-tooltip>
      <el-tooltip content="适应窗口" placement="bottom">
        <el-button text circle icon="FullScreen" aria-label="适应窗口" :disabled="locked" @click="$emit('fit')" />
      </el-tooltip>
      <el-tooltip content="放大" placement="bottom">
        <el-button text circle icon="ZoomIn" aria-label="放大流程图" :disabled="locked" @click="$emit('zoom', 1.15)" />
      </el-tooltip>
    </div>

    <div class="designer-toolbar__group designer-toolbar__group--center">
      <el-dropdown trigger="click" :disabled="locked || selectionCount < 2" @command="$emit('align', $event)">
        <el-button text icon="Operation">对齐</el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="left">左对齐</el-dropdown-item>
            <el-dropdown-item command="center">水平居中</el-dropdown-item>
            <el-dropdown-item command="right">右对齐</el-dropdown-item>
            <el-dropdown-item command="top" divided>顶部对齐</el-dropdown-item>
            <el-dropdown-item command="middle">垂直居中</el-dropdown-item>
            <el-dropdown-item command="bottom">底部对齐</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-dropdown trigger="click" :disabled="locked || selectionCount < 3" @command="$emit('distribute', $event)">
        <el-button text icon="DCaret">分布</el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="horizontal">水平等距</el-dropdown-item>
            <el-dropdown-item command="vertical">垂直等距</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-tooltip :content="simulationActive ? '退出 Token 模拟' : 'Token 流程模拟'" placement="bottom">
        <el-button
          text
          circle
          icon="VideoPlay"
          aria-label="Token 流程模拟"
          :class="{ 'is-active': simulationActive }"
          :disabled="locked"
          @click="$emit('toggle-simulation')"
        />
      </el-tooltip>
      <el-tooltip content="服务端校验" placement="bottom">
        <el-button text circle icon="CircleCheck" aria-label="服务端校验" :loading="validating" :disabled="locked" @click="$emit('validate')" />
      </el-tooltip>
    </div>

    <div class="designer-toolbar__group">
      <span v-if="issueCount" class="designer-toolbar__issues">{{ issueCount }} 项问题</span>
      <el-tooltip content="设计器设置" placement="bottom">
        <el-button text circle icon="Setting" aria-label="设计器设置" :disabled="locked" @click="$emit('settings')" />
      </el-tooltip>
      <el-tooltip :content="propertiesCollapsed ? '展开属性面板' : '折叠属性面板'" placement="bottom">
        <el-button text circle icon="Expand" aria-label="切换属性面板" :disabled="locked" @click="$emit('toggle-properties')" />
      </el-tooltip>
      <el-button
        v-hasPermi="['workflow:model:save']"
        type="primary"
        icon="Check"
        :loading="locked"
        @click="$emit('save')"
      >保存</el-button>
    </div>
  </header>
</template>

<script setup name="DesignerToolbar">
defineProps({
  /** 设计器是否处于序列化或后端保存锁定状态。 */
  locked: { type: Boolean, default: false },
  /** 当前命令栈是否允许撤销。 */
  canUndo: { type: Boolean, default: false },
  /** 当前命令栈是否允许重做。 */
  canRedo: { type: Boolean, default: false },
  /** 当前画布选择元素数量。 */
  selectionCount: { type: Number, default: 0 },
  /** Token 流程模拟是否正在运行。 */
  simulationActive: { type: Boolean, default: false },
  /** 右侧属性面板是否折叠。 */
  propertiesCollapsed: { type: Boolean, default: false },
  /** 客户端和服务端当前问题总数。 */
  issueCount: { type: Number, default: 0 },
  /** 服务端校验请求是否进行中。 */
  validating: { type: Boolean, default: false }
})

defineEmits([
  'import', 'export', 'preview', 'clear', 'undo', 'redo', 'zoom', 'fit',
  'align', 'distribute', 'toggle-simulation', 'validate', 'settings',
  'toggle-properties', 'save'
])
</script>

<style scoped lang="scss">
.designer-toolbar {
  display: grid;
  grid-template-columns: minmax(360px, 1fr) auto minmax(280px, 1fr);
  align-items: center;
  min-width: 0;
  padding: 0 10px 0 8px;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-light);
}

.designer-toolbar__group {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 2px;
}

.designer-toolbar__group--center {
  justify-content: center;
}

.designer-toolbar__group:last-child {
  justify-content: flex-end;
}

.designer-toolbar :deep(.el-button.is-circle) {
  width: 32px;
  height: 32px;
}

.designer-toolbar :deep(.el-button.is-active) {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.designer-toolbar__issues {
  margin-right: 4px;
  color: var(--el-color-danger);
  font-size: 12px;
  white-space: nowrap;
}
</style>
