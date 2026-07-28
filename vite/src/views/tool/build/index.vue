<template>
  <div class="container">
    <div class="left-board">
      <div class="logo-wrapper">
        <div class="logo">
          <span class="logo-mark" aria-hidden="true">AP</span> Form Generator
        </div>
      </div>
      <el-scrollbar class="left-scrollbar">
        <div class="components-list">
          <div class="components-title">
            <svg-icon icon-class="component" />输入型组件
          </div>
          <draggable class="components-draggable" :list="inputComponents"
            :group="{ name: 'componentsGroup', pull: 'clone', put: false }" :clone="cloneComponent"
            draggable=".components-item" :sort="false" @end="onEnd" item-key="label">
            <template #item="{ element, index }">
              <div :key="index" class="components-item" @click="addComponent(element)">
                <div class="components-body">
                  <svg-icon :icon-class="element.tagIcon" />
                  {{ element.label }}
                </div>
              </div>
            </template>
          </draggable>
          <div class="components-title">
            <svg-icon icon-class="component" />选择型组件
          </div>
          <draggable class="components-draggable" :list="selectComponents"
            :group="{ name: 'componentsGroup', pull: 'clone', put: false }" :clone="cloneComponent"
            draggable=".components-item" :sort="false" @end="onEnd" item-key="label">
            <template #item="{ element, index }">
              <div :key="index" class="components-item" @click="addComponent(element)">
                <div class="components-body">
                  <svg-icon :icon-class="element.tagIcon" />
                  {{ element.label }}
                </div>
              </div>
            </template>
          </draggable>
          <div class="components-title">
            <svg-icon icon-class="component" /> 布局型组件
          </div>
          <draggable class="components-draggable" :list="layoutComponents"
            :group="{ name: 'componentsGroup', pull: 'clone', put: false }" :clone="cloneComponent"
            draggable=".components-item" :sort="false" @end="onEnd" item-key="label">
            <template #item="{ element, index }">
              <div :key="index" class="components-item" @click="addComponent(element)">
                <div class="components-body">
                  <svg-icon :icon-class="element.tagIcon" />
                  {{ element.label }}
                </div>
              </div>
            </template>
          </draggable>
        </div>
      </el-scrollbar>
    </div>
    <div class="center-board">
      <div class="action-bar">
        <template v-if="workflowMode">
          <el-tooltip content="返回表单列表" placement="bottom">
            <el-button circle text icon="ArrowLeft" aria-label="返回表单列表" @click="backToWorkflowForms" />
          </el-tooltip>
          <el-button icon="View" text @click="openWorkflowPreview">预览</el-button>
          <el-button
            v-if="workflowForm.formId || route.query.formId"
            key="workflow-edit-save"
            v-hasPermi="['workflow:form:edit']"
            icon="Check"
            type="primary"
            :loading="workflowSaving"
            @click="openWorkflowSave"
          >保存</el-button>
          <el-button
            v-else
            key="workflow-add-save"
            v-hasPermi="['workflow:form:add']"
            icon="Check"
            type="primary"
            :loading="workflowSaving"
            @click="openWorkflowSave"
          >保存</el-button>
        </template>
        <el-button v-if="!workflowMode" icon="Download" type="primary" text @click="download">
          导出vue文件
        </el-button>
        <el-button v-if="!workflowMode" class="copy-btn-main" icon="DocumentCopy" type="primary" text @click="copy">
          复制代码
        </el-button>
        <el-button class="delete-btn" icon="Delete" text @click="empty" type="danger">
          清空
        </el-button>
      </div>
      <el-scrollbar class="center-scrollbar">
        <el-row class="center-board-row" :gutter="formConf.gutter">
          <el-form :size="formConf.size" :label-position="formConf.labelPosition" :disabled="formConf.disabled"
            :label-width="formConf.labelWidth + 'px'">
            <draggable class="drawing-board" :list="drawingList" :animation="340" group="componentsGroup"
              item-key="label">
              <template #item="{ element, index }">
                <draggable-item :key="element.renderKey" :drawing-list="drawingList" :element="element" :index="index"
                  :active-id="activeId" :form-conf="formConf" @activeItem="activeFormItem" @copyItem="drawingItemCopy"
                  @deleteItem="drawingItemDelete" />
              </template>
            </draggable>
            <div v-show="!drawingList.length" class="empty-info">
              从左侧拖入或点选组件进行表单设计
            </div>
          </el-form>
        </el-row>
      </el-scrollbar>
    </div>
    <right-panel :active-data="activeData" :form-conf="formConf" :show-field="!!drawingList.length"
      @tag-change="tagChange" />

    <code-type-dialog v-model="dialogVisible" title="选择生成类型" :showFileName="showFileName" @confirm="generate" />
    <input id="copyNode" type="hidden">

    <el-dialog v-model="workflowSaveOpen" :title="workflowForm.formId ? '保存流程表单' : '新增流程表单'" width="520px" append-to-body>
      <el-form ref="workflowFormRef" :model="workflowForm" :rules="workflowRules" label-width="88px">
        <el-form-item label="表单名称" prop="formName">
          <el-input v-model="workflowForm.formName" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="workflowForm.remark" type="textarea" :rows="3" maxlength="255" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="workflowSaveOpen = false">取消</el-button>
        <el-button
          v-if="workflowForm.formId"
          key="workflow-edit-confirm"
          v-hasPermi="['workflow:form:edit']"
          type="primary"
          :loading="workflowSaving"
          @click="saveWorkflowForm"
        >保存</el-button>
        <el-button
          v-else
          key="workflow-add-confirm"
          v-hasPermi="['workflow:form:add']"
          type="primary"
          :loading="workflowSaving"
          @click="saveWorkflowForm"
        >保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="workflowPreviewOpen" title="表单预览" width="760px" append-to-body>
      <ProcessFormRenderer v-if="workflowPreviewContent" :content="workflowPreviewContent" readonly />
    </el-dialog>
  </div>
