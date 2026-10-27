import { test, expect } from '../fixtures/app.js'

/**
 * 会话与权限契约：TC-FE-15e / 17 / 18 / 20 / 21 / 15b
 *
 * 这个文件是**被事故逼出来的**：文件 10-auth-contract 曾一次性承载
 * 敏感字段 / 40103 / 单飞 refresh / 越权 / 菜单按钮 五类断言，
 * 后来被重命名收缩为只保留敏感字段（现 16-sensitive-mask-contract），
 * 其余四类在同一轮里被当"重复覆盖"删掉了 —— 结果是**这四类一条都不剩**。
 * 教训：合并重复用例之前，先确认接收方真的把断言搬走了，否则就是净丢失。
 *
 * 与 16 号文件的分工：16 管"出参长什么样 + 账号禁用（40103）"，本文件管"谁能看到、会话怎么失效"。
 */

// 注意：账号禁用相关断言（TC-FE-22b / 22c）在 16 号文件，本文件刻意不再重复实现。
const TOKEN_KEY = process.env.E2E_TOKEN_KEY || 'sp_token'
const REFRESH_KEY = process.env.E2E_REFRESH_KEY || 'sp_refresh'

const DASHBOARD_RE = /\/dashboard\/(summary|access-trend|material-status)(\?|$)/

