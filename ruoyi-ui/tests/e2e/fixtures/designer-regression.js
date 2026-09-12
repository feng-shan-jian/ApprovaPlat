import { createApp, h, reactive } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as icons from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import ProcessDesigner from '../../../src/components/workflow/ProcessDesigner.vue'

// Only the host and directory data are fixtures. The component, controls, Modeler,
// command stack, element.changed reload and XML export are the production code.
window.mountDesignerRegression = ({ xml, forms = [] }) => {
  const state = reactive({ xml, errors: [] })
  const app = createApp({
    render: () => h(ProcessDesigner, {
      modelValue: state.xml,
      model: { modelKey: 'designerRegression', modelName: '业务配置回归' },
      forms,
      'onUpdate:modelValue': value => { state.xml = value },
      onError: error => { state.errors.push(error.message) }
    })
  })
  app.use(createPinia())
  app.use(ElementPlus)
  Object.entries(icons).forEach(([name, component]) => app.component(name, component))
  app.mount('#app')
  window.designerRegression = state
}