</template>

<script setup>
import draggable from "vuedraggable/dist/vuedraggable.common"
import ClipboardJS from 'clipboard'
import prettier from 'prettier/standalone'
import htmlParser from 'prettier/parser-html'
import { inputComponents, selectComponents, layoutComponents, formConf as formConfData } from '@/utils/generator/config'
import { drawingDefaultValue, initDrawingDefaultValue, cleanDrawingDefaultValue } from '@/utils/generator/drawingDefault'
import { makeUpHtml, vueTemplate, vueScript, cssStyle } from '@/utils/generator/html'
import { makeUpJs } from '@/utils/generator/js'
import { makeUpCss } from '@/utils/generator/css'
import Download from '@/plugins/download'
import { ElNotification } from 'element-plus'
import DraggableItem from './DraggableItem'
import RightPanel from './RightPanel'
import CodeTypeDialog from './CodeTypeDialog'
import { onMounted, watch } from 'vue'
import { createForm as createWorkflowForm, getForm as getWorkflowForm, updateForm as updateWorkflowForm } from '@/api/workflow/form'
import ProcessFormRenderer from '@/components/workflow/ProcessFormRenderer.vue'
import { builderTemplateToLegacy, legacyTemplateToBuilder } from '@/components/workflow/form/formTemplate'

initDrawingDefaultValue()

const drawingList = ref(drawingDefaultValue)
const { proxy } = getCurrentInstance()
const dialogVisible = ref(false)
const showFileName = ref(false)
const operationType = ref('')
const idGlobal = ref(100)
const activeData = ref(drawingDefaultValue[0])
const activeId = ref(drawingDefaultValue[0].formId)
const generateConf = ref(null)
const formData = ref({})
const formConf = ref(formConfData)
const route = useRoute()
const router = useRouter()
const workflowMode = computed(() => route.query.workflow === '1')
const workflowSaveOpen = ref(false)
const workflowPreviewOpen = ref(false)
const workflowPreviewContent = ref('')
const workflowSaving = ref(false)
const workflowFormRef = ref(null)
const workflowForm = reactive({ formId: undefined, formName: '', remark: '' })
const workflowRules = {
  formName: [{ required: true, message: '表单名称不能为空', trigger: 'blur' }]
}
let oldActiveId
let tempActiveData

