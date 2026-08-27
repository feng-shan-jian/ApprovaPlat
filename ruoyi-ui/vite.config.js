import { defineConfig, loadEnv } from 'vite'
import path from 'path'
import createVitePlugins from './plugins'

/**
 * 根据运行模式生成前端开发与构建配置。
 * @param {{ mode: string, command: string }} configEnv Vite 提供的运行模式与命令信息。
 * @returns {import('vite').UserConfig} 当前环境可直接使用的 Vite 配置。
 */
export default defineConfig((configEnv) => {
  // mode 决定加载的环境变量文件，command 用于区分开发服务与生产构建。
  const { mode, command } = configEnv
  const env = loadEnv(mode, process.cwd())
  const { VITE_APP_ENV } = env
  // 本机隔离 E2E 可显式切换真实后端，其余开发环境保持 8080 默认入口。
  const baseUrl = process.env.VITE_PROXY_TARGET?.trim() || env.VITE_PROXY_TARGET?.trim() || 'http://localhost:8080'
  return {
    // 部署生产环境和开发环境下的URL。
    // 默认情况下，vite 会假设你的应用是被部署在一个域名的根路径上
    // 如果应用部署在子路径中，需要将 base 设置为对应的公共路径。
    base: VITE_APP_ENV === 'production' ? '/' : '/',
    plugins: createVitePlugins(env, command === 'build'),
    resolve: {
      // https://cn.vitejs.dev/config/#resolve-alias
      alias: {
        // 设置路径
        '~': path.resolve(__dirname, './'),
        // 设置别名
        '@': path.resolve(__dirname, './src')
      },
      // https://cn.vitejs.dev/config/#resolve-extensions
      extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue']
    },
    // 打包配置
    build: {
      // https://vite.dev/config/build-options.html
      sourcemap: command === 'build' ? false : 'inline',
      outDir: 'dist',
      assetsDir: 'assets',
      chunkSizeWarningLimit: 2000,
      rollupOptions: {
        output: {
          chunkFileNames: 'static/js/[name]-[hash].js',
          entryFileNames: 'static/js/[name]-[hash].js',
          assetFileNames: 'static/[ext]/[name]-[hash].[ext]'
        }
      }
    },
    // vite 相关配置
    server: {
      port: 1024,
      host: '127.0.0.1',
      open: false,
      proxy: {
        // https://cn.vitejs.dev/config/#server-proxy
        '/dev-api': {
          target: baseUrl,
          changeOrigin: true,
          rewrite: (p) => p.replace(/^\/dev-api/, '')
        },
         // springdoc proxy
         '^/v3/api-docs/(.*)': {
          target: baseUrl,
          changeOrigin: true,
        }
      }
    },
    css: {
      postcss: {
        plugins: [
          {
            postcssPlugin: 'internal:charset-removal',
            AtRule: {
              charset: (atRule) => {
                if (atRule.name === 'charset') {
                  atRule.remove()
                }
              }
            }
          }
        ]
      }
    }
  }
})
