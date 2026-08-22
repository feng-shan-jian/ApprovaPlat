<template>
  <div class="app-container process-start-page" v-loading="loading">
    <div class="process-start-page__header">
      <div class="process-start-page__identity">
        <el-button circle text icon="ArrowLeft" :aria-label="draftId ? '返回申请草稿' : '返回新建流程'" @click="closePage" />
        <div>
          <h2>{{ formSnapshot.formName || draftState.processName || '发起流程' }}</h2>
          <div class="process-start-page__meta">
            <span>{{ formSnapshot.nodeName || '提交申请' }}</span>
            <el-tag v-if="formSnapshot.snapshotTime" size="small" type="info">部署快照</el-tag>
            <el-tag v-if="draftId" size="small" :type="draftStatusType">{{ draftStatusLabel }}</el-tag>
            <span v-if="draftId && draftState.updatedTime">保存于 {{ parseTime(draftState.updatedTime) }}</span>
          </div>
        </div>
      </div>
      <div class="process-start-page__actions">
        <el-button
          v-if="draftId"
          type="danger"
          plain
          icon="Delete"
          v-hasPermi="['workflow:process:draftRemove']"
          :disabled="writing"
          @click="removeDraft"
        >删除草稿</el-button>
        <el-button
          icon="DocumentChecked"
          v-hasPermi="['workflow:process:draftSave']"
          :loading="actionType === 'save'"
          :disabled="writing || conflict || !draftEditable"
          @click="saveDraft"
        >保存草稿</el-button>
        <el-button
          type="primary"
          icon="Promotion"
          v-hasPermi="['workflow:process:draftSubmit']"
          :loading="actionType === 'submit'"
          :disabled="writing || conflict || !draftSubmittable"
          @click="submitDraft"
        >正式提交</el-button>
      </div>
    </div>

    <el-alert
      v-if="conflict"
      class="process-start-page__alert"
      type="warning"
      title="草稿已在其他页面更新，当前输入尚未覆盖服务端数据"
      :closable="false"
      show-icon
    >
      <template #default>
        <el-button link type="primary" @click="reloadAfterConflict">重新加载服务器版本</el-button>
      </template>
    </el-alert>
    <el-alert
      v-else-if="draftId && draftState.statusReason"
      class="process-start-page__alert"
      :type="draftSubmittable ? 'info' : 'warning'"
      :title="draftState.statusReason"
      :closable="false"
      show-icon
    />

    <el-tabs v-if="ready" v-model="activeTab" class="process-start-page__tabs">
      <el-tab-pane label="申请表单" name="form">
        <div class="process-start-page__form">
          <el-form label-width="96px">
            <el-form-item label="业务主键">
              <el-input
                v-model="businessKey"
                maxlength="255"
                clearable
                placeholder="可选"
                :disabled="!draftEditable"
              />
            </el-form-item>
          </el-form>
          <el-divider />
          <ProcessFormRenderer
            ref="formRendererRef"
            v-model="formValues"
            :content="formSnapshot.content"
            :readonly="!draftEditable"
            @change="markDirty"
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
                  :disabled="!draftEditable"
                  :multiple-limit="assignment.maxUsers"
                  :placeholder="`请选择${assignment.mode === 'ALL' ? '会签' : '或签'}办理人`"
                  @change="markDirty"
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
        <ProcessViewer v-if="bpmnXml" :xml="bpmnXml" :file-name="definitionFileName" height="560px" @error="showComponentError" />
        <el-empty v-else description="当前草稿无法读取流程图" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup name="WorkflowProcessStart">
import { getWorkflowAttachment } from '@/api/workflow/attachment'
import {
  createProcessDraft,
  deleteProcessDraft,
  getProcessDraft,
  submitProcessDraft,
  updateProcessDraft
} from '@/api/workflow/draft'
import { getProcessBpmnXml, getProcessForm } from '@/api/workflow/process'
import { listApprovalUserOptions } from '@/api/workflow/identity'
import ProcessFormRenderer from '@/components/workflow/ProcessFormRenderer.vue'
import ProcessViewer from '@/components/workflow/ProcessViewer.vue'
import { flattenFormFields, normalizeFormTemplate } from '@/components/workflow/form/formTemplate'