function activeFormItem(element) {
  activeData.value = element
  activeId.value = element.formId
}
function copy() {
  dialogVisible.value = true
  showFileName.value = false
  operationType.value = 'copy'
}
function download() {
  dialogVisible.value = true
  showFileName.value = true
  operationType.value = 'download'
}
function empty() {
  proxy.$modal.confirm('确定要清空所有组件吗？', '提示', { type: 'warning' }).then(() => {
      idGlobal.value = 100
      drawingList.value = []
      cleanDrawingDefaultValue()
    }
  )
}

function onEnd(obj, a) {
  if (obj.from !== obj.to) {
    activeData.value = tempActiveData
    activeId.value = idGlobal.value
  }
}

function addComponent(item) {
  const clone = cloneComponent(item)
  drawingList.value.push(clone)
  activeFormItem(clone)
}

function cloneComponent(origin) {
  const clone = JSON.parse(JSON.stringify(origin))
  clone.formId = ++idGlobal.value
  clone.span = formConf.value.span
  clone.renderKey = +new Date() // 改变renderKey后可以实现强制更新组件
  if (!clone.layout) clone.layout = 'colFormItem'
  if (clone.layout === 'colFormItem') {
    clone.vModel = `field${idGlobal.value}`
    clone.placeholder !== undefined && (clone.placeholder += clone.label)
    tempActiveData = clone
  } else if (clone.layout === 'rowFormItem') {
    delete clone.label
    clone.componentName = `row${idGlobal.value}`
    clone.gutter = formConf.value.gutter
    tempActiveData = clone
  }
  return tempActiveData
}

function drawingItemCopy(item, parent) {
  let clone = JSON.parse(JSON.stringify(item))
  clone = createIdAndKey(clone)
  parent.push(clone)
  activeFormItem(clone)
}


function createIdAndKey(item) {
  item.formId = ++idGlobal.value
  item.renderKey = +new Date()
  if (item.layout === 'colFormItem') {
    item.vModel = `field${idGlobal.value}`
  } else if (item.layout === 'rowFormItem') {
    item.componentName = `row${idGlobal.value}`
  }
  if (Array.isArray(item.children)) {
    item.children = item.children.map(childItem => createIdAndKey(childItem))
  }
  return item
}

function drawingItemDelete(index, parent) {
  parent.splice(index, 1)
  nextTick(() => {
    const len = drawingList.value.length
    if (len) {
      activeFormItem(drawingList.value[len - 1])
    }
  })
}

function tagChange(newTag) {
  newTag = cloneComponent(newTag)
  newTag.vModel = activeData.value.vModel
  newTag.formId = activeId.value
  newTag.span = activeData.value.span
  delete activeData.value.tag
  delete activeData.value.tagIcon
  delete activeData.value.document
  Object.keys(newTag).forEach(key => {
    if (activeData.value[key] !== undefined
      && typeof activeData.value[key] === typeof newTag[key]) {
      newTag[key] = activeData.value[key]
    }
  })
  activeData.value = newTag
  updateDrawingList(newTag, drawingList.value)
}


function updateDrawingList(newTag, list) {
  const index = list.findIndex(item => item.formId === activeId.value)
  if (index > -1) {
    list.splice(index, 1, newTag)
  } else {
    list.forEach(item => {
      if (Array.isArray(item.children)) updateDrawingList(newTag, item.children)
    })
  }
}
function generate(data) {
  generateConf.value = data
  nextTick(() => {
    switch (operationType.value) {
      case 'copy':
        execCopy(data)
        break
      case 'download':
        execDownload(data)
        break
      default:
        break
    }
  })
}

function execDownload(data) {
  const codeStr = generateCode()
  const blob = new Blob([codeStr], { type: 'text/plain;charset=utf-8' })
  Download.saveAs(blob, data.fileName)
}

function execCopy(data) {
  document.getElementById('copyNode').click()
}
function AssembleFormData() {
  formData.value = { fields: JSON.parse(JSON.stringify(drawingList.value)), ...formConf.value }
}
/**
 * 将当前表单设计结果组装并格式化为可下载的 Vue 单文件组件源码。
 * @returns {string} 使用 Vue 解析器格式化后的完整 SFC 源码。
 */
