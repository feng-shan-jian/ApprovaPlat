<template>
  <div v-if="disabled" class="workflow-attachment-upload workflow-attachment-upload--readonly">
    <el-button type="primary" plain icon="Upload" disabled>上传附件</el-button>
    <ul v-if="fileList.length" class="workflow-attachment-upload__readonly-list" aria-label="已绑定附件">
      <li v-for="file in fileList" :key="file.uid">
        <el-button
          link
          type="primary"
          icon="Document"
          :aria-label="`下载附件 ${file.name}`"
          @click="previewFile(file)"
        >
          <span :title="file.name">{{ file.name }}</span>
        </el-button>
      </li>
    </ul>
  </div>
  <el-upload
    v-else
    class="workflow-attachment-upload"
    :file-list="fileList"
    :accept="accept"
    :limit="limit"
    :http-request="uploadFile"
    :before-upload="beforeUpload"
    :before-remove="beforeRemove"
    :on-preview="previewFile"
    :on-exceed="handleExceed"
  >
    <el-button type="primary" plain icon="Upload">上传附件</el-button>
  </el-upload>
</template>

<script setup name="WorkflowAttachmentUpload">
import Download from '@/plugins/download'
import { blobValidate } from '@/utils/ruoyi'
import {
  deleteWorkflowAttachment,
  downloadWorkflowAttachment,
  uploadWorkflowAttachment
} from '@/api/workflow/attachment'

const props = defineProps({
  /** 表单变量名，服务端使用该字段校验附件归属。 */
  fieldName: { type: String, required: true },
  /** 附件安全元数据数组，不能包含本地路径或静态 URL。 */
  modelValue: { type: Array, default: () => [] },
  /** 是否禁止新增和移除；已绑定附件仍可通过对象授权下载。 */
  disabled: { type: Boolean, default: false },
  /** 单字段最大附件数量。 */
  limit: { type: Number, default: 10 },
  /** 浏览器文件选择过滤规则，服务端仍执行最终 MIME 和大小校验。 */
  accept: { type: String, default: '' },
  /** 客户端即时大小门禁，默认与服务端默认 50 MiB 一致。 */
  maxSizeMb: { type: Number, default: 50 }
})

const emit = defineEmits(['update:modelValue', 'busy-change', 'error'])
const { proxy } = getCurrentInstance()
// 当前未完成写请求数量，父表单据此阻止提交时附件状态漂移。
const pendingMutations = ref(0)
// 上传、临时删除和已绑定引用移除共用顺序队列，确保下一项读取到父级刚回写的最新数组。
let mutationQueue = Promise.resolve()
const fileList = computed(() => props.modelValue.map(attachment => ({
  uid: attachment.attachmentId,
  name: attachment.originalName || attachment.name || attachment.attachmentId,
  status: 'success',
  attachment
})))

/**
 * 校验浏览器选中文件的空值和大小。
 * @param {File} file 待上传文件。
 * @returns {boolean} 允许进入真实上传请求时为 true。
 */
function beforeUpload(file) {
  if (!file || file.size <= 0) {
    proxy.$modal.msgError('不能上传空文件')
    return false
  }
  if (file.size > props.maxSizeMb * 1024 * 1024) {
    proxy.$modal.msgError(`附件不能超过 ${props.maxSizeMb} MiB`)
    return false
  }
  return true
}

/**
 * 通过受保护 multipart 接口上传临时附件并回写服务端安全元数据。
 * @param {object} options Element Plus 自定义上传参数。
 * @returns {Promise<void>} 请求完成后调用组件成功或失败回调。
 */
async function uploadFile(options) {
  setPendingMutations(pendingMutations.value + 1)
  return enqueueMutation(async () => {
    try {
      const response = await uploadWorkflowAttachment(props.fieldName, options.file)
      const attachment = response.data
      emit('update:modelValue', [...props.modelValue, attachment])
      // 等待父级 v-model 回写后再处理下一项，避免并发完成时基于旧数组相互覆盖。
      await nextTick()
      options.onSuccess(attachment)
    } catch (error) {
      options.onError(error)
      emit('error', error)
    } finally {
      setPendingMutations(pendingMutations.value - 1)
    }
  })
}

