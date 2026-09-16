import { createApp, h, reactive, ref } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as icons from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import ProcessFormRenderer from '../../../src/components/workflow/ProcessFormRenderer.vue'

// 仅宿主值与附件 HTTP 是夹具；正式表单、递归字段和 Element Plus 控件均真实挂载。
window.mountFormRegression = ({ content, values = {} }) => {
  const state = reactive({ content, values, readonly: false, updates: [], changes: [], errors: [] })
  const form = ref()
  const app = createApp({ render: () => h(ProcessFormRenderer, {
    ref: form, content: state.content, modelValue: state.values, readonly: state.readonly,
    'onUpdate:modelValue': value => { state.updates.push(value); state.values = value },
    onChange: value => state.changes.push(value),
    onError: error => state.errors.push(error.message)
  }) })
  app.use(createPinia())
  app.use(ElementPlus)
  app.config.globalProperties.$modal = { msgError: message => state.errors.push(message) }
  Object.entries(icons).forEach(([name, component]) => app.component(name, component))
  app.mount('#app')
  window.formRegression = state
  window.formControls = form.value
}