function generateCode() {
  const { type } = generateConf.value
  AssembleFormData()
  const script = vueScript(makeUpJs(formData.value, type))
  const html = vueTemplate(makeUpHtml(formData.value, type))
  const css = cssStyle(makeUpCss(formData.value))
  return prettier.format(html + script + css, {
    parser: 'vue',
    plugins: [htmlParser],
    printWidth: 110,
    tabWidth: 2,
    useTabs: false
  })
}

/**
 * 将当前生成器状态转换为后端正式旧版表单 JSON。
 * @returns {string} 使用 __config__/__vModel__ 的 JSON 正文。
 */
function serializeWorkflowTemplate() {
  AssembleFormData()
  validateWorkflowFields(drawingList.value)
  return JSON.stringify(builderTemplateToLegacy(formData.value))
}

/**
 * 递归校验流程变量名格式和唯一性，避免设计阶段产生不可部署模板。
 * @param {object[]} fields 当前生成器字段树。
 * @param {Set<string>} variables 全表单共享的已用变量集合。
 * @returns {void} 发现非法或重复变量时抛出错误。
 */
function validateWorkflowFields(fields, variables = new Set()) {
  fields.forEach(field => {
    if (field.vModel !== undefined) {
      const variable = String(field.vModel || '').trim()
      if (!/^[A-Za-z_][A-Za-z0-9_]{0,127}$/.test(variable)) {
        throw new Error(`字段“${field.label || '未命名'}”的变量名不合法`)
      }
      if (variables.has(variable)) throw new Error(`流程表单变量名重复: ${variable}`)
      variables.add(variable)
    }
    if (Array.isArray(field.children)) validateWorkflowFields(field.children, variables)
  })
}

/**
 * 加载正式流程表单并转换为当前拖拽生成器结构。
 * @param {string|number} formId 流程表单主键；为空时初始化空设计。
 * @returns {Promise<void>} 加载完成后更新表单元数据和画布。
 */
async function loadWorkflowForm(formId) {
  drawingList.value = []
  activeData.value = {}
  activeId.value = undefined
  if (!formId) return
  const response = await getWorkflowForm(formId)
  const builderTemplate = legacyTemplateToBuilder(response.data.content)
  const { fields, ...settings } = builderTemplate
  formConf.value = { ...formConfData, ...settings }
  drawingList.value = fields
  Object.assign(workflowForm, {
    formId: response.data.formId,
    formName: response.data.formName,
    remark: response.data.remark || ''
  })
  idGlobal.value = Math.max(100, findMaximumFormId(fields))
  if (fields.length) activeFormItem(fields[0])
}

/**
 * 递归查找已加载模板的最大生成器字段主键。
 * @param {object[]} fields 生成器字段树。
 * @returns {number} 最大 formId，不存在时为 0。
 */
function findMaximumFormId(fields) {
  return fields.reduce((maximum, field) => {
    const childMaximum = Array.isArray(field.children) ? findMaximumFormId(field.children) : 0
    return Math.max(maximum, Number(field.formId) || 0, childMaximum)
  }, 0)
}

/**
 * 打开真实保存对话框并先执行模板结构校验。
 * @returns {void} 校验失败时显示错误且不打开对话框。
 */
function openWorkflowSave() {
  try {
    serializeWorkflowTemplate()
    workflowSaveOpen.value = true
  } catch (error) {
    proxy.$modal.msgError(error.message)
  }
}

/**
 * 调用流程表单新增或修改接口并使用后端返回的真实主键更新路由。
 * @returns {Promise<void>} 保存完成后保留当前设计器状态。
 */
async function saveWorkflowForm() {
  const valid = await workflowFormRef.value.validate().catch(() => false)
  if (!valid) return
  workflowSaving.value = true
  try {
    const payload = {
      formName: workflowForm.formName.trim(),
      remark: workflowForm.remark?.trim() || undefined,
      content: serializeWorkflowTemplate()
    }
    if (workflowForm.formId) {
      await updateWorkflowForm({ ...payload, formId: workflowForm.formId })
    } else {
      const response = await createWorkflowForm(payload)
      workflowForm.formId = response.data.formId
      await router.replace({
        path: route.path,
        query: { ...route.query, formId: response.data.formId }
      })
    }
    workflowSaveOpen.value = false
    proxy.$modal.msgSuccess('流程表单保存成功')
  } finally {
    workflowSaving.value = false
  }
}

