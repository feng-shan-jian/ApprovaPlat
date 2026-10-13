import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import { fileURLToPath } from 'node:url'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve, sep } from 'node:path'

// Only the host and transport timing vary. SFC compilation, imports and DOM
// events use production Vue, Element Plus and the application's modules.
export async function createComponentHost(harness, middleware = (_req, _res, next) => next(), aliases = {}) {
  const root = fileURLToPath(new URL('../../..', import.meta.url))
  const cacheDir = await mkdtemp(join(tmpdir(), 'workflow-component-'))
  if (!resolve(cacheDir).startsWith(resolve(tmpdir()) + sep)) throw new Error('Unexpected component cache path')
  const server = await createServer({
    root, cacheDir, configFile: false, logLevel: 'error',
    optimizeDeps: { noDiscovery: true, include: ['vue', 'vue-router', 'pinia', 'element-plus', 'sortablejs', 'js-cookie', 'axios', 'file-saver', 'jsencrypt', 'nprogress', 'fuse.js', 'clipboard', '@element-plus/icons-vue',
      'bpmn-js/lib/Modeler', 'diagram-js-minimap', 'bpmn-js/lib/features/grid-snapping', 'bpmn-js-token-simulation'] },
    resolve: { alias: { ...aliases, '@': `${root}/src` }, extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue'] },
    define: { 'import.meta.env.VITE_APP_BASE_API': JSON.stringify('/test-api') },
    plugins: [vue(), AutoImport({ imports: ['vue', 'vue-router', 'pinia', {
      '@/utils/dict': ['useDict'], '@/utils/ruoyi': ['selectDictLabel']
    }], dts: false }), {
      name: 'production-component-host',
      resolveId(id) { if (id === '/component-harness.js') return '\0component-harness' },
      load(id) { if (id === '\0component-harness') return harness },
      configureServer(vite) {
        vite.middlewares.use((req, res, next) => {
          if (req.url?.startsWith('/component-harness?')) {
            res.setHeader('Content-Type', 'text/html')
            res.end('<!doctype html><html><body><div id="app"></div><script type="module" src="/component-harness.js"></script></body></html>')
          } else middleware(req, res, next)
        })
      }
    }], server: { host: '127.0.0.1', port: 0 }
  })
  try {
    await server.listen()
    return { url: `http://127.0.0.1:${server.httpServer.address().port}`,
      async close() { await server.close(); await rm(cacheDir, { recursive: true, force: true }) } }
  } catch (error) {
    await server.close()
    await rm(cacheDir, { recursive: true, force: true })
    throw error
  }
}
