import { expect, test as base } from '@playwright/test'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import { fileURLToPath } from 'node:url'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, resolve, sep } from 'node:path'

const root = fileURLToPath(new URL('../..', import.meta.url))
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64')

// Only the host form and HTTP responses are controlled. Vue, Element Plus, the
// production components, XHR transport and loading overlay run in the browser.
const harness = `
import { createApp, h, ref } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import FileUpload from '/src/components/FileUpload/index.vue'
import ImageUpload from '/src/components/ImageUpload/index.vue'
import modal from '/src/plugins/modal.js'
const kind = new URLSearchParams(location.search).get('kind')
const errors = ref(0)
const app = createApp({ setup() {
  const value = ref('/existing.png')
  const mounted = ref(true)
  const updates = ref(0)
  return () => h('main', [
    h('output', { 'data-testid': 'value' }, value.value),
    h('output', { 'data-testid': 'updates' }, String(updates.value)),
    h('output', { 'data-testid': 'errors' }, String(errors.value)),
    h('button', { onClick: () => { mounted.value = !mounted.value } }, '切换组件'),
    mounted.value ? h(kind === 'image' ? ImageUpload : FileUpload, {
      modelValue: value.value, action: '/upload', drag: false, limit: 10,
      fileType: ['png'], data: { business: 'settlement-test' },
      'onUpdate:modelValue': next => { value.value = next; updates.value++ }
    }) : null
  ])
}})
app.use(ElementPlus)
app.config.globalProperties.$modal = { ...modal, msgError(message) { errors.value++; modal.msgError(message) } }
app.mount('#app')
`

const test = base.extend({
  uploadHost: [async ({ browserName: _browserName }, use) => {
    const pending = new Map()
    const cacheDir = await mkdtemp(join(tmpdir(), 'upload-settlement-'))
    if (!resolve(cacheDir).startsWith(resolve(tmpdir()) + sep)) throw new Error('Unexpected upload test cache path')
    const server = await createServer({
      root, cacheDir, configFile: false, logLevel: 'error',
      optimizeDeps: { noDiscovery: true, include: ['vue', 'element-plus', 'sortablejs', 'js-cookie'] },
      resolve: { alias: { '@': `${root}/src` } },
      define: { 'import.meta.env.VITE_APP_BASE_API': JSON.stringify('/test-api') },
      plugins: [vue(), AutoImport({ imports: ['vue'], dts: false }), {
        name: 'upload-component-regression',
        resolveId(id) { if (id === '/upload-harness.js') return '\0upload-harness' },
        load(id) { if (id === '\0upload-harness') return harness },
        configureServer(vite) {
          vite.middlewares.use((req, res, next) => {
            if (req.url?.startsWith('/upload-harness?')) {
              res.setHeader('Content-Type', 'text/html')
              res.end('<!doctype html><html><body><div id="app"></div><script type="module" src="/upload-harness.js"></script></body></html>')
            } else if (req.url === '/test-api/upload' && req.method === 'POST') {
              const chunks = []
              req.on('data', chunk => chunks.push(chunk))
              req.on('end', () => {
                const body = Buffer.concat(chunks).toString()
                const name = /filename="([^"]+)"/.exec(body)?.[1]
                pending.set(name, { req, res, body })
              })
            } else if (req.url?.endsWith('.png')) {
              res.setHeader('Content-Type', 'image/png')
              res.end(png)
            } else next()
          })
        }
      }],
      server: { host: '127.0.0.1', port: 0 }
    })
    try {
      await server.listen()
      await use({
        url: `http://127.0.0.1:${server.httpServer.address().port}`,
        async wait(name) {
          await expect.poll(() => pending.has(name)).toBe(true)
          expect(pending.get(name).body).toContain('settlement-test')
        },
        release(name, outcome = 'success') {
          const request = pending.get(name)
          expect(request, `missing upload ${name}`).toBeTruthy()
          pending.delete(name)
          request.res.setHeader('X-Test-Upload-Name', name)
          if (outcome === 'network') { request.res.statusCode = 503; request.res.end('controlled HTTP failure') }
          else if (outcome === 'transport') {
            // A truncated HTTP body closes the connection after headers, so the
            // browser reports XHR onerror without retrying an empty response.
            request.res.writeHead(200, { 'Content-Length': '1000', Connection: 'close' })
            request.res.end('{}')
          }
          else {
            request.res.setHeader('Content-Type', 'application/json')
            request.res.end(JSON.stringify(outcome === 'business'
              ? { code: 500, msg: '受控业务失败', fileName: `/rejected/${name}` }
              : { code: 200, fileName: `/stored/${name}` }))
          }
        }
      })
    } finally {
      for (const request of pending.values()) request.res.destroy()
      await server.close()
      await rm(cacheDir, { recursive: true, force: true })
    }
  }, { scope: 'worker' }]
})

async function choose(page, host, names) {
  await page.locator('input[type=file]').setInputFiles(names.map(name => ({ name, mimeType: 'image/png', buffer: png })))
  for (const name of names) await host.wait(name)
  await expect(page.locator('.el-loading-mask')).toBeVisible()
}

async function settled(page, expected, updates) {
  await expect(page.getByTestId('value')).toHaveText(expected)
  await expect(page.locator('.el-loading-mask')).toHaveCount(0)
  await expect(page.getByTestId('updates')).toHaveText(String(updates))
}

