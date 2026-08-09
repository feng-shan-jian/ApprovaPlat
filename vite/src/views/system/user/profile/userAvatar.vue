<template>
  <div class="user-avatar">
    <button type="button" class="user-avatar__trigger" aria-label="修改头像" @click="editCropper">
      <img :src="options.img" alt="用户头像" @error="handleAvatarError" />
      <span class="user-avatar__overlay" aria-hidden="true">
        <el-icon><CameraFilled /></el-icon>
      </span>
    </button>

    <el-dialog
      v-model="open"
      :title="title"
      width="860px"
      class="avatar-dialog"
      append-to-body
      destroy-on-close
      @opened="modalOpened"
      @close="closeDialog"
    >
      <div class="avatar-editor">
        <section class="avatar-editor__crop" aria-label="头像裁剪区">
          <div class="avatar-editor__canvas">
            <vue-cropper
              v-if="visible"
              ref="cropper"
              :img="options.img"
              :info="true"
              :auto-crop="options.autoCrop"
              :auto-crop-width="options.autoCropWidth"
              :auto-crop-height="options.autoCropHeight"
              :fixed-box="options.fixedBox"
              :output-type="options.outputType"
              @real-time="realTime"
            />
          </div>

          <div class="avatar-editor__tools">
            <el-upload
              action="#"
              accept="image/jpeg,image/png,image/webp"
              :http-request="requestUpload"
              :show-file-list="false"
              :before-upload="beforeUpload"
            >
              <el-button>
                <el-icon><Upload /></el-icon>
                <span>选择图片</span>
              </el-button>
            </el-upload>

            <span class="avatar-editor__tool-group">
              <el-tooltip content="放大" placement="top">
                <el-button aria-label="放大头像" :icon="Plus" @click="changeScale(1)" />
              </el-tooltip>
              <el-tooltip content="缩小" placement="top">
                <el-button aria-label="缩小头像" :icon="Minus" @click="changeScale(-1)" />
              </el-tooltip>
              <el-tooltip content="向左旋转" placement="top">
                <el-button aria-label="向左旋转头像" :icon="RefreshLeft" @click="rotateLeft" />
              </el-tooltip>
              <el-tooltip content="向右旋转" placement="top">
                <el-button aria-label="向右旋转头像" :icon="RefreshRight" @click="rotateRight" />
              </el-tooltip>
            </span>
          </div>
        </section>

        <aside class="avatar-editor__preview" aria-label="头像预览">
          <p>预览</p>
          <div class="avatar-editor__preview-frame">
            <div v-if="options.previews.url" class="avatar-editor__preview-content" :style="previewTransformStyle">
              <img :src="options.previews.url" :style="options.previews.img" alt="裁剪后的头像预览" />
            </div>
            <img v-else class="avatar-editor__preview-fallback" :src="options.img" alt="头像预览" />
          </div>
          <span>头像将以正方形图片保存</span>
        </aside>
      </div>

      <template #footer>
        <el-button :disabled="uploading" @click="open = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="uploadImg">
          <el-icon v-if="!uploading"><Check /></el-icon>
          <span>保存头像</span>
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import "vue-cropper/dist/index.css"
import { VueCropper } from "vue-cropper"
import { Minus, Plus, RefreshLeft, RefreshRight } from "@element-plus/icons-vue"
import { uploadAvatar } from "@/api/system/user"
import useUserStore from "@/store/modules/user"
import defaultAvatar from "@/assets/images/default-avatar.png"