const route = useRoute()
const router = useRouter()
const { proxy } = getCurrentInstance()
const loading = ref(false)
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
const draftId = ref('')
const processDefinitionId = ref('')
const deploymentId = ref('')
const dirty = ref(false)
const conflict = ref(false)
const actionType = ref('')
const writing = computed(() => Boolean(actionType.value))
// syncingContext 标记服务端数据回填阶段，防止初始化值被误判为用户未保存修改。
const syncingContext = ref(false)
const draftState = reactive({
  processName: '',
  status: '',
  statusReason: '',
  editable: true,
  submittable: true,
  revisionNo: null,
  updatedTime: ''
})
const definitionFileName = computed(() => `workflow_${String(processDefinitionId.value || 'process').replace(/[^A-Za-z0-9_.-]/g, '_')}`)
const draftEditable = computed(() => {
  if (!draftId.value) return true
  const status = String(draftState.status || 'DRAFT').toUpperCase()
  return draftState.editable !== false && ['DRAFT', 'ACTIVE'].includes(status)
})
const draftSubmittable = computed(() => draftEditable.value && draftState.submittable !== false)
const draftStatusLabel = computed(() => {
  const status = String(draftState.status || 'DRAFT').toUpperCase()
  if (status === 'SUBMITTED') return '已提交'
  if (status === 'DELETED') return '已删除'
  if (!draftSubmittable.value) return '不可提交'
  return '草稿'
})
const draftStatusType = computed(() => draftStatusLabel.value === '草稿' ? 'info' : draftStatusLabel.value === '已提交' ? 'success' : 'danger')

/**
 * 获取新建流程路由中的流程定义主键。
 * @returns {string} 去除首尾空白的定义主键。
 */
function routeDefinitionId() {
  return String(route.params.definitionId || '').trim()
}

/**
 * 获取新建流程列表传入的部署主键并交由后端复核关系。
 * @returns {string} 去除首尾空白的部署主键。
 */
function routeDeploymentId() {
  return String(route.query.deploymentId || '').trim()
}

/**
 * 获取继续编辑路由中的草稿对象主键。
 * @returns {string} 去除首尾空白的草稿主键。
 */
function routeDraftId() {
  return String(route.params.draftId || '').trim()
}

/**
 * 清空并写入后端返回的不可变部署表单快照。
 * @param {object} snapshot 草稿详情或开始表单接口返回的快照对象。
 * @returns {void} 无返回值。
 */
function replaceFormSnapshot(snapshot) {
  Object.keys(formSnapshot).forEach(key => delete formSnapshot[key])
  const source = snapshot || {}
  // 草稿接口可直接返回已解析的模板 JSON；开始表单接口则返回含 content 的快照视图。
  if (source.content) Object.assign(formSnapshot, source)
  else Object.assign(formSnapshot, { content: source, formName: draftState.processName || '申请表单', nodeName: '提交申请' })
  if (!formSnapshot.content) throw new Error('草稿缺少不可变部署表单快照')
}

/**
 * 以部署字段顺序恢复草稿成员，未知活动不会进入页面状态。
 * @param {object} selections 后端按活动返回的用户主键数组。
 * @returns {void} 每个受控活动都得到字符串主键数组。
 */
function replaceMultiInstanceSelections(selections) {
  Object.keys(multiInstanceUserIds).forEach(key => delete multiInstanceUserIds[key])
  const source = selections || {}
  startAssignments.value.forEach(assignment => {
    const userIds = Array.isArray(source[assignment.activityId]) ? source[assignment.activityId] : []
    multiInstanceUserIds[assignment.activityId] = userIds.map(userId => String(userId))
  })
}

/**
 * 加载新建流程所需的部署表单快照和安全 BPMN XML。
 * @returns {Promise<void>} 两项数据成功后开放保存与提交入口。
 */
async function loadNewContext() {
  const definitionId = routeDefinitionId()
  const deploymentIdFromRoute = routeDeploymentId()
  if (!definitionId || !deploymentIdFromRoute) throw new Error('流程定义或部署关系不能为空')
  const [formResponse, xmlResponse] = await Promise.all([
    getProcessForm({ definitionId, deploymentId: deploymentIdFromRoute }),
    getProcessBpmnXml(definitionId)
  ])
  processDefinitionId.value = definitionId
  deploymentId.value = deploymentIdFromRoute
  replaceFormSnapshot(formResponse.data || {})
  replaceMultiInstanceSelections({})
  if (startAssignments.value.length) await searchApprovalUsers('')
  bpmnXml.value = xmlResponse.data || ''
  businessKey.value = ''
  formValues.value = {}
}

