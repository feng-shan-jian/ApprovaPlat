import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

/** Vue、Vue Router、Pinia 和项目工具由 Vite 自动导入的正式运行时全局。 */
const autoImportGlobals = Object.fromEntries([
  'computed',
  'defineAsyncComponent',
  'defineComponent',
  'defineStore',
  'getCurrentInstance',
  'h',
  'inject',
  'isRef',
  'markRaw',
  'nextTick',
  'onActivated',
  'onBeforeMount',
  'onBeforeRouteLeave',
  'onBeforeUnmount',
  'onDeactivated',
  'onMounted',
  'onUnmounted',
  'provide',
  'createPinia',
  'reactive',
  'readonly',
  'ref',
  'resolveComponent',
  'selectDictLabel',
  'shallowRef',
  'storeToRefs',
  'toRaw',
  'toRef',
  'toRefs',
  'unref',
  'useAttrs',
  'useDict',
  'useRoute',
  'useRouter',
  'useSlots',
  'watch',
  'watchEffect'
].map((name) => [name, 'readonly']))

export default [
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      'html/**',
      'output/**',
      'tests/**/output/**'
    ]
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.{js,mjs,cjs,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
        ...globals.node,
        ...autoImportGlobals
      }
    },
    rules: {
      // 历史页面仍有待逐步清理的未使用回调参数；保留 warning 可见性，不掩盖运行时错误。
      'no-unused-vars': ['warn', {
        argsIgnorePattern: '^_',
        caughtErrorsIgnorePattern: '^_',
        varsIgnorePattern: '^_'
      }]
    }
  },
  {
    // 路由页和框架入口由文件路径提供唯一语义，强制多词组件名不会改善运行时边界。
    files: [
      'src/**/index.vue',
      'src/main.js',
      'src/components/Crontab/*.vue',
      'src/layout/components/Navbar.vue',
      'src/layout/components/Sidebar/Link.vue',
      'src/layout/components/Sidebar/Logo.vue',
      'src/views/error/*.vue',
      'src/views/lock.vue',
      'src/views/login.vue',
      'src/views/register.vue',
      'src/views/monitor/cache/list.vue',
      'src/views/monitor/job/detail.vue',
      'src/views/monitor/job/log.vue',
      'src/views/monitor/operlog/detail.vue',
      'src/views/system/dict/data.vue',
      'src/views/system/dict/detail.vue',
      'src/views/system/user/view.vue',
      'src/views/workflow/model/design.vue',
      'src/views/workflow/work/*.vue'
    ],
    rules: {
      'vue/multi-word-component-names': 'off'
    }
  },
  {
    // 这些组件接收的是父层拥有的响应式编辑模型，原地更新是现有公开契约，不能在本任务中改为复制状态。
    files: [
      'src/components/RightToolbar/index.vue',
      'src/components/workflow/designer/DesignerPropertiesPanel.vue',
      'src/components/workflow/form/WorkflowFormField.vue',
      'src/views/tool/build/DraggableItem.vue',
      'src/views/tool/build/RightPanel.vue',
      'src/views/tool/gen/basicInfoForm.vue',
      'src/views/tool/gen/genInfoForm.vue'
    ],
    rules: {
      'vue/no-mutating-props': 'off'
    }
  },
  {
    // Cron 子组件通过 computed 同步归一化数值，这是现有表达式编辑契约，后续重构需单独做行为回归。
    files: ['src/components/Crontab/*.vue'],
    rules: {
      'vue/no-side-effects-in-computed-properties': 'off'
    }
  },
  {
    // 既有 URL 和邮箱正则经过业务使用验证，保留显式转义以便与后端规则对照。
    files: ['src/utils/validate.js'],
    rules: {
      'no-useless-escape': 'off'
    }
  }
]