const userStore = useUserStore()
const { proxy } = getCurrentInstance()
const cropper = ref()
const open = ref(false)
const visible = ref(false)
const uploading = ref(false)
const title = ref("修改头像")
// options 保存裁剪器输入、输出规格和实时预览数据，最终上传固定为 320px 方形 PNG。
const options = reactive({
  img: userStore.avatar || defaultAvatar,
  autoCrop: true,
  autoCropWidth: 320,
  autoCropHeight: 320,
  fixedBox: true,
  outputType: "png",
  filename: "avatar.png",
  previews: {}
})
// previewTransformStyle 将裁剪器原始预览等比缩放到 148px 圆框，避免只显示局部区域。
const previewTransformStyle = computed(() => {
  const previewWidth = Number(options.previews.w) || 148
  const previewHeight = Number(options.previews.h) || previewWidth
  const scale = Math.min(148 / previewWidth, 148 / previewHeight)
  return {
    width: `${previewWidth}px`,
    height: `${previewHeight}px`,
    transform: `scale(${scale})`,
    transformOrigin: "left top"
  }
})

/**
 * 打开头像裁剪弹窗。
 * @returns {void}
 */
function editCropper() {
  open.value = true
}

/**
 * 在弹窗完成布局后挂载裁剪器，避免隐藏容器导致画布尺寸计算错误。
 * @returns {void}
 */
function modalOpened() {
  visible.value = true
}

/**
 * 覆盖 Element Plus 默认上传行为，图片只在用户确认裁剪后统一提交。
 * @returns {void}
 */
function requestUpload() {}

/**
 * 将当前裁剪图片向左旋转九十度。
 * @returns {void}
 */
function rotateLeft() {
  cropper.value?.rotateLeft()
}

/**
 * 将当前裁剪图片向右旋转九十度。
 * @returns {void}
 */
function rotateRight() {
  cropper.value?.rotateRight()
}

/**
 * 调整裁剪图片的缩放比例。
 * @param {number} amount 正数表示放大，负数表示缩小。
 * @returns {void}
 */
function changeScale(amount) {
  cropper.value?.changeScale(amount || 1)
}

/**
 * 校验本地头像文件并读取为裁剪器可用的 Data URL。
 * @param {File} file 用户选择的原始图片文件。
 * @returns {boolean} 文件是否允许进入裁剪流程。
 */
function beforeUpload(file) {
  const allowedTypes = ["image/jpeg", "image/png", "image/webp"]
  if (!allowedTypes.includes(file.type)) {
    proxy.$modal.msgError("仅支持 JPG、PNG 或 WebP 图片")
    return false
  }
  if (file.size > 10 * 1024 * 1024) {
    proxy.$modal.msgError("头像图片不能超过 10 MB")
    return false
  }

  const reader = new FileReader()
  reader.readAsDataURL(file)
  reader.onload = () => {
    options.img = reader.result
    options.filename = file.name
  }
  reader.onerror = () => proxy.$modal.msgError("图片读取失败，请重新选择")
  return true
}

/**
 * 获取裁剪结果并通过真实头像接口持久化，成功后同步全局头像状态。
 * @returns {void}
 */
function uploadImg() {
  if (!cropper.value || uploading.value) {
    return
  }

  cropper.value.getCropBlob(async (data) => {
    if (!data) {
      proxy.$modal.msgError("头像裁剪失败，请重新选择图片")
      return
    }

    uploading.value = true
    try {
      // formData 的字段名必须与后端 /system/user/profile/avatar 接口保持一致。
      const formData = new FormData()
      formData.append("avatarfile", data, options.filename)
      const response = await uploadAvatar(formData)
      const avatarUrl = import.meta.env.VITE_APP_BASE_API + response.imgUrl
      options.img = avatarUrl
      userStore.avatar = avatarUrl
      proxy.$modal.msgSuccess("头像修改成功")
      open.value = false
    } finally {
      uploading.value = false
    }
  })
}

/**
 * 接收裁剪器实时预览数据并更新右侧预览区域。
 * @param {{ url?: string, img?: Record<string, string> }} data 裁剪器生成的预览地址与样式。
 * @returns {void}
 */
function realTime(data) {
  options.previews = data || {}
}

/**
 * 关闭弹窗时恢复已持久化头像，清除未提交裁剪结果。
 * @returns {void}
 */
function closeDialog() {
  options.img = userStore.avatar || defaultAvatar
  options.previews = {}
  visible.value = false
}