/**
 * 按不可变表单快照识别附件字段，并把服务端 UUID 水合为授权安全元数据。
 * @param {object} snapshot 草稿绑定的不可变部署表单快照。
 * @param {object} variables 草稿持久化字段值，附件字段为 UUID 数组。
 * @returns {Promise<object>} 可供表单组件回显的字段值副本。
 */
async function hydrateDraftAttachments(snapshot, variables) {
  const values = JSON.parse(JSON.stringify(variables || {}))
  const fields = flattenFormFields(normalizeFormTemplate(snapshot.content).fields)
  const attachmentFields = fields.filter(field => field.tag === 'el-upload')
  for (const field of attachmentFields) {
    const items = Array.isArray(values[field.variable]) ? values[field.variable] : []
    values[field.variable] = await Promise.all(items.map(async item => {
      if (item && typeof item === 'object' && item.attachmentId) return item
      const attachmentId = String(item || '').trim()
      if (!attachmentId) throw new Error(`${field.label || field.variable}包含无效附件标识`)
      const response = await getWorkflowAttachment(attachmentId)
      return response.data
    }))
  }
  return values
}

/**
 * 使用草稿详情响应更新本页身份、状态、版本和不可变快照。
 * @param {object} draft 服务端草稿详情。
 * @param {boolean} replaceValues 是否同时替换页面业务字段和附件回显。
 * @returns {Promise<void>} 草稿详情完成安全回填后结束。
 */
async function applyDraft(draft, replaceValues) {
  const id = String(draft?.draftId || draft?.id || draftId.value || '').trim()
  const revisionNo = Number(draft?.revisionNo)
  if (!id || !Number.isInteger(revisionNo)) throw new Error('草稿主键或乐观锁版本不完整')
  draftId.value = id
  processDefinitionId.value = String(draft.processDefinitionId || processDefinitionId.value || '').trim()
  deploymentId.value = String(draft.deploymentId || deploymentId.value || '').trim()
  Object.assign(draftState, {
    processName: draft.processName || draftState.processName || '',
    status: draft.status || draftState.status || 'DRAFT',
    statusReason: draft.statusReason || '',
    editable: draft.editable !== false,
    submittable: draft.submittable !== false,
    revisionNo,
    updatedTime: draft.updatedTime || draft.updateTime || draftState.updatedTime || ''
  })
  const snapshot = draft.formSnapshot || draft.processForm || draft.form
  if (snapshot) replaceFormSnapshot(snapshot)
  if (replaceValues) {
    if (!snapshot && !formSnapshot.content) throw new Error('草稿缺少不可变部署表单快照')
    businessKey.value = draft.businessKey || ''
    formValues.value = await hydrateDraftAttachments(formSnapshot, draft.variables || formSnapshot.values || {})
    replaceMultiInstanceSelections(draft.multiInstanceUserIds)
    if (startAssignments.value.length) await searchApprovalUsers('')
  }
}

/**
 * 加载本人草稿、不可变部署表单和值；流程图失败不阻断草稿回显和删除。
 * @returns {Promise<void>} 草稿详情完成对象授权和附件水合后开放页面。
 */
async function loadDraftContext() {
  const id = routeDraftId() || draftId.value
  if (!id) throw new Error('草稿主键不能为空')
  const response = await getProcessDraft(id)
  await applyDraft(response.data || {}, true)
  bpmnXml.value = response.data?.bpmnXml || ''
  if (!bpmnXml.value && processDefinitionId.value) {
    try {
      const xmlResponse = await getProcessBpmnXml(processDefinitionId.value)
      bpmnXml.value = xmlResponse.data || ''
    } catch {
      // 定义停用或删除时保留草稿快照的查看和删除能力，流程图只做不可用降级。
      bpmnXml.value = ''
    }
  }
}

/**
 * 根据当前路由加载新建或继续编辑上下文，并重置脏状态和冲突状态。
 * @returns {Promise<void>} 页面上下文稳定后开放交互。
 */
async function loadPage() {
  loading.value = true
  ready.value = false
  syncingContext.value = true
  conflict.value = false
  try {
    if (routeDraftId()) await loadDraftContext()
    else await loadNewContext()
    ready.value = true
    await nextTick()
    dirty.value = false
  } catch (error) {
    showComponentError(error)
    if (!routeDraftId()) closePage(true)
  } finally {
    syncingContext.value = false
    loading.value = false
  }
}

/**
 * 生成草稿保存和提交共享的最终字段请求。
 * @param {object} variables 已由表单渲染器转换的字段值。
 * @returns {object} 仅包含后端固定契约允许的业务字段。
 */
