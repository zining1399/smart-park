import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import compression from 'vite-plugin-compression'

// 部署形态由环境变量驱动：
//   VITE_BASE=/         → 根路径部署（默认）
//   VITE_BASE=/park/    → 子路径部署，同时驱动 router 的 createWebHistory(import.meta.env.BASE_URL)
//   VITE_API_BASE=/api  → 生产由 Nginx 同域反代；开发由下方 proxy 代理到后端
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiBase = env.VITE_API_BASE_URL || env.VITE_API_BASE || '/api'
  const base = env.VITE_BASE || '/'

  return {
    base,
    plugins: [
      vue(),
      // 注：Element Plus 按需引入放在 src/plugins/element-plus.js 显式注册，
      // 不使用 unplugin-vue-components（其 resolver 与 EP 2.14 目录结构不兼容会回退整库）
      // 产出 .gz，Nginx 开启 gzip_static 后直接吐压缩包
      compression({ threshold: 10240, algorithm: 'gzip', ext: '.gz', deleteOriginFile: false })
    ],
    server: {
      port: 5173,
      proxy: {
        [apiBase]: {
          target: env.VITE_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true
        }
      }
    },
    build: {
      chunkSizeWarningLimit: 800,
      rollupOptions: {
        output: {
          // vendor 拆分：echarts 仅看板页加载，其余页面不受其体积影响
          manualChunks(id) {
            if (!id.includes('node_modules')) return
            if (id.includes('echarts') || id.includes('zrender')) return 'echarts'
            if (id.includes('element-plus')) return 'element-plus'
            if (id.includes('@element-plus/icons-vue')) return 'ep-icons'
            if (id.includes('/vue/') || id.includes('vue-router') || id.includes('pinia') || id.includes('@vue')) return 'vue-vendor'
            if (id.includes('axios')) return 'axios'
            return 'vendor'
          }
        }
      }
    }
  }
})
