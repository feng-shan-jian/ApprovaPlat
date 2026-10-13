import { createServer } from 'vite'
import AutoImport from 'unplugin-auto-import/vite'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

// Load the complete production ES module with the same alias/auto-import rules.
// Replacements are limited to explicit external ports, never component state.
export async function productionModules(t, ports = {}) {
  const root = fileURLToPath(new URL('../../..', import.meta.url))
  const cacheDir = await mkdtemp(join(tmpdir(), 'workflow-module-contract-'))
  const registryKey = `contractPorts${Math.random().toString(36).slice(2)}`
  globalThis[registryKey] = ports
  const server = await createServer({
    root, cacheDir, configFile: false, logLevel: 'silent',
    optimizeDeps: { noDiscovery: true, include: [] },
    resolve: { alias: [
      ...Object.keys(ports).map(find => ({ find, replacement: `\0contract-port:${find}` })),
      { find: '@', replacement: `${root}/src` }
    ] },
    define: { 'import.meta.env.VITE_APP_BASE_API': JSON.stringify('/dev-api') },
    plugins: [{
      name: 'contract-external-ports', enforce: 'pre',
      resolveId(id) { if (id.startsWith('\0contract-port:')) return id },
      load(id) {
        if (!id.startsWith('\0contract-port:')) return
        const port = id.slice('\0contract-port:'.length)
        return Object.keys(ports[port]).map(name => name === 'default'
          ? `export default globalThis[${JSON.stringify(registryKey)}][${JSON.stringify(port)}].default`
          : `export const ${name} = globalThis[${JSON.stringify(registryKey)}][${JSON.stringify(port)}][${JSON.stringify(name)}]`).join('\n')
      }
    }, AutoImport({ imports: ['vue', 'vue-router', 'pinia'], dts: false, viteOptimizeDeps: false })],
    server: { middlewareMode: true, watch: null }
  })
  t.after(async () => { await server.close(); delete globalThis[registryKey]; await rm(cacheDir, { recursive: true, force: true }) })
  return path => server.ssrLoadModule(`/src/${path}`)
}
