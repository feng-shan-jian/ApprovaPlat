<template>
  <div class="app-container model-design-page" v-loading="loading">
    <div class="model-design-page__header">
      <div class="model-design-page__identity">
        <el-button circle text icon="ArrowLeft" aria-label="返回模型列表" @click="closePage" />
        <div>
          <h2>{{ model.modelName || '流程模型设计' }}</h2>
          <div class="model-design-page__meta">
            <span>{{ model.modelKey }}</span>
            <el-tag size="small" type="info">V{{ model.version || 1 }}</el-tag>
            <el-tag size="small" :type="model.deployed ? 'success' : 'info'">{{ model.deployed ? '已部署' : '未部署' }}</el-tag>
          </div>
        </div>
      </div>
    </div>

    <ProcessDesigner
      v-if="ready"
      v-model="bpmnXml"
      :model="model"
      :forms="formOptions"
      :identity-options="identityOptions"
      :identity-loading="identityLoading"
      :saving="saving"
      :preference="designerPreference"
      :preference-saving="preferenceSaving"
      height="100%"
      @identity-search="searchIdentityDirectory"
      @identity-resolve="resolveSelectedIdentities"
      @preference-save="savePreference"
      @save="saveDesign"
      @error="showDesignerError"
    />
  </div>
</template>

<script setup name="WorkflowModelDesign">
import { listForms } from '@/api/workflow/form'
import { listApprovalUserOptions, listClaimableIdentityOptions, listIdentityOptions, resolveIdentityOptions } from '@/api/workflow/identity'
import { getModel, getModelBpmnXml, saveModel } from '@/api/workflow/model'
import { getDesignerPreference, saveDesignerPreference } from '@/api/workflow/designer'
import ProcessDesigner from '@/components/workflow/ProcessDesigner.vue'

const route = useRoute()
const router = useRouter()
const { proxy } = getCurrentInstance()
const loading = ref(false)
const saving = ref(false)
const preferenceSaving = ref(false)
const ready = ref(false)
const bpmnXml = ref('')
const model = reactive({})
const formOptions = ref([])
const identityOptions = reactive({
  assignees: [], candidateUsers: [], candidateGroups: [], candidateRoles: [],
  activeUsers: [], activeRoles: [], activeDepts: [], autoCopyUsers: [], autoCopyGroups: []
})
const identityPending = ref(0)
const identityLoading = computed(() => identityPending.value > 0)
// designerPreference 只接收服务端默认值或数据库回读值，不使用浏览器本地状态兜底。
const designerPreference = reactive({
  theme: 'SYSTEM',
  gridEnabled: true,
  minimapEnabled: true,
  lintEnabled: true,
  tokenSimulationEnabled: false,
  propertiesCollapsed: false
})
const identityRequestVersion = {
  assignees: 0, candidateUsers: 0, candidateGroups: 0, candidateRoles: 0,
  activeUsers: 0, activeRoles: 0, activeDepts: 0, autoCopyUsers: 0, autoCopyGroups: 0
}
// pendingSaveRequest 保存尚未取得完整成功响应的用户保存意图，网络重试必须复用同一幂等键。
let pendingSaveRequest

/**
 * 获取当前路由中的 Flowable 模型主键。
 * @returns {string} 去除首尾空白的模型主键。
 */
function currentModelId() {
  return String(route.params.modelId || '').trim()
}

/**
 * 为当前保存意图创建或复用稳定 UUID，只有来源模型或 XML 改变时才形成新意图。
 * @param {string} modelId 当前设计页打开的 Flowable 模型主键。
 * @param {string} xml 本次准备持久化的完整 BPMN XML。
 * @returns {string} 可在失败重试中复用的 UUID 保存请求主键。
 */
function resolveSaveRequestId(modelId, xml) {
  if (pendingSaveRequest?.modelId === modelId && pendingSaveRequest?.xml === xml) {
    return pendingSaveRequest.requestId
  }
  if (typeof globalThis.crypto?.randomUUID !== 'function') {
    throw new Error('当前浏览器不支持安全保存请求标识')
  }
  pendingSaveRequest = Object.freeze({
    modelId,
    xml,
    requestId: globalThis.crypto.randomUUID()
  })
  return pendingSaveRequest.requestId
}

