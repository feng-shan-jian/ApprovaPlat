import compression from 'vite-plugin-compression'

/**
 * 根据构建环境创建静态资源压缩插件。
 * @param {{ VITE_BUILD_COMPRESS?: string }} env Vite 环境变量，支持 gzip、brotli 或逗号分隔组合。
 * @returns {import('vite').PluginOption[]} 需要注册到 Vite 的压缩插件列表。
 */
export default function createCompression(env) {
  const { VITE_BUILD_COMPRESS } = env
  const plugin = []
  if (VITE_BUILD_COMPRESS) {
    // 压缩类型列表决定产出 .gz、.br 文件中的一种或两种。
    const compressList = VITE_BUILD_COMPRESS.split(',')
    if (compressList.includes('gzip')) {
      // 生成与 Nginx gzip_static 配置配套的 .gz 文件，并保留原始资源。
      plugin.push(
        compression({
          ext: '.gz',
          deleteOriginFile: false
        })
      )
    }
    if (compressList.includes('brotli')) {
      plugin.push(
        compression({
          ext: '.br',
          algorithm: 'brotliCompress',
          deleteOriginFile: false
        })
      )
    }
  }
  return plugin
}
