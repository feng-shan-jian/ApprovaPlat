<template>
    <el-form>
        <el-form-item>
            <el-radio v-model='radioValue' :value="1" @change="onRadioChange">
                周，允许的通配符[, - * ? / L #]
            </el-radio>
        </el-form-item>

        <el-form-item>
            <el-radio v-model='radioValue' :value="2" @change="onRadioChange">
                不指定
            </el-radio>
        </el-form-item>

        <el-form-item>
            <el-radio v-model='radioValue' :value="3" @change="onRadioChange">
                周期从
                <el-select v-model="cycle01" @change="onRadioChange">
                    <el-option
                        v-for="(item,index) of weekList"
                        :key="index"
                        :label="item.value"
                        :value="item.key"
                    >{{item.value}}</el-option>
                </el-select>
                -
                <el-select v-model="cycle02" @change="onRadioChange">
                    <el-option
                        v-for="(item,index) of weekList"
                        :key="index"
                        :label="item.value"
                        :value="item.key"
                    >{{item.value}}</el-option>
                </el-select>
            </el-radio>
        </el-form-item>

        <el-form-item>
            <el-radio v-model='radioValue' :value="4" @change="onRadioChange">
                第
                <el-input-number v-model='average01' :min="1" :max="5" @change="onRadioChange" /> 周的
                <el-select v-model="average02" @change="onRadioChange">
                    <el-option v-for="item in weekList" :key="item.key" :label="item.value" :value="item.key" />
                </el-select>
            </el-radio>
        </el-form-item>

        <el-form-item>
            <el-radio v-model='radioValue' :value="5" @change="onRadioChange">
                本月最后一个
                <el-select v-model="weekday" @change="onRadioChange">
                    <el-option v-for="item in weekList" :key="item.key" :label="item.value" :value="item.key" />
                </el-select>
            </el-radio>
        </el-form-item>

        <el-form-item>
            <el-radio v-model='radioValue' :value="6" @change="onRadioChange">
                指定
                <el-select class="multiselect" clearable v-model="checkboxList" placeholder="可多选" multiple @change="onRadioChange">
                    <el-option v-for="item in weekList" :key="item.key" :label="item.value" :value="item.key" />
                </el-select>
            </el-radio>
        </el-form-item>

        <p v-if="radioValue === 0">当前星期表达式：{{ cron.week }}。选择上方选项后才会替换，可通过预览检查是否有效。</p>
    </el-form>
</template>

<script setup>
const emit = defineEmits(['update'])
const props = defineProps({
    cron: {
        type: Object,
        default: () => ({
            second: "*",
            min: "*",
            hour: "*",
            day: "*",
            month: "*",
            week: "?",
            year: ""
        })
    },
    check: {
        type: Function,
        default: () => {
        }
    }
})
const radioValue = ref(2)
const cycle01 = ref(2)
const cycle02 = ref(3)
const average01 = ref(1)
const average02 = ref(2)
const weekday = ref(2)
const checkboxList = ref([])
const checkCopy = ref([2])
const weekList = ref([
    {key: 1, value: '星期日'},
    {key: 2, value: '星期一'},
    {key: 3, value: '星期二'},
    {key: 4, value: '星期三'},
    {key: 5, value: '星期四'},
    {key: 6, value: '星期五'},
    {key: 7, value: '星期六'}
])
const weekNames = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']
watch(() => props.cron.week, value => changeRadioValue(value), { immediate: true })

function weekNumber(value) {
    const named = weekNames.indexOf(value)
    return named >= 0 ? named + 1 : /^[1-7]$/.test(value) ? Number(value) : NaN
}

// 这里只回填控件，不验证或改写原式；未覆盖的 Quartz 语法仍可原样提交。
function changeRadioValue(value) {
    const rule = value.toUpperCase()
    const range = rule.split('-').map(weekNumber)
    const nth = rule.split('#')
    const last = rule.endsWith('L') ? weekNumber(rule.slice(0, -1)) : NaN
    const days = rule === 'L' ? [7] : rule.split(',').map(weekNumber)
    if (value === "*") {
        radioValue.value = 1
    } else if (value === "?") {
        radioValue.value = 2
    } else if (range.length === 2 && range.every(Number.isFinite)) {
        cycle01.value = range[0]
        cycle02.value = range[1]
        radioValue.value = 3
    } else if (nth.length === 2 && Number.isFinite(weekNumber(nth[0])) && /^[1-5]$/.test(nth[1])) {
        average01.value = Number(nth[1])
        average02.value = weekNumber(nth[0])
        radioValue.value = 4
    } else if (Number.isFinite(last)) {
        weekday.value = last
        radioValue.value = 5
    } else if (days.every(Number.isFinite)) {
        checkboxList.value = [...new Set(days)]
        checkCopy.value = [...checkboxList.value]
        radioValue.value = 6
    } else {
        radioValue.value = 0
    }
}

// 只有控件 change 事件可以发布更新，导入和重新打开不会触发日周联动。
function onRadioChange() {
    if (radioValue.value === 0) return
    if (radioValue.value === 2 && props.cron.day === '?') {
        emit('update', 'day', '*', 'week')
    }
    if (radioValue.value !== 2 && props.cron.day !== '?') {
        emit('update', 'day', '?', 'week')
    }
    switch (radioValue.value) {
        case 1:
            emit('update', 'week', '*', 'week')
            break
        case 2:
            emit('update', 'week', '?', 'week')
            break
        case 3:
            cycle01.value = props.check(cycle01.value, 1, 7)
            cycle02.value = props.check(cycle02.value, 1, 7)
            emit('update', 'week', cycle01.value + '-' + cycle02.value, 'week')
            break
        case 4:
            average01.value = props.check(average01.value || 1, 1, 5)
            average02.value = props.check(average02.value, 1, 7)
            emit('update', 'week', average02.value + '#' + average01.value, 'week')
            break
        case 5:
            weekday.value = props.check(weekday.value, 1, 7)
            emit('update', 'week', weekday.value + 'L', 'week')
            break
        case 6:
            if (checkboxList.value.length === 0) {
                checkboxList.value.push(checkCopy.value[0])
            } else {
                checkCopy.value = [...checkboxList.value]
            }
            emit('update', 'week', checkboxList.value.join(','), 'week')
            break
    }
}
</script>

<style lang="scss" scoped>
.el-input-number--small, .el-select, .el-select--small {
    margin: 0 0.5rem;
}
.el-select, .el-select--small {
    width: 8rem;
}
.el-select.multiselect, .el-select--small.multiselect {
    width: 17.8rem;
}
</style>
