<template>
  <section v-if="rows.length" class="waiting-events" aria-label="当前等待事件">
    <div class="waiting-events__heading"><h3>当前等待事件</h3><el-button text :loading="loading" @click="emit('refresh')">刷新状态</el-button></div>
    <el-alert v-if="hasJobs && !timerReady" title="当前服务节点未确认普通执行器可用，请核对调度节点及到期作业。" type="warning" :closable="false" />
    <el-table v-if="rows.length" :data="rows" size="small">
      <el-table-column label="等待类型" width="120"><template #default="{ row }">{{ labels[row.type] || row.type }}</template></el-table-column>
      <el-table-column label="节点" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ nodeName(row.nodeId) }}</template></el-table-column>
      <el-table-column prop="eventName" label="消息名 / 接收节点" min-width="190" show-overflow-tooltip />
      <el-table-column label="到期时间" min-width="180"><template #default="{ row }">{{ row.dueTime ? parseTime(row.dueTime) : '—' }}</template></el-table-column>
      <el-table-column label="状态" min-width="110"><template #default="{ row }">{{ states[row.state] || row.state }}</template></el-table-column>
      <el-table-column label="剩余重试" width="90"><template #default="{ row }">{{ row.retries ?? '—' }}</template></el-table-column>
    </el-table>
    <el-collapse v-hasPermi="['workflow:runtime-event:list']"><el-collapse-item title="运行技术标识" name="ids"><p v-for="row in rows" :key="`${row.type}:${row.executionId}:${row.jobId || row.eventName}`">{{ row.nodeId }} · 执行 {{ row.executionId }}<span v-if="row.jobId"> · 作业 {{ row.jobId }}</span></p></el-collapse-item></el-collapse>
    <p v-if="truncated">等待记录较多，当前展示前 200 条。</p>
    <p v-if="rows.length">消息与接收事件需由授权集成凭据投递；处理结果可在运行事件台账按实例或请求标识查询。</p>
  </section>
</template>
<script setup>
const props = defineProps({
  processInstanceId: { type: String, required: true },
  snapshot: { type: Object, default: null },
  activityInstances: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['refresh'])
const rows = computed(() => props.snapshot?.processInstanceId === props.processInstanceId ? props.snapshot.waits || [] : [])
const timerReady = computed(() => props.snapshot?.localTimerExecutionReady === true)
const truncated = computed(() => props.snapshot?.truncated === true)
const labels = { MESSAGE: '等待消息', SIGNAL: '等待信号', RECEIVE: '等待接收', TIMER: '定时等待', JOB: '执行作业' }
const states = { running: '等待外部事件', suspended: '挂起', WAITING: '等待到期', EXECUTABLE: '待执行', SUSPENDED: '挂起', DEAD_LETTER: '重试已耗尽' }
const hasJobs = computed(() => rows.value.some(row => ['TIMER', 'JOB'].includes(row.type)))
function nodeName(id) { return props.activityInstances.find(activity => activity.activityId === id)?.activityName || id }
</script>
<style scoped>
.waiting-events { margin: 20px 0; padding: 16px; border: 1px solid var(--el-border-color-light); border-radius: 6px; }
.waiting-events__heading { display: flex; align-items: center; justify-content: space-between; }
h3 { margin: 0 0 12px; font-size: 15px; }
p { color: var(--el-text-color-secondary); font-size: 12px; }
.el-alert { margin-bottom: 12px; }
</style>