/**
 * 当远程头像失效时回退到项目内默认头像，保证所有入口头像始终可见。
 * @param {Event} event 图片元素触发的加载错误事件。
 * @returns {void}
 */
function handleAvatarError(event) {
  const image = event.target
  if (image instanceof HTMLImageElement && image.src !== defaultAvatar) {
    image.src = defaultAvatar
  }
}

watch(
  () => userStore.avatar,
  (avatar) => {
    // 只有弹窗关闭时才同步外部头像，避免覆盖用户正在裁剪的本地图片。
    if (!open.value) {
      options.img = avatar || defaultAvatar
    }
  }
)
</script>

<style scoped lang="scss">
.user-avatar {
  display: inline-flex;
}

.user-avatar__trigger {
  position: relative;
  display: inline-flex;
  width: 126px;
  height: 126px;
  padding: 5px;
  overflow: hidden;
  cursor: pointer;
  background: rgb(255 255 255 / 7%);
  border: 1px solid rgb(255 255 255 / 22%);
  border-radius: 50%;
  box-shadow: 0 12px 28px rgb(0 0 0 / 24%);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    border-radius: 50%;
  }

  &:focus-visible {
    outline: 2px solid #8dd3c2;
    outline-offset: 4px;
  }
}

.user-avatar__overlay {
  position: absolute;
  inset: 5px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  font-size: 25px;
  background: rgb(16 27 22 / 54%);
  border-radius: 50%;
  opacity: 0;
  transition: opacity 180ms ease;
}

.user-avatar__trigger:hover .user-avatar__overlay,
.user-avatar__trigger:focus-visible .user-avatar__overlay {
  opacity: 1;
}

:global(.avatar-dialog) {
  max-width: calc(100vw - 32px);
}

:global(.avatar-dialog .el-dialog__body) {
  padding: 24px;
}

.avatar-editor {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(210px, 0.72fr);
  gap: 22px;
}

.avatar-editor__crop {
  min-width: 0;
}

.avatar-editor__canvas {
  height: 350px;
  overflow: hidden;
  background: #101713;
  border: 1px solid var(--app-border-strong);
  border-radius: 6px;
}

.avatar-editor__tools {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}

.avatar-editor__tool-group {
  display: inline-flex;
  gap: 6px;

  .el-button {
    width: 34px;
    min-width: 34px;
    padding: 0;
    margin-left: 0;
  }
}

.avatar-editor__preview {
  display: flex;
  align-items: center;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
  padding: 24px 18px;
  background: var(--app-surface-soft);
  border: 1px solid var(--app-border);
  border-radius: 6px;

  p {
    margin: 0 0 18px;
    color: var(--app-text);
    font-size: 14px;
    font-weight: 680;
  }

  span {
    margin-top: 16px;
    color: var(--app-text-muted);
    font-size: 12px;
    line-height: 1.5;
    text-align: center;
  }
}

.avatar-editor__preview-frame {
  width: 148px;
  height: 148px;
  overflow: hidden;
  background: var(--app-surface);
  border: 5px solid var(--app-surface);
  border-radius: 50%;
  box-shadow: 0 0 0 1px var(--app-border-strong), var(--app-shadow-md);

}

.avatar-editor__preview-content,
.avatar-editor__preview-content img {
  display: block;
  max-width: none;
}

.avatar-editor__preview-fallback {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

@media (max-width: 680px) {
  :global(.avatar-dialog .el-dialog__body) {
    padding: 18px;
  }

  .avatar-editor {
    grid-template-columns: minmax(0, 1fr);
  }

  .avatar-editor__canvas {
    height: 300px;
  }

  .avatar-editor__preview {
    min-height: 220px;
  }

  .avatar-editor__tools {
    align-items: stretch;
    flex-direction: column;
  }

  .avatar-editor__tool-group {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));

    .el-button {
      width: 100%;
    }
  }
}
</style>
