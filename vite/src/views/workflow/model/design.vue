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
      <el-checkbox v-model="saveAsNewVersion" :disabled="Boolean(model.deployed)">保存为新版本</el-checkbox>
    </div>

    <ProcessDesigner
      v-if="ready"
      v-model="bpmnXml"
      :model="model"
      :forms="formOptions"
      :identity-options="identityOptions"
      :identity-loading="identityLoading"
      :saving="saving"
      height="calc(100vh - 148px)"
      @identity-search="searchIdentityDirectory"
      @save="saveDesign"
      @error="showDesignerError"
    />
  </div>
</template>

<script setup name="WorkflowModelDesign">
import { listForms } from '@/api/workflow/form'
import { listApprovalUserOptions, listClaimableIdentityOptions } from '@/api/workflow/identity'
import { getModel, getModelBpmnXml, saveModel } from '@/api/workflow/model'
import ProcessDesigner from '@/components/workflow/ProcessDesigner.vue'

const route = useRoute()
const router = useRouter()
const { proxy } = getCurrentInstance()
const loading = ref(false)
const saving = ref(false)
const ready = ref(false)
const bpmnXml = ref('')
const model = reactive({})
const formOptions = ref([])
const identityOptions = reactive({ assignees: [], candidateUsers: [], candidateGroups: [] })
const identityPending = ref(0)
const identityLoading = computed(() => identityPending.value > 0)
const saveAsNewVersion = ref(false)
const identityRequestVersion = { assignees: 0, candidateUsers: 0, candidateGroups: 0 }

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
 * @returns {'assignees'|'candidateUsers'|'candidateGroups'|''} 对应选项池键；空串表示非法组合。
 */
function identityRequestTarget(request) {
  if (request?.type === 'user' && request?.capability === 'approval') return 'assignees'
  if (request?.type === 'user' && request?.capability === 'claim') return 'candidateUsers'
  if (request?.type === 'group' && request?.capability === 'claim') return 'candidateGroups'
  return ''
}

/**
 * 根据设计器检索请求读取直接办理人或完整可认领候选身份目录。
 * @param {{type: 'user'|'group', keyword: string, capability: 'approval'|'claim'}} request 检索类型、关键字和资格范围。
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
    const [roleResponse, deptResponse] = await Promise.all([
      listClaimableIdentityOptions({ type: 'role', keyword, pageNum: 1, pageSize: 50 }),
      listClaimableIdentityOptions({ type: 'dept', keyword, pageNum: 1, pageSize: 50 })
    ])
    if (requestVersion === identityRequestVersion.candidateGroups) {
      identityOptions.candidateGroups = [
        ...(roleResponse.rows || []), ...(deptResponse.rows || [])
      ]
    }
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
      searchIdentityDirectory({ type: 'user', capability: 'approval', keyword: '' }),
      searchIdentityDirectory({ type: 'user', capability: 'claim', keyword: '' }),
      searchIdentityDirectory({ type: 'group', capability: 'claim', keyword: '' })
    ])
    Object.keys(model).forEach(key => delete model[key])
    Object.assign(model, modelResponse.data || {})
    bpmnXml.value = xmlResponse.data || ''
    saveAsNewVersion.value = Boolean(model.deployed)
    ready.value = true
  } finally {
    loading.value = false
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
    const response = await saveModel({
      modelId: currentModelId(),
      bpmnXml: xml,
      newVersion: Boolean(model.deployed || saveAsNewVersion.value)
    })
    const savedModelId = String(response.data?.modelId || currentModelId())
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
  padding-top: 12px;
}

.model-design-page__header {
  display: flex;
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

@media (max-width: 768px) {
  .model-design-page__header {
    align-items: flex-start;
  }

  .model-design-page__meta {
    flex-wrap: wrap;
  }
}
</style>