/**
 * 移除列表项前按附件状态执行不同语义：DRAFT/BOUND 仅移除当前表单引用，TEMP 先删除临时文件。
 * @param {object} file Element Plus 文件项。
 * @returns {Promise<boolean>} 当前表单引用已安全移除时返回 true，否则返回 false。
 */
async function beforeRemove(file) {
  const attachment = file.attachment || props.modelValue.find(item => item.attachmentId === file.uid)
  if (!attachment || props.disabled) return false
  if (['DRAFT', 'BOUND'].includes(attachment.status)) {
    return enqueueMutation(async () => {
      // DRAFT 解绑由下一次草稿 CAS 保存提交，BOUND 属于流程审计；两者都禁止组件直接物理删除。
      emit('update:modelValue', props.modelValue.filter(item => item.attachmentId !== attachment.attachmentId))
      await nextTick()
      return true
    })
  }
  if (attachment.status !== 'TEMP') {
    emit('error', new Error('当前附件状态不允许移除'))
    return false
  }
  setPendingMutations(pendingMutations.value + 1)
  return enqueueMutation(async () => {
    try {
      // TEMP 尚未进入流程审计，必须以后端删除成功作为移除当前引用的前置条件。
      await deleteWorkflowAttachment(attachment.attachmentId)
      emit('update:modelValue', props.modelValue.filter(item => item.attachmentId !== attachment.attachmentId))
      await nextTick()
      return true
    } catch (error) {
      emit('error', error)
      return false
    } finally {
      setPendingMutations(pendingMutations.value - 1)
    }
  })
}

/**
 * 下载经过服务端对象授权的私有附件。
 * @param {object} file Element Plus 文件项。
 * @returns {Promise<void>} 下载失败时触发 error。
 */
async function previewFile(file) {
  const attachment = file.attachment || props.modelValue.find(item => item.attachmentId === file.uid)
  if (!attachment) return
  try {
    const blob = await downloadWorkflowAttachment(attachment.attachmentId)
    if (!blobValidate(blob)) {
      // 业务异常也可能以 HTTP 200 JSON Blob 返回，必须提示错误并禁止保存成同名假附件。
      await Download.printErrMsg(blob)
      return
    }
    Download.saveAs(blob, attachment.originalName || 'attachment')
  } catch (error) {
    emit('error', error)
  }
}

/**
 * 将附件变更加入顺序队列，保证每次都基于父级已确认的最新 v-model 修改。
 * @param {() => Promise<unknown>} mutation 待执行的上传、临时删除或字段引用移除操作。
 * @returns {Promise<unknown>} 当前写操作的执行结果。
 */
function enqueueMutation(mutation) {
  const queued = mutationQueue.then(mutation, mutation)
  mutationQueue = queued.catch(() => undefined)
  return queued
}

/**
 * 更新附件写操作计数并通知父表单阻止未完成状态提交。
 * @param {number} value 当前上传和删除请求总数。
 * @returns {void} 无返回值。
 */
function setPendingMutations(value) {
  pendingMutations.value = Math.max(0, value)
  emit('busy-change', pendingMutations.value > 0)
}

/**
 * 提示超过模板配置的附件数量上限。
 * @returns {void} 无返回值。
 */
function handleExceed() {
  proxy.$modal.msgWarning(`当前字段最多上传 ${props.limit} 个附件`)
}

defineExpose({ isUploading: () => pendingMutations.value > 0 })
</script>

<style scoped>
.workflow-attachment-upload {
  width: 100%;
}

.workflow-attachment-upload :deep(.el-upload-list__item-name) {
  max-width: min(520px, 70vw);
}

.workflow-attachment-upload__readonly-list {
  padding: 0;
  margin: 10px 0 0;
  list-style: none;
}

.workflow-attachment-upload__readonly-list li + li {
  margin-top: 4px;
}

.workflow-attachment-upload__readonly-list .el-button {
  max-width: min(520px, 70vw);
  justify-content: flex-start;
}

.workflow-attachment-upload__readonly-list .el-button span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
