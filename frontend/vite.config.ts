import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    // 开发期通过 Vite 代理转发到后端，浏览器始终同源，彻底规避 CORS
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 上传大文件时不要被代理层缓冲卡住
        proxyTimeout: 0,
        timeout: 0,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    /**
     * 告警阈值从默认 500KB 抬到 1100KB，不是「把提示关掉」，而是分组之后剩下的两个包
     * 本身就是单库体积下限：element-plus 全量注册 1,040KB、echarts 运行时 530KB。
     *
     * <p>前者要再降只能改成组件级按需引入，得新增 unplugin-vue-components /
     * unplugin-auto-import 两个构建期依赖并改动全部 12 个页面的组件解析方式；后者是
     * 图表库自身的心跳体积，且已经在只在进大屏时才下载。首屏真正要付的业务代码只有 36KB。
     */
    chunkSizeWarningLimit: 1100,
    rollupOptions: {
      output: {
        /**
         * 三方库按「多久变一次」分组，而不是全塞进入口 chunk。
         *
         * <p>业务页面天天改，vue / element-plus / echarts 几个月才升一次版。混在一个 972KB 的
         * index.js 里，等于每次发版都要让用户重新下一整包；拆开后只有业务 chunk 的 hash 会变，
         * vendor 继续命中强缓存。分组名进文件名，排查线上包体大小一眼能看出是谁占的地方。
         *
         * <p>返回 null 表示交给 rolldown 自己决定（没列到的零碎依赖仍按默认策略合并），
         * 不强行把整个 node_modules 塞进一个 vendor——那样反而会把只给某个懒加载路由用的库
         * 提前拽进首屏。
         */
        manualChunks: (id: string) => {
          if (!id.includes('node_modules')) {
            return null
          }
          const normalized = id.replace(/\\/g, '/')
          const pkg = normalized.slice(normalized.lastIndexOf('/node_modules/') + '/node_modules/'.length)
          if (pkg.startsWith('echarts') || pkg.startsWith('zrender')) {
            return 'vendor-echarts'
          }
          if (pkg.startsWith('element-plus') || pkg.startsWith('@element-plus')) {
            return 'vendor-element-plus'
          }
          if (pkg.startsWith('vue') || pkg.startsWith('@vue') || pkg.startsWith('pinia')) {
            return 'vendor-core'
          }
          if (pkg.startsWith('marked') || pkg.startsWith('dompurify')) {
            return 'vendor-markdown'
          }
          return null
        },
      },
    },
  },
})
