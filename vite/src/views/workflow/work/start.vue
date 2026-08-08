<template>
  <div class="app-container process-start-page" v-loading="loading">
    <div class="process-start-page__header">
      <div class="process-start-page__identity">
        <el-button circle text icon="ArrowLeft" aria-label="返回新建流程" @click="closePage" />
        <div>
          <h2>{{ formSnapshot.formName || '发起流程' }}</h2>
          <div class="process-start-page__meta">
            <span>{{ formSnapshot.nodeName || '提交申请' }}</span>
            <el-tag v-if="formSnapshot.snapshotTime" size="small" type="info">部署快照</el-tag>
          </div>
        </div>
      </div>
      <el-button type="primary" icon="Promotion" :loading="submitting" @click="submitProcess">提交申请</el-button>
    </div>

    <el-tabs v-if="ready" v-model="activeTab" class="process-start-page__tabs">
      <el-tab-pane label="申请表单" name="form">
        <div class="process-start-page__form">
          <el-form label-width="96px">
            <el-form-item label="业务主键">
              <el-input v-model="businessKey" maxlength="255" clearable placeholder="可选" />
            </el-form-item>
          </el-form>
          <el-divider />
          <ProcessFormRenderer
            ref="formRendererRef"
            v-model="formValues"
            :content="formSnapshot.content"
            @error="showComponentError"
          />
          <template v-if="startAssignments.length">
            <el-divider />
            <el-form label-width="132px" class="process-start-page__assignments">
              <el-form-item
                v-for="assignment in startAssignments"
                :key="assignment.activityId"
                :label="assignmentLabel(assignment)"
                required
              >
                <el-select
                  v-model="multiInstanceUserIds[assignment.activityId]"
                  multiple
                  filterable
                  remote
                  reserve-keyword
                  :remote-method="searchApprovalUsers"
                  :loading="identityLoading"
                  :multiple-limit="assignment.maxUsers"
                  :placeholder="`请选择${assignment.mode === 'ALL' ? '会签' : '或签'}办理人`"
                >
                  <el-option
                    v-for="user in approvalUserOptions"
                    :key="user.value"
                    :label="user.label"
                    :value="String(user.value)"
                  />
                </el-select>
              </el-form-item>
            </el-form>
          </template>
        </div>
      </el-tab-pane>
      <el-tab-pane label="流程图" name="diagram">
        <ProcessViewer :xml="bpmnXml" :file-name="definitionFileName" height="560px" @error="showComponentError" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup name="WorkflowProcessStart">
import { getProcessBpmnXml, getProcessForm, startProcess } from '@/api/workflow/process'
import { listApprovalUserOptions } from '@/api/workflow/identity'
import ProcessFormRenderer from '@/components/workflow/ProcessFormRenderer.vue'
import ProcessViewer from '@/components/workflow/ProcessViewer.vue'

const route = useRoute()
const { proxy } = getCurrentInstance()
const loading = ref(false)
const submitting = ref(false)
const ready = ref(false)
const activeTab = ref('form')
const businessKey = ref('')
const formValues = ref({})
const formSnapshot = reactive({})
const bpmnXml = ref('')
const formRendererRef = ref(null)
// multiInstanceUserIds 只承载后端投影的发起成员字段，不能写入普通表单变量或平台保留变量。
const multiInstanceUserIds = reactive({})
const approvalUserOptions = ref([])
const identityLoading = ref(false)
let identityRequestVersion = 0
const startAssignments = computed(() => Array.isArray(formSnapshot.startMultiInstanceAssignments)
  ? formSnapshot.startMultiInstanceAssignments
  : [])
const definitionFileName = computed(() => `workflow_${String(route.params.definitionId || 'process').replace(/[^A-Za-z0-9_.-]/g, '_')}`)

/**
 * 获取路由中的流程定义主键。
 * @returns {string} 去除首尾空白的定义主键。
 */
function definitionId() {
  return String(route.params.definitionId || '').trim()
}

/**
 * 获取列表传入的部署主键并交由后端复核定义关系。
 * @returns {string} 去除首尾空白的部署主键。
 */
function deploymentId() {
  return String(route.query.deploymentId || '').trim()
}

/**
 * 并行加载不可变开始表单快照和安全 BPMN XML。
 * @returns {Promise<void>} 两项数据均成功后开放真实提交入口。
 */
async function loadStartContext() {
  if (!definitionId() || !deploymentId()) {
    proxy.$modal.msgError('流程定义或部署关系不能为空')
    closePage()
    return
  }
  loading.value = true
  ready.value = false
  try {
    const [formResponse, xmlResponse] = await Promise.all([
      getProcessForm({ definitionId: definitionId(), deployId: deploymentId() }),
      getProcessBpmnXml(definitionId())
    ])
    Object.keys(formSnapshot).forEach(key => delete formSnapshot[key])
    Object.assign(formSnapshot, formResponse.data || {})
    bpmnXml.value = xmlResponse.data || ''
    formValues.value = {}
    Object.keys(multiInstanceUserIds).forEach(key => delete multiInstanceUserIds[key])
    startAssignments.value.forEach(assignment => {
      multiInstanceUserIds[assignment.activityId] = []
    })
    if (startAssignments.value.length) await searchApprovalUsers('')
    ready.value = true
  } finally {
    loading.value = false
  }
}

