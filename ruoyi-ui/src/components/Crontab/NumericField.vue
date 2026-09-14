<template>
    <el-form :style="{ '--cron-select-width': field === 'min' ? '19.8rem' : '18.8rem' }">
        <el-form-item>
            <el-radio v-model="radioValue" :value="1">
                {{ settings.label }}，允许的通配符[, - * /]
            </el-radio>
        </el-form-item>
        <el-form-item>
            <el-radio v-model="radioValue" :value="2">
                周期从
                <el-input-number v-model="cycle01" :min="settings.min" :max="settings.max - 1" /> -
                <el-input-number v-model="cycle02" :min="cycle01 + 1" :max="settings.max" /> {{ settings.unit }}
            </el-radio>
        </el-form-item>
        <el-form-item>
            <el-radio v-model="radioValue" :value="3">
                从
                <el-input-number v-model="average01" :min="settings.min" :max="settings.max - 1" /> {{ settings.unit }}开始，每
                <el-input-number v-model="average02" :min="1" :max="settings.max - average01" /> {{ settings.label }}执行一次
            </el-radio>
        </el-form-item>
        <el-form-item>
            <el-radio v-model="radioValue" :value="4">
                指定
                <el-select v-model="checkboxList" clearable placeholder="可多选" multiple :multiple-limit="field === 'month' ? 8 : 10">
                    <el-option v-for="item in options" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
            </el-radio>
        </el-form-item>
    </el-form>
</template>

<script setup>
const emit = defineEmits(['update'])
const props = defineProps({
    field: { type: String, required: true },
    cron: { type: Object, required: true },
    check: { type: Function, required: true }
})

// 四个数值字段共享同一套编辑规则；日、周和年的特殊语法留在各自组件。
const settings = {
    second: { label: '秒', unit: '秒', min: 0, max: 59 },
    min: { label: '分钟', unit: '分钟', min: 0, max: 59 },
    hour: { label: '小时', unit: '时', min: 0, max: 23 },
    month: { label: '月', unit: '月', min: 1, max: 12 }
}[props.field]
const monthLabels = ['一月', '二月', '三月', '四月', '五月', '六月', '七月', '八月', '九月', '十月', '十一月', '十二月']
const options = Array.from({ length: settings.max - settings.min + 1 }, (_, index) => ({
    value: index + settings.min,
    label: props.field === 'month' ? monthLabels[index] : index + settings.min
}))
const radioValue = ref(1)
const cycle01 = ref(settings.min)
const cycle02 = ref(settings.min + 1)
const average01 = ref(settings.min)
const average02 = ref(1)
const checkboxList = ref([])
const checkCopy = ref([settings.min])
const cycleTotal = computed(() => {
    cycle01.value = props.check(cycle01.value, settings.min, settings.max - 1)
    cycle02.value = props.check(cycle02.value, cycle01.value + 1, settings.max)
    return cycle01.value + '-' + cycle02.value
})
const averageTotal = computed(() => {
    average01.value = props.check(average01.value, settings.min, settings.max - 1)
    average02.value = props.check(average02.value, 1, settings.max - average01.value)
    return average01.value + '/' + average02.value
})
const checkboxString = computed(() => checkboxList.value.join(','))

watch(() => props.cron[props.field], value => {
    // 与原小时编辑器一致：小时表达式变化时，将通配的分、秒固定为 0。
    if (props.field === 'hour') {
        if (props.cron.min === '*') emit('update', 'min', '0', 'hour')
        if (props.cron.second === '*') emit('update', 'second', '0', 'hour')
    }
    if (value === '*') {
        radioValue.value = 1
    } else if (value.includes('-')) {
        const parts = value.split('-')
        cycle01.value = Number(parts[0])
        cycle02.value = Number(parts[1])
        radioValue.value = 2
    } else if (value.includes('/')) {
        const parts = value.split('/')
        average01.value = Number(parts[0])
        average02.value = Number(parts[1])
        radioValue.value = 3
    } else {
        checkboxList.value = [...new Set(value.split(',').map(Number))]
        radioValue.value = 4
    }
})
watch([radioValue, cycleTotal, averageTotal, checkboxString], () => {
    if (radioValue.value === 4) {
        if (checkboxList.value.length === 0) checkboxList.value.push(checkCopy.value[0])
        else checkCopy.value = checkboxList.value
    }
    const value = ['*', cycleTotal.value, averageTotal.value, checkboxString.value][radioValue.value - 1]
    emit('update', props.field, value, props.field)
})
</script>

<style lang="scss" scoped>
.el-input-number--small, .el-select, .el-select--small {
    margin: 0 0.2rem;
}
.el-select, .el-select--small {
    width: var(--cron-select-width);
}
</style>
