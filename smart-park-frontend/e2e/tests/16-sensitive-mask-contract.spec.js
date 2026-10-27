import { test, expect } from '../fixtures/app.js'
import { factories } from '../fixtures/api.js'

/**
 * TC-FE-22 敏感字段命名与形态契约（裁决 17，api-contract §六）
 *
 * 这是 D7 联调里**唯一一条无人替代**的断言：qa 的 11/12/14/15 已覆盖菜单、
 * 越权与单飞 refresh，但"出参到底叫什么、长什么样"只有这里卡。
 *
 * 契约（已冻结，与后端 AdminInitializer/Jackson 默认策略一致）：
 *   JSON 恒为驼峰 → idCardMask / phoneMask；下划线写法只存在于 DB 列名
 *   密文列 idCard / phone 在出参里被 toMaskedView 覆盖成掩码（不得下发密文）
 *   哈希列 idCardHash / phoneHash 恒为 null（不得外泄）
 *
 * ⚠️ 后端一旦改回下划线（给 Jackson 加 snake_case 策略）或漏改 toMaskedView，
 *    本用例立刻红灯。前端已按"只认驼峰、不做下划线兜底"实现，
 *    **不要为了让它变绿去放宽断言**——真出偏差要在接口层暴露，而不是被容错掩盖。
 */
const CRED_ENV = {
  admin: ['E2E_USER_ADMIN', 'E2E_PASS_ADMIN']
}

const TOKEN_KEY = process.env.E2E_TOKEN_KEY || 'sp_token'
const REFRESH_KEY = process.env.E2E_REFRESH_KEY || 'sp_refresh'

async function loginToken(api, apiURL, role = 'admin') {
  const [u, p] = CRED_ENV[role]
  const res = await api.login(api.ctx, apiURL, process.env[u], process.env[p])
  const { data } = await api.parse(res)
  return { token: data?.token, refreshToken: data?.refreshToken, user: data?.user }
}

