<template>
    <div>
        <el-tabs type="border-card">
            <el-tab-pane label="秒" v-if="shouldHide('second')">
                <NumericField field="second"
                    @update="updateCrontabValue"
                    :check="checkNumber"
                    :cron="crontabValueObj"
                />
            </el-tab-pane>

            <el-tab-pane label="分钟" v-if="shouldHide('min')">
                <NumericField field="min"
                    @update="updateCrontabValue"
                    :check="checkNumber"
                    :cron="crontabValueObj"
                />
            </el-tab-pane>

            <el-tab-pane label="小时" v-if="shouldHide('hour')">
                <NumericField field="hour"
                    @update="updateCrontabValue"
                    :check="checkNumber"
                    :cron="crontabValueObj"
                />
            </el-tab-pane>

            <el-tab-pane label="日" v-if="shouldHide('day')">
                <CrontabDay
                    @update="updateCrontabValue"
                    :check="checkNumber"
                    :cron="crontabValueObj"
                    ref="cronday"
                />
            </el-tab-pane>

            <el-tab-pane label="月" v-if="shouldHide('month')">
                <NumericField field="month"
                    @update="updateCrontabValue"
                    :check="checkNumber"
                    :cron="crontabValueObj"
                />
            </el-tab-pane>

            <el-tab-pane label="周" v-if="shouldHide('week')">
                <CrontabWeek
                    @update="updateCrontabValue"
                    :check="checkNumber"
                    :cron="crontabValueObj"
                    ref="cronweek"
                />
            </el-tab-pane>

            <el-tab-pane label="年" v-if="shouldHide('year')">
                <CrontabYear
                    @update="updateCrontabValue"
                    :check="checkNumber"
                    :cron="crontabValueObj"
                    ref="cronyear"
                />
            </el-tab-pane>
        </el-tabs>

        <div class="popup-main">
            <div class="popup-result">
                <p class="title">时间表达式</p>
                <table>
                    <thead>
                        <tr>
                            <th v-for="item of tabTitles" :key="item">{{item}}</th>
                            <th>Cron 表达式</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr>
                            <td v-for="(value, field) in crontabValueObj" :key="field">
                                <span v-if="value.length < 10">{{ value }}</span>
                                <el-tooltip v-else :content="value" placement="top"><span>{{ value }}</span></el-tooltip>
                            </td>
                            <td class="result">
                                <span v-if="crontabValueString.length < 90">{{crontabValueString}}</span>
                                <el-tooltip v-else :content="crontabValueString" placement="top"><span>{{crontabValueString}}</span></el-tooltip>
                            </td>
                        </tr>
                    </tbody>
                </table>
            </div>
            <CrontabResult :ex="crontabValueString"></CrontabResult>

            <div class="pop_btn">
                <el-button type="primary" @click="submitFill">确定</el-button>
                <el-button type="warning" @click="clearCron">重置</el-button>
                <el-button @click="hidePopup">取消</el-button>
            </div>
        </div>
    </div>
</template>

<script setup>
import NumericField from "./NumericField.vue"
import CrontabDay from "./day.vue"
import CrontabWeek from "./week.vue"
import CrontabYear from "./year.vue"
import CrontabResult from "./result.vue"
const emit = defineEmits(['hide', 'fill'])
const props = defineProps({
    hideComponent: {
        type: Array,
        default: () => [],
    },
    expression: {
        type: String,
        default: ""
    }
})
const tabTitles = ref(["秒", "分钟", "小时", "日", "月", "周", "年"])
const hiddenComponents = ref([])
const importedExpression = ref(null)
let importing = false
let importRevision = 0
const crontabValueObj = ref({
    second: "*",
    min: "*",
    hour: "*",
    day: "*",
    month: "*",
    week: "?",
    year: "",
})
const crontabValueString = computed(() => {
    if (importedExpression.value !== null) return importedExpression.value
    const obj = crontabValueObj.value
    return obj.second
        + " "
        + obj.min
        + " "
        + obj.hour
        + " "
        + obj.day
        + " "
        + obj.month
        + " "
        + obj.week
        + (obj.year === "" ? "" : " " + obj.year)
})
watch(() => props.expression, expression => resolveExp(expression))
function shouldHide(key) {
    return !(hiddenComponents.value && hiddenComponents.value.includes(key))
}
function resolveExp(expression) {
    // 反解析 表达式
    if (expression) {
        const arr = expression.trim().split(/\s+/)
        if (arr.length === 6 || arr.length === 7) {
            // 这里只按字段回填；合法性由后端 Quartz 判断。
            let obj = {
                second: arr[0],
                min: arr[1],
                hour: arr[2],
                day: arr[3],
                month: arr[4],
                week: arr[5],
                year: arr[6] ? arr[6] : ""
            }
            replaceCron(obj, expression)
        } else {
            replaceCron(crontabValueObj.value, expression)
        }
    } else {
        // 没有传入的表达式 则还原
        clearCron()
    }
}
// 旧字段控件会在回填时发布 update；导入和重置只替换状态，不接受这些回声。
function replaceCron(value, expression = null) {
    const current = ++importRevision
    importing = true
    importedExpression.value = expression
    crontabValueObj.value = value
    nextTick(() => { if (current === importRevision) importing = false })
}
// 由子组件触发，更改表达式组成的字段值
function updateCrontabValue(name, value, _from) {
    if (importing) return
    importedExpression.value = null
    crontabValueObj.value[name] = value
}
// 表单选项的子组件校验数字格式（通过-props传递）
function checkNumber(value, minLimit, maxLimit) {
    // 检查必须为整数
    value = Math.floor(value)
    if (value < minLimit) {
        value = minLimit
    } else if (value > maxLimit) {
        value = maxLimit
    }
    return value
}
// 隐藏弹窗
function hidePopup() {
    emit("hide")
}
// 填充表达式
function submitFill() {
    emit("fill", crontabValueString.value)
    hidePopup()
}
function clearCron() {
    // 还原选择项
    replaceCron({
        second: "*",
        min: "*",
        hour: "*",
        day: "*",
        month: "*",
        week: "?",
        year: "",
    })
}
onMounted(() => {
    resolveExp(props.expression)
    hiddenComponents.value = props.hideComponent
})
</script>

<style lang="scss" scoped>
.pop_btn {
    text-align: center;
    margin-top: 20px;
}
.popup-main {
    position: relative;
    margin: 10px auto;
    border-radius: 5px;
    font-size: 12px;
    overflow: hidden;
}
.popup-title {
    overflow: hidden;
    line-height: 34px;
    padding-top: 6px;
    background: #f2f2f2;
}
.popup-result {
    box-sizing: border-box;
    line-height: 24px;
    margin: 25px auto;
    padding: 15px 10px 10px;
    border: 1px solid #ccc;
    position: relative;
}
.popup-result .title {
    position: absolute;
    top: -28px;
    left: 50%;
    width: 140px;
    font-size: 14px;
    margin-left: -70px;
    text-align: center;
    line-height: 30px;
    background: #fff;
}
.popup-result table {
    text-align: center;
    width: 100%;
    margin: 0 auto;
}
.popup-result table td:not(.result) {
    width: 3.5rem;
    min-width: 3.5rem;
    max-width: 3.5rem;
}
.popup-result table span {
    display: block;
    width: 100%;
    font-family: arial;
    line-height: 30px;
    height: 30px;
    white-space: nowrap;
    overflow: hidden;
    border: 1px solid #e8e8e8;
}
.popup-result-scroll {
    font-size: 12px;
    line-height: 24px;
    height: 10em;
    overflow-y: auto;
}
</style>
