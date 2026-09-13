<template>
    <div class="popup-result" aria-live="polite" :aria-busy="loading">
        <p class="title">未来 5 次计划时间</p>
        <p v-if="timeZone">调度时区：{{ timeZone }}（与服务器实际调度一致）</p>
        <ul class="popup-result-scroll">
            <li v-if="loading">正在查询计划时间...</li>
            <li v-else-if="error">{{ error }}</li>
            <template v-else>
                <li v-for="item in resultList" :key="item">{{ item }}</li>
                <li v-if="resultList.length === 0">表达式有效，但没有未来计划时间。</li>
                <li v-else-if="resultList.length < 5">仅有以上 {{ resultList.length }} 次未来计划时间。</li>
            </template>
        </ul>
    </div>
</template>

<script setup>
import { previewCron } from '@/api/monitor/job'

const props = defineProps({
    ex: {
        type: String,
        default: ''
    }
})
const resultList = ref([])
const timeZone = ref('')
const loading = ref(false)
const error = ref('')
let revision = 0

watch(() => props.ex, async expression => {
    const current = ++revision
    resultList.value = []
    timeZone.value = ''
    error.value = ''
    loading.value = true
    try {
        const { data } = await previewCron(expression)
        if (current !== revision) return
        resultList.value = data.executionTimes
        timeZone.value = data.timeZone
    } catch (failure) {
        if (current !== revision) return
        error.value = `预览失败：${failure?.message || '无法连接服务器，请重试'}`
    } finally {
        if (current === revision) loading.value = false
    }
}, { immediate: true })

onBeforeUnmount(() => { revision++ })
</script>
