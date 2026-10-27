import { test, expect } from '../fixtures/app.js'

/**
 * TC-FE-16 未登录直接访问受保护页面 → 被拦到 /login?redirect=... → 登录后回原路径
 *
 * M3 前端已实现路由守卫（router.beforeEach：无 token → /login 带 redirect 参数；
 * 登录后 `router.replace(redirect || '/dashboard')`）。
 * ⏳ 需后端 /auth/* 就位后联调。
 *
 * ⚠️ 与 TC-FE-13 的区别：FE-13 验「token 失效后被踢」，本用例验「来源路径被记住」。
 *    两者都要求 URL 里出现 /login，但本例的落点必须是原路径而不是 /dashboard。
 */
const loginUiReady = process.env.E2E_LOGIN_UI === '1'

test('TC-FE-16 未登录直输 /visitors 被拦截，登录后回到 /visitors 而非 /dashboard', async ({ page }) => {
  test.skip(!loginUiReady, 'TODO：等待后端 /auth/* 就位后联调')

  // 前置：确保无任何凭证（模拟未登录）
  await page.goto('/login')
  await page.evaluate(() => window.localStorage.clear())

  // 直输受保护路径
  await page.goto('/visitors')

  // 断言点①：被重定向到登录页且带 redirect 参数（/visitors 被 URL 编码为 %2Fvisitors）
  await expect(page).toHaveURL(/\/login\?redirect=%2Fvisitors/)

  // 登录（登录页已提供 name="username" / name="password"，且支持回车提交）
  await page.locator('input[name="username"]').first().fill(process.env.E2E_USER_ADMIN || 'admin')
  await page.locator('input[name="password"]').first().fill(process.env.E2E_PASS_ADMIN || 'admin123')
  await page.locator('.el-button:has-text("登录")').first().click()

  // 断言点②：回到原路径
  await expect(page).toHaveURL(/\/visitors$/)
})

test('TC-FE-16b 无 redirect 参数时，登录后落到 /dashboard（防回跳逻辑写反）', async ({ page }) => {
  test.skip(!loginUiReady, 'TODO：等待后端 /auth/* 就位后联调')

  await page.goto('/login')
  await page.evaluate(() => window.localStorage.clear())

  await page.locator('input[name="username"]').first().fill(process.env.E2E_USER_ADMIN || 'admin')
  await page.locator('input[name="password"]').first().fill(process.env.E2E_PASS_ADMIN || 'admin123')
  await page.locator('.el-button:has-text("登录")').first().click()

  await expect(page).toHaveURL(/\/dashboard/)
})
