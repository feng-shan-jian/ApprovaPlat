<template>
  <div class="user-task-sla-editor">
    <el-form-item label="审批 SLA">
      <el-switch
        v-model="draft.enabled"
        :disabled="!draft.enabled && !canEnable"
        @change="toggleEnabled"
      />
      <div v-if="!draft.enabled && !canEnable" class="user-task-sla-editor__tip">
        启用前需要至少一个业务日历，以及一个升级办理人或受控升级事件。
      </div>
    </el-form-item>
    <template v-if="draft.enabled">
      <el-form-item label="业务日历" required>
        <el-select v-model="draft.calendarKey" filterable :loading="loading" @change="submit">
          <el-option
            v-for="calendar in calendars"
            :key="calendar.calendarKey"
            :label="`${calendar.calendarName} · ${calendar.calendarKey}`"
            :value="calendar.calendarKey"
          />
        </el-select>
      </el-form-item>
      <div class="user-task-sla-editor__grid">
        <el-form-item label="首次提醒（分钟）" required>
          <el-input-number v-model="draft.reminderMinutes" :min="1" :max="525600" controls-position="right" @change="submit" />
        </el-form-item>
        <el-form-item label="最大提醒次数" required>
          <el-input-number v-model="draft.maxReminders" :min="1" :max="100" controls-position="right" @change="submit" />
        </el-form-item>
      </div>
      <el-form-item v-if="draft.maxReminders > 1" label="重复提醒间隔（分钟）" required>
        <el-input-number v-model="draft.reminderRepeatMinutes" :min="1" :max="525600" controls-position="right" @change="submit" />
      </el-form-item>
      <el-form-item label="超时升级（分钟）" required>
        <el-input-number v-model="draft.escalationMinutes" :min="1" :max="525600" controls-position="right" @change="submit" />
      </el-form-item>
      <el-form-item label="升级办理人">
        <el-select
          v-model="draft.escalationUserId"
          filterable
          clearable
          remote
          reserve-keyword
          :remote-method="searchAssignees"
          :loading="identityLoading"
          @change="submit"
        >
          <el-option v-for="user in assigneeOptions" :key="user.value" :label="user.label" :value="String(user.value)" />
        </el-select>
      </el-form-item>
      <el-form-item label="受控升级事件">
        <el-select v-model="draft.escalationEventCode" filterable clearable :loading="loading" @change="submit">
          <el-option
            v-for="event in escalationOptions"
            :key="event.eventCodeId"
            :label="`${event.eventName} · ${event.eventCode}`"
            :value="event.eventCode"
          />
        </el-select>
        <div class="user-task-sla-editor__tip">升级办理人和受控升级事件至少配置一项。</div>
      </el-form-item>
    </template>
  </div>
</template>

<script setup name="UserTaskSlaEditor">
const props = defineProps({
  /** 当前 UserTask 的结构化 SLA 作者配置。 */
  modelValue: { type: Object, required: true },
  /** 正式数据库返回的启用业务日历。 */
  calendars: { type: Array, default: () => [] },
  /** 正式数据库返回的启用 ESCALATION 编码。 */
  escalationOptions: { type: Array, default: () => [] },
  /** 具备审批能力的正式用户选项。 */
  assigneeOptions: { type: Array, default: () => [] },
  /** 日历或事件目录加载状态。 */
  loading: { type: Boolean, default: false },
  /** 正式用户目录加载状态。 */
  identityLoading: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'change', 'identity-search'])
const draft = reactive(createDefaultSla())
const canEnable = computed(() => props.calendars.length > 0
  && (props.escalationOptions.length > 0 || props.assigneeOptions.length > 0))

/**
 * 创建字段完整的 SLA 默认值。
 * @returns {object} 可直接编辑且不共享引用的 SLA 配置。
 */
function createDefaultSla() {
  return {
    enabled: false,
    calendarKey: '',
    reminderMinutes: 60,
    reminderRepeatMinutes: 60,
    maxReminders: 1,
    escalationMinutes: 240,
    escalationUserId: '',
    escalationEventCode: ''
  }
}

/**
 * 将父组件回读的 BPMN 配置同步到编辑态。
 * @param {object|undefined} value UserTask 的结构化 SLA 配置。
 * @returns {void} 缺失字段使用受控默认值。
 */
function syncDraft(value) {
  Object.assign(draft, createDefaultSla(), value || {})
}

watch(() => props.modelValue, syncDraft, { immediate: true, deep: true })

/**
 * 向父组件请求按审批能力检索正式升级办理人。
 * @param {string} keyword 用户输入的检索词。
 * @returns {void} 查询结果由父组件通过 assigneeOptions 回写。
 */
function searchAssignees(keyword) {
  emit('identity-search', keyword)
}

/**
 * 启用时从正式目录补齐首个合法日历和升级目标，确保第一次提交就是完整配置。
 * @param {boolean} enabled 开关变更后的目标状态。
 * @returns {void} 目录尚未满足启用条件时保持停用。
 */
function toggleEnabled(enabled) {
  if (enabled && !canEnable.value) {
    draft.enabled = false
    return
  }
  if (enabled) {
    if (!draft.calendarKey) draft.calendarKey = String(props.calendars[0]?.calendarKey || '')
    if (!draft.escalationUserId && !draft.escalationEventCode) {
      if (props.escalationOptions.length) {
        draft.escalationEventCode = String(props.escalationOptions[0]?.eventCode || '')
      } else {
        draft.escalationUserId = String(props.assigneeOptions[0]?.value || '')
      }
    }
  }
  submit()
}

/**
 * 提交字段完整的 SLA 配置，由父组件执行跨字段校验并写入 BPMN 命令栈。
 * @returns {void} 同时更新 v-model 并发出显式 change 事件。
 */
function submit() {
  const value = {
    enabled: Boolean(draft.enabled),
    calendarKey: String(draft.calendarKey || '').trim(),
    reminderMinutes: Number(draft.reminderMinutes),
    reminderRepeatMinutes: Number(draft.reminderRepeatMinutes),
    maxReminders: Number(draft.maxReminders),
    escalationMinutes: Number(draft.escalationMinutes),
    escalationUserId: String(draft.escalationUserId || '').trim(),
    escalationEventCode: String(draft.escalationEventCode || '').trim()
  }
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<style scoped>
.user-task-sla-editor,
.user-task-sla-editor :deep(.el-select),
.user-task-sla-editor :deep(.el-input-number) {
  width: 100%;
}

.user-task-sla-editor__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
}

.user-task-sla-editor__tip {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
</style>
