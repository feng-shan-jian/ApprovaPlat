<template>
  <el-form label-position="top" :disabled="busy" class="event-console" @submit.prevent="send">
    <el-alert title="发送会推进真实流程。凭据控制事件类型和变量权限，下面的关联条件用于唯一匹配实例；重试时保留请求标识。" type="info" :closable="false" />
    <el-form-item label="集成凭据" required><el-input v-model="token" aria-label="集成凭据" type="password" autocomplete="off" placeholder="仅保留在当前窗口，关闭后清除" /></el-form-item>
    <div class="event-console__grid">
      <el-form-item label="事件类型"><el-select v-model="eventType"><el-option label="消息" value="MESSAGE" /><el-option label="信号" value="SIGNAL" /><el-option label="接收任务" value="RECEIVE" /></el-select></el-form-item>
      <el-form-item :label="eventType === 'RECEIVE' ? '接收节点 ID' : '消息 / 信号名称'" required><el-input v-model="eventName" maxlength="255" /></el-form-item>
      <el-form-item label="关联类型"><el-select v-model="correlationType"><el-option label="流程实例主键" value="processInstanceId" /><el-option label="业务主键" value="businessKey" /></el-select></el-form-item>
      <el-form-item label="关联值" required><el-input v-model="correlationValue" maxlength="255" /></el-form-item>
    </div>
    <el-form-item label="请求标识（相同标识只允许同一请求内容）" required><el-input v-model="requestId" maxlength="36"><template #append><el-button @click="newRequest">新请求</el-button></template></el-input></el-form-item>
    <div class="event-console__heading"><strong>凭据允许的变量</strong><el-button text :disabled="variables.length >= 128" @click="variables.push({ key: '', type: 'string', value: '' })">添加变量</el-button></div>
    <div v-for="(variable, index) in variables" :key="index" class="event-console__variable">
      <el-input v-model="variable.key" aria-label="变量名" placeholder="变量名" maxlength="128" />
      <el-select v-model="variable.type" aria-label="变量类型"><el-option label="文本" value="string" /><el-option label="数值" value="number" /><el-option label="布尔值" value="boolean" /></el-select>
      <el-select v-if="variable.type === 'boolean'" v-model="variable.value" aria-label="变量值"><el-option label="是" :value="true" /><el-option label="否" :value="false" /></el-select>
      <el-input v-else v-model="variable.value" aria-label="变量值" placeholder="变量值" />
      <el-button text type="danger" @click="variables.splice(index, 1)">删除</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="result" :title="`${result.resultCode} · ${result.resultSummary}`" type="success" :closable="false">
      实例 {{ result.matchedProcessInstanceId }} · 执行 {{ result.matchedExecutionId }}
    </el-alert>
    <div class="event-console__actions"><el-button type="primary" native-type="submit" :loading="busy">发送事件</el-button></div>
  </el-form>
</template>
<script setup>
import { submitRuntimeEvent } from '@/api/workflow/runtimeEvent'
const emit = defineEmits(['processed'])
const token = ref(''), eventType = ref('MESSAGE'), eventName = ref(''), correlationType = ref('processInstanceId'), correlationValue = ref('')
const requestId = ref(crypto.randomUUID()), variables = ref([]), busy = ref(false), error = ref(''), result = ref(null)
function newRequest() { requestId.value = crypto.randomUUID(); result.value = null; error.value = '' }
async function send() {
  if (busy.value) return
  error.value = ''; result.value = null
  try {
    if (!token.value.trim() || !eventName.value.trim() || !correlationValue.value.trim()) throw new Error('请填写凭据、事件名称及关联值')
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(requestId.value)) throw new Error('请求标识必须为规范小写 UUID')
    const values = Object.create(null)
    for (const variable of variables.value) {
      const key = variable.key.trim()
      if (!/^[A-Za-z_][A-Za-z0-9_]{0,127}$/.test(key) || ['__proto__', 'constructor', 'prototype'].includes(key) || Object.hasOwn(values, key)) throw new Error('变量名无效或重复')
      const value = variable.type === 'number' ? Number(variable.value) : variable.value
      if (variable.type === 'number' && (String(variable.value).trim() === '' || !Number.isFinite(value))) throw new Error('数值变量必须填写有限数值')
      if (variable.type === 'boolean' && typeof value !== 'boolean') throw new Error('请选择布尔变量值')
      values[key] = value
    }
    busy.value = true
    result.value = await submitRuntimeEvent(eventType.value, token.value.trim(), {
      requestId: requestId.value, eventName: eventName.value.trim(), [correlationType.value]: correlationValue.value.trim(), variables: values
    })
    emit('processed', result.value)
  } catch (failure) { error.value = failure.message || '事件发送失败' }
  finally { busy.value = false }
}
onBeforeUnmount(() => { token.value = ''; variables.value = [] })
</script>
<style scoped>
.event-console__grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.event-console :deep(.el-select) { width: 100%; }
.event-console .el-alert { margin-bottom: 16px; }
.event-console__heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.event-console__variable { display: grid; grid-template-columns: 1fr 100px 1fr auto; gap: 8px; margin-bottom: 10px; }
.event-console__actions { display: flex; justify-content: flex-end; margin-top: 20px; }
@media (max-width: 600px) { .event-console__grid { grid-template-columns: 1fr; } .event-console__variable { grid-template-columns: 1fr 1fr; } }
</style>