/**
 * 使用正式序列化结果打开运行时表单预览。
 * @returns {void} 模板非法时显示错误且不打开预览。
 */
function openWorkflowPreview() {
  try {
    workflowPreviewContent.value = serializeWorkflowTemplate()
    workflowPreviewOpen.value = true
  } catch (error) {
    proxy.$modal.msgError(error.message)
  }
}

/**
 * 返回流程表单管理列表。
 * @returns {void} 无返回值。
 */
function backToWorkflowForms() {
  router.push('/workflow/form')
}
watch(() => activeData.value.label, (val, oldVal) => {
  if (
    activeData.value.placeholder === undefined
    || !activeData.value.tag
    || oldActiveId !== activeId.value
  ) {
    return
  }
  activeData.value.placeholder = activeData.value.placeholder.replace(oldVal, '') + val
})
watch(activeId, (val) => {
  oldActiveId = val
}, { immediate: true })

let clipboard = null
onMounted(() => {
  clipboard = new ClipboardJS('#copyNode', {
    text: trigger => {
      const codeStr = generateCode()
      ElNotification({ title: '成功', message: '代码已复制到剪切板，可粘贴。', type: 'success' })
      return codeStr
    }
  })
  clipboard.on('error', e => {
    proxy.$modal.msgError('代码复制失败')
  })
  if (workflowMode.value) {
    loadWorkflowForm(route.query.formId).catch(error => proxy.$modal.msgError(error.message || '流程表单加载失败'))
  }
})
onUnmounted(() => {
  clipboard.destroy()
})
</script>

<style lang='scss'>
$lighterBlue: #409EFF;

