<template>
  <div class="app-container dmn-page">
    <header class="dmn-page__heading">
      <div>
        <h2>DMN 决策</h2>
        <p>管理 Flowable 官方决策版本，并供业务规则任务冻结精确版本</p>
      </div>
      <div class="dmn-page__summary">
        <span><strong>{{ decisionKeyCount }}</strong> 个决策</span>
        <span><strong>{{ rows.length }}</strong> 个版本</span>
      </div>
    </header>

    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5">
        <el-button type="primary" plain icon="Upload" v-hasPermi="['workflow:dmn:add']" @click="openDeploy">部署 DMN</el-button>
      </el-col>
      <right-toolbar :show-search="false" @queryTable="loadRows" />
    </el-row>

    <el-table v-loading="loading" :data="rows" row-key="decisionId">
      <el-table-column label="决策" min-width="230">
        <template #default="scope">
          <div class="dmn-page__identity">
            <strong>{{ scope.row.decisionName || scope.row.decisionKey }}</strong>
            <code>{{ scope.row.decisionKey }}</code>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="版本" width="84" align="center">
        <template #default="scope"><el-tag size="small">v{{ scope.row.version }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="decisionType" label="类型" width="120" align="center" />
      <el-table-column prop="category" label="分类" min-width="150" show-overflow-tooltip />
      <el-table-column prop="resourceName" label="资源" min-width="210" show-overflow-tooltip />
      <el-table-column label="部署时间" width="172">
        <template #default="scope">{{ parseTime(scope.row.deploymentTime) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="76" fixed="right" align="center">
        <template #default="scope">
          <el-tooltip content="删除该部署" placement="top">
            <el-button link type="danger" icon="Delete" aria-label="删除该部署" v-hasPermi="['workflow:dmn:remove']" @click="removeRow(scope.row)" />
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogOpen" title="部署 DMN 决策" width="min(820px, 86vw)" append-to-body>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
        <el-form-item label="资源文件" required>
          <el-upload ref="uploadRef" :auto-upload="false" :limit="1" accept=".dmn,application/xml,text/xml" :on-change="readFile" :on-remove="clearFile">
            <el-button icon="FolderOpened">选择 .dmn 文件</el-button>
          </el-upload>
        </el-form-item>
        <el-form-item label="资源名" prop="resourceName"><el-input v-model="form.resourceName" maxlength="255" /></el-form-item>
        <el-form-item label="分类" prop="category"><el-input v-model="form.category" maxlength="255" /></el-form-item>
        <el-form-item label="DMN XML" prop="dmnXml">
          <el-input v-model="form.dmnXml" type="textarea" :rows="16" resize="vertical" maxlength="2097152" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">部署</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="WorkflowDmn">
import { deployDmnDecision, listDmnDecisions, removeDmnDeployment } from '@/api/workflow/dmn'

const { proxy } = getCurrentInstance()
const loading = ref(false)
const saving = ref(false)
const rows = ref([])
const dialogOpen = ref(false)
const formRef = ref(null)
const uploadRef = ref(null)
const form = reactive(emptyForm())
const decisionKeyCount = computed(() => new Set(rows.value.map(row => row.decisionKey)).size)
const rules = {
  resourceName: [
    { required: true, message: '资源名不能为空', trigger: 'blur' },
    { pattern: /^.{1,251}\.dmn$/i, message: '资源名必须以 .dmn 结尾', trigger: 'blur' }
  ],
  category: [{ max: 255, message: '分类不能超过 255 个字符', trigger: 'blur' }],
  dmnXml: [{ required: true, message: 'DMN XML 不能为空', trigger: 'blur' }]
}
let pageInitialized = false

/**
 * 创建不含上一轮文件正文的部署表单。
 * @returns {{resourceName:string, category:string, dmnXml:string}} 可直接编辑的表单模型。
 */
function emptyForm() {
  return { resourceName: '', category: '', dmnXml: '' }
}