/**
 * 分页加载全部有效表单，确保 BPMN 节点可以引用任一正式模板。
 * @returns {Promise<void>} 所有分页完成后一次性更新表单选项。
 */
async function loadAllForms() {
  const firstPage = await listForms({ pageNum: 1, pageSize: 50 })
  const forms = [...(firstPage.rows || [])]
  const pageCount = Math.ceil((firstPage.total || 0) / 50)
  for (let pageNum = 2; pageNum <= pageCount; pageNum += 1) {
    const page = await listForms({ pageNum, pageSize: 50 })
    forms.push(...(page.rows || []))
  }
  formOptions.value = forms
}

/**
 * 将设计器身份请求映射到隔离的选项池，非法能力组合必须失败关闭。
 * @param {{type?: string, capability?: string}|undefined} request 设计器提交的身份类型和能力范围。
 * @returns {string} 对应隔离选项池键；空串表示非法组合。
 */
function identityRequestTarget(request) {
  const contracts = {
    assignees: ['user', 'approval'], candidateUsers: ['user', 'claim'],
    candidateGroups: ['group', 'claim'], candidateRoles: ['role', 'claim'],
    activeUsers: ['user', ''], activeRoles: ['role', ''], activeDepts: ['dept', ''],
    autoCopyUsers: ['user', 'copy'], autoCopyGroups: ['group', 'copy']
  }
  const target = String(request?.target || '')
  const contract = contracts[target]
  return contract && contract[0] === request?.type && contract[1] === (request?.capability || '')
    ? target
    : ''
}

/**
 * 根据设计器检索请求读取直接办理人或完整可认领候选身份目录。
 * @param {{type: 'user'|'group', keyword: string, capability: 'approval'|'claim'|'copy'}} request 检索类型、关键字和资格范围。
 * @returns {Promise<void>} 最新请求完成后替换对应身份选项，过期响应会被丢弃。
 */
async function searchIdentityDirectory(request) {
  const target = identityRequestTarget(request)
  if (!target) throw new TypeError('工作流身份目录请求不合法')
  const keyword = String(request?.keyword || '').trim()
  const requestVersion = ++identityRequestVersion[target]
  identityPending.value += 1
  try {
    if (target === 'assignees') {
      // 直接办理人必须具备服务端定义的完整 approval 办理资格。
      const response = await listApprovalUserOptions({ keyword, pageNum: 1, pageSize: 50 })
      if (requestVersion === identityRequestVersion.assignees) {
        identityOptions.assignees = response.rows || []
      }
      return
    }
    if (target === 'candidateUsers') {
      // 候选用户还必须能查看待签、执行认领并在认领后完成办理，不能沿用较宽的办理人目录。
      const response = await listClaimableIdentityOptions({
        type: 'user', keyword, pageNum: 1, pageSize: 50
      })
      if (requestVersion === identityRequestVersion.candidateUsers) {
        identityOptions.candidateUsers = response.rows || []
      }
      return
    }
    if (target === 'candidateGroups') {
      const [roleResponse, deptResponse] = await Promise.all([
        listClaimableIdentityOptions({ type: 'role', keyword, pageNum: 1, pageSize: 50 }),
        listClaimableIdentityOptions({ type: 'dept', keyword, pageNum: 1, pageSize: 50 })
      ])
      if (requestVersion === identityRequestVersion.candidateGroups) {
        identityOptions.candidateGroups = [
          ...(roleResponse.rows || []), ...(deptResponse.rows || [])
        ]
      }
      return
    }
    if (target === 'autoCopyUsers') {
      // 自动抄送固定用户使用后端 copy 能力目录，目录层先过滤有效用户和抄送对象可见性资格。
      const response = await listIdentityOptions({
        type: 'user', capability: 'copy', keyword, pageNum: 1, pageSize: 50
      })
      if (requestVersion === identityRequestVersion.autoCopyUsers) {
        identityOptions.autoCopyUsers = response.rows || []
      }
      return
    }
    if (target === 'autoCopyGroups') {
      // 角色和部门并行查询后保持各自 ROLE/DEPT 稳定编码，禁止前端自造组标识。
      const [roleResponse, deptResponse] = await Promise.all([
        listIdentityOptions({ type: 'role', capability: 'copy', keyword, pageNum: 1, pageSize: 50 }),
        listIdentityOptions({ type: 'dept', capability: 'copy', keyword, pageNum: 1, pageSize: 50 })
      ])
      if (requestVersion === identityRequestVersion.autoCopyGroups) {
        identityOptions.autoCopyGroups = [...(roleResponse.rows || []), ...(deptResponse.rows || [])]
      }
      return
    }
    if (target === 'candidateRoles') {
      const response = await listClaimableIdentityOptions({
        type: 'role', keyword, pageNum: 1, pageSize: 50
      })
      if (requestVersion === identityRequestVersion.candidateRoles) {
        identityOptions.candidateRoles = response.rows || []
      }
      return
    }
    const type = { activeUsers: 'user', activeRoles: 'role', activeDepts: 'dept' }[target]
    const response = await listIdentityOptions({ type, keyword, pageNum: 1, pageSize: 50 })
    if (requestVersion === identityRequestVersion[target]) {
      identityOptions[target] = response.rows || []
    }
  } finally {
    identityPending.value -= 1
  }
}

