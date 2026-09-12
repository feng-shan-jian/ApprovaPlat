<template>
  <section v-if="rows.length || error" class="waiting-events" aria-label="当前等待事件">
    <div class="waiting-events__heading"><h3>当前等待事件</h3><el-button text :loading="loading" @click="load">刷新</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="hasJobs && !timerReady" title="当前服务节点未确认普通执行器可用，请核对调度节点及到期作业。" type="warning" :closable="false" />
    <el-table v-if="rows.length" :data="rows" size="small">
      <el-table-column label="等待类型" width="120"><template #default="{ row }">{{ labels[row.type] || row.type }}</template></el-table-column>
      <el-table-column prop="nodeId" label="节点" min-width="160" show-overflow-tooltip />
      <el-table-column prop="eventName" label="消息名 / 接收节点" min-width="190" show-overflow-tooltip />
      <el-table-column prop="executionId" label="执行主键" min-width="190" show-overflow-tooltip />
      <el-table-column label="到期时间" min-width="180"><template #default="{ row }">{{ row.dueTime ? parseTime(row.dueTime) : '—' }}</template></el-table-column>
      <el-table-column label="状态" min-width="110"><template #default="{ row }">{{ states[row.state] || row.state }}</template></el-table-column>
      <el-table-column label="剩余重试" width="90"><template #default="{ row }">{{ row.retries ?? '—' }}</template></el-table-column>
    </el-table>
    <p v-if="truncated">等待记录较多，当前展示前 200 条。</p>
    <p v-if="rows.length">消息与接收事件需由授权集成凭据投递；处理结果可在运行事件台账按实例或请求标识查询。</p>
  </section>
</template>
<script setup>
import request from '@/utils/request'
const props = defineProps({ processInstanceId: { type: String, required: true } })
const rows = ref([]), error = ref(''), loading = ref(false), timerReady = ref(false), truncated = ref(false)
const labels = { MESSAGE: '等待消息', SIGNAL: '等待信号', RECEIVE: '等待接收', TIMER: '定时等待', JOB: '执行作业' }
const states = { running: '运行中', suspended: '挂起', WAITING: '待领取', EXECUTABLE: '待执行', SUSPENDED: '挂起', DEAD_LETTER: '死信' }
const hasJobs = computed(() => rows.value.some(row => ['TIMER', 'JOB'].includes(row.type)))
let sequence = 0
async function load() {
  const current = ++sequence
  rows.value = []; error.value = ''; loading.value = true
  try {
    const response = await request({ url: '/workflow/process/waiting', params: { processInstanceId: props.processInstanceId }, suppressErrorMessage: true })
    if (current !== sequence) return
    if (response.data?.processInstanceId !== props.processInstanceId || !Array.isArray(response.data.waits)) throw new Error('等待状态响应不完整')
    rows.value = response.data.waits
    timerReady.value = response.data.localTimerExecutionReady === true
    truncated.value = response.data.truncated === true
  } catch (failure) { if (current === sequence) error.value = failure.message || '等待状态加载失败' }
  finally { if (current === sequence) loading.value = false }
}
watch(() => props.processInstanceId, load, { immediate: true })
onBeforeUnmount(() => { sequence++ })
</script>
<style scoped>
.waiting-events { margin: 20px 0; padding: 16px; border: 1px solid var(--el-border-color-light); border-radius: 6px; }
.waiting-events__heading { display: flex; align-items: center; justify-content: space-between; }
h3 { margin: 0 0 12px; font-size: 15px; }
p { color: var(--el-text-color-secondary); font-size: 12px; }
.el-alert { margin-bottom: 12px; }
</style>