/**
 * 校验部署表单并发起真实 Flowable 流程，附件由后端在同一事务绑定。
 * @returns {Promise<void>} 发起成功后关闭当前页并进入对象授权详情。
 */
async function submitProcess() {
  // 校验本身是异步的，互斥标志必须在第一个 await 前设置，防止快速双击创建两个正式实例。
  if (!formRendererRef.value || submitting.value) return
  submitting.value = true
  try {
    const valid = await formRendererRef.value.validate().catch(error => {
      showComponentError(error)
      return false
    })
    if (!valid) return
    const variables = formRendererRef.value.getValues()
    const startMembers = {}
    for (const assignment of startAssignments.value) {
      const selected = multiInstanceUserIds[assignment.activityId] || []
      if (selected.length < assignment.minUsers || selected.length > assignment.maxUsers) {
        proxy.$modal.msgError(`${assignmentLabel(assignment)}必须选择 ${assignment.minUsers} 至 ${assignment.maxUsers} 人`)
        return
      }
      startMembers[assignment.activityId] = selected.map(userId => Number(userId))
    }
    const response = await startProcess(definitionId(), {
      businessKey: businessKey.value.trim() || undefined,
      variables,
      multiInstanceUserIds: startMembers
    })
    // 正式服务返回 WorkflowProcessInstanceSnapshot.id；旧字段仅用于兼容平滑切换期响应。
    const processInstanceId = response.data?.id || response.data?.processInstanceId || response.data?.procInsId
    if (!processInstanceId) throw new Error('流程发起结果缺少实例主键')
    proxy.$modal.msgSuccess('流程发起成功')
    proxy.$tab.closeOpenPage({ path: `/workflow/process-detail/${processInstanceId}` })
  } finally {
    submitting.value = false
  }
}

/**
 * 生成发起成员字段的简洁业务标签。
 * @param {{activityName: string, mode: 'ALL'|'ANY'}} assignment 后端部署模型投影。
 * @returns {string} 节点名称与会签/或签语义组合标签。
 */
function assignmentLabel(assignment) {
  return `${assignment.activityName}（${assignment.mode === 'ALL' ? '会签' : '或签'}）`
}

/**
 * 从正式审批用户目录检索发起时可选成员，过期响应不会覆盖最新关键字结果。
 * @param {string} keyword 用户输入的姓名或账号关键字。
 * @returns {Promise<void>} 成功后更新最多 50 个正式可办理用户选项。
 */
async function searchApprovalUsers(keyword) {
  const requestVersion = ++identityRequestVersion
  identityLoading.value = true
  try {
    const response = await listApprovalUserOptions({
      keyword: String(keyword || '').trim(), pageNum: 1, pageSize: 50
    })
    if (requestVersion === identityRequestVersion) mergeApprovalUserOptions(response.rows || [])
  } finally {
    if (requestVersion === identityRequestVersion) identityLoading.value = false
  }
}

/**
 * 合并最新审批目录结果并保留当前已选成员，避免远程检索后已选项退化为裸用户主键。
 * @param {Array<{value: string|number, label: string}>} rows 本次正式审批用户目录查询结果。
 * @returns {void} 更新后的选项以本次查询顺序为主，并补回仍被选中的历史选项。
 */
function mergeApprovalUserOptions(rows) {
  // selectedUserIds 表示全部发起多实例字段当前选择的正式用户主键。
  const selectedUserIds = new Set(Object.values(multiInstanceUserIds)
    .flatMap(userIds => Array.isArray(userIds) ? userIds : [])
    .map(userId => String(userId)))
  const mergedOptions = new Map(rows.map(option => [String(option.value), option]))
  approvalUserOptions.value.forEach(option => {
    const userId = String(option.value)
    if (selectedUserIds.has(userId) && !mergedOptions.has(userId)) {
      mergedOptions.set(userId, option)
    }
  })
  approvalUserOptions.value = [...mergedOptions.values()]
}

/**
 * 显示表单或流程图组件返回的稳定错误。
 * @param {Error} error 公共组件错误对象。
 * @returns {void} 无返回值。
 */
function showComponentError(error) {
  proxy.$modal.msgError(error?.message || '流程数据处理失败')
}

/**
 * 关闭当前标签并返回新建流程列表。
 * @returns {void} 无返回值。
 */
function closePage() {
  proxy.$tab.closeOpenPage({ path: '/office/create', query: { t: Date.now() } })
}

loadStartContext()
</script>

<style scoped lang="scss">
.process-start-page {
  padding-top: 12px;
}

.process-start-page__header {
  display: flex;
  min-height: 54px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.process-start-page__identity {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.process-start-page__identity h2 {
  margin: 0 0 3px;
  overflow: hidden;
  font-size: 17px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.process-start-page__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.process-start-page__tabs {
  margin-top: 8px;
}

.process-start-page__form {
  max-width: 980px;
  padding: 10px 4px 28px;
}

.process-start-page__assignments {
  max-width: 760px;
}

.process-start-page__assignments :deep(.el-select) {
  width: 100%;
}

@media (max-width: 768px) {
  .process-start-page__header {
    align-items: flex-start;
  }
}
</style>