/**
 * 将批量回显结果合并到对应正式目录池，防止重开模型时远程分页外对象显示裸值。
 * @param {string} target 设计器身份选项池。
 * @param {object[]} rows 正式目录返回的名称和实时可用状态。
 * @returns {void} 同值新结果覆盖旧结果，其余已加载检索选项保持不变。
 */
function mergeResolvedIdentityOptions(target, rows) {
  const merged = new Map((identityOptions[target] || [])
    .map(option => [String(option.value), option]))
  for (const row of rows || []) merged.set(String(row.value), row)
  identityOptions[target] = [...merged.values()]
}

/**
 * 通过正式批量接口回显已保存身份；混合候选组按角色和部门分开核验后合并。
 * @param {{target:string,type:string,capability:string,values:string[]}} request 受控目录回显请求。
 * @returns {Promise<void>} 最新正式名称和 available 状态合并完成后结束。
 */
async function resolveSelectedIdentities(request) {
  const target = identityRequestTarget(request)
  const values = [...new Set((Array.isArray(request?.values) ? request.values : [])
    .map(value => String(value || '').trim()).filter(Boolean))]
  if (!target || !values.length || values.length > 200) {
    throw new TypeError('工作流已选身份回显请求不合法')
  }
  identityPending.value += 1
  try {
    if (target === 'candidateGroups') {
      const roleValues = values.filter(value => /^ROLE[1-9]\d{0,18}$/.test(value))
      const deptValues = values.filter(value => /^DEPT[1-9]\d{0,18}$/.test(value))
      if (roleValues.length + deptValues.length !== values.length) {
        throw new TypeError('候选组已选身份值不合法')
      }
      const responses = await Promise.all([
        roleValues.length ? resolveIdentityOptions({ type: 'role', capability: 'claim', values: roleValues }) : null,
        deptValues.length ? resolveIdentityOptions({ type: 'dept', capability: 'claim', values: deptValues }) : null
      ])
      mergeResolvedIdentityOptions(target, responses.flatMap(response => response?.data || []))
      return
    }
    const contract = {
      assignees: ['user', 'approval'], candidateUsers: ['user', 'claim'],
      candidateRoles: ['role', 'claim'], activeUsers: ['user', ''],
      activeRoles: ['role', ''], activeDepts: ['dept', '']
    }[target]
    const response = await resolveIdentityOptions({
      type: contract[0], capability: contract[1], values
    })
    mergeResolvedIdentityOptions(target, response.data || [])
  } finally {
    identityPending.value -= 1
  }
}

/**
 * 加载模型详情、安全 BPMN XML、正式表单及首批身份目录。
 * @returns {Promise<void>} 所有关键数据就绪后才挂载设计器。
 */