function draftValuesPayload(variables) {
  const startMembers = {}
  startAssignments.value.forEach(assignment => {
    startMembers[assignment.activityId] = (multiInstanceUserIds[assignment.activityId] || [])
      .map(userId => Number(userId))
  })
  return {
    businessKey: businessKey.value.trim() || null,
    variables,
    multiInstanceUserIds: startMembers
  }
}

/**
 * 创建或按 CAS 更新草稿，并切换到可刷新恢复的继续编辑路由。
 * @param {object} variables 已完成附件忙碌门禁的字段值。
 * @returns {Promise<object>} 服务端返回的最新草稿详情。
 */
async function persistDraft(variables) {
  const payload = draftValuesPayload(variables)
  const response = draftId.value
    ? await updateProcessDraft(draftId.value, { expectedVersion: draftState.revisionNo, ...payload })
    : await createProcessDraft({ processDefinitionId: processDefinitionId.value, ...payload })
  const savedDraft = response.data || {}
  await applyDraft(savedDraft, false)
  dirty.value = false
  conflict.value = false
  if (routeDraftId() !== draftId.value) {
    await router.replace({ name: 'WorkflowProcessDraftEdit', params: { draftId: draftId.value } })
  }
  return savedDraft
}

/**
 * 保存允许缺少正式必填项的申请草稿，附件写请求必须先完成。
 * @returns {Promise<void>} 后端持久化成功后更新 CAS 版本和保存时间。
 */
async function saveDraft() {
  if (writing.value || conflict.value || !draftEditable.value || !formRendererRef.value) return
  actionType.value = 'save'
  try {
    await formRendererRef.value.ensureAttachmentsIdle()
    await persistDraft(formRendererRef.value.getValues())
    proxy.$modal.msgSuccess('草稿保存成功')
  } catch (error) {
    handleWriteError(error)
  } finally {
    actionType.value = ''
  }
}

/**
 * 正式提交当前申请；首次提交先建立可追踪草稿，再由提交事务创建唯一实例。
 * @returns {Promise<void>} 成功后进入真实流程实例详情。
 */
async function submitDraft() {
  if (writing.value || conflict.value || !draftSubmittable.value || !formRendererRef.value) return
  // 写互斥必须在首个 await 前建立，防止异步校验期间快速双击创建两个正式实例。
  actionType.value = 'submit'
  try {
    const valid = await formRendererRef.value.validate().catch(error => {
      showComponentError(error)
      return false
    })
    if (!valid) return
    const variables = formRendererRef.value.getValues()
    for (const assignment of startAssignments.value) {
      const selected = multiInstanceUserIds[assignment.activityId] || []
      if (selected.length < assignment.minUsers || selected.length > assignment.maxUsers) {
        proxy.$modal.msgError(`${assignmentLabel(assignment)}必须选择 ${assignment.minUsers} 至 ${assignment.maxUsers} 人`)
        return
      }
    }
    if (!draftId.value) await persistDraft(variables)
    const response = await submitProcessDraft(draftId.value, {
      expectedVersion: draftState.revisionNo,
      ...draftValuesPayload(variables)
    })
    // 提交响应只接受统一后的正式协议字段，缺失时禁止猜测旧别名并跳转错误实例。
    const processInstanceId = response.data?.processInstanceId
    if (!processInstanceId) throw new Error('草稿提交结果缺少流程实例主键')
    dirty.value = false
    proxy.$modal.msgSuccess('申请提交成功')
    proxy.$tab.closeOpenPage({ path: `/workflow/process-detail/${processInstanceId}` })
  } catch (error) {
    handleWriteError(error)
  } finally {
    actionType.value = ''
  }
}

/**
 * 生成发起成员字段的业务标签。
 * @param {{activityName: string, mode: 'ALL'|'ANY'}} assignment 后端部署模型投影。
 * @returns {string} 节点名称与会签或或签语义组合标签。
 */
function assignmentLabel(assignment) {
  return `${assignment.activityName}（${assignment.mode === 'ALL' ? '会签' : '或签'}）`
}

/**
 * 从正式审批用户目录检索发起时可选成员，过期响应不会覆盖最新关键字结果。
 * @param {string} keyword 用户输入的姓名或账号关键字。
 * @returns {Promise<void>} 成功后更新正式可办理用户选项。
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
 * 合并目录结果并保留当前已选成员，避免远程检索后选择值丢失。
 * @param {Array<{value: string|number, label: string}>} rows 正式审批用户目录结果。
 * @returns {void} 更新后的选项以本次查询顺序为主。
 */
