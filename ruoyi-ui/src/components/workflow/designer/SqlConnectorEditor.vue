<template>
  <div class="sql-connector-editor">
    <el-form-item label="SQL 数据源" required>
      <el-select v-model="draft.dataSourceKey" filterable @change="emitChange">
        <el-option
          v-for="source in dataSources"
          :key="source.dataSourceId"
          :label="`${source.dataSourceName} · ${source.connectionType} · R${source.revisionNo}`"
          :value="source.dataSourceKey"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="命名参数 SQL" required>
      <el-input v-model="draft.sql" type="textarea" :rows="6" maxlength="8192" @change="emitChange" />
    </el-form-item>
    <el-form-item v-if="isExternalWrite" label="幂等唯一列" required>
      <el-input v-model="draft.idempotencyColumn" maxlength="128" @change="emitChange" />
    </el-form-item>
    <div class="sql-connector-editor__section">
      <div class="sql-connector-editor__heading">
        <span>参数映射</span>
        <el-tooltip content="添加参数映射" placement="top">
          <el-button link icon="Plus" aria-label="添加参数映射" @click="addParameter" />
        </el-tooltip>
      </div>
      <div v-for="(item, index) in draft.parameters" :key="item.key" class="sql-connector-editor__parameter">
        <el-input v-model="item.name" maxlength="128" placeholder="SQL 参数名" @change="emitChange" />
        <el-input v-model="item.variable" maxlength="128" placeholder="流程变量" @change="emitChange" />
        <el-tooltip content="删除参数映射" placement="top">
          <el-button link type="danger" icon="Delete" aria-label="删除参数映射" @click="removeParameter(index)" />
        </el-tooltip>
      </div>
    </div>
    <el-form-item label="结果变量">
      <el-input v-model="draft.resultVariable" maxlength="128" @change="emitChange" />
    </el-form-item>
    <el-form-item label="查询最大行数">
      <el-input-number v-model="draft.maxRows" :min="1" :max="1000" controls-position="right" @change="emitChange" />
    </el-form-item>
    <el-alert
      v-if="selectedSource"
      type="info"
      :closable="false"
      show-icon
      :title="`${selectedSource.connectionType} · ${selectedSource.queryTimeoutSeconds} 秒 · ${selectedSource.allowedTables.length} 张授权表`"
    />
  </div>
</template>

<script setup name="SqlConnectorEditor">
const props = defineProps({
  modelValue: { type: String, default: '{}' },
  dataSources: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'change'])
const draft = reactive(createEmptyConfig())
let parameterSequence = 0
const selectedSource = computed(() => props.dataSources.find(source => source.dataSourceKey === draft.dataSourceKey))
// 外库写必须显式声明由目标表唯一约束保护的幂等列，查询和主库事务写无需该字段。
const isExternalWrite = computed(() => selectedSource.value?.connectionType === 'EXTERNAL'
  && /^\s*(insert|update|delete)\b/i.test(draft.sql))

/**
 * 创建 SQL 作者配置初始值。
 * @returns {object} 与服务端 Schema 一致的可编辑状态。
 */
function createEmptyConfig() {
  return { dataSourceKey: '', sql: '', parameters: [], resultVariable: '', idempotencyColumn: '', maxRows: 100 }
}

/**
 * 从 BPMN 中的 JSON 安全回读受控字段。
 * @param {string} value 作者配置 JSON。
 * @returns {void} 非法原文保持空状态并交由服务端门禁拒绝。
 */
function loadConfig(value) {
  Object.assign(draft, createEmptyConfig())
  try {
    const parsed = JSON.parse(value || '{}')
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') return
    draft.dataSourceKey = typeof parsed.dataSourceKey === 'string' ? parsed.dataSourceKey : ''
    draft.sql = typeof parsed.sql === 'string' ? parsed.sql : ''
    draft.resultVariable = typeof parsed.resultVariable === 'string' ? parsed.resultVariable : ''
    draft.idempotencyColumn = typeof parsed.idempotencyColumn === 'string' ? parsed.idempotencyColumn : ''
    draft.maxRows = Number.isInteger(parsed.maxRows) ? parsed.maxRows : 100
    draft.parameters = Object.entries(parsed.parameters || {}).map(([name, variable]) => ({
      key: ++parameterSequence,
      name,
      variable: typeof variable === 'string' ? variable : ''
    }))
  } catch {
    // 不猜测损坏配置；下一次有效编辑会生成结构化 JSON。
  }
}

/**
 * 新增一行参数映射。
 * @returns {void} 只修改编辑态，待字段变化时统一写入命令栈。
 */
function addParameter() {
  draft.parameters.push({ key: ++parameterSequence, name: '', variable: '' })
}

/**
 * 删除指定参数映射并立即同步 BPMN。
 * @param {number} index 参数行索引。
 * @returns {void} 输出删除后的规范配置。
 */
function removeParameter(index) {
  draft.parameters.splice(index, 1)
  emitChange()
}

/**
 * 输出字段顺序稳定的 SQL 作者配置 JSON。
 * @returns {void} 同时更新 v-model 并通知父级写入 bpmn-js 命令栈。
 */
function emitChange() {
  const parameters = {}
  for (const item of draft.parameters) {
    const name = item.name.trim()
    const variable = item.variable.trim()
    if (name && variable) parameters[name] = variable
  }
  const normalized = {
    dataSourceKey: draft.dataSourceKey.trim(),
    sql: draft.sql.trim(),
    parameters,
    maxRows: draft.maxRows
  }
  if (draft.resultVariable.trim()) normalized.resultVariable = draft.resultVariable.trim()
  if (isExternalWrite.value && draft.idempotencyColumn.trim()) {
    normalized.idempotencyColumn = draft.idempotencyColumn.trim()
  }
  const json = JSON.stringify(normalized)
  emit('update:modelValue', json)
  emit('change', json)
}

watch(() => props.modelValue, value => loadConfig(value), { immediate: true })
</script>

<style scoped>
.sql-connector-editor,
.sql-connector-editor__section {
  display: grid;
  gap: 6px;
}

.sql-connector-editor :deep(.el-select),
.sql-connector-editor :deep(.el-input-number) {
  width: 100%;
}

.sql-connector-editor__heading,
.sql-connector-editor__parameter {
  display: grid;
  align-items: center;
  gap: 6px;
}

.sql-connector-editor__heading {
  grid-template-columns: minmax(0, 1fr) 28px;
  color: var(--el-text-color-regular);
  font-size: 12px;
}

.sql-connector-editor__parameter {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 28px;
}

.sql-connector-editor :deep(.el-alert) {
  margin-bottom: 10px;
  border-radius: 4px;
}
</style>