test.describe('FE-17/18 页面与按钮级权限', () => {
  test('TC-FE-17 门岗的写操作按钮被隐藏（节点仍在），权限页可写', async ({ page, ui, loginAs }) => {
    await loginAs('gate')

    // ⚠️ 断言写法（这条曾写错过，记录在此防重犯）：
    //    v-permission 的实现是 el.style.display='none' + aria-hidden='true'，**节点仍在 DOM**。
    //    用 toHaveCount(0) 会把"正确实现的隐藏"误判成缺陷——那是测试自身的缺陷。
    //    正确写法：先证明节点存在，再证明它不可见。
    for (const [path, label] of [
      ['/access/employees', '新增员工'],
      ['/access/vehicles', '新增车辆']
    ]) {
      await page.goto(path)
      const btn = page.locator('.el-button', { hasText: label })
      await expect(btn, `${label} 节点应存在（v-permission 只隐藏不移除）`).toHaveCount(1)
      await expect(btn.first()).toBeHidden()
      await expect(btn.first()).toHaveAttribute('aria-hidden', 'true')
    }

    // 页面级只读仍然可用（隐藏按钮 ≠ 页面挂了）
    await expect(page.locator('.el-table')).toBeVisible()

    // 权限页：与后端 POST/PUT /api/permissions 门岗放行一致，必须能新增。
    // 这条同时是前后端口径对账哨兵：按钮被改回 ADMIN-only 而后端仍放行 → 立即上报。
    await page.goto('/access/permissions')
    await expect(ui.button('新增权限')).toBeVisible()
  })

  test('TC-FE-17b 对照：管理员可见写操作按钮（防上条写成永真）', async ({ page, loginAs }) => {
    await loginAs('admin')
    await page.goto('/access/employees')
    const btn = page.locator('.el-button', { hasText: '新增员工' })
    await expect(btn).toHaveCount(1)
    await expect(btn.first()).toBeVisible()
  })

  test('TC-FE-18 越权直输 URL 落 403；未知路径落 404；管理员全页面可达', async ({ page, loginAs }) => {
    // ① 门岗直输仓管页面
    await loginAs('gate')
    await page.goto('/stock/materials')
    await expect(page).toHaveURL(/\/403/)
    await expect(page.locator('.el-result__title')).toContainText('403')
    // 不得因为进了 403 就把物资表格渲染出来
    await expect(page.locator('.el-table')).toHaveCount(0)

    // ② 仓管直输门禁页面
    await page.evaluate(() => localStorage.clear())
    await loginAs('stock')
    await page.goto('/access/records')
    await expect(page).toHaveURL(/\/403/)
    await expect(page.locator('.el-result__title')).toContainText('403')

    // ③ 未知路径落 404（不白屏）
    await loginAs('admin')
    await page.goto(`/no-such-page-${Date.now()}`)
    await expect(page.locator('body')).toContainText(/页面不存在|404/)

    // 对照：管理员进入全部业务页面均可达（防守卫规则写反导致全拦）
    for (const path of ['/access/employees', '/access/vehicles', '/access/permissions', '/visitors', '/stock/materials', '/alarms']) {
      await page.goto(path)
      await expect(page).toHaveURL(new RegExp(path.replace(/\//g, '\\/')))
      await expect(page.locator('.el-table')).toBeVisible()
    }
  })
})

test.describe('FE-20 并发 401 单飞 refresh', () => {
  // 并发源：看板 Dashboard.vue 的 onMounted 用 Promise.all 并发发三个请求，
  // 是天然、稳定、无需造作的并发场景，比"连点三次按钮"可靠得多。
  test('TC-FE-20 三个并发请求只触发 1 次 refresh，且原请求各重放 1 次', async ({ page, api, apiURL, loginAs }) => {
    await loginAs('admin')
    let refreshCount = 0
    let dashboardCount = 0
    page.on('request', (r) => {
      if (DASHBOARD_RE.test(r.url())) dashboardCount += 1
    })

    // refresh 请求被计数后放行到后端取真实新 token：
    // 后续三个业务请求是打真后端的，重放必须携带真 token，否则验不到"重放成功"
    await page.route('**/api/auth/refresh', async (route) => {
      refreshCount += 1
      const res = await api.login(
        api.ctx,
        apiURL,
        process.env.E2E_USER_ADMIN || 'admin',
        process.env.E2E_PASS_ADMIN || 'admin123'
      )
      const { data } = await api.parse(res)
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          message: 'ok',
          data: { token: data?.token, refreshToken: data?.refreshToken, expiresIn: 7200, user: data?.user }
        })
      })
    })

    // 只把 access 换成过期值：sp_refresh 与 sp_user 保持原样
    // （清掉 sp_user 会走"有 token 无 user → 引导恢复"路径，不是我们要验的 401 链路）
    await page.evaluate(([tk]) => localStorage.setItem(tk, 'expired.access.token'), [TOKEN_KEY])

    await page.goto('/dashboard')
    await page.waitForResponse((r) => r.url().includes('/dashboard/summary') && r.status() === 200)

    // 断言点①：refresh 恰好 1 次（3 次即缺陷，后两次会拿已被轮换的旧 refresh 直接掉线）
    expect(refreshCount, `refresh 应只发生一次，实际 ${refreshCount} 次`).toBe(1)
    // 断言点②：3 次失败 + 3 次重放 = 6（少了说明队列丢请求，多了说明重放被重复触发）
    expect(dashboardCount, `业务请求应为 3 失败 + 3 重放 = 6，实际 ${dashboardCount}`).toBe(6)
  })

  test('TC-FE-20b refresh 也失效时一次性踢回登录，不循环刷新', async ({ page, loginAs }) => {
    await loginAs('admin')
    let refreshCount = 0
    let dashboardCount = 0
    page.on('request', (r) => {
      if (DASHBOARD_RE.test(r.url())) dashboardCount += 1
    })

    // refresh 也过期：直接返回 40101，模拟服务端拒绝
    await page.route('**/api/auth/refresh', async (route) => {
      refreshCount += 1
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ code: 40101, message: '登录已失效，请重新登录', data: null })
      })
    })

    await page.goto('/')
    await page.evaluate(
      ([tk, rk]) => {
        localStorage.setItem(tk, 'expired.access.token')
        localStorage.setItem(rk, 'expired.refresh.token')
      },
      [TOKEN_KEY, REFRESH_KEY]
    )
    await page.goto('/dashboard')

    // 断言点①：踢回登录页
    await expect(page).toHaveURL(/\/login/)
    // 断言点②：refresh 最多 1 次，≥2 即循环刷新
    expect(refreshCount, `refresh 失败后不应循环，实际 ${refreshCount} 次`).toBeLessThanOrEqual(1)
    // 断言点③：不再重放业务请求
    expect(dashboardCount, `refresh 失败后不应重放业务请求，实际 ${dashboardCount} 次`).toBe(3)
    // 断言点④：凭证清空
    expect(await page.evaluate(([k]) => localStorage.getItem(k), [TOKEN_KEY])).toBeNull()
  })
})

// 账号禁用相关（TC-FE-22b 登录页 40103 / TC-FE-22c 业务请求 40103 踢登录）
// 由 frontend 维护在 tests/16-sensitive-mask-contract.spec.js，本文件不重复实现：
// 同一条断言放两处，修一处就会漏另一处，属于典型的"重复覆盖变净丢失"。
