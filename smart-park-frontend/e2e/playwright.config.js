import { defineConfig, devices } from '@playwright/test'

/**
 * 智慧园区管理系统 E2E 配置
 *
 * 环境变量（均可缺省，缺省值面向本地 docker-compose 联调环境）：
 *   E2E_BASE_URL     前端地址，默认 http://localhost:5173（Vite dev，/api 代理到 8080）
 *   E2E_API_URL      后端地址，默认 http://localhost:8080/api
 *   E2E_LOGIN_UI     =1 时走 /login 页面表单登录；否则走接口登录 + 注入凭证（默认）
 *   E2E_TOKEN_KEY    access token 的 localStorage 键名，默认 sp_token（M2 前端实测值）
 *   E2E_REFRESH_KEY  refresh token 的键名，默认 sp_refresh
 *   E2E_USER_KEY     用户信息的键名，默认 sp_user（存 JSON 字符串，必须一并注入）
 *   E2E_USER_ADMIN / E2E_PASS_ADMIN     管理员账号
 *   E2E_USER_GATE   / E2E_PASS_GATE     门岗账号
 *   E2E_USER_STOCK  / E2E_PASS_STOCK    仓管账号
 *   E2E_AUTH_TOKEN  直接给定 token（跳过登录步骤，用于只验证页面渲染的场景）
 *
 * ⚠️ 凭证是“三件套”而非单 token：sp_token(access) / sp_refresh(refresh) / sp_user(JSON:{"username","realName","role"})。
 *    只注入 sp_token 时，应用启动引导会走“有 token 无 user → 调 /auth/me 恢复”，
 *    后端未起就 401 → 前端清凭证 → 用例在第 0 步被踢回登录页。三件套缺一不可。
 *
 * 浏览器矩阵承载 TC-FE-11（兼容性）：
 *   chromium / firefox / webkit × 1366x768，test:compat 一键跑三浏览器。
 */
const baseURL = process.env.E2E_BASE_URL || 'http://localhost:5173'
const apiURL = process.env.E2E_API_URL || 'http://localhost:8080/api'

export default defineConfig({
  testDir: './tests',
  // 冒烟集要求 <10 分钟；单条用例超时给足，整体靠并行压缩
  timeout: 60 * 1000,
  expect: { timeout: 10 * 1000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 4 : 2,
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }], ['junit', { outputFile: 'reports/junit.xml' }]]
    : [['list'], ['html', { open: 'never' }]],
  outputDir: './test-results',
  use: {
    baseURL,
    apiURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    // 客户现场基线分辨率【待确认：国产浏览器需追加 project】
    viewport: { width: 1366, height: 768 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    extraHTTPHeaders: { 'Accept-Language': 'zh-CN' }
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } }
  ],
  // 后端与前端需在 8080 / 5173 就绪；联调阶段用 docker-compose 起
  webServer: process.env.E2E_START_SERVERS
    ? [
        { command: 'cd ../../smart-park-backend && mvn -q spring-boot:run', url: apiURL + '/dashboard/summary', reuseExistingServer: !process.env.CI, timeout: 180 * 1000 },
        { command: 'cd .. && npm run dev', url: baseURL, reuseExistingServer: !process.env.CI, timeout: 120 * 1000 }
      ]
    : []
})