/**
 * 从真实后端刷新全部 DMN 来源版本。
 * @returns {Promise<void>} 请求结束后更新正式目录表格。
 */
async function loadRows() {
  loading.value = true
  try {
    const response = await listDmnDecisions()
    rows.value = Array.isArray(response.data) ? response.data : []
  } finally {
    loading.value = false
  }
}

/**
 * 打开部署窗口并清空上一轮 XML 和文件选择。
 * @returns {void} 对话框打开后重置校验状态。
 */
function openDeploy() {
  Object.assign(form, emptyForm())
  uploadRef.value?.clearFiles()
  dialogOpen.value = true
  nextTick(() => formRef.value?.clearValidate())
}

/**
 * 读取用户选择的 DMN 文件，大小超限时在客户端提前拒绝。
 * @param {object} uploadFile Element Plus UploadFile，包含文件名和原始 File。
 * @returns {Promise<void>} 成功后回填资源名和 XML 正文。
 */
async function readFile(uploadFile) {
  const file = uploadFile?.raw
  if (!file) return
  if (file.size <= 0 || file.size > 2 * 1024 * 1024) {
    proxy.$modal.msgError('DMN 文件大小必须在 2 MiB 以内')
    uploadRef.value?.clearFiles()
    return
  }
  form.resourceName = file.name.toLowerCase().endsWith('.dmn') ? file.name : `${file.name}.dmn`
  form.dmnXml = await file.text()
  nextTick(() => formRef.value?.validateField(['resourceName', 'dmnXml']).catch(() => undefined))
}

/**
 * 清除已选择文件对应的正文，避免页面显示无文件但仍可提交旧 XML。
 * @returns {void} 同步清空资源名和 XML。
 */
function clearFile() {
  form.resourceName = ''
  form.dmnXml = ''
}

/**
 * 把当前 DMN 正文交给服务端安全校验并创建官方不可回退部署。
 * @returns {Promise<void>} 部署成功后关闭窗口并刷新正式目录。
 */
async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await deployDmnDecision({
      resourceName: form.resourceName.trim(),
      category: form.category.trim(),
      dmnXml: form.dmnXml
    })
    proxy.$modal.msgSuccess('DMN 决策部署成功')
    dialogOpen.value = false
    await loadRows()
  } finally {
    saving.value = false
  }
}

/**
 * 删除当前决策所属的来源部署；被流程冻结引用时由后端以 409 拒绝且零副作用。
 * @param {object} row 当前官方 DMN 决策版本行。
 * @returns {Promise<void>} 删除成功后刷新目录。
 */
async function removeRow(row) {
  await proxy.$modal.confirm(`确认删除“${row.decisionName || row.decisionKey}” v${row.version} 所在部署吗？`)
  await removeDmnDeployment(row.deploymentId)
  proxy.$modal.msgSuccess('DMN 部署已删除')
  await loadRows()
}

onMounted(async () => {
  await loadRows()
  pageInitialized = true
})
onActivated(() => { if (pageInitialized) loadRows() })
</script>

<style scoped>
.dmn-page__heading { display: flex; align-items: flex-end; justify-content: space-between; min-height: 68px; margin: -4px 0 20px; padding-bottom: 14px; border-bottom: 1px solid var(--el-border-color-light); }
.dmn-page__heading h2 { margin: 0; font-size: 22px; letter-spacing: 0; }
.dmn-page__heading p { margin: 5px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.dmn-page__summary { display: flex; gap: 24px; color: var(--el-text-color-secondary); font-size: 13px; }
.dmn-page__summary strong { margin-right: 4px; color: var(--el-text-color-primary); font-family: Consolas, monospace; font-size: 18px; }
.dmn-page__identity { display: grid; gap: 4px; }
.dmn-page__identity code { color: var(--el-text-color-secondary); font-family: Consolas, monospace; font-size: 12px; }
</style>