for (const kind of ['file', 'image']) {
  test.describe(`${kind} upload`, () => {
    test.beforeEach(async ({ page, uploadHost }) => {
      await page.goto(`${uploadHost.url}/upload-harness?kind=${kind}`)
      await expect(page.getByTestId('value')).toHaveText('/existing.png')
    })

    test.afterEach(async ({ page }) => {
      expect(await page.pageErrors()).toEqual([])
    })

    for (const outcomes of [['success', 'success'], ['network', 'success'], ['success', 'network'], ['transport', 'success'], ['success', 'transport'], ['network', 'network'], ['business', 'success'], ['success', 'business'], ['business', 'business']]) {
      test(`${outcomes.join(' then ')} settles and accepts a second batch`, async ({ page, uploadHost }) => {
        await choose(page, uploadHost, ['first.png', 'second.png'])
        const firstFinished = outcomes[0] === 'transport'
          ? page.waitForEvent('requestfailed', request => request.url().endsWith('/upload'))
          : page.waitForResponse(response => response.headers()['x-test-upload-name'] === 'first.png').then(response => response.finished())
        uploadHost.release('first.png', outcomes[0])
        await firstFinished
        if (outcomes[0] !== 'success') await expect(page.getByTestId('errors')).toHaveText('1')
        // One completed callback must not finish the remaining request.
        await expect(page.locator('.el-loading-mask')).toBeVisible()
        await expect(page.getByTestId('value')).toHaveText('/existing.png')
        uploadHost.release('second.png', outcomes[1])
        const urls = ['/existing.png', ...['first.png', 'second.png'].filter((_, i) => outcomes[i] === 'success').map(name => `/stored/${name}`)]
        await settled(page, urls.join(','), 1)
        await choose(page, uploadHost, ['third.png'])
        uploadHost.release('third.png')
        await settled(page, [...urls, '/stored/third.png'].join(','), 2)
        const rows = page.locator(kind === 'image' ? '.el-upload-list__item' : '.upload-file-list li')
        await expect(rows).toHaveCount(urls.length + 1)
      })
    }

    test('deleting an existing item during upload keeps pending requests and successful results', async ({ page, uploadHost }) => {
      await choose(page, uploadHost, ['first.png', 'second.png'])
      // The production fullscreen loading mask normally blocks pointer input;
      // dispatch the existing DOM control to exercise the in-flight callback.
      const remove = kind === 'image' ? page.locator('.el-upload-list__item-delete').first() : page.getByText('删除', { exact: true }).first()
      await remove.dispatchEvent('click')
      await expect(page.getByTestId('value')).toHaveText('')
      uploadHost.release('first.png')
      uploadHost.release('second.png', 'network')
      await settled(page, '/stored/first.png', 2)
      await expect(page.locator(kind === 'image' ? '.el-upload-list__item' : '.upload-file-list li')).toHaveCount(1)
      const finalRemove = kind === 'image' ? page.locator('.el-upload-list__item-delete').first() : page.getByText('删除', { exact: true }).first()
      await finalRemove.dispatchEvent('click')
      await settled(page, '', 3)
    })

    test('reverse completion keeps the Element Plus selection order', async ({ page, uploadHost }) => {
      await choose(page, uploadHost, ['selected-first.png', 'selected-second.png'])
      const secondResponse = page.waitForResponse(response => response.headers()['x-test-upload-name'] === 'selected-second.png')
      uploadHost.release('selected-second.png')
      await (await secondResponse).finished()
      await expect(page.getByTestId('value')).toHaveText('/existing.png')
      await expect(page.locator('.el-loading-mask')).toBeVisible()
      uploadHost.release('selected-first.png')
      await settled(page, '/existing.png,/stored/selected-first.png,/stored/selected-second.png', 1)
    })

    test('unmount closes loading and late responses do not publish or contaminate remount', async ({ page, uploadHost }) => {
      await choose(page, uploadHost, ['unmounted.png'])
      await page.getByRole('button', { name: '切换组件' }).dispatchEvent('click')
      await expect(page.locator('.el-loading-mask')).toHaveCount(0)
      uploadHost.release('unmounted.png')
      await expect(page.getByTestId('updates')).toHaveText('0')
      await page.getByRole('button', { name: '切换组件' }).click()
      await choose(page, uploadHost, ['remounted.png'])
      uploadHost.release('remounted.png')
      await settled(page, '/existing.png,/stored/remounted.png', 1)
    })

    test('invalid type does not poison the accepted upload', async ({ page, uploadHost }) => {
      await page.locator('input[type=file]').setInputFiles([
        { name: 'invalid.txt', mimeType: 'text/plain', buffer: Buffer.from('invalid') },
        { name: 'accepted.png', mimeType: 'image/png', buffer: png }
      ])
      await uploadHost.wait('accepted.png')
      await expect(page.locator('.el-message')).toContainText('格式不正确')
      uploadHost.release('accepted.png')
      await settled(page, '/existing.png,/stored/accepted.png', 1)
    })

    if (kind === 'image') {
      test('removing a pending image settles the successful sibling and preserves preview URLs', async ({ page, uploadHost }) => {
        await choose(page, uploadHost, ['kept.png', 'removed.png'])
        const completed = page.waitForResponse(response => response.headers()['x-test-upload-name'] === 'kept.png')
        uploadHost.release('kept.png')
        await (await completed).finished()
        await page.locator('.el-upload-list__item').nth(2).locator('.el-upload-list__item-delete').dispatchEvent('click')
        await settled(page, '/existing.png,/stored/kept.png', 1)
        uploadHost.release('removed.png')
        await expect(page.locator('.el-upload-list__item')).toHaveCount(2)
        const kept = page.locator('.el-upload-list__item').last()
        await expect(kept.locator('img')).toHaveAttribute('src', '/test-api/stored/kept.png')
        await kept.hover()
        await kept.locator('.el-upload-list__item-preview').click()
        await expect(page.getByRole('dialog').locator('img')).toHaveAttribute('src', '/test-api/stored/kept.png')
        await expect(page.getByTestId('updates')).toHaveText('1')
      })
    }
  })
}