function mergeApprovalUserOptions(rows) {
  const selectedUserIds = new Set(Object.values(multiInstanceUserIds)
    .flatMap(userIds => Array.isArray(userIds) ? userIds : [])
    .map(userId => String(userId)))
  const mergedOptions = new Map(rows.map(option => [String(option.value), option]))
  approvalUserOptions.value.forEach(option => {
    const userId = String(option.value)
    if (selectedUserIds.has(userId) && !mergedOptions.has(userId)) mergedOptions.set(userId, option)
  })
  approvalUserOptions.value = [...mergedOptions.values()]
}

/**
 * 删除当前本人草稿，服务端负责 CAS、附件解绑和状态校验。
 * @returns {Promise<void>} 删除成功后返回本人草稿列表。
 */
async function removeDraft() {
  if (writing.value || !draftId.value) return
  await proxy.$modal.confirm('确认删除当前申请草稿吗？')
  actionType.value = 'delete'
  try {
    await deleteProcessDraft(draftId.value, draftState.revisionNo)
    dirty.value = false
    proxy.$modal.msgSuccess('草稿删除成功')
    closePage(true)
  } catch (error) {
    handleWriteError(error)
  } finally {
    actionType.value = ''
  }
}

/**
 * 处理草稿写请求失败；CAS 冲突保留当前输入并禁止继续覆盖。
 * @param {unknown} error 统一请求层返回的业务错误。
 * @returns {void} 无返回值。
 */
function handleWriteError(error) {
  const subCode = String(error?.subCode || '').toUpperCase()
  const casConflict = !subCode || ['DRAFT_VERSION_CONFLICT', 'DRAFT_CAS_CONFLICT', 'VERSION_CONFLICT', 'CAS_CONFLICT'].includes(subCode)
  if (Number(error?.code) === 409 && casConflict) {
    conflict.value = true
    return
  }
  if (Number(error?.code) === 409 && subCode.includes('DEFINITION')) {
    // 定义停用、删除或版本过期后锁定草稿编辑提交，但仍保留本人查看和删除入口。
    draftState.editable = false
    draftState.submittable = false
    draftState.statusReason = error?.message || '草稿绑定的流程定义当前不可用'
  }
  showComponentError(error)
}

/**
 * 经用户确认后丢弃冲突页面输入并重新读取服务器草稿版本。
 * @returns {Promise<void>} 重新加载成功后解除冲突门禁。
 */
async function reloadAfterConflict() {
  await proxy.$modal.confirm('重新加载会丢弃当前页面尚未保存的输入，是否继续？')
  await loadPage()
}

/**
 * 标记表单存在尚未保存到后端的真实修改。
 * @returns {void} 无返回值。
 */
function markDirty() {
  if (!syncingContext.value && ready.value && draftEditable.value) dirty.value = true
}

/**
 * 显示表单、附件、流程图或草稿接口返回的稳定错误。
 * @param {unknown} error 公共组件或请求错误对象。
 * @returns {void} 无返回值。
 */
function showComponentError(error) {
  const message = typeof error?.message === 'string' ? error.message.trim() : ''
  if (message) proxy.$modal.msgError(message)
}

/**
 * 关闭当前页并返回新建流程或本人草稿列表。
 * @param {boolean} force 是否已经完成删除、提交或初始化失败，无需离开确认。
 * @returns {void} 无返回值。
 */
function closePage(force = false) {
  if (force) dirty.value = false
  proxy.$tab.closeOpenPage({ path: draftId.value ? '/office/draft' : '/office/create', query: { t: Date.now() } })
}

watch(businessKey, () => markDirty())
watch(() => route.fullPath, loadPage)

onBeforeRouteLeave(async () => {
  if (!dirty.value || writing.value) return true
  try {
    await proxy.$modal.confirm('当前申请有未保存修改，确认离开吗？')
    return true
  } catch {
    return false
  }
})

loadPage()
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

.process-start-page__meta,
.process-start-page__actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.process-start-page__meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.process-start-page__actions {
  flex-shrink: 0;
}

.process-start-page__alert,
.process-start-page__tabs {
  margin-top: 12px;
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
  .process-start-page__header,
  .process-start-page__actions {
    align-items: stretch;
  }

  .process-start-page__header {
    flex-direction: column;
  }

  .process-start-page__actions {
    flex-wrap: wrap;
  }
}
</style>