test.describe('FE-22 敏感字段命名与形态', () => {
  test('TC-FE-22a 出参恒为驼峰掩码：有 idCardMask/phoneMask，无下划线写法，密文与哈希不外泄', async ({ api, apiURL }) => {
    const { token } = await loginToken(api, apiURL)

    // 先造数，保证列表非空——空列表会让下面的 find 拿到 undefined，断言变成假通过
    const visitorId = await api.createVisitor(api.ctx, apiURL, token, factories.visitor())
    const employeeId = await api.createEmployee(api.ctx, apiURL, token, factories.employee())
    try {
      const v = await api.parse(
        await api.ctx.get(`${apiURL}/visitors?pageNum=1&pageSize=20`, { headers: api.authHeader(token) })
      )
      const e = await api.parse(
        await api.ctx.get(`${apiURL}/employees?pageNum=1&pageSize=20`, { headers: api.authHeader(token) })
      )

      const visitor = (v.data?.list || []).find((x) => x.id === visitorId)
      const employee = (e.data?.list || []).find((x) => x.id === employeeId)
      expect(visitor, '访客列表应包含刚创建的记录').toBeTruthy()
      expect(employee, '员工列表应包含刚创建的记录').toBeTruthy()

      // ① 驼峰掩码字段必须存在
      expect(Object.keys(visitor)).toContain('idCardMask')
      expect(Object.keys(visitor)).toContain('phoneMask')
      expect(Object.keys(employee)).toContain('phoneMask')

      // ② 绝不允许下划线写法（后端若切 Jackson snake_case，这里红灯）
      for (const key of ['id_card', 'phone_mask', 'id_card_mask', 'idCard_mask', 'idcardMask']) {
        expect(Object.keys(visitor)).not.toContain(key)
        expect(Object.keys(employee)).not.toContain(key)
      }

      // ③ 密文列即使出现也必须是掩码值（后端 toMaskedView 用 mask 覆盖 cipher）
      if (visitor.idCard != null) expect(String(visitor.idCard)).toBe(visitor.idCardMask)
      if (visitor.phone != null) expect(String(visitor.phone)).toBe(visitor.phoneMask)
      if (employee.phone != null) expect(String(employee.phone)).toBe(employee.phoneMask)
      expect(visitor.idCardHash ?? null, '身份证哈希不得外泄').toBeNull()
      expect(visitor.phoneHash ?? null, '手机号哈希不得外泄').toBeNull()
      expect(employee.phoneHash ?? null, '手机号哈希不得外泄').toBeNull()

      // ④ 掩码形态：身份证前 6 后 4 中间星号；手机号 3-4-4
      expect(visitor.idCardMask).toMatch(/^\d{6}\*{4,}\d{4}$/)
      expect(visitor.phoneMask).toMatch(/^\d{3}\*{4}\d{4}$/)
      expect(employee.phoneMask).toMatch(/^\d{3}\*{4}\d{4}$/)
    } finally {
      await api.cleanup(api.ctx, apiURL, token, 'visitors', visitorId)
      await api.cleanup(api.ctx, apiURL, token, 'employees', employeeId)
    }
  })

  test('TC-FE-22b 禁用账号登录报 40103，提示「禁用」且停留在登录页', async ({ page }) => {
    test.skip(!process.env.E2E_USER_DISABLED, '未配置 E2E_USER_DISABLED（真实禁用账号），跳过')
    await page.goto('/login')
    await page.locator('input[name="username"]').first().fill(process.env.E2E_USER_DISABLED)
    await page.locator('input[name="password"]').first().fill(process.env.E2E_PASS_DISABLED || 'x')
    await page.locator('.el-button:has-text("登录")').first().click()

    // 登录场景的 40103 只提示不跳转——跳转在这里是错的（会丢 redirect 参数）
    await expect(page.locator('.el-message--error')).toContainText('禁用')
    await expect(page).toHaveURL(/\/login/)
  })

  test('TC-FE-22c 业务请求上的 40103 必须踢回登录页（防僵死态）', async ({ page, ui, loginAs }) => {
    await loginAs('admin')

    // 用 route 模拟后端 filter：账号被禁用后，业务请求返回 40103（不依赖真的去改库）
    await page.route('**/api/employees*', (route) =>
      route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ code: 40103, message: '账号已被禁用，请联系管理员', data: null })
      })
    )

    await page.goto('/access/employees')

    // 断言点①：踢回登录页（不是停在员工页空转）
    await expect(page).toHaveURL(/\/login/)
    // 断言点②：提示说清是"被禁用"，而不是含糊的"请求失败"
    await expect(ui.message()).toContainText('禁用')
    // 断言点③：凭证已清空，浏览器回退后不能继续用旧 token 操作
    const left = await page.evaluate(([tk]) => window.localStorage.getItem(tk), [TOKEN_KEY])
    expect(left).toBeNull()
  })

  test('TC-FE-22d /auth/refresh 自身返回 40103 时也必须踢登录并清凭证（防"刷新成功→40103→再刷新"循环）', async ({ page, ui, loginAs }) => {
    await loginAs('admin')

    // 禁用账号的 refresh token 可能尚未过期：refresh 拿到 40103 后若只提示不清凭证，
    // 就会陷入"刷新成功 → 业务请求 40103 → 再刷新"的死循环
    await page.route('**/api/auth/refresh', (route) =>
      route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({ code: 40103, message: '账号已被禁用，请联系管理员', data: null })
      })
    )

    await page.goto('/')
    await page.evaluate(
      ([tk, rk]) => {
        window.localStorage.setItem(tk, 'expired.access.token')
        window.localStorage.setItem(rk, 'still.valid.refresh.token')
      },
      [TOKEN_KEY, REFRESH_KEY]
    )
    await page.goto('/dashboard')

    await expect(page).toHaveURL(/\/login/)
    await expect(ui.message()).toContainText('禁用')
    expect(await page.evaluate(([tk]) => window.localStorage.getItem(tk), [TOKEN_KEY])).toBeNull()
    expect(await page.evaluate(([rk]) => window.localStorage.getItem(rk), [REFRESH_KEY])).toBeNull()
  })
})
