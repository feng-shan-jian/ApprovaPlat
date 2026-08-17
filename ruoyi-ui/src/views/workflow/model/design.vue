<template>
  <div class="app-container model-design-page" v-loading="loading">
    <div class="model-design-page__header">
      <div class="model-design-page__identity">
        <el-tooltip content="返回流程模型" placement="bottom">
          <el-button class="model-design-page__back" circle text icon="ArrowLeft" aria-label="返回模型列表" @click="closePage" />
        </el-tooltip>
        <div class="model-design-page__heading">
          <div class="model-design-page__eyebrow"><i />流程编排工作台</div>
          <h2>{{ model.modelName || '流程模型设计' }}</h2>
          <div class="model-design-page__meta">
            <code>{{ model.modelKey }}</code>
            <span class="model-design-page__meta-divider" />
            <span>版本 V{{ model.version || 1 }}</span>
            <span class="model-design-page__status" :class="{ 'is-deployed': model.deployed }">
              <i />{{ model.deployed ? '已部署' : '设计中' }}
            </span>
          </div>
        </div>
      </div>
      <div class="model-design-page__format" aria-label="建模格式 BPMN 2.0，执行引擎 Flowable">
        <span>BPMN 2.0</span>
        <i />
        <span>Flowable</span>
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
      @preference-reset="restoreDefaultPreference"
      @save="saveDesign"
      @error="showDesignerError"
    />
  </div>
</template>

<script setup name="WorkflowModelDesign">
import { listForms } from '@/api/workflow/form'
import { listApprovalUserOptions, listClaimableIdentityOptions, listIdentityOptions, resolveIdentityOptions } from '@/api/workflow/identity'
import { getModel, getModelBpmnXml, saveModel } from '@/api/workflow/model'
import ProcessDesigner from '@/components/workflow/ProcessDesigner.vue'
import useUserStore from '@/store/modules/user'
import {
  DEFAULT_DESIGNER_PREFERENCE,
  loadDesignerPreference,
  resetDesignerPreference,
  saveDesignerPreference
} from '@/utils/workflowDesignerPreference'

const route = useRoute()
const router = useRouter()
const { proxy } = getCurrentInstance()
const userStore = useUserStore()
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
// designerPreference 是当前登录用户在当前浏览器中的非业务界面偏好。
const designerPreference = reactive({ ...DEFAULT_DESIGNER_PREFERENCE })
const identityRequestVersion = {
  assignees: 0, candidateUsers: 0, candidateGroups: 0, candidateRoles: 0,
  activeUsers: 0, activeRoles: 0, activeDepts: 0, autoCopyUsers: 0, autoCopyGroups: 0
}
/**
 * 获取当前路由中的 Flowable 模型主键。
 * @returns {string} 去除首尾空白的模型主键。
 */
function currentModelId() {
  return String(route.params.modelId || '').trim()
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
    const [modelResponse, xmlResponse] = await Promise.all([
      getModel(modelId),
      getModelBpmnXml(modelId),
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
    Object.assign(designerPreference, loadDesignerPreference(userStore.id))
    bpmnXml.value = xmlResponse.data || ''
    ready.value = true
  } finally {
    loading.value = false
  }
}

/**
 * 保存当前用户的完整设计器偏好，并立即采用白名单规范化后的本地结果。
 * @param {object} preference 主题、网格、小地图、Token 模拟和属性面板状态。
 * @returns {void} localStorage 写入成功后回写当前偏好。
 */
function savePreference(preference) {
  preferenceSaving.value = true
  try {
    Object.assign(designerPreference, saveDesignerPreference(userStore.id, preference))
    proxy.$modal.msgSuccess('设计器设置已保存')
  } finally {
    preferenceSaving.value = false
  }
}

/**
 * 恢复当前用户默认设置，只删除当前用户对应的偏好键。
 * @returns {void} 删除成功后立即应用默认偏好。
 */
function restoreDefaultPreference() {
  Object.assign(designerPreference, resetDesignerPreference(userStore.id))
  proxy.$modal.msgSuccess('已恢复默认设置')
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
    const expectedBpmnSha256 = String(model.bpmnSha256 || '').trim()
    const response = await saveModel({
      modelId: sourceModelId,
      bpmnXml: xml,
      expectedBpmnSha256,
      // 前端不再暴露手动版本开关；后端按已部署或历史版本状态自动另存并返回实际模型主键。
      newVersion: false
    })
    // 三个字段共同证明后端真实保存版本，任一缺失都不能沿用旧状态伪装成功。
    const savedModelId = String(response.data?.modelId || '').trim()
    const savedVersion = Number(response.data?.version)
    const savedBpmnSha256 = String(response.data?.bpmnSha256 || '').trim()
    if (!savedModelId || !Number.isInteger(savedVersion) || savedVersion <= 0 ||
      !/^[0-9a-f]{64}$/.test(savedBpmnSha256)) {
      proxy.$modal.msgError('流程模型保存结果不完整')
      return
    }
    Object.assign(model, {
      modelId: savedModelId,
      version: savedVersion,
      bpmnSha256: savedBpmnSha256
    })
    proxy.$modal.msgSuccess('流程设计保存成功')
    if (savedModelId !== currentModelId()) {
      await router.replace({ name: 'WorkflowModelDesign', params: { modelId: savedModelId } })
    }
    await loadDesigner()
  } finally {
    saving.value = false
  }
}

