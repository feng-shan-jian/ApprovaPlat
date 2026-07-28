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
  if (!formRendererRef.value) return
  const valid = await formRendererRef.value.validate().catch(error => {
    showComponentError(error)
    return false
  })
  if (!valid) return
  submitting.value = true
  try {
    const variables = formRendererRef.value.getValues()
    const response = await startProcess(definitionId(), {
      businessKey: businessKey.value.trim() || undefined,
      variables
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

@media (max-width: 768px) {
  .process-start-page__header {
    align-items: flex-start;
  }
}
</style>
