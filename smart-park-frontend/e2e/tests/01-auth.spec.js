import { test, expect } from '../fixtures/app.js'
import { factories } from '../fixtures/api.js'

/**
 * TC-FE-01 / TC-FE-02 / TC-FE-13 / TC-FE-14 —— 登录与全局错误处理
 *
 * M3 前端已落地 /login 与顶栏登出入口；⏳ 依赖后端 /auth/* 就位后才能联调执行。
 * 凭证口径：sp_token / sp_refresh / sp_user 三件套（见 fixtures/app.js）。
 * ⚠️ 40101/40102/40103/40301 在前端 src/api/request.js 里是 hard-code：
 *    若后端改动数值，前端会静默失效——出现“过期不跳登录”时先核对 code 再提缺陷。
 */
const loginUiReady = process.env.E2E_LOGIN_UI === '1'

test.describe('FE-01 登录与会话', () => {
  test('TC-FE-01 登录成功进入工作台，登出后浏览器回退不得进入需鉴权页面', async ({ page, ui }) => {
    test.skip(!loginUiReady, 'TODO(M2)：等待 /login 页面与登出入口实现')
    await page.goto('/login')
    await page.locator('input[name="username"], input[placeholder*="用户名"]').first().fill(process.env.E2E_USER_ADMIN || 'admin')
    await page.locator('input[name="password"], input[type="password"]').first().fill(process.env.E2E_PASS_ADMIN || 'admin123')
    await page.locator('button[type="submit"], .el-button:has-text("登录")').first().click()

    // 断言点：URL 落到看板 + 五个统计卡片渲染 + 无登录表单残留
    await expect(page).toHaveURL(/\/dashboard/)
    await expect(page.locator('.stat-card')).toHaveCount(5)
    await expect(page.locator('input[type="password"]')).toHaveCount(0)

    // 登出：顶栏 .user-info → 下拉「退出登录」→ 侧边栏/菜单项退出
    await ui.logout()
    await expect(page).toHaveURL(/\/login/)

    // 回退：不得回到工作台
    await page.goBack()
    await expect(page).not.toHaveURL(/\/dashboard/)
    await expect(ui.message()).toHaveCount(0)
  })

  test('TC-FE-02 错误密码登录失败并停留登录页', async ({ page }) => {
    test.skip(!loginUiReady, 'TODO(M2)：等待 /login 页面实现')
    await page.goto('/login')
    await page.locator('input[name="username"], input[placeholder*="用户名"]').first().fill(process.env.E2E_USER_ADMIN || 'admin')
    await page.locator('input[name="password"], input[type="password"]').first().fill('wrong-password')
    await page.locator('button[type="submit"], .el-button:has-text("登录")').first().click()

    // 断言点：ElMessage 报错 + 未跳转 + 表单仍在
    await expect(page.locator('.el-message--error')).toBeVisible()
    await expect(page).toHaveURL(/\/login/)
  })

  test('TC-FE-13 token 过期后任意操作统一跳转登录页且不白屏', async ({ page, ui, loginAs }) => {
    test.skip(!loginUiReady, 'TODO：等待后端 /auth/* 就位后联调')
    // 前置：正常登录进工作台
    await loginAs('admin')

    // 模拟 access token 过期：只替换 sp_token，保留 sp_user（否则会走引导逻辑而非 401 链路）
    await page.evaluate(
      (key) => window.localStorage.setItem(key, 'expired.token.value'),
      process.env.E2E_TOKEN_KEY || 'sp_token'
    )

    // 触发一次需鉴权操作
    await ui.openMenuGroup('智慧门禁')
    await ui.menu('员工管理').click()

    // 断言点：跳转登录页 + 页面有内容（非白屏）
    await expect(page).toHaveURL(/\/login/)
    await expect(page.locator('form, .login-page').first()).toBeVisible()
  })
})

test.describe('FE-14 全局错误处理', () => {
  test('TC-FE-14 接口返回 code≠0 时统一 ElMessage 提示，不白屏不崩溃', async ({ page, ui }) => {
    // 前置：拦截一个业务接口，固定返回业务错误
    await page.route('**/api/materials*', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 5001, message: '物资当前状态为「在借」，不可出库', data: null })
      })
    )
    await page.goto('/stock/materials')

    // 断言点①：弹出错误提示且文案与后端 message 一致
    await expect(ui.message()).toBeVisible()
    await expect(ui.message()).toContainText('不可出库')

    // 断言点②：页面骨架仍在（表格容器 + 分页存在），未白屏
    await expect(page.locator('.el-table')).toBeVisible()
    await expect(ui.paginationTotal()).toBeVisible()
  })

  test('TC-FE-14b XSS 防御：接口返回含脚本的物资名称不得被执行', async ({ page, ui }) => {
    const payload = '<img src=x onerror="window.__xss=1">'
    await page.route('**/api/materials*', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: 0, message: 'ok', data: { total: 1, list: [{ id: 1, code: 'M-XSS', name: payload, category: '工具', valuable: false, status: '在库' }] } })
      })
    )
    await page.goto('/stock/materials')

    // 断言点：文本原样展示（转义），且未触发 onerror
    await expect(ui.rowContaining('M-XSS')).toContainText(payload)
    expect(await page.evaluate(() => window.__xss)).toBeUndefined()
    await expect(page.locator('img[src="x"]')).toHaveCount(0)
  })
})

// 造数工厂在此文件仅用于后续联调时的扩展；保留引用避免未使用告警
export const __factories = factories