watch(() => userStore.id, userId => {
  if (String(userId ?? '').trim()) {
    Object.assign(designerPreference, loadDesignerPreference(userId))
  }
})

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
  min-height: 70px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 4px 10px;
}

.model-design-page__identity {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.model-design-page__back {
  flex: none;
  width: 36px;
  height: 36px;
  color: var(--el-text-color-regular);
  background: var(--app-surface, var(--el-bg-color));
  border: 1px solid var(--app-border, var(--el-border-color-light));
  border-radius: 9px;
  box-shadow: var(--app-shadow-sm, 0 1px 2px rgb(15 23 42 / 6%));
}

.model-design-page__back:hover,
.model-design-page__back:focus-visible {
  color: var(--el-color-primary);
  border-color: color-mix(in srgb, var(--el-color-primary) 42%, var(--el-border-color));
}

.model-design-page__heading {
  min-width: 0;
}

.model-design-page__eyebrow {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 2px;
  color: var(--el-text-color-secondary);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.13em;
}

.model-design-page__eyebrow i {
  width: 16px;
  height: 2px;
  background: var(--app-accent, var(--el-color-primary));
  border-radius: 999px;
}

.model-design-page__identity h2 {
  margin: 0 0 4px;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 19px;
  font-weight: 680;
  letter-spacing: -0.02em;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-design-page__meta {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.model-design-page__meta code {
  max-width: 260px;
  overflow: hidden;
  color: var(--el-text-color-regular);
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-design-page__meta-divider {
  width: 1px;
  height: 10px;
  background: var(--el-border-color);
}

.model-design-page__status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-left: 2px;
  padding: 2px 7px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px;
}

.model-design-page__status i {
  width: 5px;
  height: 5px;
  background: var(--el-color-warning);
  border-radius: 50%;
}

.model-design-page__status.is-deployed {
  color: var(--el-color-success);
  background: color-mix(in srgb, var(--el-color-success) 8%, transparent);
  border-color: color-mix(in srgb, var(--el-color-success) 24%, var(--el-border-color-lighter));
}

.model-design-page__status.is-deployed i {
  background: var(--el-color-success);
}

.model-design-page__format {
  display: inline-flex;
  align-items: center;
  flex: none;
  gap: 9px;
  padding: 7px 10px;
  color: var(--el-text-color-secondary);
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.05em;
  background: color-mix(in srgb, var(--app-surface, var(--el-bg-color)) 86%, transparent);
  border: 1px solid var(--app-border, var(--el-border-color-light));
  border-radius: 7px;
}

.model-design-page__format i {
  width: 3px;
  height: 3px;
  background: var(--app-accent, var(--el-color-primary));
  border-radius: 50%;
}

.model-design-page :deep(.process-designer) {
  flex: 1;
  min-height: 0;
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

  .model-design-page__format {
    display: none;
  }
}
</style>
