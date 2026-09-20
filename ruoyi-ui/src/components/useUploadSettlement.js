import { computed, onBeforeUnmount } from 'vue'

// Element Plus owns the upload list, uid and request status. Keep only the
// batch's loading flag here; successful responses stay on their upload files.
export function useUploadSettlement(fileList, publish, modal, uploader) {
  let uploading = false
  let disposed = false
  const completedFiles = computed(() => fileList.value.filter(file => !file.raw))

  function begin(message) {
    if (!uploading) modal.loading(message)
    uploading = true
  }

  function settle(files) {
    if (disposed || !uploading || files.some(file => file.status === 'ready' || file.status === 'uploading')) return false
    uploading = false
    fileList.value = files.filter(file => !file.raw || (file.status === 'success' && file.response?.code === 200)).map(file => {
      if (!file.raw) return file
      if (file.url?.startsWith('blob:')) URL.revokeObjectURL(file.url)
      return { uid: file.uid, name: file.response.fileName, url: file.response.fileName, status: 'success' }
    })
    publish(fileList.value)
    modal.closeLoading()
    return true
  }

  function removed(file, files) {
    if (disposed) return
    // Deleting an existing value must not serialize blob URLs or clear other
    // in-flight files when the parent writes modelValue back to this component.
    fileList.value = files
    if (!settle(files) && !file.raw) publish(completedFiles.value)
  }

  function changed(_file, files) {
    // onChange runs after Element Plus has added the whole selection, so the
    // component receives the complete queue before reflecting it back.
    if (!disposed && uploading) fileList.value = files
  }

  onBeforeUnmount(() => {
    disposed = true
    uploader.value?.abort()
    if (uploading) modal.closeLoading()
    uploading = false
  })

  return { begin, settle, removed, changed, completedFiles }
}