.container {
  position: relative;
  width: 100%;
  background-color: var(--el-bg-color-overlay);
  height: calc(100vh - 50px - 40px);
  overflow: hidden;

  .left-board {
    width: 260px;
    position: absolute;
    left: 0;
    top: 0;
    height: calc(100vh - 50px - 40px);

    .logo-wrapper {
      position: relative;
      height: 42px;
      border-bottom: 1px solid var(--el-border-color-extra-light);
      box-sizing: border-box;

  .logo {
        position: absolute;
        left: 12px;
        top: 6px;
        line-height: 30px;
        color: #00afff;
        font-weight: 600;
        font-size: 17px;
        white-space: nowrap;

        >img {
          width: 30px;
          height: 30px;
          vertical-align: top;
        }

        .github {
          display: inline-block;
          vertical-align: sub;
          margin-left: 15px;

          >img {
            height: 22px;
          }
        }
      }
    }

    .left-scrollbar {
      .el-scrollbar__wrap {
        box-sizing: border-box;
        overflow-x: hidden !important;
        margin-bottom: 0 !important;

        .components-list {
          padding: 8px;
          box-sizing: border-box;
          height: 100%;

          .components-title {
            font-size: 14px;
            // color: #222;
            margin: 6px 2px;

            .svg-icon {
              // color: #666;
              font-size: 18px;
              margin-right: 5px;
            }
          }

          .components-draggable {
            padding-bottom: 20px;

            .components-item {
              display: inline-block;
              width: 48%;
              margin: 1%;
              transition: transform 0ms !important;

              .components-body {
                padding: 8px 10px;
                background: var(--el-border-color-extra-light);
                font-size: 12px;
                cursor: move;
                border: 1px dashed var(--el-border-color-extra-light);
                border-radius: 3px;

                .svg-icon {
                  // color: #777;
                  font-size: 15px;
                  margin-right: 5px;
                }

                &:hover {
                  border: 1px dashed #787be8;
                  color: #787be8;

                  .svg-icon {
                    color: #787be8;
                  }
                }
              }
            }
          }


        }
      }
    }
  }

  .logo-mark {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    margin-right: 6px;
    color: #173b32;
    font-size: 10px;
    font-weight: 700;
    background: #b9e4d7;
    border-radius: 4px;
  }

  .center-board {
    height: calc(100vh - 50px - 40px);
    width: auto;
    margin: 0 350px 0 260px;
    box-sizing: border-box;

    .action-bar {
      position: relative;
      height: 42px;
      padding: 0 15px;
      box-sizing: border-box;
      ;
      border: 1px solid var(--el-border-color-extra-light);
      border-top: none;
      border-left: none;
      display: flex;
      align-items: center;
      justify-content: flex-end;

      u .delete-btn {
        color: #F56C6C;
      }
    }

    .center-scrollbar {
      height: calc(100vh - 50px - 40px - 42px);
      overflow: hidden;
      border-left: 1px solid var(--el-border-color-extra-light);
      border-right: 1px solid var(--el-border-color-extra-light);
      box-sizing: border-box;

      .el-scrollbar__view {
        overflow-x: hidden;
      }

      .center-board-row {
        padding: 12px 12px 15px 12px;
        box-sizing: border-box;

        &>.el-form {
          // 69 = 12+15+42
          height: calc(100vh - 50px - 40px - 69px);
          flex: 1;

          .drawing-board {
            height: 100%;
            position: relative;

            .components-body {
              padding: 0;
              margin: 0;
              font-size: 0;
            }

            .sortable-ghost {
              position: relative;
              display: block;
              overflow: hidden;

              &::before {
                content: " ";
                position: absolute;
                left: 0;
                right: 0;
                top: 0;
                height: 3px;
                background: rgb(89, 89, 223);
                z-index: 2;
              }
            }

            .components-item.sortable-ghost {
              width: 100%;
              height: 60px;
              background: var(--el-border-color-extra-light);
            }

            .active-from-item {
              &>.el-form-item {
                background: var(--el-border-color-extra-light);
                border-radius: 6px;
              }

              &>.drawing-item-copy,
              &>.drawing-item-delete {
                display: initial;
              }

              &>.component-name {
                color: $lighterBlue;
              }

              .el-input__wrapper {
                box-shadow: 0 0 0 1px var(--el-input-hover-border-color) inset;
              }
            }

            .el-form-item {
              margin-bottom: 15px;
            }
          }

          .drawing-item {
            position: relative;
            cursor: move;

            &.unfocus-bordered:not(.activeFromItem)>div:first-child {
              border: 1px dashed #ccc;
            }

            .el-form-item {
              padding: 12px 10px;
            }
          }

          .drawing-row-item {
            position: relative;
            cursor: move;
            box-sizing: border-box;
            border: 1px dashed #ccc;
            border-radius: 3px;
            padding: 0 2px;
            margin-bottom: 15px;

            .drawing-row-item {
              margin-bottom: 2px;
            }

            .el-col {
              margin-top: 22px;
            }

            .el-form-item {
              margin-bottom: 0;
            }

            .drag-wrapper {
              min-height: 80px;
              flex: 1;
              display: flex;
              flex-wrap: wrap;
            }

            &.active-from-item {
              border: 1px dashed $lighterBlue;
            }

            .component-name {
              position: absolute;
              top: 0;
              left: 0;
              font-size: 12px;
              color: #bbb;
              display: inline-block;
              padding: 0 6px;
            }
          }

          .drawing-item,
          .drawing-row-item {
            &:hover {
              &>.el-form-item {
                background: var(--el-border-color-extra-light);
                border-radius: 6px;
              }

              &>.drawing-item-copy,
              &>.drawing-item-delete {
                display: initial;
              }
            }

            &>.drawing-item-copy,
            &>.drawing-item-delete {
              display: none;
              position: absolute;
              top: -10px;
              width: 22px;
              height: 22px;
              line-height: 22px;
              text-align: center;
              border-radius: 50%;
              font-size: 12px;
              border: 1px solid;
              cursor: pointer;
              z-index: 1;
            }

            &>.drawing-item-copy {
              right: 56px;
              border-color: $lighterBlue;
              color: $lighterBlue;
              background: #fff;

              &:hover {
                background: $lighterBlue;
                color: #fff;
              }
            }

            &>.drawing-item-delete {
              right: 24px;
              border-color: #F56C6C;
              color: #F56C6C;
              background: #fff;

              &:hover {
                background: #F56C6C;
                color: #fff;
              }
            }
          }

          .empty-info {
            position: absolute;
            top: 46%;
            left: 0;
            right: 0;
            text-align: center;
            font-size: 18px;
            color: #ccb1ea;
            letter-spacing: 4px;
          }

        }
      }
    }
  }
}
</style>
