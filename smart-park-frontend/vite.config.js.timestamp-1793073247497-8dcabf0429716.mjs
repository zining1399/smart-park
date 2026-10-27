// vite.config.js
import { defineConfig, loadEnv } from "file:///C:/Users/wzc/Desktop/1/WorkBuddy/smart-park/smart-park-frontend/node_modules/vite/dist/node/index.js";
import vue from "file:///C:/Users/wzc/Desktop/1/WorkBuddy/smart-park/smart-park-frontend/node_modules/@vitejs/plugin-vue/dist/index.mjs";
import compression from "file:///C:/Users/wzc/Desktop/1/WorkBuddy/smart-park/smart-park-frontend/node_modules/vite-plugin-compression/dist/index.mjs";
var vite_config_default = defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const apiBase = env.VITE_API_BASE || "/api";
  const base = env.VITE_BASE || "/";
  return {
    base,
    plugins: [
      vue(),
      // 注：Element Plus 按需引入放在 src/plugins/element-plus.js 显式注册，
      // 不使用 unplugin-vue-components（其 resolver 与 EP 2.14 目录结构不兼容会回退整库）
      // 产出 .gz，Nginx 开启 gzip_static 后直接吐压缩包
      compression({ threshold: 10240, algorithm: "gzip", ext: ".gz", deleteOriginFile: false })
    ],
    server: {
      port: 5173,
      proxy: {
        [apiBase]: {
          target: env.VITE_PROXY_TARGET || "http://localhost:8080",
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
            if (!id.includes("node_modules")) return;
            if (id.includes("echarts") || id.includes("zrender")) return "echarts";
            if (id.includes("element-plus")) return "element-plus";
            if (id.includes("@element-plus/icons-vue")) return "ep-icons";
            if (id.includes("/vue/") || id.includes("vue-router") || id.includes("pinia") || id.includes("@vue")) return "vue-vendor";
            if (id.includes("axios")) return "axios";
            return "vendor";
          }
        }
      }
    }
  };
});
export {
  vite_config_default as default
};
//# sourceMappingURL=data:application/json;base64,ewogICJ2ZXJzaW9uIjogMywKICAic291cmNlcyI6IFsidml0ZS5jb25maWcuanMiXSwKICAic291cmNlc0NvbnRlbnQiOiBbImNvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9kaXJuYW1lID0gXCJDOlxcXFxVc2Vyc1xcXFx3emNcXFxcRGVza3RvcFxcXFwxXFxcXFdvcmtCdWRkeVxcXFxzbWFydC1wYXJrXFxcXHNtYXJ0LXBhcmstZnJvbnRlbmRcIjtjb25zdCBfX3ZpdGVfaW5qZWN0ZWRfb3JpZ2luYWxfZmlsZW5hbWUgPSBcIkM6XFxcXFVzZXJzXFxcXHd6Y1xcXFxEZXNrdG9wXFxcXDFcXFxcV29ya0J1ZGR5XFxcXHNtYXJ0LXBhcmtcXFxcc21hcnQtcGFyay1mcm9udGVuZFxcXFx2aXRlLmNvbmZpZy5qc1wiO2NvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9pbXBvcnRfbWV0YV91cmwgPSBcImZpbGU6Ly8vQzovVXNlcnMvd3pjL0Rlc2t0b3AvMS9Xb3JrQnVkZHkvc21hcnQtcGFyay9zbWFydC1wYXJrLWZyb250ZW5kL3ZpdGUuY29uZmlnLmpzXCI7aW1wb3J0IHsgZGVmaW5lQ29uZmlnLCBsb2FkRW52IH0gZnJvbSAndml0ZSdcbmltcG9ydCB2dWUgZnJvbSAnQHZpdGVqcy9wbHVnaW4tdnVlJ1xuaW1wb3J0IGNvbXByZXNzaW9uIGZyb20gJ3ZpdGUtcGx1Z2luLWNvbXByZXNzaW9uJ1xuXG4vLyBcdTkwRThcdTdGNzJcdTVGNjJcdTYwMDFcdTc1MzFcdTczQUZcdTU4ODNcdTUzRDhcdTkxQ0ZcdTlBNzFcdTUyQThcdUZGMUFcbi8vICAgVklURV9CQVNFPS8gICAgICAgICBcdTIxOTIgXHU2ODM5XHU4REVGXHU1Rjg0XHU5MEU4XHU3RjcyXHVGRjA4XHU5RUQ4XHU4QkE0XHVGRjA5XG4vLyAgIFZJVEVfQkFTRT0vcGFyay8gICAgXHUyMTkyIFx1NUI1MFx1OERFRlx1NUY4NFx1OTBFOFx1N0Y3Mlx1RkYwQ1x1NTQwQ1x1NjVGNlx1OUE3MVx1NTJBOCByb3V0ZXIgXHU3Njg0IGNyZWF0ZVdlYkhpc3RvcnkoaW1wb3J0Lm1ldGEuZW52LkJBU0VfVVJMKVxuLy8gICBWSVRFX0FQSV9CQVNFPS9hcGkgIFx1MjE5MiBcdTc1MUZcdTRFQTdcdTc1MzEgTmdpbnggXHU1NDBDXHU1N0RGXHU1M0NEXHU0RUUzXHVGRjFCXHU1RjAwXHU1M0QxXHU3NTMxXHU0RTBCXHU2NUI5IHByb3h5IFx1NEVFM1x1NzQwNlx1NTIzMFx1NTQwRVx1N0FFRlxuZXhwb3J0IGRlZmF1bHQgZGVmaW5lQ29uZmlnKCh7IG1vZGUgfSkgPT4ge1xuICBjb25zdCBlbnYgPSBsb2FkRW52KG1vZGUsIHByb2Nlc3MuY3dkKCksICcnKVxuICBjb25zdCBhcGlCYXNlID0gZW52LlZJVEVfQVBJX0JBU0UgfHwgJy9hcGknXG4gIGNvbnN0IGJhc2UgPSBlbnYuVklURV9CQVNFIHx8ICcvJ1xuXG4gIHJldHVybiB7XG4gICAgYmFzZSxcbiAgICBwbHVnaW5zOiBbXG4gICAgICB2dWUoKSxcbiAgICAgIC8vIFx1NkNFOFx1RkYxQUVsZW1lbnQgUGx1cyBcdTYzMDlcdTk3MDBcdTVGMTVcdTUxNjVcdTY1M0VcdTU3Mjggc3JjL3BsdWdpbnMvZWxlbWVudC1wbHVzLmpzIFx1NjYzRVx1NUYwRlx1NkNFOFx1NTE4Q1x1RkYwQ1xuICAgICAgLy8gXHU0RTBEXHU0RjdGXHU3NTI4IHVucGx1Z2luLXZ1ZS1jb21wb25lbnRzXHVGRjA4XHU1MTc2IHJlc29sdmVyIFx1NEUwRSBFUCAyLjE0IFx1NzZFRVx1NUY1NVx1N0VEM1x1Njc4NFx1NEUwRFx1NTE3Q1x1NUJCOVx1NEYxQVx1NTZERVx1OTAwMFx1NjU3NFx1NUU5M1x1RkYwOVxuICAgICAgLy8gXHU0RUE3XHU1MUZBIC5nelx1RkYwQ05naW54IFx1NUYwMFx1NTQyRiBnemlwX3N0YXRpYyBcdTU0MEVcdTc2RjRcdTYzQTVcdTU0MTBcdTUzOEJcdTdGMjlcdTUzMDVcbiAgICAgIGNvbXByZXNzaW9uKHsgdGhyZXNob2xkOiAxMDI0MCwgYWxnb3JpdGhtOiAnZ3ppcCcsIGV4dDogJy5neicsIGRlbGV0ZU9yaWdpbkZpbGU6IGZhbHNlIH0pXG4gICAgXSxcbiAgICBzZXJ2ZXI6IHtcbiAgICAgIHBvcnQ6IDUxNzMsXG4gICAgICBwcm94eToge1xuICAgICAgICBbYXBpQmFzZV06IHtcbiAgICAgICAgICB0YXJnZXQ6IGVudi5WSVRFX1BST1hZX1RBUkdFVCB8fCAnaHR0cDovL2xvY2FsaG9zdDo4MDgwJyxcbiAgICAgICAgICBjaGFuZ2VPcmlnaW46IHRydWVcbiAgICAgICAgfVxuICAgICAgfVxuICAgIH0sXG4gICAgYnVpbGQ6IHtcbiAgICAgIGNodW5rU2l6ZVdhcm5pbmdMaW1pdDogODAwLFxuICAgICAgcm9sbHVwT3B0aW9uczoge1xuICAgICAgICBvdXRwdXQ6IHtcbiAgICAgICAgICAvLyB2ZW5kb3IgXHU2MkM2XHU1MjA2XHVGRjFBZWNoYXJ0cyBcdTRFQzVcdTc3MEJcdTY3N0ZcdTk4NzVcdTUyQTBcdThGN0RcdUZGMENcdTUxNzZcdTRGNTlcdTk4NzVcdTk3NjJcdTRFMERcdTUzRDdcdTUxNzZcdTRGNTNcdTc5RUZcdTVGNzFcdTU0Q0RcbiAgICAgICAgICBtYW51YWxDaHVua3MoaWQpIHtcbiAgICAgICAgICAgIGlmICghaWQuaW5jbHVkZXMoJ25vZGVfbW9kdWxlcycpKSByZXR1cm5cbiAgICAgICAgICAgIGlmIChpZC5pbmNsdWRlcygnZWNoYXJ0cycpIHx8IGlkLmluY2x1ZGVzKCd6cmVuZGVyJykpIHJldHVybiAnZWNoYXJ0cydcbiAgICAgICAgICAgIGlmIChpZC5pbmNsdWRlcygnZWxlbWVudC1wbHVzJykpIHJldHVybiAnZWxlbWVudC1wbHVzJ1xuICAgICAgICAgICAgaWYgKGlkLmluY2x1ZGVzKCdAZWxlbWVudC1wbHVzL2ljb25zLXZ1ZScpKSByZXR1cm4gJ2VwLWljb25zJ1xuICAgICAgICAgICAgaWYgKGlkLmluY2x1ZGVzKCcvdnVlLycpIHx8IGlkLmluY2x1ZGVzKCd2dWUtcm91dGVyJykgfHwgaWQuaW5jbHVkZXMoJ3BpbmlhJykgfHwgaWQuaW5jbHVkZXMoJ0B2dWUnKSkgcmV0dXJuICd2dWUtdmVuZG9yJ1xuICAgICAgICAgICAgaWYgKGlkLmluY2x1ZGVzKCdheGlvcycpKSByZXR1cm4gJ2F4aW9zJ1xuICAgICAgICAgICAgcmV0dXJuICd2ZW5kb3InXG4gICAgICAgICAgfVxuICAgICAgICB9XG4gICAgICB9XG4gICAgfVxuICB9XG59KVxuIl0sCiAgIm1hcHBpbmdzIjogIjtBQUErWCxTQUFTLGNBQWMsZUFBZTtBQUNyYSxPQUFPLFNBQVM7QUFDaEIsT0FBTyxpQkFBaUI7QUFNeEIsSUFBTyxzQkFBUSxhQUFhLENBQUMsRUFBRSxLQUFLLE1BQU07QUFDeEMsUUFBTSxNQUFNLFFBQVEsTUFBTSxRQUFRLElBQUksR0FBRyxFQUFFO0FBQzNDLFFBQU0sVUFBVSxJQUFJLGlCQUFpQjtBQUNyQyxRQUFNLE9BQU8sSUFBSSxhQUFhO0FBRTlCLFNBQU87QUFBQSxJQUNMO0FBQUEsSUFDQSxTQUFTO0FBQUEsTUFDUCxJQUFJO0FBQUE7QUFBQTtBQUFBO0FBQUEsTUFJSixZQUFZLEVBQUUsV0FBVyxPQUFPLFdBQVcsUUFBUSxLQUFLLE9BQU8sa0JBQWtCLE1BQU0sQ0FBQztBQUFBLElBQzFGO0FBQUEsSUFDQSxRQUFRO0FBQUEsTUFDTixNQUFNO0FBQUEsTUFDTixPQUFPO0FBQUEsUUFDTCxDQUFDLE9BQU8sR0FBRztBQUFBLFVBQ1QsUUFBUSxJQUFJLHFCQUFxQjtBQUFBLFVBQ2pDLGNBQWM7QUFBQSxRQUNoQjtBQUFBLE1BQ0Y7QUFBQSxJQUNGO0FBQUEsSUFDQSxPQUFPO0FBQUEsTUFDTCx1QkFBdUI7QUFBQSxNQUN2QixlQUFlO0FBQUEsUUFDYixRQUFRO0FBQUE7QUFBQSxVQUVOLGFBQWEsSUFBSTtBQUNmLGdCQUFJLENBQUMsR0FBRyxTQUFTLGNBQWMsRUFBRztBQUNsQyxnQkFBSSxHQUFHLFNBQVMsU0FBUyxLQUFLLEdBQUcsU0FBUyxTQUFTLEVBQUcsUUFBTztBQUM3RCxnQkFBSSxHQUFHLFNBQVMsY0FBYyxFQUFHLFFBQU87QUFDeEMsZ0JBQUksR0FBRyxTQUFTLHlCQUF5QixFQUFHLFFBQU87QUFDbkQsZ0JBQUksR0FBRyxTQUFTLE9BQU8sS0FBSyxHQUFHLFNBQVMsWUFBWSxLQUFLLEdBQUcsU0FBUyxPQUFPLEtBQUssR0FBRyxTQUFTLE1BQU0sRUFBRyxRQUFPO0FBQzdHLGdCQUFJLEdBQUcsU0FBUyxPQUFPLEVBQUcsUUFBTztBQUNqQyxtQkFBTztBQUFBLFVBQ1Q7QUFBQSxRQUNGO0FBQUEsTUFDRjtBQUFBLElBQ0Y7QUFBQSxFQUNGO0FBQ0YsQ0FBQzsiLAogICJuYW1lcyI6IFtdCn0K