async function loadDesigner() {
  const modelId = currentModelId()
  if (!modelId) {
    proxy.$modal.msgError('模型主键不能为空')
    closePage()
    return
  }
  loading.value = true
  ready.value = false
  try {
    const [modelResponse, xmlResponse, preferenceResponse] = await Promise.all([
      getModel(modelId),
      getModelBpmnXml(modelId),
      getDesignerPreference(),
      loadAllForms(),
      searchIdentityDirectory({ target: 'assignees', type: 'user', capability: 'approval', keyword: '' }),
      searchIdentityDirectory({ target: 'candidateUsers', type: 'user', capability: 'claim', keyword: '' }),
      searchIdentityDirectory({ target: 'candidateGroups', type: 'group', capability: 'claim', keyword: '' }),
      searchIdentityDirectory({ target: 'candidateRoles', type: 'role', capability: 'claim', keyword: '' }),
      searchIdentityDirectory({ target: 'activeUsers', type: 'user', capability: '', keyword: '' }),
      searchIdentityDirectory({ target: 'activeRoles', type: 'role', capability: '', keyword: '' }),
      searchIdentityDirectory({ target: 'activeDepts', type: 'dept', capability: '', keyword: '' }),
      searchIdentityDirectory({ target: 'autoCopyUsers', type: 'user', capability: 'copy', keyword: '' }),
      searchIdentityDirectory({ target: 'autoCopyGroups', type: 'group', capability: 'copy', keyword: '' })
    ])
    Object.keys(model).forEach(key => delete model[key])
    Object.assign(model, modelResponse.data || {})
    Object.assign(designerPreference, preferenceResponse.data || {})
    bpmnXml.value = xmlResponse.data || ''
    ready.value = true
  } finally {
    loading.value = false
  }
}

/**
 * 原子保存当前用户的完整设计器偏好，并只采用数据库回读结果。
 * @param {object} preference 主题、网格、小地图、Lint、Token 模拟和属性面板状态。
 * @returns {Promise<void>} 服务端成功后回写真实偏好，失败时保持原状态。
 */
async function savePreference(preference) {
  preferenceSaving.value = true
  try {
    const response = await saveDesignerPreference(preference)
    Object.assign(designerPreference, response.data || {})
    proxy.$modal.msgSuccess('设计器设置已保存')
  } finally {
    preferenceSaving.value = false
  }
}

/**
 * 将设计器校验通过的 BPMN 原子保存到后端模型资源。
 * @param {string} xml 完整 BPMN 2.0 XML 正文。
 * @returns {Promise<void>} 保存成功后同步实际模型版本主键和元数据。
 */
async function saveDesign(xml) {
  saving.value = true
  try {
    const sourceModelId = currentModelId()
    const requestId = resolveSaveRequestId(sourceModelId, xml)
    const response = await saveModel({
      requestId,
      modelId: sourceModelId,
      bpmnXml: xml,
      // 前端不再暴露手动版本开关；后端按已部署或历史版本状态自动另存并返回实际模型主键。
      newVersion: false
    })
    // savedModelId 是后端本次真实落库版本；缺失时不能回退旧路由并伪装保存成功。
    const savedModelId = String(response.data?.modelId || '').trim()
    if (!savedModelId) {
      proxy.$modal.msgError('流程模型保存结果不完整')
      return
    }
    // 只有后端返回真实落库主键后才结束该保存意图；响应丢失时下次点击会复用 requestId。
    pendingSaveRequest = undefined
    proxy.$modal.msgSuccess('流程设计保存成功')
    if (savedModelId !== currentModelId()) {
      await router.replace({ name: 'WorkflowModelDesign', params: { modelId: savedModelId } })
    }
    await loadDesigner()
  } finally {
    saving.value = false
  }
}

/**
 * 显示 BPMN 导入、导出或本地结构校验错误。
 * @param {Error} error 设计器返回的错误对象。
 * @returns {void} 无返回值。
 */
function showDesignerError(error) {
  proxy.$modal.msgError(error?.message || '流程设计器处理失败')
}

/**
 * 关闭当前标签并返回流程模型列表。
 * @returns {void} 无返回值。
 */
function closePage() {
  proxy.$tab.closeOpenPage({ path: '/workflow/model', query: { t: Date.now() } })
}

loadDesigner()
</script>

<style scoped lang="scss">
.model-design-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  padding-top: 12px;
  overflow: hidden;
}

.model-design-page__header {
  display: flex;
  flex: none;
  min-height: 54px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.model-design-page__identity {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.model-design-page__identity h2 {
  margin: 0 0 3px;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 17px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-design-page__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.model-design-page :deep(.process-designer) {
  flex: 1;
  min-height: 0;
}

@media (max-width: 768px) {
  .model-design-page__header {
    align-items: flex-start;
  }

  .model-design-page__meta {
    flex-wrap: wrap;
  }
}
</style>
